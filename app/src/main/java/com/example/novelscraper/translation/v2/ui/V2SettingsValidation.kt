package com.example.novelscraper.translation.v2.ui

import com.example.novelscraper.translation.v2.domain.GEMINI_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.OPENROUTER_DESCRIPTOR
import com.example.novelscraper.translation.v2.domain.OPENROUTER_PROVIDER_NAME_MAX
import com.example.novelscraper.translation.v2.domain.OPENROUTER_PROVIDER_ORDER_MAX
import com.example.novelscraper.translation.v2.domain.ThinkingSupport
import com.example.novelscraper.translation.v2.domain.capabilitiesFor
import com.example.novelscraper.translation.v2.domain.resolveProviderOrder
import com.example.novelscraper.translation.v2.domain.resolveReasoningEffort
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
        if (profile.providerId != "gemini" && profile.providerId != "openrouter") {
            issues.add(V2SettingsIssue("$label: 未対応プロバイダー ${profile.providerId}", true))
            return@forEachIndexed
        }
        if (profile.model.isBlank()) {
            issues.add(V2SettingsIssue("$label: モデル名が空です", true))
            return@forEachIndexed
        }
        val descriptor = when (profile.providerId) {
            "gemini" -> GEMINI_DESCRIPTOR
            else -> OPENROUTER_DESCRIPTOR
        }
        val caps = descriptor.capabilitiesFor(profile.model)
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
        for ((key, value) in listOf("temperature" to profile.temperature, "topP" to profile.topP)) {
            if (value != null) {
                val range = caps.sampling[key]
                if (range == null) {
                    issues.add(V2SettingsIssue("$label: $key は ${profile.model} で未対応のため送られません", false))
                } else if (value < range.min || value > range.max) {
                    issues.add(V2SettingsIssue("$label: $key は範囲 ${range.min}〜${range.max} に丸められます", false))
                }
            }
        }
        if (profile.repetitionPenalty != null && caps.sampling["repetitionPenalty"] == null) {
            issues.add(V2SettingsIssue("$label: repetitionPenalty は未対応のため送られません", false))
        }
        if (profile.useJsonSchema && !caps.structuredOutput) {
            issues.add(V2SettingsIssue("$label: 構造化出力は未対応のため送られません", false))
        }
        if (profile.maxOutputTokens != null && (profile.maxOutputTokens < 1000 || profile.maxOutputTokens > caps.maxOutputTokens)) {
            issues.add(V2SettingsIssue("$label: maxOutputTokens は1000〜${caps.maxOutputTokens}に丸められます", false))
        }
        if (profile.providerId == "openrouter") {
            val rawEffort = profile.reasoningEffort
            if (!rawEffort.isNullOrBlank() && rawEffort.trim().lowercase() != "none" &&
                resolveReasoningEffort(rawEffort) == null
            ) {
                issues.add(V2SettingsIssue("$label: reasoningEffort ${rawEffort} は非対応のため送られません", false))
            }
            if (profile.reasoningEnabled != null && resolveReasoningEffort(rawEffort) != null) {
                issues.add(V2SettingsIssue("$label: reasoningEnabled優先のため reasoningEffort は無視されます", false))
            }
            val rawOrder = profile.providerOrder
            if (rawOrder.any { it.isBlank() }) {
                issues.add(V2SettingsIssue("$label: providerOrder の空要素は除去されます", false))
            }
            if (rawOrder.size != rawOrder.map { it.trim() }.filter { it.isNotBlank() }.distinct().size) {
                issues.add(V2SettingsIssue("$label: providerOrder の重複は除去されます", false))
            }
            if (rawOrder.any { it.trim().length > OPENROUTER_PROVIDER_NAME_MAX }) {
                issues.add(V2SettingsIssue("$label: providerOrder の長大な名前は除去されます", false))
            }
            if (resolveProviderOrder(rawOrder).size < rawOrder.map { it.trim() }.filter { it.isNotBlank() }.distinct().size) {
                issues.add(
                    V2SettingsIssue(
                        "$label: providerOrder は先頭${OPENROUTER_PROVIDER_ORDER_MAX}件に切り詰められます",
                        false
                    )
                )
            }
            if (profile.providerAllowFallbacks != null && resolveProviderOrder(rawOrder).isEmpty()) {
                issues.add(V2SettingsIssue("$label: providerOrder空のため allow_fallbacks は送られません", false))
            }
        }
        if (profile.promptOrder.isEmpty() || profile.promptOrder.any { it !in 1..7 }) {
            issues.add(V2SettingsIssue("$label: プロンプト番号は1〜7で指定してください", true))
        }
    }
    if (settings.dict.providerId == "openrouter" && settings.dict.enabled) {
        val rawOrder = settings.dict.providerOrder
        if (rawOrder.any { it.isBlank() }) {
            issues.add(V2SettingsIssue("辞書: providerOrder の空要素は除去されます", false))
        }
        if (settings.dict.providerAllowFallbacks != null && resolveProviderOrder(rawOrder).isEmpty()) {
            issues.add(V2SettingsIssue("辞書: providerOrder空のため allow_fallbacks は送られません", false))
        }
    }

    val needsGemini = settings.profiles.any { it.providerId == "gemini" }
    if (needsGemini && settings.geminiKeys.none { it.isNotBlank() }) {
        issues.add(V2SettingsIssue("Geminiキー未設定のため開始できません", true))
    }
    val needsOpenRouter = settings.profiles.any { it.providerId == "openrouter" } ||
        (settings.dict.enabled && settings.dict.providerId == "openrouter")
    if (needsOpenRouter && settings.openRouterKey.isBlank()) {
        issues.add(V2SettingsIssue("OpenRouterキー未設定のため開始できません", true))
    }
    if (settings.dict.enabled && settings.dict.model.isBlank()) {
        issues.add(V2SettingsIssue("辞書モデル未設定のため開始できません（辞書有効時は辞書モデル必須）", true))
    }
    if (settings.limits.parallelWorkers !in 1..6) {
        issues.add(V2SettingsIssue("並列ワーカーは1〜6で指定してください", true))
    }
    if (settings.limits.requestDelaySec < 0) {
        issues.add(V2SettingsIssue("要求間隔は0以上で指定してください", true))
    }
    if (settings.limits.filesPerFolder < 0) {
        issues.add(V2SettingsIssue("フォルダ上限は0以上で指定してください", true))
    }
    if (settings.limits.outputSubDir.isBlank()) {
        issues.add(V2SettingsIssue("出力サブディレクトリが空です", true))
    }
    if (settings.dict.workerCount !in 1..30) {
        issues.add(V2SettingsIssue("辞書ワーカー数は1〜30で指定してください", true))
    }
    if (settings.dict.concurrencyPerWorker !in 1..10) {
        issues.add(V2SettingsIssue("辞書同時実行数は1〜10で指定してください", true))
    }
    if (settings.dict.batchMaxBytes !in 4000..200000) {
        issues.add(V2SettingsIssue("辞書バッチ上限は4000〜200000で指定してください", true))
    }
    if (settings.split.splitSizeChars < 500) {
        issues.add(V2SettingsIssue("分割文字数は500以上で指定してください", true))
    }
    if (settings.prevContext.lines !in 1..100) {
        issues.add(V2SettingsIssue("前文脈行数は1〜100で指定してください", true))
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
    return issues
}

