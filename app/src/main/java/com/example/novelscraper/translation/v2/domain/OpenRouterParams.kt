package com.example.novelscraper.translation.v2.domain

import com.example.novelscraper.translation.v2.settings.V2ModelProfile

/**
 * OpenRouter固有4項目の解決則（pure・副作用なし）。
 * 技術的根拠1行：能力表で覆えない社方言（reasoning/provider）は送信直前で正規化・除去し、400級誤爆を未然に防ぐ。
 */
val OPENROUTER_REASONING_EFFORTS: Set<String> = setOf("low", "medium", "high")

const val OPENROUTER_PROVIDER_ORDER_MAX: Int = 10
const val OPENROUTER_PROVIDER_NAME_MAX: Int = 64

/** 空・"none"・表外値は未指定（未送信）に落とす。大文字・前後空白は正規化する */
fun resolveReasoningEffort(raw: String?): String? {
    val normalized = raw?.trim()?.lowercase() ?: return null
    if (normalized.isEmpty() || normalized == "none") return null
    return if (normalized in OPENROUTER_REASONING_EFFORTS) normalized else null
}

/** 空要素除去・重複除去・上限打切り。長大な単体名は混入させない */
fun resolveProviderOrder(raw: List<String>): List<String> {
    if (raw.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>()
    for (entry in raw) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.length > OPENROUTER_PROVIDER_NAME_MAX) continue
        out.add(trimmed)
        if (out.size >= OPENROUTER_PROVIDER_ORDER_MAX) break
    }
    return out.toList()
}

data class ResolvedOpenRouterParams(
    val reasoningEffort: String?,
    val reasoningEnabled: Boolean?,
    val providerOrder: List<String>,
    val providerAllowFallbacks: Boolean?
)

/** order空時はallow_fallbacks単独指定を落とす（buildOpenRouterBody側の送出条件と対にする） */
fun resolveOpenRouterParams(
    reasoningEffort: String?,
    reasoningEnabled: Boolean?,
    providerOrder: List<String>,
    providerAllowFallbacks: Boolean?
): ResolvedOpenRouterParams {
    val order = resolveProviderOrder(providerOrder)
    return ResolvedOpenRouterParams(
        reasoningEffort = resolveReasoningEffort(reasoningEffort),
        reasoningEnabled = reasoningEnabled,
        providerOrder = order,
        providerAllowFallbacks = if (order.isNotEmpty()) providerAllowFallbacks else null
    )
}

fun resolveOpenRouterParams(profile: V2ModelProfile): ResolvedOpenRouterParams =
    resolveOpenRouterParams(
        profile.reasoningEffort,
        profile.reasoningEnabled,
        profile.providerOrder,
        profile.providerAllowFallbacks
    )
