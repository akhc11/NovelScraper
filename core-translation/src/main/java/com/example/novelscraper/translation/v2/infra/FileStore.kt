package com.example.novelscraper.translation.v2.infra

import kotlinx.coroutines.CancellationException

/**
 * 文書ハンドル。実体（SAF DocumentFile等）に依存しない不変の参照情報。
 * 一覧取得時点のスナップショットであり、存在保証はしない（I/O時に再確認する）。
 */
data class VDoc(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val length: Long = 0L
)

/**
 * ファイル倉庫の最小契約。SAF・テスト用メモリ実装が満たす。
 * 失敗は例外ではなく null/false で返す（理由はログ層の責務）。
 * すべての操作は呼出側スレッドで実行される前提（IO切替は呼出側）。
 */
interface FileStore {
    suspend fun children(dirUri: String): List<VDoc>
    /**
     * 読取可否の軽量探査。children()は空フォルダとI/O失敗を区別しないため、
     * 許可検証用に可否だけを返す口を別に用意する。既定なし(明示実装を強制する)。
     * 技術的根拠1行：空と失敗の区別は検証責務であり、一覧取得の戻り値契約を変えずに分離する。
     * 既定trueを置かない理由：未対応実装が常に健全と誤認するfail-openを防ぐ。
     */
    suspend fun probe(dirUri: String): Boolean
    suspend fun openInputStream(fileUri: String): java.io.InputStream?
    suspend fun readText(fileUri: String): String?
    /**
     * 生バイト読込（取込の文字コード判定用）。巨大ファイル対策に上限付き。
     * 技術的根拠1行：文字コード判定は復号前のバイト列で行う必要があり、
     * 復号済みreadTextでは文字化けの有無を判定できない。
     */
    suspend fun readBytes(fileUri: String, maxBytes: Int = 64 * 1024 * 1024 + 1): ByteArray?
    suspend fun writeText(fileUri: String, content: String): Boolean
    /** 追記（結合のストリーミング用）。未対応時は上書き＋再読込で代替すること */
    suspend fun appendText(fileUri: String, content: String): Boolean
    suspend fun findChild(dirUri: String, name: String): VDoc?
    suspend fun createDir(parentUri: String, name: String): VDoc?
    suspend fun createFile(dirUri: String, name: String, mime: String): VDoc?
    /**
     * 同一フォルダ内での置換（確定操作用）。別名で完全に書いた文書を正名に置き換える。
     * 既定は非対応（null）。対応実装は正名と一致する実名の文書を返すこと。
     * 技術的根拠1行：置換対応は保存プロバイダ任意のため既定を非対応とし、呼出側の退行経路で完全性を保つ。
     */
    suspend fun renameFile(dirUri: String, fileUri: String, newName: String): VDoc? = null
    suspend fun deleteRecursively(dirUri: String): Boolean
    suspend fun deleteFile(fileUri: String): Boolean
}

/** SAFでの同名衝突による自動付与 " (1)" を検知する正規表現 */
private val COLLISION_RENAME_REGEX = Regex(""".*\s\(\d+\).*""")

/**
 * 原子保存の口。別名に書く→照合→確定を唯一の実装に集約する。
 * パイプラインは保存手順を直接書かず、この口を使うこと。
 * 技術的根拠1行：保存手順の二重実装は必ず乖離するため、実体はここだけに置く。
 */
