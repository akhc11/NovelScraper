package com.example.novelscraper.translation.v2.settings

import kotlinx.serialization.Serializable
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.pipeline.SourceLang

/**
 * 容量計画。文字上限とトークン上限を1箇所で突き合わせた結果。
 * 技術的根拠1行：文字とトークンの二重管理は必ず乖離するため、計画はここだけが作る。
 */
data class CapacityPlan(
    val targetOutputChars: Int,
    val effectiveMaxTokens: Int,
    val inputLimitBytes: Int,
    val splitThresholdBytes: Int,
    val chunkSizeBytes: Int,
    val batchMaxBytes: Int
)

/**
 * v2設定モデル。旧実装の参照・流用なし（必要項目の再定義）。
 * 全項目に既定値を持ち、未知キー無視＋欠落時既定で読む。
 */
@Serializable
data class V2ModelProfile(
    val id: String = "",
    val providerId: String = "gemini",
    val model: String = "",
    val temperature: Double? = null,
    val thinkingLevel: String? = null,
    val thinkingBudget: Int? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
    val repetitionPenalty: Double? = null,
    val reasoningEffort: String? = null,
    val reasoningEnabled: Boolean? = null,
    val providerOrder: List<String> = emptyList(),
    val providerAllowFallbacks: Boolean? = null,
    val useJsonSchema: Boolean = false,
    val promptOrder: List<Int> = listOf(1, 1),
    val useCustomPromptOrder: Boolean = false,
    val maxOutputChars: Int = 15000
)

/**
 * 辞書用プロンプトの利用者上書き。空・空白＝既定文（1〜7の customPrompts と同一約束）。
 * 技術的根拠1行：解決は純粋関数に一任し、保存形式と実行文面を分離する。
 */
@Serializable
data class V2DictPrompts(
    val batch: String = "",
    val merge: String = "",
    val translate: String = ""
)

@Serializable
data class V2DictSettings(
    val enabled: Boolean = false,
    val providerId: String = "gemini",
    val model: String = "",
    val mergeModel: String = "",
    val thinkingLevel: String? = null,
    val providerOrder: List<String> = emptyList(),
    val providerAllowFallbacks: Boolean? = null,
    val workerCount: Int = 6,
    val concurrencyPerWorker: Int = 5,
    val totalParts: Int = 100,
    val batchMaxBytes: Int = 100000,
    val maxTotalScanBytes: Int = 10000000,
    val requestDelaySec: Int = 0,
    val cooldown429Sec: Int = 60,
    val dictPrompts: V2DictPrompts = V2DictPrompts(),
    /** 既定文改訂時の識別用。今回は1固定 */
    val dictPromptsVersion: Int = 1
)

@Serializable
data class V2Limits(
    val parallelWorkers: Int = 3,
    val requestDelaySec: Int = 10,
    val filesPerFolder: Int = 0,
    val outputSubDir: String = "翻訳完了_LLM"
)

/** Physical pre-split settings. Disabled by default (parity with old default). */
@Serializable
data class V2SplitSettings(
    val enabled: Boolean = false,
    val splitSizeChars: Int = 7000,
    val inputEncoding: String = "AUTO"
)

/** Previous-story raw-text tail injection. Disabled by default (parity with old default). */
@Serializable
data class V2PrevContext(
    val enabled: Boolean = false,
    val lines: Int = 20
)

/**
 * 最終推敲（ポストエディット）設定。既定OFF（従来動作を変えない）。
 * prompt空・空白＝既定文（辞書プロンプトと同一約束）。
 * 思考系4項目はすべてnull可・null＝翻訳プロファイルの値を継承（互換維持）。
 * 技術的根拠1行：on/offと文面を1箇所にし、作業者・単複路での扱い違いをなくす。
 */
@Serializable
data class V2RefineSettings(
    val enabled: Boolean = false,
    val prompt: String = "",
    /** Gemini直結用。null＝継承 */
    val thinkingLevel: String? = null,
    /** Gemini旧式（2.5系）用。null＝継承 */
    val thinkingBudget: Int? = null,
    /** OpenRouter用。null＝継承 */
    val reasoningEffort: String? = null,
    /** OpenRouter用。null＝継承 */
    val reasoningEnabled: Boolean? = null
)

/**
 * 推敲用のプロファイル上書き対応表を作る（pure）。
 * 技術的根拠1行：上書き解決を純粋関数に寄せ、巡回器・UI・検証の三者で使い回して乖離をなくす。
 */
