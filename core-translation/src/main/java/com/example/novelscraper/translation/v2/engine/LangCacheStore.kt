package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.SourceLang
import com.example.novelscraper.translation.v2.pipeline.findOrCreateFile
import kotlinx.coroutines.CancellationException

/**
 * 言語キャッシュの単一所有者（Single Source of Truth）。
 *
 * 設計根拠（Android公式アーキテクチャガイド）:
 * - Data layerガイド: 各Repositoryは単一のSource of Truthを定義し、複数ソースの衝突解決は
 *   Repositoryだけが行う。他層はDataSourceに直接触らずRepository経由とする。
 *   → `.lang_cache` の正本は `翻訳完了_LLM` 直下の1ファイルのみとし、読み書き・移行・重複掃除の
 *   全決定をこのオブジェクトに集約する。呼び出し側は入出力URIを渡すだけでファイル名を持たない。
 * - Offline-firstガイド: 書き込みAPIは suspend 関数とし、ローカルを正として冪等にする。
 *   → [save] は同値なら書かず、固定値（ピン）を上書きしない。
 * - SAF公式ドキュメント: 同名保存時はシステムが ` (1)` を付与する／DISPLAY_NAMEは
 *   プロバイダ依存でファイル名と一致しない場合がある。
 *   → 作成前にfindし、作成後に実名検証＋重複削除＋再検索する [findOrCreateFile] 経由でのみ作る。
 *   一覧のstale（古い一覧で見落とし）による二重生成は、単一ディレクトリ掃引 [sweepDuplicates] で収束させる。
 *
 * 技術的根拠1行：ファイル名・MIME・正規名の散在をこの1箇所に封印し、入力フォルダへの書き込み経路を
 * 型レベルで存在させないことで、親／小説／出力の三重生成と ` (1)` 二重生成を構造的に不可能にする。
 */
object LangCacheStore {
    /** 正規ファイル名。このリテラルはコードベースでここにのみ存在する。 */
    const val FILE_NAME = ".lang_cache"

    /** 旧プロバイダが拡張子を付与してしまった互換名（読み・掃除のみ。新規作成しない）。 */
    val LEGACY_NAMES: List<String> = listOf(".lang_cache.txt")

    /** ドットファイルの拡張子正規化を防ぐためのMIME（従来仕様を継承）。 */
    const val MIME = "application/octet-stream"

    /** SAFの同名衝突リネーム ` (1)` 検知（Translate.ktと同一規則。大文字小文字は不問）。 */
    private val COLLISION_RENAME_REGEX = Regex(""".*\s\(\d+\).*""", RegexOption.IGNORE_CASE)

    fun parseCode(code: String): SourceLang? = when (code.trim()) {
        "ZH" -> SourceLang.ZH
        "KO" -> SourceLang.KO
        "EN" -> SourceLang.EN
        "JA" -> SourceLang.JA
        else -> null
    }

    /**
     * 翻訳対象から除外すべきキャッシュ系ファイル名か。
     * 技術的根拠1行：互換名 `.lang_cache.txt` は末尾 `.txt` のため対象列挙に混入して二重翻訳・件数不一致を起こす。接頭辞一致で一括除外する。
     */
    fun isCacheFileName(name: String): Boolean {
        if (name.equals(FILE_NAME, ignoreCase = true)) return true
        if (LEGACY_NAMES.any { it.equals(name, ignoreCase = true) }) return true
        if (!name.startsWith(".lang_cache", ignoreCase = true)) return false
        return COLLISION_RENAME_REGEX.matches(name) || name.endsWith(".txt", ignoreCase = true)
    }

