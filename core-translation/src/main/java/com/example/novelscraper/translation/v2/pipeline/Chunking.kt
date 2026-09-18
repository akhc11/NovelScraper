package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.infra.AtomicFileGateway
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 行区切り（\n）を保持しながら走査し、limitBytes を目安にチャンクにまとめる。
 * 目安であり上限保証ではない。末尾吸収の合体（上限＋吸収しきい値まで膨張し得る）と、
 * 超長行の機械分割（下記の換算前提を超える文字種あり）では超える。
 * 超長行（改行なしで limitBytes を超える行）はサロゲートペアを保護して安全に機械分割する。
 * 末端の微小余り（上限の15%または1000バイトのいずれか大きい方未満）は独立リクエスト浪費・短文サイズ比誤爆を防ぐため直前チャンクへスマート吸収する。
 */
fun splitIntoChunks(text: String, limitBytes: Int): List<String> {
    require(limitBytes > 0)
    if (text.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentBytes = 0

    fun flush() {
        if (current.isNotEmpty()) {
            chunks.add(current.toString())
            current.clear()
            currentBytes = 0
        }
    }

    fun emit(piece: String) {
        val pieceBytes = utf8Bytes(piece)
        if (currentBytes + pieceBytes > limitBytes && current.isNotEmpty()) flush()
        current.append(piece)
        currentBytes += pieceBytes
    }

    // 技術的根拠: 正規表現splitによる全行String一括確保(OOM)を排除し、改行(\n)を行末に保持したままインデックス走査で1行ずつ切り出す
    var start = 0
    val len = text.length
    while (start < len) {
        val nextNl = text.indexOf('\n', start)
        val line = if (nextNl != -1) text.substring(start, nextNl + 1) else text.substring(start)
        if (line.isNotEmpty()) {
            val lBytes = utf8Bytes(line)
            if (lBytes <= limitBytes) {
                emit(line)
            } else {
                // 改行のない超長行：サロゲートペア安全に機械分割
                // 技術的根拠: 基本多言語面の3バイト換算で上限超過を抑える（絵文字等の4バイト文字が続く場合は超え得る）
                val safeMaxChars = (limitBytes / 3).coerceAtLeast(100)
                for (segment in splitSafeOversized(line, safeMaxChars)) {
                    emit(segment)
                }
            }
        }
        if (nextNl == -1) break
        start = nextNl + 1
    }
    flush()

    // 末尾の微小チャンク吸収（スマートマージ）:
    // 2つ以上のチャンクがあり、末尾チャンクが上限の15%と1000バイトのいずれか大きい方未満の場合、直前チャンクに合体する。
    if (chunks.size >= 2) {
        val last = chunks.last()
        val absorbThresholdBytes = (limitBytes * 0.15).toInt().coerceAtLeast(1000)
        if (utf8Bytes(last) < absorbThresholdBytes) {
            val lastChunk = chunks.removeAt(chunks.size - 1)
            chunks[chunks.size - 1] = chunks.last() + lastChunk
        }
    }

    return chunks
}

/**
 * サロゲートペア（絵文字・第3第4水準漢字）の境界を壊さない機械分割。
 */
fun splitSafeOversized(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxChars).coerceAtMost(text.length)
        // end が High Surrogate の直後であればサロゲートペアの途中なので1文字手前で切る
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) {
            end--
        }
        if (end <= start) {
            // 最低でも1つの完全なコードポイントを進める
            val codePoint = text.codePointAt(start)
            end = (start + Character.charCount(codePoint)).coerceAtMost(text.length)
        }
        chunks.add(text.substring(start, end))
        start = end
    }
    return chunks
}

/**
 * 確定済みチャンク作業。名簿・場所・本文書・宣言書を一体で持ち、名寄せ再検索を不要にする。
 * 技術的根拠1行：作成直後は一覧未反映・表示名改変があり得るため、場所と本文は解決済みの実態で受け渡す。
 */
data class ChunkSession(
    val names: List<String>,
    val outDirUri: String,
    val inputs: Map<String, VDoc>,
    val manifest: ChunkManifest
)

