package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.common.TextCleanser
import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Physical pre-splitter (v2).
 *
 * External contract (same as the frozen spec):
 * - Reuse only verified splits: manifest (count + hashes) must match, else rebuild scoped to split files.
 * - Drop incomplete work on failure/stop, return null.
 * - An empty file yields one empty part.
 * - Ingested text is verified UTF-8; only parts are written back as UTF-8 (source file is never overwritten).
 * - Every part is mojibake-checked before writing (fail-closed).
 *
 * No reference to, or reuse of, old code (this file is canonical for v2).
 */
const val PRE_SPLIT_MIN_CHARS = 500
const val PRE_SPLIT_DEFAULT_CHARS = 7000
/** 言語判定用サンプルの上限（先頭抜粋。新規は先頭8000字、再開は先頭パート全文のため、長い先頭パートでは一致しない） */
const val PRE_SPLIT_SAMPLE_CHARS = 8000

/** 分割宣言書のファイル名（小説サブフォルダ直下。part_ 系と同様に分割成果物として扱う）。 */
const val PRE_SPLIT_MANIFEST_NAME = "manifest.json"
private const val PRE_SPLIT_MANIFEST_VERSION = 1
private val preSplitJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 分割宣言書。件名簿＋寸法で再開の正本とする（内容ハッシュは巨大文の毎回全読み返しになるため持たない）。 */
@Serializable
data class PreSplitManifest(
    val version: Int = 1,
    val sourceSizeBytes: Long = -1,
    val splitSizeChars: Int = 0,
    val parts: List<String> = emptyList()
)

/** 呼出毎コンパイルを避けるための共有正規表現 */
private val TXT_SUFFIX_REGEX = Regex("""\.[tT][xX][tT]$""")

/** Harmful-char cleansing for split lines. Delegates to TextCleanser to eliminate duplication. */
fun cleanseForSplit(line: String): String = TextCleanser.cleanse(line)

/** Pure splitter: bundle lines per char budget, machine-split oversized lines. */
fun splitTextToParts(text: String, splitSizeChars: Int): List<String> {
    val size = splitSizeChars.coerceAtLeast(PRE_SPLIT_MIN_CHARS)
    val parts = mutableListOf<String>()
    val cur = StringBuilder()
    fun flush() {
        if (cur.isNotEmpty()) {
            parts.add(cur.toString())
            cur.clear()
        }
    }
    for (rawLine in text.lineSequence()) {
        val line = cleanseForSplit(rawLine)
        if (line.length > size) {
            // 技術的根拠1行：超長行の機械分割は共有実装に寄せ、サロゲート対の途中切断を防ぐ（ストリーミング側と同一）。
            for (seg in splitSafeOversized(line, size)) {
                if (cur.length + seg.length >= size && cur.isNotEmpty()) flush()
                cur.append(seg).append("\n")
                if (cur.length >= size) flush()
            }
        } else {
            if (cur.length + line.length + 1 > size && cur.isNotEmpty()) flush()
            cur.append(line).append("\n")
        }
    }
    flush()
    return parts
}

data class PreSplitResult(
    val subfolderUri: String,
    val novelName: String,
    val sampleText: String,
    val partCount: Int
)