    /**
     * 指定ディレクトリから言語キャッシュを読む（読み取り専用・冪等）。
     * 優先順: 正規名 → 互換名。存在しなければ null。
     */
    suspend fun load(
        store: FileStore,
        dirUri: String,
        onLog: (String) -> Unit = {}
    ): SourceLang? {
        try {
            val canonical = store.findChild(dirUri, FILE_NAME)
            if (canonical != null && !canonical.isDirectory) {
                val code = store.readText(canonical.uri)?.trim().orEmpty()
                parseCode(code)?.let { return it }
                // 中身が壊れている場合は互換名にフォールバックせずnull（上書きはsave側の責務）
                if (code.isNotEmpty()) return null
            }
            for (legacy in LEGACY_NAMES) {
                val doc = store.findChild(dirUri, legacy)
                if (doc != null && !doc.isDirectory) {
                    parseCode(store.readText(doc.uri)?.trim().orEmpty())?.let { return it }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("⚠️ 言語キャッシュの読み取りに失敗: ${t.message}")
            return null
        }
        return null
    }

    /**
     * 出力ディレクトリにのみ永続化する（コードベース唯一の正規書き込み）。
     * - 既存値と同値なら書かない（冪等）。
     * - 既存ピンと異なる値での上書きはしない（単一言語フォルダの固定仕様を保護）。呼び出し側は
     *   事前に [load] でピンを確認し、ピンがあればそれを有効言語として使うこと。
     * - 成功後は同一ディレクトリ内の重複（互換名・` (1)`）を掃除し単一に収束させる。
     * @return true=正本が要求言語で確定（書込 or 既存一致 or ピン保持）、false=確定失敗
     */
    suspend fun save(
        store: FileStore,
        outputDirUri: String,
        lang: SourceLang,
        onLog: (String) -> Unit = {}
    ): Boolean {
        val existing = load(store, outputDirUri, onLog)
        if (existing == lang) {
            sweepDuplicates(store, outputDirUri, onLog)
            return true
        }
        if (existing != null) {
            onLog("🌐 言語キャッシュの固定値を保持します: ${existing.name}（要求=${lang.name}は上書きしません）")
            sweepDuplicates(store, outputDirUri, onLog)
            return true
        }
        val doc: VDoc = try {
            val created = store.findChild(outputDirUri, FILE_NAME)
                ?: findOrCreateFile(store, outputDirUri, FILE_NAME, MIME)
            if (created == null) {
                onLog("⚠️ 言語キャッシュの作成に失敗しました: $outputDirUri")
                return false
            }
            if (!store.writeText(created.uri, lang.name)) {
                onLog("⚠️ 言語キャッシュの書き込みに失敗しました: $outputDirUri")
                return false
            }
            created
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("⚠️ 言語キャッシュの保存に失敗: ${t.message}")
            return false
        }
        sweepDuplicates(store, outputDirUri, onLog)
        // 技術的根拠1行：作成直後は一覧未反映・表示名改変があり得るため名寄せ再検索ではなく確定済みURIで照合する。
        val readBack = try {
            store.readText(doc.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return readBack?.trim() == lang.name
    }

    /**
     * 指定ディレクトリ内を単一正本に収束させる。
     * - 正規名が有効なら互換名・衝突リネーム（`.lang_cache (1)`系）を全削除。
     * - 正規名が無く互換名だけある場合は正規名へ移行（値コピー後に互換名を削除）。
     * - 翻訳成果物（`001 (1).txt`等）には触れない。`.lang_cache` 接頭辞のみ対象。
     */
    suspend fun sweepDuplicates(
        store: FileStore,
        dirUri: String,
        onLog: (String) -> Unit = {}
    ) {
        try {
            val canonical = store.findChild(dirUri, FILE_NAME)?.takeIf { !it.isDirectory }
            val canonicalValue = canonical?.let { store.readText(it.uri)?.trim() }
            val canonicalValid = canonicalValue?.let { parseCode(it) } != null

            if (canonicalValid) {
                for (legacy in LEGACY_NAMES) {
                    try {
                        store.findChild(dirUri, legacy)?.let { store.deleteFile(it.uri) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        onLog("⚠️ 言語キャッシュの互換名削除に失敗: ${t.message}")
                    }
                }
                deleteCollisionVariants(store, dirUri, onLog)
                return
            }

            if (canonical == null) {
                var migrated: String? = null
                for (legacy in LEGACY_NAMES) {
                    try {
                        val doc = store.findChild(dirUri, legacy) ?: continue
                        if (doc.isDirectory) continue
                        val value = store.readText(doc.uri)?.trim().orEmpty()
                        val parsed = parseCode(value)
                        if (parsed != null && migrated == null) {
                            val created = store.findChild(dirUri, FILE_NAME)
                                ?: findOrCreateFile(store, dirUri, FILE_NAME, MIME)
                            if (created != null && store.writeText(created.uri, parsed.name)) {
                                migrated = parsed.name
                            }
                        }
                        // 移行済み or 無効値は削除して単一化する
                        if (migrated != null || parsed == null) store.deleteFile(doc.uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        onLog("⚠️ 言語キャッシュの移行に失敗: ${t.message}")
                    }
                }
            }
            deleteCollisionVariants(store, dirUri, onLog)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("⚠️ 言語キャッシュの重複掃除に失敗: ${t.message}")
        }
    }

    /**
     * 旧配置（入力フォルダ直下）の残骸を、正本の保存成功後に限り削除する。
     * 三重配置（親／小説／出力）を単一に収束させるための移行処理。失敗しても本流を止めない。
     */
    suspend fun migrateFromInput(
        store: FileStore,
        inputFolderUri: String,
        outputDirUri: String,
        onLog: (String) -> Unit = {}
    ) {
        if (inputFolderUri == outputDirUri) return
        // 正本が確定していない状態で旧配置を消すと値を失うため、必ず検証してから消す。
        if (load(store, outputDirUri, onLog) == null) return
        clearInputCaches(store, inputFolderUri, onLog)
    }

    /** 入力フォルダ直下の旧キャッシュ（正規・互換・衝突変種）を最善努力で全削除する。 */
    suspend fun clearInputCaches(
        store: FileStore,
        inputFolderUri: String,
        onLog: (String) -> Unit = {}
    ) {
        try {
            store.findChild(inputFolderUri, FILE_NAME)?.let {
                if (!it.isDirectory) store.deleteFile(it.uri)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("⚠️ 旧言語キャッシュの削除に失敗: ${t.message}")
        }
        for (legacy in LEGACY_NAMES) {
            try {
                store.findChild(inputFolderUri, legacy)?.let {
                    if (!it.isDirectory) store.deleteFile(it.uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onLog("⚠️ 旧言語キャッシュの互換名削除に失敗: ${t.message}")
            }
        }
        try {
            deleteCollisionVariants(store, inputFolderUri, onLog)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("⚠️ 旧言語キャッシュの重複削除に失敗: ${t.message}")
        }
    }

    /** `.lang_cache` 接頭辞の衝突リネームのみを削除する（他ファイルは不問）。 */
    private suspend fun deleteCollisionVariants(
        store: FileStore,
        dirUri: String,
        onLog: (String) -> Unit = {}
    ) {
        val children = try {
            store.children(dirUri)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onLog("⚠️ 言語キャッシュの一覧取得に失敗: ${t.message}")
            return
        }
        for (child in children) {
            if (child.isDirectory) continue
            if (child.name.equals(FILE_NAME, ignoreCase = true)) continue
            if (LEGACY_NAMES.any { it.equals(child.name, ignoreCase = true) }) continue
            if (!child.name.startsWith(".lang_cache", ignoreCase = true)) continue
            if (!COLLISION_RENAME_REGEX.matches(child.name)) continue
            try {
                store.deleteFile(child.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onLog("⚠️ 言語キャッシュの重複削除に失敗: ${child.name} ${t.message}")
            }
        }
    }
}
