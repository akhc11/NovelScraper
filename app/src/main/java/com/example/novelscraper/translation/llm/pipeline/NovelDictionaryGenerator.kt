package com.example.novelscraper.translation.llm.pipeline

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.api.GeminiApiClient
import com.example.novelscraper.translation.llm.api.LlmApiClient
import com.example.novelscraper.translation.llm.api.LlmApiResult
import com.example.novelscraper.translation.llm.api.OpenAiCompatibleClient
import com.example.novelscraper.translation.llm.engine.DictSampleMode
import com.example.novelscraper.translation.llm.engine.LlmProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class NovelDictionary(
    val style: String = "カタカナ",
    val characters: Map<String, String> = emptyMap(),
    val genders: Map<String, String> = emptyMap() // 原文名 -> "男" | "女"
)
/**
 * 辞書生成用 スレッドセーフなキークールダウントラッカー。
 * 429 Quota Exceeded 検知時に該当キーを一定時間（cooldownSec）クールダウン状態として登録し、
 * 後続の全バッチがそのキーへの無駄なアクセスを一切行わずに生きているキーへ即座にスキップできるようにする。
 */
class DictKeyCooldownTracker(
    private val apiKeys: List<String>,
    private val cooldownSec: Int
) {
    private val cooldownUntilMap = ConcurrentHashMap<Int, Long>()

    val totalKeys: Int get() = apiKeys.size

    fun isCoolingDown(keyIndex: Int): Boolean {
        val until = cooldownUntilMap[keyIndex] ?: return false
        val now = System.currentTimeMillis()
        if (now >= until) {
            cooldownUntilMap.remove(keyIndex)
            return false
        }
        return true
    }

    fun markCooldown(keyIndex: Int) {
        val until = System.currentTimeMillis() + (cooldownSec * 1000L)
        cooldownUntilMap[keyIndex] = until
    }

    fun getAvailableKey(preferredIndex: Int): Pair<Int, String>? {
        if (apiKeys.isEmpty()) return null
        val count = apiKeys.size
        for (i in 0 until count) {
            val idx = (preferredIndex + i) % count
            if (!isCoolingDown(idx)) {
                return idx to apiKeys[idx]
            }
        }
        return null // 全キーがクールダウン中
    }

    fun getMinCooldownRemainingMillis(): Long {
        val now = System.currentTimeMillis()
        val remainingTimes = cooldownUntilMap.values.map { it - now }.filter { it > 0 }
        return if (remainingTimes.isNotEmpty()) remainingTimes.minOrNull() ?: 1000L else 1000L
    }
}


object NovelDictionaryGenerator {

    val BATCH_PROMPT = """[指示]
提供された小説テキストを分析し、登場人物の人名辞書および性別（男/女）を抽出してください。

【厳格な抽出・判定ルール】
1. 表記スタイルの自動判定 (ハイブリッド対応):
   作品の世界観および人名のルーツから最適なスタイル ("カタカナ", "漢字", または "ハイブリッド") を判定してください。
   - 【カタカナ】: 西洋ファンタジー、現代SF、ゲーム転生、サイバーパンク
     (※中国語作品であっても、人名が西洋名の音訳「克莱恩 → クライン」「爱丽丝 → アリス」等の場合)
     韓国現代ドラマ、学園、現代ハンター物 (「강진호 → カン・ジンホ」)
   - 【漢字】: 東洋武侠 (中国武侠・韓国ムヒョプ)、仙侠、修仙、歴史時代劇、三国志系
     (※韓国語作品であっても、人名が東洋伝統の漢字名「청명 → 青明」「진무원 → 陳武遠」等の場合)
     中華伝統の姓名 (「李云 → 李雲」「张三 → 張三」)
   - 【ハイブリッド】: 西洋人・東洋人・現代ハンターが混在する作品では、各人名のルーツに合わせて個別に最適な表記 (西洋名はカタカナ、東洋名は漢字/自然な読み) を割り当ててください。

2. 抽出対象 (純粋な人名・固有名詞のみ):
   - 〇 抽出する: 姓名、フルネーム、愛称、ファーストネーム
   - ✕ 抽出しない (厳禁): 役職・肩書 (隊長、師兄、長老、宗主、社長、S級ハンター)、代名詞 (彼、彼女、黒衣人、老者、少年)、一般名詞 (システム、精霊、魔獣)、地名・組織名

3. 類似名・同姓同名の厳格な分離:
   - 「李云」「李云龙」「李云天」「李云海」のように字面が似ていても、それぞれ別人であるため、絶対に1つに統合せず別々のキーとして正確に抽出してください。

4. 性別判定 (男 / 女):
   - 本文の文脈・三人称・会話から、各登場人物の性別 ("男" または "女") を判定してください。判定不能な場合は省略するか "不明" としてください。

5. 出力フォーマット:
   - "characters": 原文表記 -> 日本語訳
   - "genders": 原文表記 -> "男" または "女"
   - 出力は必ずJSON形式のみとすること (前後の挨拶・コードブロック記号は一切不要)。

{"style":"ハイブリッド","characters":{"강진호":"カン・ジンホ","양은하":"ヤン・ウナ"},"genders":{"강진호":"男","양은하":"女"}}
""".trimIndent()

