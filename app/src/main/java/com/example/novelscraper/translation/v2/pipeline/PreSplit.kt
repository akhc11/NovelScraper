package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import com.example.novelscraper.translation.v2.infra.FileStore
import kotlinx.coroutines.CancellationException

/**
 * Physical pre-splitter (v2).
 *
 * External contract (same as the frozen spec):
 * - Never touch an already-split folder, return it as-is.
 * - Drop incomplete work on failure/stop, return null.
 * - An empty file yields one empty part.
 * - Ingested text is verified UTF-8 written back as UTF-8.
 * - Every part is mojibake-checked before writing (fail-closed).
 *
 * No reference to, or reuse of, old code (this file is canonical for v2).
 */
const val PRE_SPLIT_MIN_CHARS = 500
const val PRE_SPLIT_DEFAULT_CHARS = 7000

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
    log: (String) -> Unit = {}
): PreSplitResult? {
    val novelBase = fileName.replace(Regex("""\.[tT][xX][tT]$"""), "")
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
            log("pre-split: already split ($novelBase, ${parts.size} parts)")
            val sample = store.readText(parts.first().uri) ?: ""
            return PreSplitResult(existing.uri, novelBase, sample, parts.size)
        }
    }

    val isNew = (existing == null)
    val novelDir = existing?.takeIf { it.isDirectory }
        ?: store.createDir(splitRootUri, novelBase)
    if (novelDir == null || !novelDir.isDirectory) {
        log("pre-split: cannot create dir $novelBase")
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
        val bytes = store.readBytes(fileUri, V2Ingest.MAX_INGEST_BYTES + 1)
            ?: run {
                log("pre-split: cannot open $fileName")
                rollback()
                return null
            }
        val ingested = V2Ingest.ingest(bytes, declared)
        if (ingested !is V2IngestResult.Success) {
            ingested as V2IngestResult.Quarantined
            log("pre-split: skip ${ingested.reason} ($fileName: ${ingested.evidence})")
            rollback()
            return null
        }
        val fullText = ingested.text
        val charsetId = ingested.provenance.id

        var partNumber = 1
        suspend fun writePart(content: String): Boolean {
            val partName = "part_" + partNumber.toString().padStart(4, '0') + ".txt"
            val doc = store.findChild(novelDir.uri, partName)
                ?: store.createFile(novelDir.uri, partName, "text/plain")
            val ok = doc != null && store.writeText(doc.uri, content)
            if (ok) partNumber++
            return ok
        }

        for (chunk in splitTextToParts(fullText, size)) {
            if (stopped()) {
                rollback()
                return null
            }
            val reason = V2ChunkVerifier.verify(chunk, charsetId)
            if (reason != null) {
                log("pre-split: skip mojibake in $fileName ($reason)")
                rollback()
                return null
            }
            if (!writePart(chunk)) {
                log("pre-split: write failed ($fileName part $partNumber)")
                rollback()
                return null
            }
        }
        if (partNumber == 1) {
            if (!writePart("")) {
                log("pre-split: write failed ($fileName empty part)")
                rollback()
                return null
            }
        }
        val total = partNumber - 1
        log("pre-split: done $novelBase ($total parts)")
        return PreSplitResult(novelDir.uri, novelBase, fullText, total)
    } catch (e: CancellationException) {
        rollback()
        throw e
    } catch (e: Exception) {
        log("pre-split: error $fileName (${e.message})")
        rollback()
        return null
    }
}