/** 数値の保存時丸め（pure）。ブロック級の欠落は直さず呼び側に返す */
fun coercedV2Settings(settings: V2Settings): V2Settings {
    return settings.copy(
        limits = settings.limits.copy(
            parallelWorkers = settings.limits.parallelWorkers.coerceIn(1, 6),
            requestDelaySec = settings.limits.requestDelaySec.coerceAtLeast(0),
            filesPerFolder = settings.limits.filesPerFolder.coerceAtLeast(0)
        ),
        dict = settings.dict.copy(
            workerCount = settings.dict.workerCount.coerceIn(1, 30),
            concurrencyPerWorker = settings.dict.concurrencyPerWorker.coerceIn(1, 10),
            batchMaxBytes = settings.dict.batchMaxBytes.coerceIn(4000, 200000),
            maxTotalScanBytes = settings.dict.maxTotalScanBytes.coerceAtLeast(0),
            requestDelaySec = settings.dict.requestDelaySec.coerceAtLeast(0),
            cooldown429Sec = settings.dict.cooldown429Sec.coerceIn(5, 300)
        ),
        split = settings.split.copy(
            splitSizeChars = settings.split.splitSizeChars.coerceAtLeast(500)
        ),
        prevContext = settings.prevContext.copy(
            lines = settings.prevContext.lines.coerceIn(1, 100)
        ),
        geminiCooldownSec = settings.geminiCooldownSec.coerceIn(5, 300),
        profiles = settings.profiles.map {
            it.copy(maxOutputChars = it.maxOutputChars.coerceIn(2000, 100000))
        },
        sizeRatios = settings.sizeRatios.copy(
            zhMin = settings.sizeRatios.zhMin.coerceIn(10, 1000),
            zhMax = settings.sizeRatios.zhMax.coerceIn(10, 1000),
            koMin = settings.sizeRatios.koMin.coerceIn(10, 1000),
            koMax = settings.sizeRatios.koMax.coerceIn(10, 1000),
            enMin = settings.sizeRatios.enMin.coerceIn(10, 1000),
            enMax = settings.sizeRatios.enMax.coerceIn(10, 1000),
            jaMin = settings.sizeRatios.jaMin.coerceIn(10, 1000),
            jaMax = settings.sizeRatios.jaMax.coerceIn(10, 1000)
        )
    )
}