    val MERGE_PROMPT = """[指示]
以下は同じ小説の異なる範囲から独立に抽出した登場人物辞書の断片です。これらを1つの完全な辞書に統合してください。

【厳格な統合ルール】
1. 世界観の総合判定 & スタイル統一:
   小説全体の舞台設定（西洋ファンタジー・現代・東洋武侠・仙侠等）を深く推論し、作品全体で最も支配的かつ最適な表記スタイル ("カタカナ", "漢字", または "ハイブリッド") を1つ決定してください。
2. 重複排除 & フルネーム優先:
   - 同一人物の表記ゆれ（中黒の有無、長音の違い）は最も自然な1つに統一する。
   - 略称（名前のみ）とフルネーム（姓名）がある場合は【フルネーム】を最優先する。
3. ノイズ削除: 誤って混入した一般名詞・肩書・役職（隊長、師兄、長老、システム等）があれば完全に削除する。
4. 性別の統合:
   - 各断片の性別情報 ("genders") を統合する。片方の断片で性別が判明している場合はその性別を採用する。
5. 出力フォーマット: JSON形式のみを出力 (前後の解説・マークダウン記号は不要):

{"style":"ハイブリッド","characters":{"강진호":"カン・ジンホ","양은하":"ヤン・ウナ"},"genders":{"강진호":"男","양은하":"女"}}
""".trimIndent()

    val REVIEW_PROMPT = """[指示]
以下は統合された小説の登場人物辞書です。最終レビュー（自己検証・クレンジング）を行い、完璧な辞書に仕上げてください。

【最終チェック基準】
1. 地名・組織名（門派名、ギルド名、都市名、国名）が混ざっていないか？ ➔ あれば完全に削除
2. 役職・肩書（隊長、師兄、長老、宗主、社長、S級ハンター）が混ざっていないか？ ➔ あれば完全に削除
3. 一般名詞・システム（システムメッセージ、精霊、魔獣、アイテム名）が混ざっていないか？ ➔ あれば完全に削除
4. 不自然な日本語表記や長音のブレがないか？ ➔ 最も自然な表記に修正
5. 性別情報 ("genders") の整合性確認
6. 出力フォーマット: 最終確定した辞書JSON形式のみを出力 (前後の説明・コードブロック記号は一切不要):

{"style":"ハイブリッド","characters":{"강진호":"カン・ジンホ","양은하":"ヤン・ウナ"},"genders":{"강진호":"男","양은하":"女"}}
""".trimIndent()

