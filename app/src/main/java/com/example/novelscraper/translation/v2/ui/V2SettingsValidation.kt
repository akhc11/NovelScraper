package com.example.novelscraper.translation.v2.ui

import com.example.novelscraper.translation.v2.domain.OPENROUTER_PROVIDER_ORDER_MAX
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.ProviderRegistry
import com.example.novelscraper.translation.v2.domain.SamplingParam
import com.example.novelscraper.translation.v2.domain.ThinkingSupport
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import com.example.novelscraper.translation.v2.domain.resolveDouble
import com.example.novelscraper.translation.v2.domain.resolveProviderOrderReport
import com.example.novelscraper.translation.v2.domain.resolveReasoningEffort
import com.example.novelscraper.translation.v2.domain.toProviderId
import com.example.novelscraper.translation.v2.settings.V2Settings

/**
 * 設定保存前の範囲検査（pure・JVMテスト可）。
 * 技術的根拠1行：値解決則と能力表にもとづく警告を返し、保存ブロック級のみ開始を止める（送信時の最終ゲートはRotation側）。
 */
data class V2SettingsIssue(val message: String, val blocksSave: Boolean)

fun validateV2Settings(settings: V2Settings): List<V2SettingsIssue> {
    val issues = mutableListOf<V2SettingsIssue>()
    if (settings.profiles.isEmpty()) {
        issues.add(V2SettingsIssue("モデル未登録のため開始できません", true))
    }
    settings.profiles.forEachIndexed { index, profile ->
        val label = "モデル${index + 1}"
        val provider = profile.providerId.toProviderId()
        if (provider == null) {
            issues.add(V2SettingsIssue("$label: 未対応プロバイダー ${profile.providerId}", true))
            return@forEachIndexed
        }
        if (profile.model.isBlank()) {
            issues.add(V2SettingsIssue("$label: モデル名が空です", true))
            return@forEachIndexed
        }
        // 技術的根拠1行：能力表を登録簿に一本化する（nullは上部で除外済みのため仕様同一）。
        val caps = ProviderRegistry.capabilitiesFor(provider, profile.model)
        val thinking = caps.thinking
        if (profile.thinkingLevel != null) {
            val allowed = (thinking as? ThinkingSupport.Levels)?.supported
            if (allowed == null) {
                issues.add(V2SettingsIssue("$label: ${profile.model} は思考非対応のため thinkingLevel は送られません", false))
            } else if (profile.thinkingLevel !in allowed) {
                issues.add(V2SettingsIssue("$label: thinkingLevel ${profile.thinkingLevel} は非対応のため送られません", false))
            }
        }
        if (profile.thinkingBudget != null && thinking !is ThinkingSupport.Budget) {
            issues.add(V2SettingsIssue("$label: thinkingBudget は数値予算式モデルのみ有効のため送られません", false))
        }
        // 技術的根拠1行：可否・丸め判定は解決器（resolveDouble）に寄せ、送信側との乖離を防ぐ。
        for ((key, value) in listOf("temperature" to profile.temperature, "topP" to profile.topP)) {
            samplingIssue(label, key, value, caps.sampling[key], profile.model)?.let { issues.add(it) }
        }
        if (profile.repetitionPenalty != null && caps.sampling["repetitionPenalty"] == null) {
            issues.add(V2SettingsIssue("$label: repetitionPenalty は未対応のため送られません", false))
        }
        if (profile.useJsonSchema && !caps.structuredOutput) {
            issues.add(V2SettingsIssue("$label: 構造化出力は未対応のため送られません", false))
        }
        if (profile.maxOutputTokens != null &&
            (profile.maxOutputTokens < TranslationLimits.MIN_OUTPUT_TOKENS || profile.maxOutputTokens > caps.maxOutputTokens)
        ) {
            issues.add(
                V2SettingsIssue(
                    "$label: maxOutputTokens は${TranslationLimits.MIN_OUTPUT_TOKENS}〜${caps.maxOutputTokens}に丸められます",
                    false
                )
            )
        }
        if (provider == ProviderId.OPENROUTER) {
            val rawEffort = profile.reasoningEffort
            if (!rawEffort.isNullOrBlank() && rawEffort.trim().lowercase() != "none" &&
                resolveReasoningEffort(rawEffort) == null
            ) {
                issues.add(V2SettingsIssue("$label: reasoningEffort ${rawEffort} は非対応のため送られません", false))
            }
            if (profile.reasoningEnabled != null && resolveReasoningEffort(rawEffort) != null) {
                issues.add(V2SettingsIssue("$label: reasoningEnabled優先のため reasoningEffort は無視されます", false))
            }
            // 技術的根拠1行：除去判定は解決報告（resolveProviderOrderReport）に寄せ、送信側との乖離を防ぐ。
            val orderReport = resolveProviderOrderReport(profile.providerOrder)
            if (orderReport.droppedBlanks) {
                issues.add(V2SettingsIssue("$label: providerOrder の空要素は除去されます", false))
            }
            if (orderReport.droppedDupes) {
                issues.add(V2SettingsIssue("$label: providerOrder の重複は除去されます", false))
            }
            if (orderReport.droppedLong) {
                issues.add(V2SettingsIssue("$label: providerOrder の長大な名前は除去されます", false))
            }
            if (orderReport.truncated) {
                issues.add(
                    V2SettingsIssue(
                        "$label: providerOrder は先頭${OPENROUTER_PROVIDER_ORDER_MAX}件に切り詰められます",
                        false
                    )
                )
            }
            if (profile.providerAllowFallbacks != null && orderReport.resolved.isEmpty()) {
                issues.add(V2SettingsIssue("$label: providerOrder空のため allow_fallbacks は送られません", false))
            }
        }
        if (profile.promptOrder.isEmpty() || profile.promptOrder.any { it !in TranslationLimits.PROMPT_NUMBER_RANGE }) {
            issues.add(V2SettingsIssue("$label: プロンプト番号は1〜7で指定してください", true))
        }
    }
    if (settings.dict.enabled && settings.dict.thinkingLevel != null && settings.dict.model.isNotBlank()) {
        val dictCaps = ProviderRegistry.capabilitiesForOrNull(settings.dict.providerId, settings.dict.model)
        val dictAllowed = (dictCaps?.thinking as? ThinkingSupport.Levels)?.supported
        if (dictAllowed == null) {
            issues.add(V2SettingsIssue("辞書: ${settings.dict.model} は思考非対応のため thinkingLevel は送られません", false))
        } else if (settings.dict.thinkingLevel !in dictAllowed) {
            issues.add(V2SettingsIssue("辞書: thinkingLevel ${settings.dict.thinkingLevel} は非対応のため送られません", false))
        }
    }
    if (settings.dict.providerId.toProviderId() == ProviderId.OPENROUTER && settings.dict.enabled) {
        val orderReport = resolveProviderOrderReport(settings.dict.providerOrder)
        if (orderReport.droppedBlanks) {
            issues.add(V2SettingsIssue("辞書: providerOrder の空要素は除去されます", false))
        }
        if (settings.dict.providerAllowFallbacks != null && orderReport.resolved.isEmpty()) {
            issues.add(V2SettingsIssue("辞書: providerOrder空のため allow_fallbacks は送られません", false))
        }
    }

    val needsGemini = settings.profiles.any { it.providerId.toProviderId() == ProviderId.GEMINI }
    if (needsGemini && settings.geminiKeys.none { it.isNotBlank() }) {
        issues.add(V2SettingsIssue("Geminiキー未設定のため開始できません", true))
    }
    val needsOpenRouter = settings.profiles.any { it.providerId.toProviderId() == ProviderId.OPENROUTER } ||
        (settings.dict.enabled && settings.dict.providerId.toProviderId() == ProviderId.OPENROUTER)
    if (needsOpenRouter && settings.openRouterKey.isBlank()) {
        issues.add(V2SettingsIssue("OpenRouterキー未設定のため開始できません", true))
    }
    if (settings.dict.enabled && settings.dict.model.isBlank()) {
        issues.add(V2SettingsIssue("辞書モデル未設定のため開始できません（辞書有効時は辞書モデル必須）", true))
    }
    if (settings.limits.parallelWorkers !in TranslationLimits.WORKER_COUNT_RANGE) {
        issues.add(
            V2SettingsIssue(
                "並列ワーカーは${TranslationLimits.WORKER_COUNT_RANGE.first}〜${TranslationLimits.WORKER_COUNT_RANGE.last}で指定してください",
                true
            )
        )
    }
    if (settings.limits.requestDelaySec < 0) {
        issues.add(V2SettingsIssue("要求間隔は0以上で指定してください", true))
    }
    if (settings.geminiCooldownSec < 0) {
        issues.add(V2SettingsIssue("429待機は0以上で指定してください", true))
    }
    if (settings.transientRetryDelaySec < 0) {
        issues.add(V2SettingsIssue("一時エラー待機は0以上で指定してください", true))
    }
    if (settings.limits.filesPerFolder < 0) {
        issues.add(V2SettingsIssue("フォルダ上限は0以上で指定してください", true))
    }
    if (settings.limits.outputSubDir.isBlank()) {
        issues.add(V2SettingsIssue("出力サブディレクトリが空です", true))
    }
    if (settings.dict.workerCount !in TranslationLimits.DICT_WORKER_RANGE) {
        issues.add(
            V2SettingsIssue(
                "辞書ワーカー数は${TranslationLimits.DICT_WORKER_RANGE.first}〜${TranslationLimits.DICT_WORKER_RANGE.last}で指定してください",
                true
            )
        )
    }
    if (settings.dict.concurrencyPerWorker !in TranslationLimits.DICT_CONCURRENCY_RANGE) {
        issues.add(
            V2SettingsIssue(
                "辞書同時実行数は${TranslationLimits.DICT_CONCURRENCY_RANGE.first}〜${TranslationLimits.DICT_CONCURRENCY_RANGE.last}で指定してください",
                true
            )
        )
    }
    if (settings.dict.batchMaxBytes !in TranslationLimits.DICT_BATCH_BYTES_RANGE) {
        issues.add(
            V2SettingsIssue(
                "辞書バッチ上限は${TranslationLimits.DICT_BATCH_BYTES_RANGE.first}〜${TranslationLimits.DICT_BATCH_BYTES_RANGE.last}で指定してください",
                true
            )
        )
    }
    if (settings.split.splitSizeChars < TranslationLimits.SPLIT_MIN_CHARS) {
        issues.add(V2SettingsIssue("分割文字数は${TranslationLimits.SPLIT_MIN_CHARS}以上で指定してください", true))
    }
    if (settings.prevContext.lines !in TranslationLimits.PREV_LINES_RANGE) {
        issues.add(
            V2SettingsIssue(
                "前文脈行数は${TranslationLimits.PREV_LINES_RANGE.first}〜${TranslationLimits.PREV_LINES_RANGE.last}で指定してください",
                true
            )
        )
    }
    val cost = settings.cost
    if ((cost.maxTokens != null && cost.maxTokens < 0) || (cost.maxCost != null && cost.maxCost < 0)) {
        issues.add(V2SettingsIssue("コスト上限は0以上で指定してください", true))
    }
    val ratios = settings.sizeRatios
    for ((label, min, max) in listOf(
        Triple("中国語", ratios.zhMin, ratios.zhMax),
        Triple("韓国語", ratios.koMin, ratios.koMax),
        Triple("英語", ratios.enMin, ratios.enMax),
        Triple("日本語", ratios.jaMin, ratios.jaMax)
    )) {
        if (min > max) {
            issues.add(V2SettingsIssue("${label}サイズ比は最小≦最大で指定してください", true))
        }
    }
    for (profile in settings.profiles) {
        if (profile.maxOutputChars !in TranslationLimits.OUTPUT_CHARS_RANGE) {
            issues.add(
                V2SettingsIssue(
                    "${profile.model.ifBlank { "モデル" }}: 目標文字数は${TranslationLimits.OUTPUT_CHARS_RANGE.first}〜${TranslationLimits.OUTPUT_CHARS_RANGE.last}文字に丸められます",
                    false
                )
            )
        }
    }
    return issues
}

