package com.example.novelscraper.translation.common

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.pipeline.TextCleanser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

object NovelPhysicalSplitter {

    private const val TAG = "NovelPhysicalSplitter"
    const val DEFAULT_SPLIT_SIZE_CHARS = 7000 // 目安文字数 (約7,000文字毎にパート分割)
    // 文字化け判定: U+FFFD(置換文字)の混入率。誤判定時は数十%以上、正規文はほぼ0のため中間の1%に設定
    const val MOJIBAKE_FFFD_RATIO_THRESHOLD = 0.01
    // ごく短いチャンク内の孤立した正規FFFDによる誤検出を防ぐ最小個数
    const val MOJIBAKE_FFFD_MIN_COUNT = 5
    // 文字種ベース判定の最小チャンク長 (短文はノイズのためFFFD判定のみ)
    const val MOJIBAKE_MIN_SCRIPT_LEN = 100
    // 軟層1: 期待文字種の最低比率 (正規文は数十%のため余裕は10倍以上)
    const val MOJIBAKE_EXPECTED_SCRIPT_MIN_RATIO = 0.01
    const val MOJIBAKE_EXPECTED_CJK_MIN_RATIO = 0.05
    // 軟層1の除外: ASCII主体の塊 (英語引用・pp歌詞等) は正当としうるため判定しない
    const val MOJIBAKE_ASCII_DOMINANT_RATIO = 0.80
    // 軟層2 (単バイト・ラテン系): CJK→ラテン誤読はラテン拡張過密＋ASCII希薄になる
    // 較正値: 正規文 latinExt≦17.8%/ascii≧82% (仏独伊西土越英)、誤読文 latinExt≧41.5%/ascii≦18.5% (韓日中→1252)
    const val MOJIBAKE_LATIN_EXT_DENSE_RATIO = 0.30
    const val MOJIBAKE_LATIN_MIN_ASCII_RATIO = 0.50

    private class MojibakeDetectedException(message: String) : IOException(message)

    private fun fffdCount(text: String): Int = text.count { it == '\uFFFD' }

    private enum class CharsetScriptKind { KOREAN, JAPANESE, CHINESE, LATIN_SINGLE, OTHER_SINGLE, UNICODE }

    private fun scriptKindOf(charset: Charset): CharsetScriptKind {
        val n = charset.name().uppercase()
        if (n.contains("2022")) {
            return when {
                n.contains("JP") -> CharsetScriptKind.JAPANESE
                n.contains("KR") -> CharsetScriptKind.KOREAN
                n.contains("CN") -> CharsetScriptKind.CHINESE
                else -> CharsetScriptKind.UNICODE
            }
        }
        return when {
            n.contains("949") || n.contains("EUC-KR") -> CharsetScriptKind.KOREAN
            n.contains("SJIS") || n.contains("SHIFT_JIS") || n.contains("932") || n.contains("31J") ||
                n.contains("EUC-JP") -> CharsetScriptKind.JAPANESE
            n.contains("GB") || n.contains("BIG5") -> CharsetScriptKind.CHINESE
            n.contains("1252") || n.contains("1254") || n.contains("1258") -> CharsetScriptKind.LATIN_SINGLE
            n.contains("1251") || n.contains("1253") || n.contains("1255") ||
                n.contains("1256") || n.contains("TIS") || n.contains("874") -> CharsetScriptKind.OTHER_SINGLE
            else -> CharsetScriptKind.UNICODE
        }
    }

    private class ScriptCounts(
        var ascii: Int = 0,
        var hangul: Int = 0,
        var hirakata: Int = 0,
        var cjk: Int = 0,
        var latinExt: Int = 0,
        var combining: Int = 0,
        var arabic: Int = 0,
        var hebrew: Int = 0,
        var thai: Int = 0
    )

    private fun countScripts(text: String): ScriptCounts {
        val c = ScriptCounts()
        for (ch in text) {
            when {
                ch in ' '..'~' || ch == '\n' || ch == '\r' || ch == '\t' -> c.ascii++
                ch in '가'..'힣' -> c.hangul++
                ch in 'ぁ'..'ヿ' -> c.hirakata++
                ch in '一'..'鿿' -> c.cjk++
                ch in ' '..'ɏ' || ch in 'Ḁ'..'ỿ' -> c.latinExt++
                ch in '̀'..'ͯ' -> c.combining++
                ch in '؀'..'ۿ' -> c.arabic++
                ch in '֐'..'׿' -> c.hebrew++
                ch in 'ก'..'๛' -> c.thai++
            }
        }
        return c
    }

