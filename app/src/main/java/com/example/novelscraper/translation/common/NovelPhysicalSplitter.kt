package com.example.novelscraper.translation.common

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.common.ingest.ChunkVerifier
import com.example.novelscraper.translation.common.ingest.DeclaredEncoding
import com.example.novelscraper.translation.common.ingest.IngestResult
import com.example.novelscraper.translation.common.ingest.TextIngest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 物理分割器 (Ingest v2 世代)。
 *
 * 旧世代との契約差分:
 * - 文字コード判定は先頭サンプルではなく [TextIngest] が全量を取り込んで行う
 *   (境界切断の概念は廃止。上限 [TextIngest.MAX_INGEST_BYTES] 超は隔離)。
 * - 復号テキストは検証済み UTF-8 文字列として受け取り、そのまま UTF-8 で書き出す。
 * - 塊検証は [ChunkVerifier] (3層) が担い、理由付きでスキップする。
 *
 * 不変の外部契約:
 * - 分割済みフォルダは一切触らず返す。失敗時は作りかけを破棄して null。
 * - 空ファイルは空1パート。停止操作は即時協調キャンセル＋ロールバック。
 * - I/O 境界は無言で落とさない (理由付きログ＋ロールバック)。
 */
object NovelPhysicalSplitter {

    private const val TAG = "NovelPhysicalSplitter"
    const val DEFAULT_SPLIT_SIZE_CHARS = 7000 // 目安文字数 (約7,000文字毎にパート分割)

    private class SplitAbortedException(message: String) : IOException(message)

    /**
     * 単一の生テキストファイル (例: 小説名.txt) を指定文字数ごとに物理分割し、
     * 「<splitRootDir>/<小説名>/part_XXXX.txt」に出力する。
     *
     * @return 分割が完了した（または既存の）小説サブフォルダ (DocumentFile)、失敗時は null
     */
    suspend fun splitSingleTextFile(
        context: Context,
        fileDoc: DocumentFile,
        splitRootDir: DocumentFile,
        splitSizeChars: Int = DEFAULT_SPLIT_SIZE_CHARS,
        declared: DeclaredEncoding? = null,
        onLog: (String) -> Unit = {},
        // 取込済みテキストの再利用口。検証済み全文が確定した時点で1回だけ呼ばれる
        // (既存分割ショートカット・隔離・失敗時は呼ばれない)。
        // 技術的根拠1行: 呼出側が生ファイルを再読込せずに言語検出等を行えるようにする。
        onIngestedText: ((String) -> Unit)? = null
    ): DocumentFile? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        val fileName = fileDoc.name ?: return@withContext null
        val novelBaseName = fileName.replace(Regex("""\.[tT][xX][tT]$"""), "")
        val effectiveSplitChars = splitSizeChars.coerceAtLeast(500)

        // 1. 既存データ保護: すでにパートが存在する場合は一切触らず安全にスキップ
        val existingNovelDir = splitRootDir.findFile(novelBaseName)
        if (existingNovelDir != null && existingNovelDir.isDirectory) {
            val existingParts = existingNovelDir.listFiles().filter { it.isFile && it.name?.endsWith(".txt") == true }
            if (existingParts.isNotEmpty()) {
                onLog("ℹ️ 物理分割: すでに分割完了しています ($novelBaseName, ${existingParts.size}件)")
                return@withContext existingNovelDir
            }
        }

        val isNewlyCreated = (existingNovelDir == null)
        val novelDir = existingNovelDir ?: splitRootDir.createDirectory(novelBaseName) ?: return@withContext null

