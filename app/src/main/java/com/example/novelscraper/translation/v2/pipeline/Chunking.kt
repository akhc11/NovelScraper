package com.example.novelscraper.translation.v2.pipeline

import com.example.novelscraper.translation.v2.infra.FileStore

/**
 * 段落境界でのチャンク分割。巨大1行は機械分割で救済する。
 * 末端の微小余りは直前チャンクへ吸収させず、呼び元の方針に委ねる（pure）。
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

    fun emit(piece: String, pieceBytes: Int) {
        if (currentBytes + pieceBytes > limitBytes && current.isNotEmpty()) flush()
        current.append(piece)
        currentBytes += pieceBytes
    }

    // 段落（空行区切り）優先、なければ行区切り
    val paragraphs = text.split(Regex("(?<=\n\n)|(?<=\n)"))
    for (p in paragraphs) {
        if (p.isEmpty()) continue
        val pBytes = utf8Bytes(p)
        if (pBytes <= limitBytes) {
            emit(p, pBytes)
            continue
        }
        // 巨大塊は固定幅で機械分割
        var start = 0
        while (start < p.length) {
            val end = (start + limitBytes).coerceAtMost(p.length)
            // バイト境界の安全側：limitBytesは文字数上限の目安として使う
            val piece = p.substring(start, end)
            emit(piece, utf8Bytes(piece))
            start = end
        }
    }
    flush()
    return chunks
}

/**
 * チャンク群を作業所 in/ に書き出す。既存チャンクがあれば何もしない（再開対応）。
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
 * チャンク境界の空行増殖を抑える。全件揃い確認は呼出側の責務。
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
    for (name in chunkNames) {
        val out = store.findChild(outDirUri, name) ?: return false
        val text = store.readText(out.uri) ?: return false
        if (text.isBlank()) return false
        val piece = if (first) text else "\n\n" + text.trimStart('\n')
        if (!store.appendText(finalUri, piece)) return false
        first = false
    }
    return true
}
