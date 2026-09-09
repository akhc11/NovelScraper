package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.infra.FileStore
import kotlinx.coroutines.CancellationException

/**
 * Physical pre-splitter (v2).
 *
 * External contract (same as the frozen spec):
 * - Never touch an already-split folder: return cached info (sample is a head excerpt, not the full text).
 * - Drop incomplete work on failure/stop, return null.
 * - An empty file yields one empty part.
 * - Ingested text is verified UTF-8; only parts are written back as UTF-8 (source file is never overwritten).
 * - Every part is mojibake-checked before writing (fail-closed).
 *
 * No reference to, or reuse of, old code (this file is canonical for v2).
 */
const val PRE_SPLIT_MIN_CHARS = 500
const val PRE_SPLIT_DEFAULT_CHARS = 7000
/** 言語判定用サンプルの上限（先頭抜粋。新規・再開の両経路で一致させる） */
const val PRE_SPLIT_SAMPLE_CHARS = 8000

/** 呼出毎コンパイルを避けるための共有正規表現 */
private val TXT_SUFFIX_REGEX = Regex("""\.[tT][xX][tT]$""")

/** Harmful-char cleansing for split lines. Keeps \n \r \t, drops ISO controls/BOM/ZWSP. */
fun cleanseForSplit(line: String): String {
    val sb = StringBuilder(line.length)
    for (ch in line) {
        when {
            ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(ch)
            ch.isISOControl() -> Unit
            ch.code == 0xFEFF || ch.code == 0x200B -> Unit
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

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
            var start = 0
            while (start < line.length) {
                val end = (start + size).coerceAtMost(line.length)
                val seg = line.substring(start, end)
                if (cur.length + seg.length >= size && cur.isNotEmpty()) flush()
                cur.append(seg).append("\n")
                if (cur.length >= size) flush()
                start = end
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
        val parts = store.children(existing.uri)
            .filter { !it.isDirectory && it.name.endsWith(".txt", ignoreCase = true) }
            .sortedBy { it.name }
        if (parts.isNotEmpty()) {
            log("ℹ️ すでに分割完了済みです ($novelBase: ${parts.size}パート - already split)")
            val sample = store.readText(parts.first().uri) ?: ""
            return PreSplitResult(existing.uri, novelBase, sample, parts.size)
        }
    }

    val isNew = (existing == null)
    val novelDir = existing?.takeIf { it.isDirectory }
        ?: store.createDir(splitRootUri, novelBase)
    if (novelDir == null || !novelDir.isDirectory) {
        log("❌ 分割フォルダ作成に失敗しました ($novelBase)")
        return null
    }

    suspend fun rollback() {
        try {
            if (isNew) {
                store.deleteRecursively(novelDir.uri)
            } else {
                for (c in store.children(novelDir.uri)) {
                    if (c.isDirectory) store.deleteRecursively(c.uri) else store.deleteFile(c.uri)
                }
            }
        } catch (_: Exception) {
            // Rollback is best-effort; the original reason is already logged.
        }
    }

    try {
        if (stopped()) {
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
            if (ok) partNumber++
            return ok
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
                        var start = 0
                        while (start < line.length) {
                            val end = (start + size).coerceAtMost(line.length)
                            val seg = line.substring(start, end)
                            if (cur.length + seg.length >= size && cur.isNotEmpty()) {
                                if (!flushCur()) return@use false
                            }
                            cur.append(seg).append("\n")
                            if (cur.length >= size) {
                                if (!flushCur()) return@use false
                            }
                            start = end
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
            rollback()
            throw e
        } catch (e: Exception) {
            log("❌ ストリーミング分割エラー: $fileName (${e.message})")
            false
        }

        if (!streamOk) {
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
        // 技術的根拠1行：sampleは言語判定専用のため全文を保持せず先頭抜粋に統一する（再開経路＝先頭partと一致）。
        val sampleText = if (sampleSb.length > PRE_SPLIT_SAMPLE_CHARS) sampleSb.substring(0, PRE_SPLIT_SAMPLE_CHARS) else sampleSb.toString()
        return PreSplitResult(novelDir.uri, novelBase, sampleText, total)
    } catch (e: CancellationException) {
        rollback()
        throw e
    } catch (e: Exception) {
        log("❌ 物理分割エラー: $fileName (${e.message})")
        rollback()
        return null
    }
}
