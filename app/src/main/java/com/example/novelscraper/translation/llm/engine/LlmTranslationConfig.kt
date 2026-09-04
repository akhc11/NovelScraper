package com.example.novelscraper.translation.llm.engine

import com.example.novelscraper.translation.llm.pipeline.SourceLanguage
import com.example.novelscraper.translation.llm.pipeline.TranslationQualityValidator
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class DictSampleMode(val displayName: String) {
    HEAD("先頭から"),
    UNIFORM("全編均等 (長編向け)")
}

enum class LlmProvider(val displayName: String) {
    GEMINI("Google AI Studio (Gemini)"),
    OPENROUTER("OpenRouter"),
    GROQ("Groq")
}

@Serializable
data class PromptOrderPreset(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val order: List<Int>
)

@Serializable
data class ModelProfile(
    val id: String = UUID.randomUUID().toString(),
    val provider: LlmProvider = LlmProvider.GEMINI,
    val modelName: String,
    val maxOutputChars: Int = 15000,     // 目標日本語出力文字数 (デフォルト15,000文字)
    val thinkingLevel: String = "medium", // minimal, low, medium, high
    val thinkingBudget: Int? = null,     // 0=思考OFF, -1=動的, 正数=トークン数
    val temperature: Double? = null,     // null時はAPIに送信しない
    val splitThresholdBytes: Int = 50000,
    val chunkSizeBytes: Int = 45000,
    val batchMaxBytes: Int = 45000,
    val promptOrder: List<Int> = listOf(1, 1),
    val useCustomPromptOrder: Boolean = false, // trueなら一括設定や言語自動選択に上書きされず、このモデル固有のpromptOrderを絶対優先
    val reasoningEffort: String = "none", // OpenRouter用 (none, low, medium, high)
    val reasoningEnabled: Boolean? = null, // OpenRouter DeepSeek V3.2用 {"reasoning":{"enabled":false}}
    val providerOrder: List<String> = emptyList(), // OpenRouter用 (例: ["upstage", "baidu/fp8"])
    val providerAllowFallbacks: Boolean? = false,  // OpenRouter用 (false=指定社のみ完全固定, true=他社フォールバック許可)
    val topP: Double? = null,
    val repetitionPenalty: Double? = null
)