/** 塊の正体（名寄せ用）。名前は opaque な取っ手で、一致はハッシュで見る。 */
@Serializable
data class ChunkEntry(
    val name: String = "",
    val sha256: String = ""
)

/**
 * チャンク作業の宣言書（再開の単一正本）。辞書生成の manifest と同型。
 * 存在ベース再開を廃し「宣言書あり＋原文一致＋設定一致＋入塊一致」の全成立時のみ再利用する。
 * 技術的根拠1行：中断・設定変更・原文編集のいずれでも欠落結合が起きないよう、再開可否を1文書の照合に集約する。
 */
@Serializable
data class ChunkManifest(
    val version: Int = 2,
    val sourceHash: String = "",
    val chunkSizeBytes: Int = 0,
    val chunks: List<ChunkEntry> = emptyList(),
    /** 完了台帳：名簿名→出側の実名。表示名改変があっても再利用できる。 */
    val done: Map<String, String> = emptyMap(),
    /** 同一記録での未解決停止の連続回数。終端判定用。新規分割・別記録で消える。 */
    val haltStreak: Int = 0,
    /** 連続停止中の記録名。 */
    val haltChunk: String = ""
)

/** 同一記録での未解決停止がこの回数に達したら終端（親失敗記録）にする。送らず止まるため待機費用は準備だけであり、単体経路の確定UXと一致させる。 */
const val DEAD_HALT_STREAK = 2

/** 旧形式（名簿のみ）。移行受入れ用に読むだけ。 */
@Serializable
private data class LegacyChunkManifest(
    val version: Int = 1,
    val sourceHash: String = "",
    val chunkSizeBytes: Int = 0,
    val chunks: List<String> = emptyList()
)

