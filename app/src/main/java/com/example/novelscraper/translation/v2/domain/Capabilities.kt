package com.example.novelscraper.translation.v2.domain

/**
 * モデル能力記述子。社・モデル差をデータで吸収するための中核。
 * 既知プロバイダーの未知モデルは控えめ既定（OpenRouter: 上限8192・構造化出力なし。
 * Gemini: 上限8192・構造化出力なし）で動作する。
 * 記述子自体が未知（null）の場合は呼出側（Rotation側）で判定する（本ファイルはnullを返さない）。
 * 表はコード内蔵＋設定上書き可能とする（上書き層は別工程）。
 */
sealed interface ThinkingSupport {
    /** 思考機能なし（パラメータを送らない・出さない） */
    data object None : ThinkingSupport

    /** 離散レベル式（対応集合＋既定） */
    data class Levels(val supported: Set<String>, val default: String? = null) : ThinkingSupport

    /** 数値予算式（対応範囲） */
    data class Budget(val range: IntRange) : ThinkingSupport
}

data class SamplingParam(val min: Double, val max: Double)

data class ModelCapabilities(
    val thinking: ThinkingSupport = ThinkingSupport.None,
    val sampling: Map<String, SamplingParam> = emptyMap(),
    val structuredOutput: Boolean = false,
    val safetyTunable: Boolean = false,
    val maxOutputTokens: Int = 65536
)

data class ProviderDescriptor(
    val id: String,
    val models: Map<String, ModelCapabilities> = emptyMap(),
    val unknownDefault: ModelCapabilities = ModelCapabilities(),
    /** 枠の帰属（Geminiはモデル別、他社は共有等）。キー正規化済み小文字で照合する */
    val quotaScopeOf: (String) -> String = { it.trim().lowercase() }
)

fun ProviderDescriptor.capabilitiesFor(modelId: String): ModelCapabilities {
    return models[modelId.trim().lowercase()] ?: unknownDefault
}

private val GEMINI_FLASH_LEVELS_ALL =
    ThinkingSupport.Levels(setOf("minimal", "low", "medium", "high"), default = "medium")

/** Gemini系の共通サンプリング対応（temperature 0.0〜2.0）。topP/repetitionPenaltyは未対応のため送らない */
private val GEMINI_SAMPLING = mapOf("temperature" to SamplingParam(0.0, 2.0))

/** Gemini 2.5系用の思考予算範囲（-1: 動的, 0: OFF, 1..24576: 上限トークン数） */
private val GEMINI_2_5_BUDGET = ThinkingSupport.Budget(-1..24576)

/** Gemini 3.7/3.8系の共通能力（LOW/MEDIUM/HIGH・上限64000）。 */
private val GEMINI_3_7_3_8_FLASH = ModelCapabilities(
    thinking = ThinkingSupport.Levels(setOf("low", "medium", "high"), default = "medium"),
    sampling = GEMINI_SAMPLING,
    structuredOutput = true,
    maxOutputTokens = 64000
)

/** Gemini 3.5/3.6系・軽量版の共通能力（4値思考・上限65536）。 */
private val GEMINI_3_5_FLASH_FAMILY = ModelCapabilities(
    thinking = GEMINI_FLASH_LEVELS_ALL,
    sampling = GEMINI_SAMPLING,
    structuredOutput = true,
    maxOutputTokens = 65536
)

/** Gemini 2.5系の共通能力（思考予算式・上限65536）。 */
private val GEMINI_2_5_FAMILY = ModelCapabilities(
    thinking = GEMINI_2_5_BUDGET,
    sampling = GEMINI_SAMPLING,
    structuredOutput = true,
    maxOutputTokens = 65536
)

/** Gemini旧型・Gemma系の共通能力（思考なし・上限8192）。 */
private val GEMINI_LEGACY_FAMILY = ModelCapabilities(
    thinking = ThinkingSupport.None,
    sampling = GEMINI_SAMPLING,
    maxOutputTokens = 8192
)