    fun parseDictionaryJson(rawJson: String): NovelDictionary? {
        return try {
            var text = rawJson.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val firstBrace = text.indexOf('{')
            val lastBrace = text.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                text = text.substring(firstBrace, lastBrace + 1)
            }

            // 1. 標準デコード試行
            try {
                return LlmApiClient.json.decodeFromString(NovelDictionary.serializer(), text)
            } catch (e: Exception) {
                // フォールバックパースへ移行
            }

            // 2. 柔軟な JsonElement フォールバックパース (ネスト形式等に対応)
            val jsonElement = LlmApiClient.json.parseToJsonElement(text)
            if (jsonElement is kotlinx.serialization.json.JsonObject) {
                val style = jsonElement["style"]?.let {
                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else "カタカナ"
                } ?: "カタカナ"

                val charMap = mutableMapOf<String, String>()
                val genderMap = mutableMapOf<String, String>()

                val charsObj = jsonElement["characters"]
                if (charsObj is kotlinx.serialization.json.JsonObject) {
                    for ((k, v) in charsObj) {
                        when (v) {
                            is kotlinx.serialization.json.JsonPrimitive -> {
                                charMap[k] = v.content
                            }
                            is kotlinx.serialization.json.JsonObject -> {
                                val nameVal = v["name"] ?: v["trans"] ?: v["japanese"]
                                if (nameVal is kotlinx.serialization.json.JsonPrimitive) {
                                    charMap[k] = nameVal.content
                                }
                                val gVal = v["gender"] ?: v["sex"]
                                if (gVal is kotlinx.serialization.json.JsonPrimitive) {
                                    genderMap[k] = gVal.content
                                }
                            }
                            else -> {}
                        }
                    }
                }

                val gendersObj = jsonElement["genders"]
                if (gendersObj is kotlinx.serialization.json.JsonObject) {
                    for ((k, v) in gendersObj) {
                        if (v is kotlinx.serialization.json.JsonPrimitive) {
                            genderMap[k] = v.content
                        }
                    }
                }

                if (charMap.isNotEmpty()) {
                    return NovelDictionary(style = style, characters = charMap, genders = genderMap)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 指定されたパート数・サンプリングモードに基づいて辞書抽出対象ファイルを選定する。
     */
    fun selectSampleFiles(
        allFiles: List<DocumentFile>,
        totalParts: Int,
        sampleMode: DictSampleMode
    ): List<DocumentFile> {
        if (allFiles.isEmpty()) return emptyList()
        val totalCount = allFiles.size
        if (totalParts <= 0 || totalCount <= totalParts) {
            return allFiles
        }

        return when (sampleMode) {
            DictSampleMode.HEAD -> allFiles.take(totalParts)
            DictSampleMode.UNIFORM -> {
                val headCount = (totalParts * 0.50).toInt().coerceAtLeast(1)
                val midCount = (totalParts * 0.25).toInt().coerceAtLeast(1)
                val tailCount = (totalParts - headCount - midCount).coerceAtLeast(1)

                val headRange = 0 until headCount.coerceAtMost(totalCount)

                val midCenter = totalCount / 2
                val midStart = (midCenter - midCount / 2).coerceIn(0, totalCount - 1)
                val midEnd = (midStart + midCount).coerceIn(0, totalCount)
                val midRange = midStart until midEnd

                val tailStart = (totalCount - tailCount).coerceIn(0, totalCount)
                val tailRange = tailStart until totalCount

                val distinctIndices = (headRange + midRange + tailRange).distinct().sorted()

                val finalIndices = if (distinctIndices.size < totalParts) {
                    val set = distinctIndices.toMutableSet()
                    for (i in 0 until totalCount) {
                        if (set.size >= totalParts) break
                        set.add(i)
                    }
                    set.sorted()
                } else {
                    distinctIndices.take(totalParts)
                }

                finalIndices.mapNotNull { allFiles.getOrNull(it) }
            }
        }
    }

    /**
     * テキストサイズに基づき小ファイルを結合し巨大ファイルを分割して安全なバッチリストを生成する。
     */
    fun buildSmartBatches(
        context: Context,
        files: List<DocumentFile>,
        maxBatchBytes: Int,
        maxTotalScanBytes: Int = 2000000
    ): List<String> {
        val batches = mutableListOf<String>()
        val currentBatchBuffer = StringBuilder()
        var currentBatchBytes = 0
        var totalAccumulatedScanBytes = 0

        for (file in files) {
            if (totalAccumulatedScanBytes >= maxTotalScanBytes) {
                break
            }

            val raw = try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    TextCharsetDetector.readTextAutoDetect(stream)
                }
            } catch (e: Exception) {
                null
            } ?: continue

            val content = TextCleanser.cleanse(raw).trim()
            if (content.isBlank()) continue

            val contentBytes = content.toByteArray(Charsets.UTF_8).size
            totalAccumulatedScanBytes += contentBytes

            if (contentBytes > maxBatchBytes) {
                if (currentBatchBuffer.isNotEmpty()) {
                    batches.add(currentBatchBuffer.toString().trim())
                    currentBatchBuffer.clear()
                    currentBatchBytes = 0
                }

                val paragraphs = content.split(Regex("(?<=\\n\\n|\\n)"))
                val chunkSb = StringBuilder()
                var chunkBytes = 0

                for (p in paragraphs) {
                    val pBytes = p.toByteArray(Charsets.UTF_8).size
                    if (chunkBytes + pBytes > maxBatchBytes && chunkSb.isNotEmpty()) {
                        batches.add(chunkSb.toString().trim())
                        chunkSb.clear()
                        chunkBytes = 0
                    }
                    chunkSb.append(p)
                    chunkBytes += pBytes
                }
                if (chunkSb.isNotEmpty()) {
                    batches.add(chunkSb.toString().trim())
                }
            } else {
                if (currentBatchBytes + contentBytes > maxBatchBytes && currentBatchBuffer.isNotEmpty()) {
                    batches.add(currentBatchBuffer.toString().trim())
                    currentBatchBuffer.clear()
                    currentBatchBytes = 0
                }

                if (currentBatchBuffer.isNotEmpty()) {
                    currentBatchBuffer.append("\n\n")
                    currentBatchBytes += 2
                }
                currentBatchBuffer.append(content)
                currentBatchBytes += contentBytes
            }
        }

        if (currentBatchBuffer.isNotEmpty()) {
            batches.add(currentBatchBuffer.toString().trim())
        }

        return batches
    }

    /**
     * 複数パートから辞書をキープール分散並列で抽出し、マージ＆最終レビューを経て確定する。
     * 途中で停止した場合の中断再開 (レジューム) と、不完全マージ防止ガードに対応。
     */
    suspend fun generate(
        context: Context,
        folderDoc: DocumentFile,
        sampleFiles: List<DocumentFile>,
        provider: LlmProvider,
        apiKeys: List<String>,
        model: String,
        mergeModel: String = model,
        endpoint: String = "",
        providerOrder: List<String> = emptyList(),
        providerAllowFallbacks: Boolean? = null,
        maxBatchBytes: Int = 50000,
        maxTotalParts: Int = 100,
        sampleMode: DictSampleMode = DictSampleMode.HEAD,
        maxTotalScanBytes: Int = 2000000,
        parallelCount: Int = 4,
        requestDelaySec: Int = 0,
        cooldown429Sec: Int = 60,
        onLog: (String) -> Unit = {}
    ): NovelDictionary? = coroutineScope {
        val validApiKeys = apiKeys.filter { it.isNotBlank() }
        val targetFiles = selectSampleFiles(sampleFiles, maxTotalParts, sampleMode)
        if (targetFiles.isEmpty() || validApiKeys.isEmpty()) return@coroutineScope null

        val dictBuildingDir = folderDoc.findFile(".dict_building") ?: folderDoc.createDirectory(".dict_building")
        if (dictBuildingDir == null) {
            onLog("❌ 辞書生成: 作業ディレクトリ作成失敗")
            return@coroutineScope null
        }

        val effectiveMaxBytes = maxBatchBytes.coerceIn(4000, 100000)
        val smartBatches = buildSmartBatches(context, targetFiles, effectiveMaxBytes, maxTotalScanBytes)
        val totalBatches = smartBatches.size

        if (smartBatches.isEmpty()) {
            onLog("⚠️ 辞書生成: 有効なテキストがありません")
            return@coroutineScope null
        }

        val effectiveParallel = parallelCount.coerceIn(1, 30)
        val semaphore = Semaphore(effectiveParallel)
        val keyCount = validApiKeys.size
        val modeLabel = if (maxTotalParts <= 0) "全件対象" else "${sampleMode.displayName} (${targetFiles.size}ファイル)"
        val providerOrderLabel = if (provider == LlmProvider.OPENROUTER && providerOrder.isNotEmpty()) " / ルーティング:${providerOrder.joinToString(",")}" else ""
        onLog("📖 辞書生成 開始 (範囲:${modeLabel} ➔ ${totalBatches}バッチ[上限:${effectiveMaxBytes}B] / 並列${effectiveParallel} / 待機:${requestDelaySec}秒 / 429待機:${cooldown429Sec}秒 / 分散キー数:${keyCount} / プロバイダー:${provider.displayName} / モデル:${model}${providerOrderLabel})")

        val keyTracker = DictKeyCooldownTracker(validApiKeys, cooldown429Sec)

        // 各バッチの処理（キープール分散ラウンドロビン ＆ 共有クールダウントラッカー ＆ 自動リトライ）
        val deferredResults = smartBatches.mapIndexed { index, batchText ->
            val batchNum = index + 1
            val batchFileName = String.format("batch_%04d.json", batchNum)
            val existingBatchDoc = dictBuildingDir.findFile(batchFileName)

            async {
                semaphore.withPermit {
                    if (existingBatchDoc != null) {
                        val content = readDocContent(context, existingBatchDoc)
                        if (!content.isNullOrBlank()) {
                            onLog("  📖 辞書生成: バッチ $batchNum/$totalBatches (完了済 / スキップ)")
                            return@withPermit content
                        }
                    }

                    if (batchText.isBlank()) return@withPermit null
                    val bSize = batchText.toByteArray(Charsets.UTF_8).size

                    val maxRetries = (keyCount.coerceAtLeast(1) * 2).coerceIn(2, 6)
                    var preferredKeyIdx = (batchNum - 1) % keyCount

                    for (retry in 0..maxRetries) {
                        // 生存キーの探索（429中のキーは最初から1回も叩かず即スキップ！）
                        val liveKeyEntry = keyTracker.getAvailableKey(preferredKeyIdx)

                        val (currentKeyIdx, currentKey) = if (liveKeyEntry != null) {
                            liveKeyEntry
                        } else {
                            // 全キーがクールダウン中の場合
                            val waitMs = keyTracker.getMinCooldownRemainingMillis().coerceIn(1000L, cooldown429Sec * 1000L)
                            onLog("  ⏳ [全キー制限中] バッチ $batchNum: 全キーがクールダウン中のため ${waitMs / 1000}秒 待機...")
                            delay(waitMs)
                            keyTracker.getAvailableKey(preferredKeyIdx)
                                ?: (preferredKeyIdx to validApiKeys[preferredKeyIdx])
                        }

                        val keyDisplayIndex = currentKeyIdx + 1
                        val retryLabel = if (retry > 0) " [再試行 $retry/$maxRetries]" else ""
                        onLog("  📖 辞書生成: バッチ $batchNum/$totalBatches (${bSize}B / キー[$keyDisplayIndex/$keyCount])${retryLabel} 抽出中...")

                        val result = executeLlmRequest(
                            provider = provider,
                            apiKey = currentKey,
                            model = model,
                            endpoint = endpoint,
                            prompt = BATCH_PROMPT,
                            sourceText = batchText,
                            providerOrder = providerOrder,
                            providerAllowFallbacks = providerAllowFallbacks
                        )

                        when (result) {
                            is LlmApiResult.Success -> {
                                val rawText = result.text.trim()
                                val batchDoc = dictBuildingDir.findFile(batchFileName) ?: dictBuildingDir.createFile("application/json", batchFileName)
                                if (batchDoc != null) {
                                    writeDocContent(context, batchDoc, rawText)
                                }
                                onLog("  📖 辞書生成: バッチ $batchNum/$totalBatches 完了")
                                if (requestDelaySec > 0) {
                                    delay(requestDelaySec * 1000L)
                                }
                                return@withPermit rawText
                            }
                            is LlmApiResult.QuotaExceeded -> {
                                // 429検知：全バッチに即座にクールダウンを共有！
                                keyTracker.markCooldown(currentKeyIdx)

                                if (retry < maxRetries) {
                                    val nextEntry = keyTracker.getAvailableKey(currentKeyIdx + 1)
                                    if (nextEntry != null) {
                                        val nextKeyDisplay = nextEntry.first + 1
                                        onLog("  ⏳ [429 Quota Exceeded] バッチ $batchNum: キー[$keyDisplayIndex]制限(全バッチ共有)。生存キー[$nextKeyDisplay]へ即時交代...")
                                        preferredKeyIdx = nextEntry.first
                                        delay(1000L)
                                    } else {
                                        val waitMs = keyTracker.getMinCooldownRemainingMillis().coerceIn(1000L, cooldown429Sec * 1000L)
                                        onLog("  ⏳ [429 Quota Exceeded] バッチ $batchNum: 全キー制限到達。${waitMs / 1000}秒待機して再試行...")
                                        delay(waitMs)
                                    }
                                } else {
                                    onLog("  ❌ [429 Quota Exceeded] バッチ $batchNum: 最大再試行回数に達しました")
                                }
                            }
                            else -> {
                                if (retry < maxRetries) {
                                    delay(1000L * (retry + 1))
                                }
                            }
                        }
                    }

                    onLog("  ⚠️ 辞書生成: バッチ $batchNum/$totalBatches 失敗")
                    null
                }
            }
        }

        val batchResults = deferredResults.awaitAll().filterNotNull()

        // 【根本治療1】全バッチが100%揃っていない場合は不完全マージを固く禁止し、次回再開に安全保留する
        if (batchResults.size < totalBatches) {
            onLog("⚠️ 辞書生成: 未完了バッチが存在します (${batchResults.size}/$totalBatches バッチ完了)。辞書確定を保留し、次回未完了分から再開します。")
            return@coroutineScope null
        }

        // 1バッチのみの場合はレビューへ直接移行
        val mergedDict: NovelDictionary? = if (batchResults.size == 1) {
            parseDictionaryJson(batchResults.first())
        } else {
            // 複数バッチ結果をマージ (最大3回リトライ)
            onLog("📖 辞書生成: 統合マージ中 (${batchResults.size}断片 ➔ $mergeModel)...")
            val mergeInput = batchResults.mapIndexed { i, json ->
                "[バッチ${i + 1}の辞書断片]\n$json"
            }.joinToString("\n\n")

            var parsedMerged: NovelDictionary? = null

            for (mergeRetry in 0..2) {
                val liveMergeKey = keyTracker.getAvailableKey(mergeRetry)
                val mergeKeyIndex = liveMergeKey?.first ?: (mergeRetry % keyCount)
                val mergeApiKey = liveMergeKey?.second ?: validApiKeys[mergeKeyIndex]
                val mergeKeyDisplay = mergeKeyIndex + 1

                if (mergeRetry > 0) {
                    onLog("  🔄 辞書マージ: 再試行 [${mergeRetry + 1}/3] (キー[$mergeKeyDisplay/$keyCount])...")
                } else {
                    onLog("  🔄 辞書マージ: 実行中 (キー[$mergeKeyDisplay/$keyCount])...")
                }

                val mergeResult = executeLlmRequest(
                    provider = provider,
                    apiKey = mergeApiKey,
                    model = mergeModel,
                    endpoint = endpoint,
                    prompt = MERGE_PROMPT,
                    sourceText = mergeInput,
                    providerOrder = providerOrder,
                    providerAllowFallbacks = providerAllowFallbacks
                )

                when (mergeResult) {
                    is LlmApiResult.Success -> {
                        val parsed = parseDictionaryJson(mergeResult.text)
                        if (parsed != null) {
                            parsedMerged = parsed
                            if (requestDelaySec > 0) {
                                delay(requestDelaySec * 1000L)
                            }
                            break
                        }
                    }
                    is LlmApiResult.QuotaExceeded -> {
                        keyTracker.markCooldown(mergeKeyIndex)
                        if (mergeRetry < 2) {
                            onLog("  ⏳ [429 Quota Exceeded] 辞書マージ: キー[$mergeKeyDisplay]制限検知。別キーへ即時交代...")
                            delay(1000L)
                        }
                    }
                    else -> {
                        if (mergeRetry < 2) delay(1000L * (mergeRetry + 1))
                    }
                }
            }
            parsedMerged
        }

        if (mergedDict == null) {
            onLog("❌ 辞書生成: マージ処理またはパースに失敗しました (次回再挑戦)")
            return@coroutineScope null
        }

        // ---- 最終レビュー (自己検証・ノイズクレンジング: 最大2回リトライ) ----
        onLog("🔍 辞書生成: 最終レビュー・ノイズ精査中 (${mergedDict.characters.size}名 ➔ $mergeModel)...")
        val reviewInput = LlmApiClient.json.encodeToString(NovelDictionary.serializer(), mergedDict)
        var finalDict: NovelDictionary = mergedDict

        for (reviewRetry in 0..1) {
            val liveReviewKey = keyTracker.getAvailableKey(reviewRetry + 1)
            val reviewKeyIdx = liveReviewKey?.first ?: ((reviewRetry + 1) % keyCount)
            val reviewApiKey = liveReviewKey?.second ?: validApiKeys[reviewKeyIdx]
            val reviewKeyIndex = reviewKeyIdx + 1

            if (reviewRetry > 0) {
                onLog("  🔄 辞書レビュー: 再試行 [${reviewRetry + 1}/2] (キー[$reviewKeyIndex/$keyCount])...")
            } else {
                onLog("  🔄 辞書レビュー: 実行中 (キー[$reviewKeyIndex/$keyCount])...")
            }

            val reviewResult = executeLlmRequest(
                provider = provider,
                apiKey = reviewApiKey,
                model = mergeModel,
                endpoint = endpoint,
                prompt = REVIEW_PROMPT,
                sourceText = reviewInput,
                providerOrder = providerOrder,
                providerAllowFallbacks = providerAllowFallbacks
            )

            when (reviewResult) {
                is LlmApiResult.Success -> {
                    val parsedReview = parseDictionaryJson(reviewResult.text)
                    if (parsedReview != null) {
                        finalDict = parsedReview
                        break
                    }
                }
                is LlmApiResult.QuotaExceeded -> {
                    keyTracker.markCooldown(reviewKeyIdx)
                    if (reviewRetry < 1) {
                        onLog("  ⏳ [429 Quota Exceeded] 辞書レビュー: キー[$reviewKeyIndex]制限検知。別キーへ即時交代...")
                        delay(1000L)
                    }
                }
                else -> {
                    if (reviewRetry < 1) delay(1000L)
                }
            }
        }

        saveFinalDictionary(context, folderDoc, dictBuildingDir, finalDict, onLog)
        return@coroutineScope finalDict
    }

    private fun saveFinalDictionary(
        context: Context,
        folderDoc: DocumentFile,
        dictBuildingDir: DocumentFile,
        dict: NovelDictionary,
        onLog: (String) -> Unit
    ) {
        val savedFile = folderDoc.findFile("dictionary.json") ?: folderDoc.createFile("application/json", "dictionary.json")
        if (savedFile != null) {
            writeDocContent(context, savedFile, LlmApiClient.json.encodeToString(NovelDictionary.serializer(), dict))
            deleteDirectoryRecursively(dictBuildingDir)
            onLog("✅ 辞書確定 (最終精査完了): ${dict.characters.size}名 (世界観スタイル:【${dict.style}】)")
        }
    }

    private fun deleteDirectoryRecursively(dir: DocumentFile) {
        try {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    deleteDirectoryRecursively(child)
                } else {
                    child.delete()
                }
            }
            dir.delete()
        } catch (_: Exception) {}
    }

    private fun readDocContent(context: Context, doc: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                TextCharsetDetector.readTextAutoDetect(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDocContent(context: Context, doc: DocumentFile, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun executeLlmRequest(
        provider: LlmProvider,
        apiKey: String,
        model: String,
        endpoint: String,
        prompt: String,
        sourceText: String,
        providerOrder: List<String> = emptyList(),
        providerAllowFallbacks: Boolean? = null
    ): LlmApiResult {
        return when (provider) {
            LlmProvider.GEMINI -> {
                GeminiApiClient.generateContent(
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    sourceText = sourceText
                )
            }
            LlmProvider.OPENROUTER, LlmProvider.GROQ -> {
                OpenAiCompatibleClient.chatCompletion(
                    apiKey = apiKey,
                    model = model,
                    endpoint = endpoint,
                    prompt = prompt,
                    sourceText = sourceText,
                    providerOrder = providerOrder,
                    providerAllowFallbacks = providerAllowFallbacks
                )
            }
        }
    }
}