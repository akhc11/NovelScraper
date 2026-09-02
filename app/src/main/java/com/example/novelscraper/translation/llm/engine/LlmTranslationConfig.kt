package com.example.novelscraper.translation.llm.engine

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
    val thinkingLevel: String = "medium", // minimal, low, medium, high
    val thinkingBudget: Int? = null,     // 0=思考OFF, -1=動的, 正数=トークン数
    val temperature: Double? = null,     // null時はAPIに送信しない
    val splitThresholdBytes: Int = 13000,
    val chunkSizeBytes: Int = 12000,
    val batchMaxBytes: Int = 12000,
    val promptOrder: List<Int> = listOf(1, 1),
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
            splitThresholdBytes = 13000,
            chunkSizeBytes = 12000,
            batchMaxBytes = 12000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.6-flash",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = null,
            splitThresholdBytes = 14000,
            chunkSizeBytes = 12000,
            batchMaxBytes = 12000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.7-flash",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = null,
            splitThresholdBytes = 14000,
            chunkSizeBytes = 12000,
            batchMaxBytes = 12000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.1-flash-lite",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = 1.0,
            splitThresholdBytes = 14000,
            chunkSizeBytes = 12000,
            batchMaxBytes = 12000,
            promptOrder = listOf(1, 1)
        ),
        ModelProfile(
            modelName = "gemini-3.5-flash-lite",
            provider = LlmProvider.GEMINI,
            thinkingLevel = "medium",
            temperature = 1.0,
            splitThresholdBytes = 14000,
            chunkSizeBytes = 12000,
            batchMaxBytes = 12000,
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
    val textSplitSizeBytes: Int = 8000,

    val enableDictGen: Boolean = false,
    val dictProvider: LlmProvider = LlmProvider.GEMINI,
    val dictModel: String = "gemma-4-31b-it",
    val dictMergeModel: String = "gemini-3.5-flash", // マージ・レビュー用モデル (空欄時は dictModel を使用)
    val dictTotalParts: Int = 100, // 0 = 全ファイル
    val dictSampleMode: DictSampleMode = DictSampleMode.HEAD, // 抽出範囲モード (先頭 / 全編均等)
    val dictBatchMaxBytes: Int = 50000, // 1回のAPI送信最大サイズ (デフォルト50KB ≒ 約15,000トークン安全圏)
    val dictMaxTotalScanBytes: Int = 2000000, // 辞書用合計最大スキャン容量 (2MBセーフティガード)
    val dictParallelCount: Int = 30, // 辞書生成並列数 (キー数×5推奨, gemma-4-31b: 15RPM/キー)
    val dictRequestDelaySec: Int = 0, // 辞書生成 1リクエストごとの待機秒数 (0=待機なし, Gemma等TPM制限時は10〜15秒推奨)
    val dict429CooldownSec: Int = 60, // 辞書生成 429 Quota Exceeded 検知時の待機秒数 (デフォルト60秒)

    val enablePrevSrcContext: Boolean = false,
    val prevSrcContextLines: Int = 20,

    val parallelWorkers: Int = 2, // 本文翻訳並列ワーカー数 (1〜6)
    val filesPerFolder: Int = 0,   // 0=無制限, >0=フォルダあたり上限
    val requestDelaySec: Int = 2
)