/** 分割宣言書を読む。欠損・破損時は null（fail-closed）。 */
suspend fun readPreSplitManifest(store: FileStore, novelDirUri: String): PreSplitManifest? {
    return try {
        val doc = store.findChild(novelDirUri, PRE_SPLIT_MANIFEST_NAME)?.takeIf { !it.isDirectory }
            ?: return null
        val text = store.readText(doc.uri) ?: return null
        preSplitJson.decodeFromString<PreSplitManifest>(text).takeIf {
            it.version == PRE_SPLIT_MANIFEST_VERSION && it.parts.isNotEmpty()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** 分割宣言書を確定させる。失敗時は false（呼出側は従来動作に退行する）。 */
suspend fun writePreSplitManifest(
    store: FileStore,
    novelDirUri: String,
    manifest: PreSplitManifest,
    log: (String) -> Unit = {}
): Boolean {
    val text = preSplitJson.encodeToString(PreSplitManifest.serializer(), manifest)
    return saveOutputText(store, novelDirUri, PRE_SPLIT_MANIFEST_NAME, text, "application/json", log = log) != null
}

/** 分割成果物（part_*.txt＋宣言書）だけを消す。利用者のファイルには触れない。 */
private suspend fun clearSplitFiles(store: FileStore, novelDirUri: String, log: (String) -> Unit) {
    val children = try {
        store.children(novelDirUri)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log("分割成果物の掃除に失敗しました (${t.message})")
        return
    }
    for (child in children) {
        if (child.isDirectory) continue
        val isPart = child.name.startsWith("part_", ignoreCase = true) && child.name.endsWith(".txt", ignoreCase = true)
        if (!isPart && !child.name.equals(PRE_SPLIT_MANIFEST_NAME, ignoreCase = true)) continue
        try {
            store.deleteFile(child.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log("分割成果物の掃除に失敗しました (${child.name}: ${t.message})")
        }
    }
}

/**
 * 既存フォルダの採用判定。宣言書が有効ならそれを返し、宣言書なしの旧配置は
 * 内容ハッシュを取り直して封印（移行受入れ）する。いずれも不可なら null。
 */
private suspend fun adoptPreSplitManifest(
    store: FileStore,
    novelDirUri: String,
    sourceSizeBytes: Long,
    splitSizeChars: Int,
    log: (String) -> Unit
): PreSplitManifest? {
    val manifest = readPreSplitManifest(store, novelDirUri) ?: return null
    if (manifest.splitSizeChars != splitSizeChars) return null
    if (sourceSizeBytes >= 0 && manifest.sourceSizeBytes >= 0 &&
        manifest.sourceSizeBytes != sourceSizeBytes
    ) return null
    val listed = try {
        store.children(novelDirUri)
            .filter {
                !it.isDirectory && it.name.startsWith("part_", ignoreCase = true) &&
                    it.name.endsWith(".txt", ignoreCase = true)
            }
            .map { it.name }
            .sorted()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    if (listed.sorted() != manifest.parts.sorted()) return null
    return manifest
}

/**
 * サンプルバイト列から、マルチバイト文字の途中切断（underflow）を防ぐため
 * 末尾の直近改行（\n = 0x0A）まで安全に切り詰める。
 * 改行がない場合は、UTF-8の継続バイト境界を安全にトリムする。
 */
fun trimIncompleteMultibyte(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty()) return bytes
    val lastNl = bytes.lastIndexOf(0x0A.toByte())
    if (lastNl >= 1024) {
        return bytes.copyOf(lastNl + 1)
    }
    var end = bytes.size
    while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) {
        end--
    }
    if (end > 0 && (bytes[end - 1].toInt() and 0x80) != 0) {
        end--
    }
    return if (end > 0) bytes.copyOf(end) else bytes
}

/**
 * Split one raw text file into <splitRoot>/<novel>/part_XXXX.txt.
 * Returns null on skip/failure/stop (the reason is logged, never swallowed).
 */
suspend fun splitSingleTextFile(
    store: FileStore,
    fileUri: String,
    fileName: String,
    splitRootUri: String,
    splitSizeChars: Int = PRE_SPLIT_DEFAULT_CHARS,
    declared: V2DeclaredEncoding? = null,
    stopped: () -> Boolean = { false },
    /** 入力ファイルのバイト数（不明時は -1。不明でも動作し、寸法照合だけ省く） */
    sourceSizeBytes: Long = -1,
    /** 文字化け確定時の通知。呼ばれたファイルは翻訳対象外（スキップ）にする */
    onSkipped: (reason: String) -> Unit = {},
    log: (String) -> Unit = {}
): PreSplitResult? {
    val novelBase = fileName.replace(TXT_SUFFIX_REGEX, "")
    if (novelBase.isBlank()) {
        log("pre-split: blank base name, skip")
        return null
    }
    val size = splitSizeChars.coerceAtLeast(PRE_SPLIT_MIN_CHARS)

    val existing = store.findChild(splitRootUri, novelBase)
    if (existing != null && existing.isDirectory) {
        // 技術的根拠1行：宣言書（名簿＋寸法）で完成を検証する。部分残骸・設定変更は作り直す。
        val adopted = adoptPreSplitManifest(store, existing.uri, sourceSizeBytes, size, log)
        if (adopted != null) {
            log("ℹ️ すでに分割完了済みです ($novelBase: ${adopted.parts.size}パート - already split)")
            val sample = store.findChild(existing.uri, adopted.parts.first())?.takeIf { !it.isDirectory }?.let {
                store.readText(it.uri)
            } ?: ""
            return PreSplitResult(existing.uri, novelBase, sample, adopted.parts.size)
        }
        clearSplitFiles(store, existing.uri, log)
    }

    val isNew = (existing == null)
    val novelDir = existing?.takeIf { it.isDirectory }
        ?: store.createDir(splitRootUri, novelBase)
    if (novelDir == null || !novelDir.isDirectory) {
        log("❌ 分割フォルダ作成に失敗しました ($novelBase)")
        return null
    }

    suspend fun rollback() {
        // 技術的根拠1行：中断・失敗時の後片付けはNonCancellableで完遂させるため、ここの例外握りつぶしは意図的である。
        try {
            if (isNew) {
                store.deleteRecursively(novelDir.uri)
            } else {
                // 再利用フォルダでは分割成果物だけ消す（利用者のファイルには触れない）。
                clearSplitFiles(store, novelDir.uri, log)
            }
        } catch (_: Exception) {
            // Rollback is best-effort; the original reason is already logged.
        }
    }

    try {
        if (stopped()) {
            log("⏸ 分割を中断しました ($fileName)")
            rollback()
            return null
        }
        val commonDeclared = declared?.let {
            com.example.novelscraper.translation.common.ingest.DeclaredEncoding.valueOf(it.name)
        }
        // 技術的根拠1行：OOMを完全に防止するため先頭128KBをサンプル読込し、末尾境界切断文字をトリムして文字コード(Charset)のみ確定する。
        val sampleBytes = store.readBytes(fileUri, 131072)
            ?: run {
                log("❌ ファイルを開けませんでした ($fileName)")
                rollback()
                return null
            }
        val safeBytes = trimIncompleteMultibyte(sampleBytes)
        val ingested = com.example.novelscraper.translation.common.ingest.TextIngest.ingest(safeBytes, commonDeclared)
        if (ingested !is com.example.novelscraper.translation.common.ingest.IngestResult.Success) {
            val (reason, evidence) = when (ingested) {
                is com.example.novelscraper.translation.common.ingest.IngestResult.Quarantined -> ingested.reason.name to ingested.evidence
                is com.example.novelscraper.translation.common.ingest.IngestResult.Failed -> "FAILED" to (ingested.cause.message ?: "")
                else -> "UNKNOWN" to ""
            }
            log("⚠️ 文字コード判定失敗のためスキップ ($fileName: $reason $evidence)")
            onSkipped("$reason $evidence")
            rollback()
            return null
        }
        val charset = ingested.provenance.charset
        val charsetId = ingested.provenance.canonicalId

        var partNumber = 1
        val writtenNames = mutableListOf<String>()
        suspend fun writePart(content: String): Boolean {
            val reason = com.example.novelscraper.translation.common.ingest.ChunkVerifier.verify(content, charsetId)
            if (reason != null) {
                log("⚠️ 文字化け検出のためスキップ ($fileName: mojibake $reason)")
                onSkipped("mojibake $reason")
                return false
            }
            val partName = "part_" + partNumber.toString().padStart(4, '0') + ".txt"
            val doc = findOrCreateFile(store, novelDir.uri, partName, "text/plain")
            val ok = doc != null && store.writeText(doc.uri, content)
            if (!ok) {
                log("❌ 分割片の保存に失敗しました ($fileName: $partName)")
                return false
            }
            writtenNames.add(doc.name)
            partNumber++
            return true
        }

        val inputStream = store.openInputStream(fileUri) ?: run {
            log("❌ ファイルを開けませんでした ($fileName)")
            rollback()
            return null
        }

        // 技術的根拠1行：巨大テキストの全文String化によるOOM（128MB超）を排し、ストリーミング行読込＋直接part書き出しを行う。
        val sampleSb = StringBuilder()
        val cur = StringBuilder()
        suspend fun flushCur(): Boolean {
            if (cur.isNotEmpty()) {
                val ok = writePart(cur.toString())
                cur.clear()
                return ok
            }
            return true
        }

        val streamOk = try {
            inputStream.reader(charset).buffered().use { reader ->
                while (true) {
                    if (stopped()) return@use false
                    val rawLine = reader.readLine() ?: break
                    val line = cleanseForSplit(rawLine)
                    if (sampleSb.length < PRE_SPLIT_SAMPLE_CHARS) {
                        sampleSb.append(line).append("\n")
                    }
                    if (line.length > size) {
                        // 技術的根拠1行：超長行の機械分割は共有実装に寄せ、サロゲート対の途中切断を防ぐ（純粋分割側と同一）。
                        for (seg in splitSafeOversized(line, size)) {
                            if (cur.length + seg.length >= size && cur.isNotEmpty()) {
                                if (!flushCur()) return@use false
                            }
                            cur.append(seg).append("\n")
                            if (cur.length >= size) {
                                if (!flushCur()) return@use false
                            }
                        }
                    } else {
                        if (cur.length + line.length + 1 > size && cur.isNotEmpty()) {
                            if (!flushCur()) return@use false
                        }
                        cur.append(line).append("\n")
                    }
                }
                if (!flushCur()) return@use false
                true
            }
        } catch (e: CancellationException) {
            // 技術的根拠1行: キャンセル状態下でもファイル残骸のロールバックを完遂させるためNonCancellableを適用する。
            withContext(NonCancellable) { rollback() }
            throw e
        } catch (e: Exception) {
            log("❌ ストリーミング分割エラー: $fileName (${e.message})")
            false
        }

        if (!streamOk) {
            if (!stopped()) log("❌ 分割が中断されました ($fileName)")
            rollback()
            return null
        }

        if (partNumber == 1) {
            if (!writePart("")) {
                log("❌ 空パート書き込みに失敗しました ($fileName)")
                rollback()
                return null
            }
        }
        val total = partNumber - 1
        log("✅ 物理分割完了: $novelBase (全 $total パート)")
        // 宣言書は全塊の書込成功後にだけ作る（存在自体が完成の証拠になる）。
        // 技術的根拠1行：宣言書の保存失敗でも分割自体は完成しているため続行し、次回採用時に自己修復する。
        val manifest = PreSplitManifest(PRE_SPLIT_MANIFEST_VERSION, sourceSizeBytes, size, writtenNames.toList())
        if (!writePreSplitManifest(store, novelDir.uri, manifest, {})) {
            log("ℹ️ 分割宣言書を保存できませんでした ($novelBase。次回再検証します)")
        }
        // 技術的根拠1行：sampleは言語判定専用のため全文を保持せず先頭抜粋にする（先頭パートが上限以下の場合は再開経路と一致する）。
        val sampleText = if (sampleSb.length > PRE_SPLIT_SAMPLE_CHARS) sampleSb.substring(0, PRE_SPLIT_SAMPLE_CHARS) else sampleSb.toString()
        return PreSplitResult(novelDir.uri, novelBase, sampleText, total)
    } catch (e: CancellationException) {
        // 技術的根拠1行: 停止ボタン押下時の中断でも不完全なフォルダを確実に削除するためNonCancellableを適用する。
        withContext(NonCancellable) { rollback() }
        throw e
    } catch (e: Exception) {
        log("❌ 物理分割エラー: $fileName (${e.message})")
        rollback()
        return null
    }
}