/** サンプリング値の検証。判定は解決器に寄せ、送信側との乖離を防ぐ（pure） */
internal fun samplingIssue(
    label: String,
    key: String,
    value: Double?,
    range: SamplingParam?,
    model: String
): V2SettingsIssue? {
    if (value == null) return null
    if (!value.isFinite()) {
        return V2SettingsIssue("$label: $key は有限数で指定してください（送られません）", false)
    }
    if (range == null) {
        return V2SettingsIssue("$label: $key は $model で未対応のため送られません", false)
    }
    if (resolveDouble(range, null, value).coerced) {
        return V2SettingsIssue("$label: $key は範囲 ${range.min}〜${range.max} に丸められます", false)
    }
    return null
}

/** 数値の保存時丸め（pure）。ブロック級の欠落は直さず呼び側に返す */
fun coercedV2Settings(settings: V2Settings): V2Settings {
    return settings.copy(
        limits = settings.limits.copy(
            parallelWorkers = settings.limits.parallelWorkers.coerceIn(
                TranslationLimits.WORKER_COUNT_RANGE.first,
                TranslationLimits.WORKER_COUNT_RANGE.last
            ),
            requestDelaySec = settings.limits.requestDelaySec.coerceAtLeast(0),
            filesPerFolder = settings.limits.filesPerFolder.coerceAtLeast(0)
        ),
        dict = settings.dict.copy(
            workerCount = settings.dict.workerCount.coerceIn(
                TranslationLimits.DICT_WORKER_RANGE.first,
                TranslationLimits.DICT_WORKER_RANGE.last
            ),
            concurrencyPerWorker = settings.dict.concurrencyPerWorker.coerceIn(
                TranslationLimits.DICT_CONCURRENCY_RANGE.first,
                TranslationLimits.DICT_CONCURRENCY_RANGE.last
            ),
            batchMaxBytes = settings.dict.batchMaxBytes.coerceIn(
                TranslationLimits.DICT_BATCH_BYTES_RANGE.first,
                TranslationLimits.DICT_BATCH_BYTES_RANGE.last
            ),
            maxTotalScanBytes = settings.dict.maxTotalScanBytes.coerceAtLeast(0),
            requestDelaySec = settings.dict.requestDelaySec.coerceAtLeast(0),
            cooldown429Sec = settings.dict.cooldown429Sec.coerceIn(
                TranslationLimits.COOLDOWN_MIN_SEC,
                TranslationLimits.COOLDOWN_MAX_SEC
            )
        ),
        split = settings.split.copy(
            splitSizeChars = settings.split.splitSizeChars.coerceAtLeast(TranslationLimits.SPLIT_MIN_CHARS)
        ),
        prevContext = settings.prevContext.copy(
            lines = settings.prevContext.lines.coerceIn(
                TranslationLimits.PREV_LINES_RANGE.first,
                TranslationLimits.PREV_LINES_RANGE.last
            )
        ),
        geminiCooldownSec = settings.geminiCooldownSec.coerceIn(
            TranslationLimits.COOLDOWN_MIN_SEC,
            TranslationLimits.COOLDOWN_MAX_SEC
        ),
        profiles = settings.profiles.map {
            it.copy(
                maxOutputChars = it.maxOutputChars.coerceIn(
                    TranslationLimits.OUTPUT_CHARS_RANGE.first,
                    TranslationLimits.OUTPUT_CHARS_RANGE.last
                )
            )
        },
        sizeRatios = settings.sizeRatios.copy(
            zhMin = settings.sizeRatios.zhMin.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            zhMax = settings.sizeRatios.zhMax.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            koMin = settings.sizeRatios.koMin.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            koMax = settings.sizeRatios.koMax.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            enMin = settings.sizeRatios.enMin.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            enMax = settings.sizeRatios.enMax.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            jaMin = settings.sizeRatios.jaMin.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            ),
            jaMax = settings.sizeRatios.jaMax.coerceIn(
                TranslationLimits.SIZE_RATIO_RANGE.first,
                TranslationLimits.SIZE_RATIO_RANGE.last
            )
        )
    )
}
