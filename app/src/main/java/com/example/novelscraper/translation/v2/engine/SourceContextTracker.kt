package com.example.novelscraper.translation.v2.engine

import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import com.example.novelscraper.translation.v2.pipeline.cleanseBasic

/**
 * 【前文末尾注入の統一仕様】
 * 1. 単体翻訳:
 *    直前話（i - 1）の【原文末尾（設定行数）】を注入。
 *    全ワーカー共有キャッシュ（ConcurrentHashMap）により、ワーカー数に関わらず直前話を特定する（設定無効・先頭話は注入なし）。
 * 2. バッチ翻訳:
 *    ・一括送信時: バッチ先頭ファイルに対する直前話（i - 1）の【原文末尾（設定行数）】を注入。
 *    ・単体フォールバック時: 各話に対して直前話（i - 1）の【原文末尾（設定行数）】を注入（誤訳伝染防止）。
 * 3. チャンク翻訳（大ファイル分割翻訳）:
 *    ・先頭の未処理チャンクのみ直前話（i - 1）の【原文末尾（設定行数）】を注入。
 *    ・後続チャンクは同一エピソード内の接続のため、直前チャンクの【翻訳後訳文末尾（設定行数）】を数珠つなぎ注入。
 *    （訳文末尾と原文末尾の重ね注入はしない）
 *
 * 全ワーカー共有の原文コンテキスト管理（スレッドセーフ）。
 * 並行ワーカー間での文脈欠落（null化）を抑止し、ファイル順（インデックス順）で直前話（i - 1）の
 * クレンジング済み原文末尾N行を提供する（前文脈設定が無効の場合は提供しない）。
 */
class SourceContextTracker(
    private val files: List<VDoc>,
    private val store: FileStore,
    private val contextLines: Int,
    private val enabled: Boolean
) {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    suspend fun getPrevSourceTail(index: Int): String? {
        if (!enabled || index <= 0 || index >= files.size) return null
        val targetIdx = index - 1
        val cached = cache[targetIdx]
        if (cached != null) return cached.ifEmpty { null }

        val file = files.getOrNull(targetIdx) ?: return null
        val raw = store.readText(file.uri) ?: return null
        val clean = cleanseBasic(raw)
        if (clean.isBlank()) return null
        val tail = clean.lines().takeLast(
            contextLines.coerceIn(
                TranslationLimits.PREV_LINES_RANGE.first,
                TranslationLimits.PREV_LINES_RANGE.last
            )
        ).joinToString("\n")
        cache[targetIdx] = tail
        return tail.ifEmpty { null }
    }

    fun putSource(index: Int, content: String) {
        if (!enabled || index < 0 || index >= files.size) return
        val tail = content.lines().takeLast(
            contextLines.coerceIn(
                TranslationLimits.PREV_LINES_RANGE.first,
                TranslationLimits.PREV_LINES_RANGE.last
            )
        ).joinToString("\n")
        cache[index] = tail
    }
}
