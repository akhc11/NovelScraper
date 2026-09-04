package com.example.novelscraper

import com.example.novelscraper.translation.llm.api.GeminiApiClient
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.engine.LlmTranslationConfig
import com.example.novelscraper.translation.llm.pipeline.NovelDictionary
import com.example.novelscraper.translation.llm.pipeline.NovelDictionaryGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Ignore("Manual stress test only")
class GemmaStressTest {

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN).format(Date())
        println("[$time] $msg")
    }

    @Test
    fun testGemma4StressTest() = runBlocking {
        val config = LlmTranslationConfig()
        val apiKeys = config.geminiApiKeys.filter { it.isNotBlank() }
        val model = "gemma-4-31b-it"

        log("================================================================================")
        log("🚀 Gemma 4 31B 辞書生成ストレステスト開始")
        log("・対象モデル: $model")
        log("・登録APIキー数: ${apiKeys.size} (プロジェクト独立・個別16k TPM / 30 RPM)")
        log("・目標: 16k TPM の限界までトークンを消費し、負荷耐性・レート制限挙動を検証")
        log("================================================================================")

        val partsDir = listOf(
            File("test_data/거신사냥꾼/parts"),
            File("../test_data/거신사냥꾼/parts"),
            File("C:/Users/asan6/AndroidStudioProjects/NovelScraper2/test_data/거신사냥꾼/parts")
        ).firstOrNull { it.exists() && it.isDirectory }

        if (partsDir == null) {
            log("❌ parts ディレクトリが見つかりません")
            return@runBlocking
        }

        val partFiles = partsDir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: emptyList()

        log("📂 検出パートファイル数: ${partFiles.size} 件")

        // 1バッチあたり 4パート結合 ＝ 約 40KB (約 14,000〜15,000 トークン: 16k TPM上限の 90〜95% 限界攻め)
        val partsPerBatch = 4
        val testBatchCount = 6
        val targetBatches = (0 until testBatchCount).map { bIdx ->
            val slice = partFiles.drop(bIdx * partsPerBatch).take(partsPerBatch)
            slice.joinToString("\n\n") { it.readText(Charsets.UTF_8) }
        }

        log("🎯 超限界ストレステストバッチ数: $testBatchCount 件 (1バッチあたり約 40KB ≒ 約 14,500 トークン)")
        targetBatches.forEachIndexed { i, b ->
            val bSize = b.toByteArray(Charsets.UTF_8).size
            log("   ・バッチ #${i + 1}: ${bSize} bytes (約 ${bSize / 2.75} トークン推定)")
        }

        val parallelCount = 6 // 6キー並列
        val semaphore = Semaphore(parallelCount)
        val keyCount = apiKeys.size

        val totalTokensUsed = AtomicInteger(0)
        val keyTokenMap = ConcurrentHashMap<Int, AtomicInteger>()
        val keyRequestMap = ConcurrentHashMap<Int, AtomicInteger>()
        for (i in 0 until keyCount) {
            keyTokenMap[i] = AtomicInteger(0)
            keyRequestMap[i] = AtomicInteger(0)
        }

        val batchResults = ConcurrentHashMap<Int, String>()
        val startTime = System.currentTimeMillis()

        // 1リクエスト待機ディレイ (秒): 限界テストのため短め(5秒)
        val requestDelaySec = 5
        val cooldown429Sec = 60

        val jobs = targetBatches.mapIndexed { index, content ->
            val batchNum = index + 1
            val keyIndex = (batchNum - 1) % keyCount
            val apiKey = apiKeys[keyIndex]
            val byteSize = content.toByteArray(Charsets.UTF_8).size

            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val maxRetries = 2
                    for (retry in 0..maxRetries) {
                        val retryLabel = if (retry > 0) " [再試行 $retry/$maxRetries]" else ""
                        log("▶ [バッチ #$batchNum/$testBatchCount] 送信開始 (キー#${keyIndex + 1}/$keyCount | ${byteSize}B / 16k TPM極限アタック)$retryLabel")

                        val reqStart = System.currentTimeMillis()
                        val result = GeminiApiClient.generateContent(
                            apiKey = apiKey,
                            model = model,
                            prompt = NovelDictionaryGenerator.BATCH_PROMPT,
                            sourceText = content
                        )
                        val elapsedMs = System.currentTimeMillis() - reqStart

                        when (result) {
                            is LlmApiResult.Success -> {
                                val pTokens = result.promptTokens
                                val cTokens = result.completionTokens
                                val tTokens = result.totalTokens
                                val total = totalTokensUsed.addAndGet(tTokens)
                                val keyTotal = keyTokenMap[keyIndex]?.addAndGet(tTokens) ?: 0
                                keyRequestMap[keyIndex]?.incrementAndGet()

                                log("  ✅ [バッチ #$batchNum] 成功 (${elapsedMs}ms) | 入力:${pTokens}T / 出力:${cTokens}T / 合計:${tTokens}T | キー#${keyIndex + 1}累積:${keyTotal}T | 全体累積:${total}T")
                                log("     抽出結果抜粋: ${result.text.trim().take(120).replace("\n", " ")}...")

                                batchResults[batchNum] = result.text.trim()

                                if (requestDelaySec > 0) {
                                    log("  ⏳ [バッチ #$batchNum] リクエスト間隔待機 (${requestDelaySec}秒)...")
                                    delay(requestDelaySec * 1000L)
                                }
                                break
                            }
                            is LlmApiResult.QuotaExceeded -> {
                                log("  ⚠️ [429 Quota Exceeded] バッチ #$batchNum (キー#${keyIndex + 1}): レート制限検知！ (${elapsedMs}ms)")
                                log("     詳細: ${result.message}")
                                if (retry < maxRetries) {
                                    log("  ⏳ 枠回復のため ${cooldown429Sec}秒 待機して再試行します...")
                                    delay(cooldown429Sec * 1000L)
                                } else {
                                    log("  ❌ [バッチ #$batchNum] 最大再試行回数超過")
                                }
                            }
                            is LlmApiResult.NetworkError -> {
                                log("  ⚠️ [Network Error] バッチ #$batchNum: HTTP ${result.statusCode} - ${result.message}")
                                if (retry < maxRetries) delay(2000L)
                            }
                            is LlmApiResult.ConfigError -> {
                                log("  ⚙️ [Config Error] バッチ #$batchNum: (${result.kind}) ${result.message}")
                                break
                            }
                            is LlmApiResult.FatalError -> {
                                log("  ❌ [Fatal Error] バッチ #$batchNum: HTTP ${result.statusCode} - ${result.message}")
                                break
                            }
                            is LlmApiResult.QualityError -> {
                                log("  ⚠️ [Quality Error] バッチ #$batchNum: ${result.reason}")
                                if (retry < maxRetries) delay(2000L)
                            }
                        }
                    }
                }
            }
        }

        jobs.awaitAll()

        val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        log("================================================================================")
        log("📊 バッチ抽出完了フェーズ集計")
        log("・所要時間: ${String.format(Locale.US, "%.1f", totalElapsedSec)} 秒")
        log("・完了バッチ数: ${batchResults.size} / $testBatchCount")
        log("・総消費トークン数: ${totalTokensUsed.get()} トークン")
        log("・スループット: ${String.format(Locale.US, "%.1f", totalTokensUsed.get() / (totalElapsedSec / 60.0))} トークン/分")
        for (i in 0 until keyCount) {
            val kTokens = keyTokenMap[i]?.get() ?: 0
            val kReqs = keyRequestMap[i]?.get() ?: 0
            log("  - キー #${i + 1}: ${kReqs}リクエスト / ${kTokens}トークン (16k TPM枠の ${String.format(Locale.US, "%.1f", (kTokens.toDouble() / 16000.0) * 100)}%)")
        }
        log("================================================================================")

        if (batchResults.isEmpty()) {
            log("❌ 有効なバッチ結果がありません。テストを中断します。")
            return@runBlocking
        }

        // 統合マージフェーズ
        val mergeModel = "gemini-2.5-flash"
        val mergeInput = batchResults.entries.sortedBy { it.key }.joinToString("\n\n") { (num, json) ->
            "[バッチ${num}の辞書断片]\n$json"
        }
        val mergeInputBytes = mergeInput.toByteArray(Charsets.UTF_8).size
        val mergeInputChars = mergeInput.length
        log("🔄 辞書統合マージ開始 (${batchResults.size}断片 ➔ $mergeModel)")
        log("📦 マージ入力断片の合計サイズ: ${mergeInputChars} 文字 / ${mergeInputBytes} バイト")
        batchResults.entries.sortedBy { it.key }.forEach { (num, text) ->
            val bSize = text.toByteArray(Charsets.UTF_8).size
            log("   ・断片 #$num: ${text.length} 文字 / ${bSize} バイト")
        }

        val mergeStart = System.currentTimeMillis()
        val mergeResult = GeminiApiClient.generateContent(
            apiKey = apiKeys.first(),
            model = mergeModel,
            prompt = NovelDictionaryGenerator.MERGE_PROMPT,
            sourceText = mergeInput
        )
        val mergeElapsed = System.currentTimeMillis() - mergeStart

        val mergedDict: NovelDictionary? = if (mergeResult is LlmApiResult.Success) {
            log("✅ マージ成功 (${mergeElapsed}ms) | 入力(プロンプト+断片):${mergeResult.promptTokens}T / 出力(思考+結果):${mergeResult.completionTokens}T / 合計:${mergeResult.totalTokens}T")
            log("   マージ結果プレビュー:\n${mergeResult.text.trim()}")
            NovelDictionaryGenerator.parseDictionaryJson(mergeResult.text)
        } else {
            log("❌ マージ失敗: $mergeResult")
            null
        }

        if (mergedDict != null) {
            log("📖 マージ後辞書: スタイル=【${mergedDict.style}】, 登録人数=${mergedDict.characters.size}名")
            mergedDict.characters.forEach { (src, trans) ->
                log("   ・$src ➔ $trans")
            }
        }

        log("================================================================================")
        log("🎉 Gemma 4 31B 辞書生成ストレステスト全行程終了")
        log("================================================================================")
    }

    @Test
    fun testGemini31FlashLiteStressTest() = runBlocking {
        val config = LlmTranslationConfig()
        val apiKeys = config.geminiApiKeys.filter { it.isNotBlank() }
        val model = "gemini-3.1-flash-lite"
        val mergeModel = "gemini-2.5-flash"

        log("================================================================================")
        log("🚀 Gemini 3.1 Flash-Lite 辞書生成 10倍トークン超限界ストレステスト開始")
        log("・対象モデル: $model (250k TPM / 15 RPM / 500 RPD)")
        log("・マージモデル: $mergeModel (100万+ TPM)")
        log("・登録APIキー数: ${apiKeys.size} (プロジェクト独立・個別250k TPM)")
        log("・目標: Gemma 4 (40KB) の約10倍 (約350KB / 約12万〜14万トークン) を1リクエストで送信")
        log("================================================================================")

        val partsDir = listOf(
            File("test_data/거신사냥꾼/parts"),
            File("../test_data/거신사냥꾼/parts"),
            File("C:/Users/asan6/AndroidStudioProjects/NovelScraper2/test_data/거신사냥꾼/parts")
        ).firstOrNull { it.exists() && it.isDirectory }

        if (partsDir == null) {
            log("❌ parts ディレクトリが見つかりません")
            return@runBlocking
        }

        val partFiles = partsDir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: emptyList()

        log("📂 検出パートファイル数: ${partFiles.size} 件")

        // 1バッチあたり 35パート結合 ＝ 約 350KB (約 120,000〜140,000 トークン)
        val partsPerBatch = 35
        val testBatchCount = 2
        val targetBatches = (0 until testBatchCount).map { bIdx ->
            val slice = partFiles.drop(bIdx * partsPerBatch).take(partsPerBatch)
            slice.joinToString("\n\n") { it.readText(Charsets.UTF_8) }
        }

        val totalChars = targetBatches.sumOf { it.length }
        val totalBytes = targetBatches.sumOf { it.toByteArray(Charsets.UTF_8).size }
        log("🎯 10倍超限界バッチ数: $testBatchCount 件 (合計 ${partsPerBatch * testBatchCount} パート分 / 約 ${totalBytes / 1000} KB / 約 ${totalChars} 文字)")
        targetBatches.forEachIndexed { i, b ->
            val bSize = b.toByteArray(Charsets.UTF_8).size
            log("   ・バッチ #${i + 1}: ${bSize} bytes (約 ${bSize / 2.75} トークン推定)")
        }

        val parallelCount = 6 // 6キー並列
        val semaphore = Semaphore(parallelCount)
        val keyCount = apiKeys.size

        val totalTokensUsed = AtomicInteger(0)
        val keyTokenMap = ConcurrentHashMap<Int, AtomicInteger>()
        val keyRequestMap = ConcurrentHashMap<Int, AtomicInteger>()
        for (i in 0 until keyCount) {
            keyTokenMap[i] = AtomicInteger(0)
            keyRequestMap[i] = AtomicInteger(0)
        }

        val batchResults = ConcurrentHashMap<Int, String>()
        val startTime = System.currentTimeMillis()

        val jobs = targetBatches.mapIndexed { index, content ->
            val batchNum = index + 1
            val keyIndex = (batchNum - 1) % keyCount
            val apiKey = apiKeys[keyIndex]
            val byteSize = content.toByteArray(Charsets.UTF_8).size

            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val maxRetries = 2
                    for (retry in 0..maxRetries) {
                        val retryLabel = if (retry > 0) " [再試行 $retry/$maxRetries]" else ""
                        log("▶ [バッチ #$batchNum/$testBatchCount] 送信開始 (キー#${keyIndex + 1}/$keyCount | ${byteSize}B / 10倍トークン極限アタック)$retryLabel")

                        val reqStart = System.currentTimeMillis()
                        val result = GeminiApiClient.generateContent(
                            apiKey = apiKey,
                            model = model,
                            prompt = NovelDictionaryGenerator.BATCH_PROMPT,
                            sourceText = content
                        )
                        val elapsedMs = System.currentTimeMillis() - reqStart

                        when (result) {
                            is LlmApiResult.Success -> {
                                val pTokens = result.promptTokens
                                val cTokens = result.completionTokens
                                val tTokens = result.totalTokens
                                val total = totalTokensUsed.addAndGet(tTokens)
                                val keyTotal = keyTokenMap[keyIndex]?.addAndGet(tTokens) ?: 0
                                keyRequestMap[keyIndex]?.incrementAndGet()

                                log("  ✅ [バッチ #$batchNum] 成功 (${elapsedMs}ms) | 入力:${pTokens}T / 出力:${cTokens}T / 合計:${tTokens}T | キー#${keyIndex + 1}累積:${keyTotal}T | 全体累積:${total}T")
                                log("     抽出結果抜粋: ${result.text.trim().take(120).replace("\n", " ")}...")

                                batchResults[batchNum] = result.text.trim()
                                break
                            }
                            is LlmApiResult.QuotaExceeded -> {
                                log("  ⚠️ [429 Quota Exceeded] バッチ #$batchNum (キー#${keyIndex + 1}): レート制限検知！ (${elapsedMs}ms)")
                                log("     詳細: ${result.message}")
                                if (retry < maxRetries) {
                                    log("  ⏳ 60秒待機して再試行します...")
                                    delay(60000L)
                                }
                            }
                            is LlmApiResult.NetworkError -> {
                                log("  ⚠️ [Network Error] バッチ #$batchNum: HTTP ${result.statusCode} - ${result.message}")
                                if (retry < maxRetries) delay(2000L)
                            }
                            is LlmApiResult.ConfigError -> {
                                log("  ⚙️ [Config Error] バッチ #$batchNum: (${result.kind}) ${result.message}")
                                break
                            }
                            is LlmApiResult.FatalError -> {
                                log("  ❌ [Fatal Error] バッチ #$batchNum: HTTP ${result.statusCode} - ${result.message}")
                                break
                            }
                            is LlmApiResult.QualityError -> {
                                log("  ⚠️ [Quality Error] バッチ #$batchNum: ${result.reason}")
                                if (retry < maxRetries) delay(2000L)
                            }
                        }
                    }
                }
            }
        }

        jobs.awaitAll()

        val totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        log("================================================================================")
        log("📊 Gemini 3.1 Flash-Lite バッチ抽出完了フェーズ集計")
        log("・所要時間: ${String.format(Locale.US, "%.1f", totalElapsedSec)} 秒")
        log("・完了バッチ数: ${batchResults.size} / $testBatchCount")
        log("・総消費トークン数: ${totalTokensUsed.get()} トークン")
        log("・スループット: ${String.format(Locale.US, "%.1f", totalTokensUsed.get() / (totalElapsedSec / 60.0))} トークン/分")
        for (i in 0 until keyCount) {
            val kTokens = keyTokenMap[i]?.get() ?: 0
            val kReqs = keyRequestMap[i]?.get() ?: 0
            log("  - キー #${i + 1}: ${kReqs}リクエスト / ${kTokens}トークン (250k TPM枠の ${String.format(Locale.US, "%.1f", (kTokens.toDouble() / 250000.0) * 100)}%)")
        }
        log("================================================================================")

        if (batchResults.isEmpty()) {
            log("❌ 有効なバッチ結果がありません。テストを中断します。")
            return@runBlocking
        }

        // 統合マージフェーズ
        val mergeInput = batchResults.entries.sortedBy { it.key }.joinToString("\n\n") { (num, json) ->
            "[バッチ${num}の辞書断片]\n$json"
        }
        val mergeInputBytes = mergeInput.toByteArray(Charsets.UTF_8).size
        val mergeInputChars = mergeInput.length
        log("🔄 辞書統合マージ開始 (${batchResults.size}断片 ➔ $mergeModel)")
        log("📦 マージ入力断片の合計サイズ: ${mergeInputChars} 文字 / ${mergeInputBytes} バイト")

        val mergeStart = System.currentTimeMillis()
        val mergeResult = GeminiApiClient.generateContent(
            apiKey = apiKeys.first(),
            model = mergeModel,
            prompt = NovelDictionaryGenerator.MERGE_PROMPT,
            sourceText = mergeInput
        )
        val mergeElapsed = System.currentTimeMillis() - mergeStart

        val mergedDict: NovelDictionary? = if (mergeResult is LlmApiResult.Success) {
            log("✅ マージ成功 (${mergeElapsed}ms) | 入力:${mergeResult.promptTokens}T / 出力:${mergeResult.completionTokens}T / 合計:${mergeResult.totalTokens}T")
            log("   マージ結果プレビュー:\n${mergeResult.text.trim()}")
            NovelDictionaryGenerator.parseDictionaryJson(mergeResult.text)
        } else {
            log("❌ マージ失敗: $mergeResult")
            null
        }

        if (mergedDict != null) {
            log("📖 マージ後辞書: スタイル=【${mergedDict.style}】, 登録人数=${mergedDict.characters.size}名, 性別登録=${mergedDict.genders.size}名")
            mergedDict.characters.forEach { (src, trans) ->
                val g = mergedDict.genders[src]?.let { " [性別: $it]" } ?: ""
                log("   ・$src ➔ $trans$g")
            }
        }

        log("================================================================================")
        log("🎉 Gemini 3.1 Flash-Lite 10倍トークンストレステスト全行程終了")
        log("================================================================================")
    }
}