fun buildRefineProfileOverrides(
    profiles: List<V2ModelProfile>,
    refine: V2RefineSettings
): Map<String, V2ModelProfile> {
    if (!refine.enabled) return emptyMap()
    if (refine.thinkingLevel == null && refine.thinkingBudget == null &&
        refine.reasoningEffort == null && refine.reasoningEnabled == null
    ) return emptyMap()
    return profiles.filter { it.id.isNotBlank() }.associate { profile ->
        profile.id to profile.copy(
            thinkingLevel = refine.thinkingLevel ?: profile.thinkingLevel,
            thinkingBudget = refine.thinkingBudget ?: profile.thinkingBudget,
            reasoningEffort = refine.reasoningEffort ?: profile.reasoningEffort,
            reasoningEnabled = refine.reasoningEnabled ?: profile.reasoningEnabled
        )
    }
}

/** Prompt selection. Auto stays OFF by default (parity with old default). */
@Serializable
data class V2PromptSelection(
    val autoEnabled: Boolean = false,
    val autoOrderKo: List<Int> = listOf(3, 7),
    val autoOrderZh: List<Int> = listOf(1, 1),
    val autoOrderEn: List<Int> = listOf(2, 7)
)

@Serializable
data class V2PromptPreset(
    val id: String = "",
    val label: String = "",
    val order: List<Int> = listOf(1, 1)
)

fun defaultV2PromptPresets(): List<V2PromptPreset> = listOf(
    V2PromptPreset("zh_std", "中国語 標準", listOf(1, 1)),
    V2PromptPreset("ko_std", "韓国語 標準", listOf(3, 7)),
    V2PromptPreset("en_std", "英語 標準", listOf(2, 7)),
    V2PromptPreset("nsfw", "成人向け (NSFW)", listOf(4, 4)),
    V2PromptPreset("literal", "直訳・構造維持", listOf(5, 7)),
    V2PromptPreset("readable", "意訳・読みやすさ重視", listOf(6, 7))
)

/**
 * 原文に対する日本語訳文のUTF-8バイト比率（%）の許容範囲。
 *
 * 【設計根拠・上限の妥当性について】:
 * 本アプリは「小説本文の章・チャンク単位（数千〜数万文字）」を翻訳するパイプラインである。
 * 単語や1行程度の極小テキストであれば局所的に300%を超えることがあり得るが、
 * まとまった小説の文章では統計的に平均化されるため、英語→日本語のUTF-8バイト比が220%（enMax）を超えることはあり得ない。
 * もし英語小説で220%（300%や320%など）に達している場合、それは正常な意訳ではなく、
 * LLMによる解説文の混入、不要な描写の水増し、あるいは同一フレーズの繰り返し（暴走・ハルシネーション）である。
 * 小説翻訳の品質・安全性を担保する防波堤として、enMax = 220（既定値）を厳格に維持する。
 */
@Serializable
data class V2SizeRatios(
    val zhMin: Int = 102,
    val zhMax: Int = 200,
    val koMin: Int = 90,
    val koMax: Int = 150,
    val enMin: Int = 105,
    val enMax: Int = 220, // 小説翻訳では300%超（320%等）はあり得ず、幻覚・水増しとして弾く
    val jaMin: Int = 100,
    val jaMax: Int = 200
)

@Serializable
data class V2CostCaps(
    val maxTokens: Long? = null,
    val maxCost: Double? = null
)