        fun rollback() {
            try {
                if (isNewlyCreated) {
                    novelDir.delete()
                } else {
                    novelDir.listFiles().forEach { it.delete() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rollback incomplete folder: $novelBaseName", e)
            }
        }

        suspend fun failClean(message: String): DocumentFile? {
            onLog(message)
            withContext(NonCancellable) { rollback() }
            return null
        }

        // 2. 取込: 全量判定・検証・UTF-8正規化 (開けない場合は失敗扱い)
        // declared 指定時は自動判定を迂回する (1000個案件の確定路)
        val ingested = context.contentResolver.openInputStream(fileDoc.uri)?.use { stream ->
            TextIngest.ingest(stream, declared)
        } ?: return@withContext failClean("❌ 分割エラー: $fileName (ファイルを開けませんでした)")

        val provenance = when (ingested) {
            is IngestResult.Success -> ingested.provenance
            is IngestResult.Quarantined -> {
                Log.w(TAG, "Skipping novel due to quarantine: $fileName (${ingested.reason})")
                onLog("⏭️ スキップ: ${ingested.reason} のため $fileName をスキップしました (${ingested.evidence})")
                withContext(NonCancellable) { rollback() }
                return@withContext null
            }
            is IngestResult.Failed -> {
                val cause = ingested.cause.message
                return@withContext failClean("❌ 分割エラー: $fileName ($cause)")
            }
        }
        val fullText = (ingested as IngestResult.Success).text
        onIngestedText?.invoke(fullText)

        onLog("✂️ 物理分割中: $fileName (${provenance.charset.displayName()}, 目安:${effectiveSplitChars}文字) → 分割済み/$novelBaseName/")

        // 3. 分割出力 (直接 UTF-8 書き出し & トランザクション例外保証)
        var partNumber = 1

        fun writePartFile(content: String) {
            val partName = String.format("part_%04d.txt", partNumber)
            val partDoc = novelDir.createFile("text/plain", partName)
                ?: throw IOException("Failed to create part file: $partName")
            context.contentResolver.openOutputStream(partDoc.uri, "wt")?.use { out ->
                out.writer(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            } ?: throw IOException("Failed to open output stream for: $partName")
            partNumber++
        }

        var splitSuccess = false
        try {
            splitLines(fullText.lineSequence(), effectiveSplitChars) { chunkText ->
                coroutineContext.ensureActive()
                val reason = ChunkVerifier.verify(chunkText, provenance.canonicalId)
                if (reason != null) {
                    throw SplitAbortedException(
                        "Mojibake detected in $fileName " +
                            "(charset=${provenance.charset.displayName()}, reason=$reason)"
                    )
                }
                writePartFile(chunkText)
            }
            // 空ファイル対策: 最低限1つのパートを出力して整合性を保つ
            if (partNumber == 1) {
                writePartFile("")
            }
            splitSuccess = true
        } catch (e: CancellationException) {
            // 停止ボタン押下時: 中途半端な作りかけファイルをその場で即座にロールバック
            withContext(NonCancellable) { rollback() }
            throw e
        } catch (e: SplitAbortedException) {
            // 文字化け検出時: 化けたパートを残さずロールバックし、当該小説をスキップ (null返却)
            Log.w(TAG, "Skipping novel due to mojibake: $fileName", e)
            onLog("⏭️ スキップ: 文字化けを検出したため $fileName をスキップしました")
            withContext(NonCancellable) { rollback() }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming file: $fileName", e)
            onLog("❌ 分割エラー: $fileName (${e.message})")
            withContext(NonCancellable) { rollback() }
        }

        if (splitSuccess && partNumber > 1) {
            val totalParts = partNumber - 1
            onLog("✅ 分割完了: $novelBaseName (${totalParts} パート)")
            novelDir
        } else {
            null
        }
    }

    /**
     * 行シーケンスを指定文字数ごとに分割し、各チャンクをコールバックに渡すコアロジック。
     * 各行は [TextCleanser] で有害文字を除去してから束ねる。
     */
    fun splitLines(
        lines: Sequence<String>,
        splitSizeChars: Int = DEFAULT_SPLIT_SIZE_CHARS,
        onChunk: (String) -> Unit
    ) {
        val effectiveSplitChars = splitSizeChars.coerceAtLeast(500)
        val currentChunk = StringBuilder()

        fun flush() {
            if (currentChunk.isNotEmpty()) {
                onChunk(currentChunk.toString())
                currentChunk.clear()
            }
        }

        for (rawLine in lines) {
            val line = TextCleanser.cleanse(rawLine)
            if (line.length > effectiveSplitChars) {
                // 改行のない超長行フォールバック
                for (segment in splitOversizedLine(line, effectiveSplitChars)) {
                    if (currentChunk.length + segment.length >= effectiveSplitChars && currentChunk.isNotEmpty()) {
                        flush()
                    }
                    currentChunk.append(segment).append("\n")
                    if (currentChunk.length >= effectiveSplitChars) {
                        flush()
                    }
                }
            } else {
                if (currentChunk.length + line.length + 1 > effectiveSplitChars && currentChunk.isNotEmpty()) {
                    flush()
                }
                currentChunk.append(line).append("\n")
            }
        }
        flush()
    }

    /**
     * テスト用ヘルパー: 全チャンクを List<String> として取得する。
     */
    fun splitLinesIntoChunks(
        lines: Sequence<String>,
        splitSizeChars: Int = DEFAULT_SPLIT_SIZE_CHARS
    ): List<String> {
        val result = mutableListOf<String>()
        splitLines(lines, splitSizeChars) { result.add(it) }
        return result
    }

    private fun splitOversizedLine(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < line.length) {
            val end = (start + maxChars).coerceAtMost(line.length)
            chunks.add(line.substring(start, end))
            start = end
        }
        return chunks
    }
}