const val CHUNK_MANIFEST_NAME = "manifest.json"
private const val CHUNK_MANIFEST_VERSION = 2
private val chunkManifestJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 宣言書を読む。旧形式は名簿のみに変換して移行受入れする。欠損・破損時は null（fail-closed）。 */
suspend fun readChunkManifest(store: FileStore, workDirUri: String): ChunkManifest? {
    return try {
        val doc = store.findChild(workDirUri, CHUNK_MANIFEST_NAME)?.takeIf { !it.isDirectory }
            ?: return null
        val text = store.readText(doc.uri) ?: return null
        chunkManifestJson.decodeFromString<ChunkManifest>(text).takeIf {
            it.version == CHUNK_MANIFEST_VERSION && it.sourceHash.isNotEmpty() && it.chunks.isNotEmpty()
        } ?: chunkManifestJson.decodeFromString<LegacyChunkManifest>(text).takeIf {
            it.sourceHash.isNotEmpty() && it.chunks.isNotEmpty()
        }?.let {
            ChunkManifest(it.version, it.sourceHash, it.chunkSizeBytes, it.chunks.map { name -> ChunkEntry(name, "") })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** 宣言書を確定させる。内容照合は共有保存口が行うため、ここでは成否だけを受け取る。 */
suspend fun writeChunkManifest(
    store: FileStore,
    workDirUri: String,
    manifest: ChunkManifest,
    log: (String) -> Unit = {}
): Boolean {
    val text = chunkManifestJson.encodeToString(ChunkManifest.serializer(), manifest)
    return saveOutputText(store, workDirUri, CHUNK_MANIFEST_NAME, text, "application/json", log = log) != null
}

/**
 * 未解決停止を数える。同一記録での連続回数を返し、宣言書に持ち越す。
 * 技術的根拠1行：単発の停止と永続的な詰まりを区別し、後者だけを終端（親失敗記録）に回す。
 */
suspend fun noteHaltStreak(
    store: FileStore,
    workDirUri: String,
    manifest: ChunkManifest,
    chunkName: String,
    log: (String) -> Unit = {}
): ChunkManifest {
    val streak = if (manifest.haltChunk == chunkName) manifest.haltStreak + 1 else 1
    val updated = manifest.copy(haltStreak = streak, haltChunk = chunkName)
    return if (writeChunkManifest(store, workDirUri, updated, log)) updated else manifest
}

/**
 * 完了台帳に記録する（表示名改変が検出された場合のみ書く）。
 * 技術的根拠1行：通常時は書込ゼロで済ませ、改変がある場合だけ台帳で実名を追跡し再利用を保つ。
 */
suspend fun markChunkDone(
    store: FileStore,
    workDirUri: String,
    manifest: ChunkManifest,
    manifestName: String,
    actualOutName: String,
    log: (String) -> Unit = {}
): ChunkManifest {
    if (manifest.done[manifestName] == actualOutName) return manifest
    val updated = manifest.copy(done = manifest.done + (manifestName to actualOutName))
    return if (writeChunkManifest(store, workDirUri, updated, log)) updated else manifest
}

/** 作業所内の全ファイルを最善努力で消す（作り直し用）。 */
private suspend fun clearWorkFiles(store: FileStore, dirUri: String, log: (String) -> Unit) {
    val children = try {
        store.children(dirUri)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("chunk session clear failed: ${t.message}")
        return
    }
    for (child in children) {
        try {
            if (child.isDirectory) store.deleteRecursively(child.uri) else store.deleteFile(child.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("chunk session clear failed: ${child.name} ${t.message}")
        }
    }
}

/**
 * チャンク作業を開始する（本番流では宣言書で正本化された唯一の入口）。
 * 有効な宣言書があればその作業を返し、なければ作業所を作り直して新規分割する。
 * 場所は解決済みURIで返す（作成直後の一覧未反映に左右されない）。
 * 失敗時は null を返す（呼出側は欠落結合せず中断する）。
 */
suspend fun prepareChunkSession(
    store: FileStore,
    workDirUri: String,
    fileContent: String,
    limitBytes: Int,
    log: (String) -> Unit = {}
): ChunkSession? {
    val inDir = store.findChild(workDirUri, "in") ?: store.createDir(workDirUri, "in")
    val outDir = store.findChild(workDirUri, "out") ?: store.createDir(workDirUri, "out")
    if (inDir == null || outDir == null) {
        log("chunk session dir failed")
        return null
    }
    val hash = sha256Hex(fileContent)
    probeSessionStorage(store, workDirUri, log)
    val manifest = readChunkManifest(store, workDirUri)
    if (manifest != null && manifest.chunkSizeBytes == limitBytes && manifest.sourceHash == hash) {
        // 新形式：入塊を内容で解決する（名前は opaque な取っ手）。
        // 技術的根拠1行：一覧の表示名は改変され得るため、一致はハッシュで見る。
        if (manifest.version == CHUNK_MANIFEST_VERSION && manifest.chunks.all { it.sha256.isNotEmpty() }) {
            val resolved = resolveInputsByHash(store, inDir.uri, manifest.chunks, log)
            if (resolved != null) {
                sweepSessionTmp(store, outDir.uri, log)
                return ChunkSession(manifest.chunks.map { it.name }, outDir.uri, resolved, manifest)
            }
        } else {
            // 旧形式の移行受入れ：名寄せ照合（次回分割で新形式に自己修復する）
            val resolved = resolveInputsByName(store, inDir.uri, manifest.chunks.map { it.name }.toSet(), log)
            if (resolved != null) {
                sweepSessionTmp(store, outDir.uri, log)
                return ChunkSession(manifest.chunks.map { it.name }, outDir.uri, resolved, manifest)
            }
        }
        // 入側だけ作り直す。出側の確定済み訳文は残す。
        // 技術的根拠1行：再分割は決定的に同一結果になるため、出側の名簿対応は保たれる。入側の一時的な不一致で課金済み訳文を捨てない。
        log("chunk session input mismatch, re-splitting input only (translations kept)")
        clearWorkFiles(store, inDir.uri, log)
    } else {
        // 宣言書の失効・欠損時のみ出側も消す（古い訳文は無効のため）。
        if (manifest != null) log("chunk session expired (settings/content changed), re-splitting")
        clearWorkFiles(store, inDir.uri, log)
        clearWorkFiles(store, outDir.uri, log)
    }
    val fresh = freshSplit(store, workDirUri, inDir.uri, fileContent, limitBytes, hash, log) ?: return null
    val inputs = resolveInputsByHash(store, inDir.uri, fresh.chunks, log)
    if (inputs == null) {
        log("chunk session resolve failed")
        return null
    }
    return ChunkSession(fresh.chunks.map { it.name }, outDir.uri, inputs, fresh)
}

/**
 * 新規分割して宣言書まで確定させる。名簿名は実作成名（表示名改変があっても実態と一致する）。
 * @return 名簿と宣言書。失敗時は null（入側は掃除済み）。
 */
private suspend fun freshSplit(
    store: FileStore,
    workDirUri: String,
    inDirUri: String,
    fileContent: String,
    limitBytes: Int,
    hash: String,
    log: (String) -> Unit
): ChunkManifest? {
    val names = writeChunks(store, inDirUri, fileContent, limitBytes, log)
    if (names.isEmpty()) return null
    // 宣言書は内容ハッシュ付きで作る（分割は純粋関数のため再計算は同一結果。件数不一致は構造的に起きない）。
    val parts = splitIntoChunks(fileContent, limitBytes)
    val fresh = ChunkManifest(
        CHUNK_MANIFEST_VERSION, hash, limitBytes,
        names.zip(parts) { name, part -> ChunkEntry(name, sha256Hex(part)) }
    )
    if (!writeChunkManifest(store, workDirUri, fresh, log)) {
        clearWorkFiles(store, inDirUri, log)
        return null
    }
    return fresh
}

/**
 * 入塊を名寄せで解決する（旧形式の移行受入れ専用）。
 * @return 名簿名→文書。不完全なら null。
 */
private suspend fun resolveInputsByName(
    store: FileStore,
    inDirUri: String,
    wanted: Set<String>,
    log: (String) -> Unit
): Map<String, VDoc>? {
    val listed = try {
        store.children(inDirUri).filter { !it.isDirectory }.associateBy { it.name }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("chunk session read failed: ${t.message}")
        return null
    }
    if (listed.keys != wanted) return null
    return listed.filterKeys { it in wanted }
}

/**
 * 入塊を内容ハッシュで解決する。一覧の表示名に依拠しない。
 * @return 名簿名→文書。不完全なら null（呼出側は作り直す）。
 */
private suspend fun resolveInputsByHash(
    store: FileStore,
    inDirUri: String,
    entries: List<ChunkEntry>,
    log: (String) -> Unit
): Map<String, VDoc>? {
    val children = try {
        store.children(inDirUri).filter { !it.isDirectory }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("chunk session read failed: ${t.message}")
        return null
    }
    val pool = mutableMapOf<String, MutableList<VDoc>>()
    for (child in children) {
        val text = try {
            store.readText(child.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        pool.getOrPut(sha256Hex(text)) { mutableListOf() }.add(child)
    }
    val resolved = mutableMapOf<String, VDoc>()
    for (entry in entries) {
        val doc = pool[entry.sha256]?.removeFirstOrNull() ?: return null
        resolved[entry.name] = doc
    }
    return resolved
}

/** 異常終了時の別名残骸だけ掃除する（実体は [AtomicFileGateway]）。 */
private suspend fun sweepSessionTmp(store: FileStore, outDirUri: String, log: (String) -> Unit) {
    AtomicFileGateway(store, log).sweepStaleTmp(outDirUri)
}

/**
 * 保存層の自己診断。作成→一覧→置換→削除を1往復し、癖を1行で記録する。
 * 技術的根拠1行：利用者報告を待たず癖を可視化するため、成否は作業に影響させない。
 */
private suspend fun probeSessionStorage(store: FileStore, dirUri: String, log: (String) -> Unit) {
    try {
        val probe = ".probe_session"
        val created = store.findChild(dirUri, probe) ?: store.createFile(dirUri, probe, "text/plain")
        if (created == null || created.isDirectory) return
        if (!store.writeText(created.uri, "probe")) return
        val listed = try {
            store.findChild(dirUri, probe)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (listed == null) {
            log("storage probe: 一覧未反映あり（作成直後は見えない場合がある）")
        } else if (!listed.name.equals(probe, ignoreCase = true)) {
            log("storage probe: 表示名改変あり（要求=$probe 実測=${listed.name}）")
        }
        val renamed = try {
            store.renameFile(dirUri, created.uri, ".probe_session2")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (renamed == null) {
            log("storage probe: 置換非対応（退行経路で動作）")
        } else {
            deleteQuietly(store, renamed.uri, log)
            return
        }
        deleteQuietly(store, created.uri, log)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("storage probe failed: ${t.message}")
    }
}

/**
 * チャンク群を作業所 in/ に書き出す。呼出時点で in/ は空であること（[prepareChunkSession] が消去する。消去失敗時はハッシュ解決が正しさを保つ）。
 * 各塊は読み返し照合つきで書き、1件でも失敗したら空一覧を返す（呼出側は欠落結合せず中断する）。
 * 名簿は要求名ではなく実作成名で返す（表示名改変があっても実態と宣言書が一致する）。
 * @return チャンク名一覧（空なら失敗）
 */
suspend fun writeChunks(
    store: FileStore,
    inDirUri: String,
    fileContent: String,
    limitBytes: Int,
    log: (String) -> Unit = {}
): List<String> {
    val chunks = splitIntoChunks(fileContent, limitBytes)
    if (chunks.isEmpty()) return emptyList()
    val names = mutableListOf<String>()
    for ((index, chunk) in chunks.withIndex()) {
        val name = "chunk_" + String.format("%04d", index + 1)
        val doc = store.createFile(inDirUri, name, "text/plain")
        if (doc == null || !store.writeText(doc.uri, chunk)) {
            log("chunk write failed: $name")
            return emptyList()
        }
        // 技術的根拠1行：短縮書込のまま宣言書に載せると欠落結合になるため、読み返し不一致は失敗として作り直させる。
        val readBack = try {
            store.readText(doc.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (readBack != chunk) {
            log("chunk verify failed: $name")
            return emptyList()
        }
        names.add(doc.name)
    }
    return names
}

/**
 * out/ の完成チャンクを結合した全文を組み立てる。欠落・空文があれば null（呼出側は公開せず中断する）。
 * 境界の空行増殖だけを正規化する（文境界の検出はしない）。
 * 技術的根拠: outDocsが渡された場合はO(1)で直接VDocを取得し、不要なfindChild Binder IPCクエリを全廃する。
 */
suspend fun buildJoinedText(
    store: FileStore,
    outDirUri: String,
    chunkNames: List<String>,
    log: (String) -> Unit = {},
    outDocs: Map<String, VDoc>? = null
): String? {
    val sb = StringBuilder(chunkNames.size * 4096)
    var first = true
    var prevEndingNewlines = 0
    for (name in chunkNames) {
        val out = outDocs?.get(name) ?: store.findChild(outDirUri, name) ?: return null
        val text = store.readText(out.uri) ?: return null
        if (text.isBlank()) return null

        val piece = if (first) {
            text
        } else {
            val trimmed = text.trimStart('\r', '\n')
            when {
                // 技術的根拠1行：直前チャンク末尾に改行(1個または2個以上)がすでにある場合、空行増殖を防ぐためそのまま繋ぐ
                prevEndingNewlines >= 1 -> trimmed
                // 技術的根拠1行：検証済みチャンクの末尾改行が落ちている場合(0個)のみ、行の癒着を防ぐため\nを補完して繋ぐ
                else -> "\n" + trimmed
            }
        }
        sb.append(piece)

        // 末尾の改行数を記録
        prevEndingNewlines = text.takeLast(2).count { it == '\n' }
        first = false
    }
    return sb.toString()
}

/**
 * out/ の完成チャンクを結合して最終文書へ直接保存する（旧来口。振る舞い不変）。
 * 技術的根拠1行：SAFの"wa"追記非互換（Google Driveや特定プロバイダで失敗）を物理排除するため、メモリ結合してwtで一括保存する。
 */
suspend fun joinOutputsStreaming(
    store: FileStore,
    outDirUri: String,
    chunkNames: List<String>,
    finalUri: String,
    log: (String) -> Unit = {},
    outDocs: Map<String, VDoc>? = null
): Boolean {
    val text = buildJoinedText(store, outDirUri, chunkNames, log, outDocs) ?: return false
    val ok = store.writeText(finalUri, text)
    if (!ok) log("join writeText failed")
    return ok
}