    /**
     * 文字化け判定の理由を返す純粋関数 (null＝正常)。3層構造:
     * - FFFD層: 置換文字の洪水 (従来通り。UTF-8フォールバック等の誤復号を検出)
     * - 確定層: charsetが原理的に出せない文字種の混入 (デコーダ仕様上、正規復号では起こり得ない)
     * - 軟層: 期待文字種の欠落 (KOREAN/JAPANESE/CHINESE) とラテン拡張過密 (LATIN_SINGLE)。
     *   短文・ASCII主体・結合文字あり (分解ベトナム語) は除外し、誤スキップ (作品喪失) より見逃しを許す側に倒す。
     */
    fun mojibakeReason(text: String, charset: Charset = StandardCharsets.UTF_8): String? {
        if (text.isEmpty()) return null
        val fffd = fffdCount(text)
        if (fffd >= MOJIBAKE_FFFD_MIN_COUNT &&
            fffd.toDouble() / text.length > MOJIBAKE_FFFD_RATIO_THRESHOLD
        ) {
            return "FFFD=$fffd/${text.length}"
        }
        val kind = scriptKindOf(charset)
        if (kind == CharsetScriptKind.UNICODE) return null
        val s = countScripts(text)
        // 確定層: 出せない文字種 (チャンク長を問わない)
        val impossible = when (kind) {
            // 単バイト復号はU+0100以上を出せない (€等の約物は対象外のため安全)
            CharsetScriptKind.LATIN_SINGLE, CharsetScriptKind.OTHER_SINGLE ->
                s.hangul + s.hirakata + s.cjk > 0
            // JIS X 0208 / KS X 1001 / GB系に存在しない文字種
            CharsetScriptKind.JAPANESE -> s.hangul > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            CharsetScriptKind.KOREAN -> s.hirakata > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            CharsetScriptKind.CHINESE -> s.hirakata > 0 || s.arabic > 0 || s.hebrew > 0 || s.thai > 0
            else -> false
        }
        if (impossible) return "impossible-script for ${charset.name()}"
        if (text.length < MOJIBAKE_MIN_SCRIPT_LEN) return null
        val len = text.length.toDouble()
        // 軟層1: 期待文字種の欠落 (ASCII主体は英語引用等の正当文のため除外)
        if (s.ascii / len < MOJIBAKE_ASCII_DOMINANT_RATIO) {
            val missing = when (kind) {
                // 漢字混じり (漢文引用等) は正当のためcjkでも救う
                CharsetScriptKind.KOREAN -> s.hangul / len < MOJIBAKE_EXPECTED_SCRIPT_MIN_RATIO &&
                    s.cjk / len < MOJIBAKE_EXPECTED_CJK_MIN_RATIO
                CharsetScriptKind.JAPANESE -> s.hirakata / len < MOJIBAKE_EXPECTED_SCRIPT_MIN_RATIO &&
                    s.cjk / len < MOJIBAKE_EXPECTED_CJK_MIN_RATIO
                CharsetScriptKind.CHINESE -> s.cjk / len < MOJIBAKE_EXPECTED_CJK_MIN_RATIO &&
                    s.hangul / len < MOJIBAKE_EXPECTED_SCRIPT_MIN_RATIO
                else -> false
            }
            if (missing) return "missing-expected-script for ${charset.name()}"
        }
        // 軟層2: CJK→ラテン誤読はラテン拡張過密＋ASCII希薄になる (AND条件で誤検出を抑える)
        if (kind == CharsetScriptKind.LATIN_SINGLE && s.combining == 0 &&
            s.latinExt / len > MOJIBAKE_LATIN_EXT_DENSE_RATIO &&
            s.ascii / len < MOJIBAKE_LATIN_MIN_ASCII_RATIO
        ) {
            return "latin-gibberish for ${charset.name()}"
        }
        return null
    }

    /**
     * チャンクが文字化けかを判定する純粋関数。
     * @param charset 復号に使った文字コード (既定UTF-8＝FFFD判定のみ。分割経路では検出charsetを渡す)
     */
    fun isMojibakeChunk(text: String, charset: Charset = StandardCharsets.UTF_8): Boolean {
        return mojibakeReason(text, charset) != null
    }

    /**
     * 単一の生テキストファイル (例: 小説名.txt) を指定文字数ごとに物理分割し、
     * 「<splitRootDir>/<小説名>/part_XXXX.txt」に出力する。
     *
     * 特徴:
     * - すでに分割済み (フォルダ内に .txt パートが存在) の場合は一切触らず即座に既存フォルダを返す (安全スキップ)。
     * - 停止ボタン押下 (CancellationException) や例外発生時は、今回新規作成した作りかけフォルダを即座に削除 (即時ロールバック)。
     * - 入力を開けない場合や文字化け検出時は作りかけを破棄して当該小説をスキップ (null返却)。
     * - 1行ストリーミング処理 (メモリ数KB)、文字コード自動判定、有害文字クレンジング。
     * - coroutineContext.ensureActive() による停止操作時の即時協調キャンセル。
     *
     * @return 分割が完了した（または既存の）小説サブフォルダ (DocumentFile)、失敗時は null
     */
    suspend fun splitSingleTextFile(
        context: Context,
        fileDoc: DocumentFile,
        splitRootDir: DocumentFile,
        splitSizeChars: Int = DEFAULT_SPLIT_SIZE_CHARS,
        onLog: (String) -> Unit = {}
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

        // 2. 先頭 64KB から文字コードを高精度自動判定 (開けない場合は失敗扱い)
        val charset = context.contentResolver.openInputStream(fileDoc.uri)?.use { stream ->
            UniversalCharsetDetector.detectCharsetFromStream(stream)
        } ?: return@withContext failClean("❌ 分割エラー: $fileName (ファイルを開けませんでした)")

        onLog("✂️ 物理分割中: $fileName (${charset.displayName()}, 目安:${effectiveSplitChars}文字) → 分割済み/$novelBaseName/")

        // 3. ストリーミング分割出力 (メモリ消費ゼロ & 直接 UTF-8 書き出し & トランザクション例外保証)
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
            val inStream = context.contentResolver.openInputStream(fileDoc.uri)
                ?: return@withContext failClean("❌ 分割エラー: $fileName (ファイルを開けませんでした)")
            inStream.use { stream ->
                InputStreamReader(stream, charset).buffered().use { reader ->
                    splitLines(reader.lineSequence(), effectiveSplitChars) { chunkText ->
                        coroutineContext.ensureActive()
                        val reason = mojibakeReason(chunkText, charset)
                        if (reason != null) {
                            throw MojibakeDetectedException(
                                "Mojibake detected in $fileName " +
                                    "(charset=${charset.displayName()}, reason=$reason)"
                            )
                        }
                        writePartFile(chunkText)
                    }
                }
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
        } catch (e: MojibakeDetectedException) {
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
     * 行シーケンスを指定文字数ごとに分割し、各チャンクをコールバックに渡すコアストリーミングロジック。
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