@Serializable
data class LlmTranslationConfig(
    val provider: LlmProvider = LlmProvider.GEMINI,
    val geminiApiKeys: List<String> = listOf(
        "***REMOVED***",
        "***REMOVED***",
        "***REMOVED***",
        "***REMOVED***",
        "***REMOVED***",
        "***REMOVED***"
    ),
    val geminiRotationEnabled: Boolean = true,
    val geminiCooldownSec: Int = 15,

    val openRouterApiKey: String = "",
    val openRouterEndpoint: String = "https://openrouter.ai/api/v1/chat/completions",

    val groqApiKey: String = "",
    val groqEndpoint: String = "https://api.groq.com/openai/v1/chat/completions",

    // モデル毎の個別プロファイルリスト (巡回順)
    val modelProfiles: List<ModelProfile> = listOf(
        ModelProfile(
            modelName = "gemini-3.5-flash",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = null,
            splitThresholdBytes = 50000,
            chunkSizeBytes = 45000,
            batchMaxBytes = 45000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.6-flash",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = null,
            splitThresholdBytes = 50000,
            chunkSizeBytes = 45000,
            batchMaxBytes = 45000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.7-flash",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = null,
            splitThresholdBytes = 50000,
            chunkSizeBytes = 45000,
            batchMaxBytes = 45000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.1-flash-lite",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = 1.0,
            splitThresholdBytes = 50000,
            chunkSizeBytes = 45000,
            batchMaxBytes = 45000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.5-flash-lite",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = 1.0,
            splitThresholdBytes = 50000,
            chunkSizeBytes = 45000,
            batchMaxBytes = 45000,
            promptOrder = listOf(1, 1)
        )
    ),

    // プロンプト順序のユーザー定義プリセット一覧
    val promptPresets: List<PromptOrderPreset> = listOf(
        PromptOrderPreset(label = "1, 1 (中)", order = listOf(1, 1)),
        PromptOrderPreset(label = "2, 7 (英)", order = listOf(2, 7)),
        PromptOrderPreset(label = "3, 7 (韓)", order = listOf(3, 7)),
        PromptOrderPreset(label = "4, 7 (成人)", order = listOf(4, 7)),
        PromptOrderPreset(label = "1, 6, 7 (意訳)", order = listOf(1, 6, 7))
    ),

    // レガシー互換用の旧プロパティ（ModelProfileに移行済み）
    val geminiModels: List<String> = emptyList(),
    val openRouterModel: String = "google/gemma-4-31b-it:free",
    val groqModel: String = "llama-3.3-70b-versatile",
    val selectedPromptNumber: Int = 1,
    val fallbackPromptNumbers: List<Int> = listOf(1, 1),
    val splitThresholdBytes: Int = 13000,
    val chunkSizeBytes: Int = 12000,
    val batchMaxBytes: Int = 12000,

    val customPrompts: Map<Int, String> = emptyMap(),
    val outputSubDir: String = "翻訳完了_LLM",

    val enableCompletionMarker: Boolean = true,
    val enableTextSplit: Boolean = false,
    val textSplitSizeChars: Int = 7000,

    val enableDictGen: Boolean = false,
    val dictProvider: LlmProvider = LlmProvider.GEMINI,
    val dictModel: String = DEFAULT_DICT_GEMINI_MODEL,
    val dictMergeModel: String = DEFAULT_DICT_GEMINI_MODEL, // レガシー互換用

    // プロバイダー別の個別辞書モデル・設定
    val dictGeminiModel: String = DEFAULT_DICT_GEMINI_MODEL,
    val dictGeminiMergeModel: String = DEFAULT_DICT_GEMINI_MODEL,
    val dictOpenRouterModel: String = DEFAULT_DICT_OPENROUTER_MODEL,
    val dictOpenRouterMergeModel: String = "",
    val dictOpenRouterProviderOrder: List<String> = emptyList(),
    val dictOpenRouterProviderAllowFallbacks: Boolean? = false,
    val dictGroqModel: String = DEFAULT_DICT_GROQ_MODEL,
    val dictGroqMergeModel: String = "",

    val dictTotalParts: Int = 100, // 0 = 全ファイル
    val dictSampleMode: DictSampleMode = DictSampleMode.UNIFORM, // 抽出範囲モード (先頭 / 全編均等)
    val dictBatchMaxBytes: Int = 100000, // 1回のAPI送信最大サイズ (デフォルト100KB ≒ 約5話分)
    val dictMaxTotalScanBytes: Int = 10000000, // 辞書用合計最大スキャン容量 (10MBセーフティガード)
    val dictWorkerCount: Int = 6, // 辞書生成 同時ワーカー数 (キー分散数)
    val dictConcurrencyPerWorker: Int = 5, // 1ワーカーあたりの並列リクエスト数
    val dictParallelCount: Int = 30, // 辞書生成並列数 (互換用: dictWorkerCount * dictConcurrencyPerWorker)
    val dictRequestDelaySec: Int = 0, // 辞書生成 1リクエストごとの待機秒数 (0=待機なし, Gemma等TPM制限時は10〜15秒推奨)
    val dict429CooldownSec: Int = 60, // 辞書生成 429 Quota Exceeded 検知時の待機秒数 (デフォルト60秒)

    val enablePrevSrcContext: Boolean = false,
    val prevSrcContextLines: Int = 20,

    val parallelWorkers: Int = 2, // 本文翻訳並列ワーカー数 (1〜6)
    val filesPerFolder: Int = 0,   // 0=無制限, >0=フォルダあたり上限
    val requestDelaySec: Int = 10,

    // 言語連動プロンプト自動選択 (成人向け等の手動選択を保護するためデフォルトOFF)
    val enableAutoPromptOrder: Boolean = false,
    val autoPromptOrderKorean: List<Int> = listOf(3, 7),
    val autoPromptOrderChinese: List<Int> = listOf(1, 1),
    val autoPromptOrderEnglish: List<Int> = listOf(2, 7),

    // 言語別 品質検証サイズ比設定 (min %, max %)。既定値は TranslationQualityValidator の単一管理点を参照。
    val sizeRatioZhMin: Int = TranslationQualityValidator.ZH_MIN_RATIO,
    val sizeRatioZhMax: Int = TranslationQualityValidator.ZH_MAX_RATIO,
    val sizeRatioKoMin: Int = TranslationQualityValidator.KO_MIN_RATIO,
    val sizeRatioKoMax: Int = TranslationQualityValidator.KO_MAX_RATIO,
    val sizeRatioEnMin: Int = TranslationQualityValidator.EN_MIN_RATIO,
    val sizeRatioEnMax: Int = TranslationQualityValidator.EN_MAX_RATIO,
    val sizeRatioJaMin: Int = TranslationQualityValidator.JA_MIN_RATIO,
    val sizeRatioJaMax: Int = TranslationQualityValidator.JA_MAX_RATIO
) {
    /**
     * 言語別の実効サイズ比範囲 (min %, max %) を取得
     */
    fun getSizeRatioRange(sourceLang: SourceLanguage): Pair<Int, Int> {
        val (minVal, maxVal) = when (sourceLang) {
            SourceLanguage.ZH -> sizeRatioZhMin to sizeRatioZhMax
            SourceLanguage.KO -> sizeRatioKoMin to sizeRatioKoMax
            SourceLanguage.EN -> sizeRatioEnMin to sizeRatioEnMax
            SourceLanguage.JA -> sizeRatioJaMin to sizeRatioJaMax
        }
        val safeMin = minVal.coerceIn(50, 300)
        val safeMax = maxVal.coerceIn(safeMin, 500)
        return safeMin to safeMax
    }

    /**
     * 辞書生成の実効同時APIリクエスト数 (ワーカー数 × 並列数) を取得
     */
    fun getEffectiveDictParallelCount(): Int {
        val calculated = dictWorkerCount * dictConcurrencyPerWorker
        return if (calculated > 0) calculated.coerceIn(1, 30) else dictParallelCount.coerceIn(1, 30)
    }

    fun getEffectiveDictModel(targetProvider: LlmProvider = dictProvider): String {
        val model = when (targetProvider) {
            LlmProvider.GEMINI -> dictGeminiModel.ifBlank { dictModel }
            LlmProvider.OPENROUTER -> dictOpenRouterModel.ifBlank { dictModel }
            LlmProvider.GROQ -> dictGroqModel.ifBlank { dictModel }
        }
        return model.trim().ifBlank {
            when (targetProvider) {
                LlmProvider.GEMINI -> DEFAULT_DICT_GEMINI_MODEL
                LlmProvider.OPENROUTER -> DEFAULT_DICT_OPENROUTER_MODEL
                LlmProvider.GROQ -> DEFAULT_DICT_GROQ_MODEL
            }
        }
    }

    /**
     * 現在の辞書プロバイダーに応じた実効マージ・レビューモデルを取得
     */
    fun getEffectiveDictMergeModel(targetProvider: LlmProvider = dictProvider): String {
        val mergeModel = when (targetProvider) {
            LlmProvider.GEMINI -> dictGeminiMergeModel.ifBlank { dictMergeModel }
            LlmProvider.OPENROUTER -> dictOpenRouterMergeModel
            LlmProvider.GROQ -> dictGroqMergeModel
        }
        return mergeModel.trim().ifBlank { getEffectiveDictModel(targetProvider) }
    }

    /**
     * 辞書生成用の OpenRouter プロバイダー指定 (ルーティング)
     */
    fun getEffectiveDictProviderOrder(): List<String> {
        return if (dictProvider == LlmProvider.OPENROUTER) dictOpenRouterProviderOrder else emptyList()
    }

    /**
     * 辞書生成用の OpenRouter フォールバック許可フラグ
     */
    fun getEffectiveDictProviderAllowFallbacks(): Boolean? {
        return if (dictProvider == LlmProvider.OPENROUTER) dictOpenRouterProviderAllowFallbacks else false
    }

    /**
     * 検出言語とプロファイルの目標出力文字数に応じた実効入力分割閾値（バイト）を取得
     * - 目標日本語出力文字数 (maxOutputChars) から言語別の翻訳膨張率で逆算
     */
    fun getEffectiveSplitThreshold(sourceLang: SourceLanguage, profile: ModelProfile): Int {
        val targetChars = profile.maxOutputChars.coerceIn(2000, 100000)

        val inputBytes = when (sourceLang) {
            // 中国語: 漢字1文字=3B, 日本語への文字膨張率 約1.6倍 ➔ 目標20,000字で約37.5KB
            SourceLanguage.ZH -> (targetChars * 3.0 / 1.6).toInt()
            // 韓国語: ハングル1文字=3B, 日本語への文字膨張率 約1.1倍 ➔ 目標20,000字で約54.5KB
            SourceLanguage.KO -> (targetChars * 3.0 / 1.1).toInt()
            // 英語: 1文字=1B, 1単語≒5Bで日本語約2.8文字 ➔ 目標20,000字で約35.7KB (約7,142単語)
            SourceLanguage.EN -> (targetChars / 2.8 * 5.0).toInt()
            SourceLanguage.JA -> targetChars * 3
        }

        return inputBytes.coerceAtLeast(4000)
    }

    /**
     * 検出言語とプロファイルの目標出力文字数に応じた実効チャンクサイズ（バイト）を取得
     */
    fun getEffectiveChunkSize(sourceLang: SourceLanguage, profile: ModelProfile): Int {
        val threshold = getEffectiveSplitThreshold(sourceLang, profile)
        return (threshold * 0.9).toInt().coerceAtLeast(3000)
    }

    /**
     * 検出言語とプロファイルの目標出力文字数に応じた実効バッチサイズ（バイト）を取得
     * - 小ファイルを [SEG:N] でまとめて翻訳する最大合計バイト数
     */
    fun getEffectiveBatchSize(sourceLang: SourceLanguage, profile: ModelProfile): Int {
        val threshold = getEffectiveSplitThreshold(sourceLang, profile)
        return (threshold * 0.9).toInt().coerceAtLeast(3000)
    }

    /**
     * 検出言語とプロファイルに応じた実効プロンプト順序を取得
     * - profile.useCustomPromptOrder == true の場合は、言語自動選択よりもモデル固有設定を100%最優先 (保護)
     * - それ以外で enableAutoPromptOrder == true の場合は、検出言語ごとのプロンプト順序を適用
     * - それ以外は手動順序 (profile.promptOrder) を適用
     */
    fun getEffectivePromptOrder(sourceLang: SourceLanguage, profile: ModelProfile): List<Int> {
        // モデル個別でカスタムプロンプト順序が有効な場合は絶対最優先
        if (profile.useCustomPromptOrder) {
            return profile.promptOrder
        }
        if (!enableAutoPromptOrder) return profile.promptOrder
        return when (sourceLang) {
            SourceLanguage.KO -> autoPromptOrderKorean.ifEmpty { listOf(3, 7) }
            SourceLanguage.ZH -> autoPromptOrderChinese.ifEmpty { listOf(1, 1) }
            SourceLanguage.EN -> autoPromptOrderEnglish.ifEmpty { listOf(2, 7) }
            SourceLanguage.JA -> profile.promptOrder
        }
    }

    companion object {
        const val DEFAULT_DICT_GEMINI_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_DICT_OPENROUTER_MODEL = "google/gemma-4-31b-it:free"
        const val DEFAULT_DICT_GROQ_MODEL = "llama-3.3-70b-versatile"
    }
}
