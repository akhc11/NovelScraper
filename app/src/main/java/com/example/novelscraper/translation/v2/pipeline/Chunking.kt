package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.infra.FileStore

/** 呼出毎コンパイルを避けるための共有正規表現 */
private val PARAGRAPH_SPLIT_REGEX = Regex("(?<=\n\n+)")
private val LINE_SPLIT_REGEX = Regex("(?<=\n)")

/**
 * 段落（空行区切り）優先、次に行区切り、超長行はサロゲートペアを保護して機械分割する（文境界の検出はしない）。
 * 末端の微小余り（上限の15%または1000バイトのいずれか大きい方未満）は独立リクエストの浪費・短文サイズ比誤爆を防ぐため直前チャンクへスマート吸収する。
 */
fun splitIntoChunks(text: String, limitBytes: Int): List<String> {
    require(limitBytes > 0)
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

    // 1. 段落（空行区切り）でまず大まかに走査
    // (?<=\n\n+) で空行境界を保持しながら分割
    val paragraphs = text.split(PARAGRAPH_SPLIT_REGEX)
    for (paragraph in paragraphs) {
        if (paragraph.isEmpty()) continue
        val pBytes = utf8Bytes(paragraph)
        if (pBytes <= limitBytes) {
            emit(paragraph)
            continue
        }

        // 2. 段落が上限を超える場合：行単位（\n）で分割
        val lines = paragraph.split(LINE_SPLIT_REGEX)
        for (line in lines) {
            if (line.isEmpty()) continue
            val lBytes = utf8Bytes(line)
            if (lBytes <= limitBytes) {
                emit(line)
                continue
            }

            // 3. 1行が上限を超える場合（改行のない超長行）：サロゲートペア安全に機械分割
            // 技術的根拠: UTF-8マルチバイト（CJK=3バイト）での上限超過を防ぐため最大文字数を換算
            val safeMaxChars = (limitBytes / 3).coerceAtLeast(100)
            for (segment in splitSafeOversized(line, safeMaxChars)) {
                emit(segment)
            }
        }
    }
    flush()

    // 4. 末尾の微小チャンク吸収（スマートマージ）:
    // 2つ以上のチャンクがあり、末尾チャンクが上限の15%と1000バイトのいずれか大きい方未満の場合、
    // たった1行・数行のために独立したAPIリクエストを浪費したり、短文サイズ比チェックで誤爆するのを防ぐため直前チャンクに合体する。
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
 * チャンク群を作業所 in/ に書き出す。既存チャンクがあればそのまま使い回す（再開対応。limit変更時の再分割はしない）。
 * @return チャンク名一覧（空なら失敗）
 */
suspend fun writeChunks(
    store: FileStore,
    inDirUri: String,
    fileContent: String,
    limitBytes: Int,
    log: (String) -> Unit = {}
): List<String> {
    val existing = store.children(inDirUri)
        .filter { !it.isDirectory && it.name.startsWith("chunk_") }
        .sortedBy { it.name }
    if (existing.isNotEmpty()) return existing.map { it.name }

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
        names.add(name)
    }
    return names
}

/**
 * out/ の完成チャンクを追記ストリーミングで結合する。全文をメモリに貯めない。
 * 境界の空行増殖だけを正規化する（文境界の検出はしない）。全件揃い確認は呼出側の責務。
 */
suspend fun joinOutputsStreaming(
    store: FileStore,
    outDirUri: String,
    chunkNames: List<String>,
    finalUri: String,
    log: (String) -> Unit = {}
): Boolean {
    if (!store.writeText(finalUri, "")) {
        log("join init failed")
        return false
    }
    var first = true
    var prevEndingNewlines = 0
    for (name in chunkNames) {
        val out = store.findChild(outDirUri, name) ?: return false
        val text = store.readText(out.uri) ?: return false
        if (text.isBlank()) return false

        val piece = if (first) {
            text
        } else {
            val trimmed = text.trimStart('\r', '\n')
            when {
                prevEndingNewlines >= 2 -> "\n\n" + trimmed
                // 技術的根拠1行：検証済みチャンクは末尾改行が落ちているため、無区切り結合では切れ目の行が癒着する。
                else -> "\n" + trimmed
            }
        }
        if (!store.appendText(finalUri, piece)) return false

        // 末尾の改行数を記録
        prevEndingNewlines = text.takeLast(2).count { it == '\n' }
        first = false
    }
    return true
}
