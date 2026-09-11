package com.example.novelscraper.translation.v2.domain

/**
 * プロバイダー記述子の単一登録簿（Single Source of Truth）。
 * 技術的根拠1行：業界標準のcapability-first・provider/model登録方式に従い、能力表の分散whenを1箇所に寄せて新会社追加を1行登録にする。
 * 動作例：Anthropic追加→ここに1行足すだけで設定・検証・表示・送信が追従する（各所のwhen修正は不要）。
 * 既存2社の判定仕様は維持する：未知値は用途別に使い分ける（検証はnull扱い、UI表示はOpenRouter互換に倒す従来通り）。
 */
object ProviderRegistry {
    val descriptors: Map<ProviderId, ProviderDescriptor> = mapOf(
        ProviderId.GEMINI to GEMINI_DESCRIPTOR,
        ProviderId.OPENROUTER to OPENROUTER_DESCRIPTOR
    )

    /** 型付きIDの厳密解決（検証済み用。未知キーは来ない前提） */
    fun descriptorFor(id: ProviderId): ProviderDescriptor =
        descriptors[id] ?: OPENROUTER_DESCRIPTOR

    /** 文字列からの寛容解決（未知・nullはnull。検証の未対応分岐用） */
    fun descriptorForOrNull(raw: String?): ProviderDescriptor? {
        val id = raw?.toProviderId() ?: return null
        return descriptors[id]
    }

    /** 文字列からの表示用解決（未知はOpenRouter互換に倒す従来仕様を維持） */
    fun descriptorForOrOpenRouter(raw: String?): ProviderDescriptor {
        val id = raw?.toProviderId() ?: return OPENROUTER_DESCRIPTOR
        return descriptors[id] ?: OPENROUTER_DESCRIPTOR
    }

    /** 型付き能力解決 */
    fun capabilitiesFor(id: ProviderId, model: String): ModelCapabilities =
        descriptorFor(id).capabilitiesFor(model)

    /** 寛容能力解決（未知はnull） */
    fun capabilitiesForOrNull(raw: String?, model: String): ModelCapabilities? =
        descriptorForOrNull(raw)?.capabilitiesFor(model)

    /** 表示用能力解決（未知はOpenRouter互換） */
    fun capabilitiesForOrOpenRouter(raw: String?, model: String): ModelCapabilities =
        descriptorForOrOpenRouter(raw).capabilitiesFor(model)
}