class AtomicFileGateway(
    private val store: FileStore,
    private val log: (String) -> Unit = {}
) {
    /**
     * 重複 "(1)" を作らない生成。プロバイダが同名衝突時に自動リネームする環境向け。
     * 技術的根拠1行：作成物の実名が要求と異なり、かつ衝突重複（" (1)"）の場合のみ消して既存を探し直す。拡張子正規化等は受容する。
     */
    suspend fun findOrCreateFile(dirUri: String, name: String, mime: String): VDoc? {
        store.findChild(dirUri, name)?.let { return it }
        val created = store.createFile(dirUri, name, mime) ?: return null
        if (created.name.equals(name, ignoreCase = true)) return created
        val isCollisionRename = COLLISION_RENAME_REGEX.matches(created.name)
        if (!isCollisionRename) return created
        store.deleteFile(created.uri)
        return store.findChild(dirUri, name)
    }

    /**
     * 唯一の訳文保存実装。別名に書く→照合→確定の順で公開し、保存済み文書またはnullを返す。
     * 確定は置換対応時のみ置換し、非対応時は直接確定＋照合に退行する。いずれも照合不一致は失敗とする。
     * 技術的根拠1行：未検証の上書きを全経路でなくし、異常終了のどの時点でも欠落を完成と誤認しない方向に収束させる。
     */
    suspend fun saveVerified(
        dirUri: String,
        fileName: String,
        text: String,
        mime: String = "text/plain",
        existing: VDoc? = null
    ): VDoc? {
        if (existing != null) {
            // 同一文書への上書きはURIを保つため直接書込＋照合とする
            if (!store.writeText(existing.uri, text)) {
                log("output save error (write): $fileName")
                return null
            }
            if (!verifyBytes(existing.uri, fileName, text)) return null
            return existing
        }
        // 別名は完成判定から不可視な接頭辞にし、異常終了の残骸は次回保存時に自己掃除する
        val tmpName = ".tmp_" + fileName.replace('.', '_')
        try {
            store.findChild(dirUri, tmpName)?.let { if (!it.isDirectory) store.deleteFile(it.uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("tmp sweep failed: $tmpName ${t.message}")
        }
        val tmp = findOrCreateFile(dirUri, tmpName, mime)
            ?: run {
                log("output save error (create): $fileName")
                return null
            }
        if (!store.writeText(tmp.uri, text)) {
            log("output save error (write): $fileName")
            deleteQuietly(tmp.uri)
            return null
        }
        if (!verifyBytes(tmp.uri, fileName, text)) {
            deleteQuietly(tmp.uri)
            return null
        }
        // 確定：置換を試みる。旧成果物は置換成功時に置き換わり、非対応時は直接確定＋照合に退行する。
        try {
            store.renameFile(dirUri, tmp.uri, fileName)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 非対応時は退行経路へ
        }
        // 退行：直接作成＋書込＋照合
        deleteQuietly(tmp.uri)
        val direct = findOrCreateFile(dirUri, fileName, mime)
            ?: run {
                log("output save error (create): $fileName")
                return null
            }
        if (!store.writeText(direct.uri, text)) {
            log("output save error (write): $fileName")
            return null
        }
        if (!verifyBytes(direct.uri, fileName, text)) return null
        return direct
    }

    /**
     * 書込内容の照合。確定済みURIから直接読み返して全文照合する。
     * 技術的根拠1行：作成直後の文書は一覧未反映・表示名改変があり得るため名寄せ再検索を使わず、確定済みURIで照合する。
     */
    suspend fun verifyBytes(fileUri: String, fileName: String, text: String): Boolean {
        val read = try {
            store.readText(fileUri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (read == text) return true
        log("output verify error (content): $fileName")
        return false
    }

    /** 最善努力の削除。失敗は記録のみで本流を止めない。 */
    suspend fun deleteQuietly(fileUri: String) {
        try {
            store.deleteFile(fileUri)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("cleanup failed: $fileUri ${t.message}")
        }
    }

    /** 異常終了時の別名残骸だけ掃除する（名簿外のため結合には不可視だが hygiene として除去）。 */
    suspend fun sweepStaleTmp(dirUri: String, prefix: String = ".tmp_") {
        try {
            for (child in store.children(dirUri)) {
                if (!child.isDirectory && child.name.startsWith(prefix)) {
                    try {
                        store.deleteFile(child.uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        log("chunk tmp sweep failed: ${child.name} ${t.message}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("chunk tmp sweep failed: ${t.message}")
        }
    }
}
