package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.infra.FileStore

/** 呼出毎コンパイルを避けるための共有正規表現（固定長後読みで安全に行末改行を保持） */
private val LINE_SPLIT_REGEX = Regex("(?<=\n)")

/**
 * 行区切り（\n）を保持しながら走査し、limitBytes を超えない範囲でチャンクにまとめる。
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

    // 技術的根拠: 固定長後読み (?<=\n) で改行を行末に保持したまま1パス走査。段落境界も改行も1バイトも失われない
    val lines = text.split(LINE_SPLIT_REGEX)
    for (line in lines) {
        if (line.isEmpty()) continue
        val lBytes = utf8Bytes(line)
        if (lBytes <= limitBytes) {
            emit(line)
        } else {
            // 改行のない超長行：サロゲートペア安全に機械分割
            // 技術的根拠: UTF-8マルチバイト（CJK=3バイト）での上限超過を防ぐため最大文字数を換算
            val safeMaxChars = (limitBytes / 3).coerceAtLeast(100)
            for (segment in splitSafeOversized(line, safeMaxChars)) {
                emit(segment)
            }
        }
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
    val sb = StringBuilder()
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
        sb.append(piece)

        // 末尾の改行数を記録
        prevEndingNewlines = text.takeLast(2).count { it == '\n' }
        first = false
    }
    // 技術的根拠1行：SAFの"wa"追記非互換（Google Driveや特定プロバイダで失敗）を物理排除するため、メモリ結合してwtで一括保存する。
    val ok = store.writeText(finalUri, sb.toString())
    if (!ok) log("join writeText failed")
    return ok
}