/** Gemini既知表。公式対応表の写し（MINIMAL未対応＝3.7/3.8系）。Gemma系は思考なし（None）として収録 */
val GEMINI_DESCRIPTOR = ProviderDescriptor(
    id = "gemini",
    models = mapOf(
        "gemini-3.8-flash" to GEMINI_3_7_3_8_FLASH,
        "gemini-3.7-flash" to GEMINI_3_7_3_8_FLASH,
        "gemini-3.6-flash" to GEMINI_3_5_FLASH_FAMILY,
        "gemini-3.5-flash" to GEMINI_3_5_FLASH_FAMILY,
        "gemini-3.5-flash-lite" to GEMINI_3_5_FLASH_FAMILY,
        "gemini-3.1-flash-lite" to GEMINI_3_5_FLASH_FAMILY,
        "gemini-3-flash-preview" to ModelCapabilities(
            thinking = ThinkingSupport.Levels(
                setOf("minimal", "low", "medium", "high"),
                default = "high"
            ),
            sampling = GEMINI_SAMPLING,
            structuredOutput = true,
            maxOutputTokens = 65536
        ),
        "gemini-2.5-flash" to GEMINI_2_5_FAMILY,
        "gemini-2.5-pro" to GEMINI_2_5_FAMILY,
        "gemini-2.0-flash" to GEMINI_LEGACY_FAMILY,
        "gemini-1.5-flash" to GEMINI_LEGACY_FAMILY,
        "gemini-1.5-pro" to GEMINI_LEGACY_FAMILY,
        "gemma-4-31b-it" to GEMINI_LEGACY_FAMILY
    ),
    unknownDefault = ModelCapabilities(sampling = GEMINI_SAMPLING, maxOutputTokens = 8192)
)

/** OpenRouterの温度のみ能力（上限はモデル別）。 */
private fun orTempOnly(maxOutputTokens: Int, structuredOutput: Boolean = true) = ModelCapabilities(
    sampling = mapOf("temperature" to SamplingParam(0.0, 2.0)),
    structuredOutput = structuredOutput,
    maxOutputTokens = maxOutputTokens
)

/** Claude 3.5系の共通能力（温度0〜1・上限8192）。 */
private val CLAUDE_35_FAMILY = ModelCapabilities(
    sampling = mapOf("temperature" to SamplingParam(0.0, 1.0)),
    structuredOutput = true,
    maxOutputTokens = 8192
)

/** OpenRouter既知表。任意IDのため既定は控えめ（思考なし・温度のみ・上限8192）とする。deepseek-r1は推論型のため構造化出力なし */
val OPENROUTER_DESCRIPTOR = ProviderDescriptor(
    id = "openrouter",
    models = mapOf(
        "deepseek/deepseek-v3.2" to orTempOnly(8192),
        "deepseek/deepseek-v3.1" to orTempOnly(8192),
        "deepseek/deepseek-r1" to orTempOnly(8192, structuredOutput = false),
        "anthropic/claude-3.5-sonnet" to CLAUDE_35_FAMILY,
        "anthropic/claude-3.5-haiku" to CLAUDE_35_FAMILY,
        "anthropic/claude-3.7-sonnet" to ModelCapabilities(
            sampling = mapOf("temperature" to SamplingParam(0.0, 1.0)),
            structuredOutput = true,
            maxOutputTokens = 64000
        ),
        "openai/gpt-4o-mini" to orTempOnly(16384),
        "openai/gpt-4o" to orTempOnly(16384),
        "qwen/qwen-2.5-72b-instruct" to orTempOnly(8192)
    ),
    unknownDefault = ModelCapabilities(
        sampling = mapOf("temperature" to SamplingParam(0.0, 2.0)),
        maxOutputTokens = 8192
    ),
    quotaScopeOf = { "shared" }
)