@Serializable
data class V2Settings(
    val version: Int = 3,
    val geminiKeys: List<String> = emptyList(),
    val openRouterKey: String = "",
    val openRouterEndpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    val profiles: List<V2ModelProfile> = listOf(
        V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash", maxOutputTokens = 65536)
    ),
    val dict: V2DictSettings = V2DictSettings(),
    val limits: V2Limits = V2Limits(),
    val cost: V2CostCaps = V2CostCaps(),
    val split: V2SplitSettings = V2SplitSettings(),
    val prevContext: V2PrevContext = V2PrevContext(),
    val refine: V2RefineSettings = V2RefineSettings(),
    val promptSelection: V2PromptSelection = V2PromptSelection(),
    val customPrompts: Map<Int, String> = emptyMap(),
    val promptPresets: List<V2PromptPreset> = defaultV2PromptPresets(),
    val sizeRatios: V2SizeRatios = V2SizeRatios(),
    val geminiRotationEnabled: Boolean = true,
    val geminiCooldownSec: Int = 60,
    val transientRetryDelaySec: Int = 2
) {
    companion object {
        /**
         * 同梱既定キーは廃止（空）。技術的根拠1行：APK内の秘密情報は逆コンパイルで必ず漏洩するため(BYOK化)、既定値は持たず利用者入力のみとする。
         * 動作例：新規導入→鍵欄は空で「鍵未設定のため開始できません」と案内し、自分の鍵を貼って開始する。旧定数は互換のため残すが中身は空。
         * 注意：漏洩済みの旧6鍵は各プロバイダ側で無効化(ローテーション)すること。履歴からも消えないためgit履歴の掃除は別途必要。
         */
        val DEFAULT_GEMINI_API_KEYS: List<String> = emptyList()

        /**
         * 言語ごとの情報密度と日本語翻訳時の膨張率から、AIモデルの出力上限（maxOutputChars）に
         * 収まる最適な入力バイト数を逆算する（pure）。
         *
         * 計算根拠：
         * - ZH (中国語): 日本語訳で約1.6倍に膨張。UTF-8 3バイト換算 -> (chars / 1.6) * 3
         * - KO (韓国語): 日本語訳で約1.1倍に膨張。UTF-8 3バイト換算 -> (chars / 1.1) * 3
         * - EN (英語): 単語あたり約5バイト、1単語は日本語約2.8文字換算 -> (chars / 2.8) * 5
         * - JA (日本語): 日本語1文字あたり3バイト換算 -> chars * 3
         */
        fun calculateInputLimitBytes(sourceLang: SourceLang, maxOutputChars: Int): Int {
            val chars = maxOutputChars.coerceIn(2000, 100000)
            return when (sourceLang) {
                SourceLang.ZH -> ((chars * 3.0 / 1.6).toInt()).coerceAtLeast(4000)
                SourceLang.KO -> ((chars * 3.0 / 1.1).toInt()).coerceAtLeast(4000)
                SourceLang.EN -> ((chars / 2.8 * 5.0).toInt()).coerceAtLeast(4000)
                SourceLang.JA -> (chars * 3).coerceAtLeast(4000)
            }
        }

        /**
         * 要求トークン上限を能力上限・下限に丸め、思考予約分を控除する。
         * 既定の予約は0（機種別の思考消費量の実測値が入り次第、呼出側で指定する）。
         */
        fun resolveMaxTokens(requested: Int?, modelMax: Int, thinkingReserveTokens: Int = 0): Int {
            val base = requested?.coerceIn(TranslationLimits.MIN_OUTPUT_TOKENS, modelMax) ?: modelMax
            return (base - thinkingReserveTokens).coerceAtLeast(TranslationLimits.MIN_OUTPUT_TOKENS)
        }

        /**
         * 容量計画の唯一の入口。出力予算（文字・トークン）から入力・分割・束ねの上限を一括算出する。
         * 有効トークンが出力上限を下回る場合（利用者の絞り込み・思考予約）は入力も同比率で縮める。
         * 技術的根拠1行：出力予算を絞ったのに入力を据え置くと構造的に打切るため、同比率で連動させる。
         */
        fun planCapacity(
            sourceLang: SourceLang,
            targetOutputChars: Int,
            effectiveMaxTokens: Int,
            modelMaxTokens: Int
        ): CapacityPlan {
            val chars = targetOutputChars.coerceIn(
                TranslationLimits.OUTPUT_CHARS_RANGE.first,
                TranslationLimits.OUTPUT_CHARS_RANGE.last
            )
            val scale = if (modelMaxTokens > 0) {
                (effectiveMaxTokens.toDouble() / modelMaxTokens).coerceIn(0.0, 1.0)
            } else 1.0
            val limit = (calculateInputLimitBytes(sourceLang, chars) * scale).toInt().coerceAtLeast(4000)
            return CapacityPlan(
                targetOutputChars = chars,
                effectiveMaxTokens = effectiveMaxTokens,
                inputLimitBytes = limit,
                splitThresholdBytes = limit,
                chunkSizeBytes = (limit * 0.9).toInt().coerceAtLeast(3000),
                batchMaxBytes = (limit * 0.85).toInt().coerceAtLeast(3000)
            )
        }

        fun inputSizeEstimateKb(maxOutputChars: Int): Triple<Int, Int, Int> {
            val zhBytes = calculateInputLimitBytes(SourceLang.ZH, maxOutputChars)
            val koBytes = calculateInputLimitBytes(SourceLang.KO, maxOutputChars)
            val enBytes = calculateInputLimitBytes(SourceLang.EN, maxOutputChars)
            return Triple(zhBytes / 1024, koBytes / 1024, enBytes / 1024)
        }
    }
}
