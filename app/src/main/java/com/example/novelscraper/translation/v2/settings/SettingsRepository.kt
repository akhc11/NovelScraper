package com.example.novelscraper.translation.v2.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.novelscraper.translation.v2.domain.V2DeclaredEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

private val Context.v2DataStore by preferencesDataStore(name = "v2_settings")

/** 旧取込の結果。合格分のみ継承し、不合格は既定値＋警告表示にする */
data class LegacyImport(
    val settings: V2Settings,
    val warnings: List<String> = emptyList()
)

interface SettingsRepository {
    val settings: Flow<V2Settings>
    suspend fun save(settings: V2Settings)
    suspend fun importLegacy(rawJson: String): LegacyImport
}

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    private object Keys {
        val SETTINGS = stringPreferencesKey("v2_settings_json")
    }

    override val settings: Flow<V2Settings> = context.v2DataStore.data.map { prefs ->
        val raw = prefs[Keys.SETTINGS] ?: return@map V2Settings()
        try {
            v2Json.decodeFromString(V2Settings.serializer(), raw)
        } catch (_: Exception) {
            // 破損時は既存保護：既定値を返し、上書きしない
            V2Settings()
        }
    }

    override suspend fun save(settings: V2Settings) = withContext(Dispatchers.IO) {
        try {
            val raw = v2Json.encodeToString(V2Settings.serializer(), settings)
            context.v2DataStore.edit { it[Keys.SETTINGS] = raw }
            } catch (e: Exception) {
                android.util.Log.w("V2Settings", "save failed", e)
            }
            Unit
        }

    override suspend fun importLegacy(rawJson: String): LegacyImport =
        withContext(Dispatchers.Default) {
            importLegacySettings(rawJson)
        }

    companion object {
        val v2Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        private val importJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 旧設定JSONの一括取込（pure・検証付き）。項目ごとに採用／警告を分ける。
         * 不明プロバイダー・壊値は捨てて警告に回し、全体の失敗にしない。
         * kotlinxのみで実装し、Androidスタブ（org.json）に依存しない。
         */
        fun importLegacySettings(rawJson: String): LegacyImport {
            val warnings = mutableListOf<String>()
            if (rawJson.isBlank()) return LegacyImport(V2Settings(), listOf("旧設定なし（既定値で開始）"))
            val root = try {
                importJson.parseToJsonElement(rawJson) as? JsonObject
                    ?: return LegacyImport(V2Settings(), listOf("旧設定の解析失敗（既定値で開始）: objectではない"))
            } catch (e: Exception) {
                return LegacyImport(V2Settings(), listOf("旧設定の解析失敗（既定値で開始）: ${e.message}"))
            }

            val geminiKeys = root.stringList("geminiApiKeys")
            val openRouterKey = root.stringOr("openRouterApiKey", "")

            val profiles = mutableListOf<V2ModelProfile>()
            val rawProfiles = root["modelProfiles"] as? JsonArray
            if (rawProfiles != null) {
                for (element in rawProfiles) {
                    val p = element as? JsonObject ?: continue
                    val provider = p.stringOr("provider", "GEMINI").uppercase()
                    if (provider != "GEMINI" && provider != "OPENROUTER") {
                        warnings.add("未対応プロバイダーのため除外: $provider")
                        continue
                    }
                    val model = p.stringOr("modelName", "").trim()
                    if (model.isEmpty()) {
                        warnings.add("モデル名空のため除外")
                        continue
                    }
                    profiles.add(
                        V2ModelProfile(
                            id = p.stringOr("id", ""),
                            providerId = provider.lowercase(),
                            model = model,
                            temperature = p.nullableDouble("temperature"),
                            thinkingLevel = p.stringOr("thinkingLevel", "").ifBlank { null },
                            thinkingBudget = p.nullableInt("thinkingBudget"),
                            topP = p.nullableDouble("topP"),
                            repetitionPenalty = p.nullableDouble("repetitionPenalty"),
                            reasoningEffort = p.stringOr("reasoningEffort", "").ifBlank { null },
                            reasoningEnabled = p.nullableBoolean("reasoningEnabled"),
                            providerOrder = p.stringList("providerOrder"),
                            providerAllowFallbacks = p.nullableBoolean("providerAllowFallbacks"),
                            useJsonSchema = p.booleanOr("useJsonSchema", false),
                            promptOrder = p.intList("promptOrder")
                                .filter { it in 1..7 }.ifEmpty { listOf(1, 1) },
                            useCustomPromptOrder = p.booleanOr("useCustomPromptOrder", false),
                            maxOutputChars = p.intOr("maxOutputChars", 15000).coerceIn(2000, 100000)
                        )
                    )
                }
            }

            val dictProviderId = root.stringOr("dictProvider", "GEMINI").lowercase()
                .takeIf { it == "gemini" || it == "openrouter" } ?: run {
                warnings.add("辞書プロバイダー不明のためGemini扱い")
                "gemini"
            }
            val dictModelKey = if (dictProviderId == "openrouter") "dictOpenRouterModel" else "dictGeminiModel"
            val dictMergeKey =
                if (dictProviderId == "openrouter") "dictOpenRouterMergeModel" else "dictGeminiMergeModel"
            val dict = V2DictSettings(
                enabled = root.booleanOr("enableDictGen", false),
                providerId = dictProviderId,
                model = root.stringOr(dictModelKey, "").ifBlank { root.stringOr("dictModel", "") },
                mergeModel = root.stringOr(dictMergeKey, "").ifBlank { root.stringOr("dictMergeModel", "") },
                thinkingLevel = root.stringOr("dictThinkingLevel", "").ifBlank { null },
                providerOrder = root.stringList("dictOpenRouterProviderOrder"),
                providerAllowFallbacks = root.nullableBoolean("dictOpenRouterProviderAllowFallbacks"),
                workerCount = root.intOr("dictWorkerCount", 6).coerceIn(1, 30),
                concurrencyPerWorker = root.intOr("dictConcurrencyPerWorker", 5).coerceIn(1, 10),
                totalParts = root.intOr("dictTotalParts", 100).coerceAtLeast(0),
                batchMaxBytes = root.intOr("dictBatchMaxBytes", 100000).coerceIn(4000, 200000),
                requestDelaySec = root.intOr("dictRequestDelaySec", 0).coerceAtLeast(0),
                cooldown429Sec = root.intOr("dict429CooldownSec", 60).coerceIn(5, 300)
            )

            val limits = V2Limits(
                parallelWorkers = root.intOr("parallelWorkers", 3).coerceIn(1, 6),
                requestDelaySec = root.intOr("requestDelaySec", 10).coerceAtLeast(0),
                filesPerFolder = root.intOr("filesPerFolder", 0).coerceAtLeast(0),
                outputSubDir = root.stringOr("outputSubDir", "翻訳完了_LLM").ifBlank { "翻訳完了_LLM" }
            )

            val split = V2SplitSettings(
                enabled = root.booleanOr("enableTextSplit", false),
                splitSizeChars = root.intOr("textSplitSizeChars", 7000).coerceAtLeast(500),
                inputEncoding = root.stringOr("inputEncoding", "AUTO")
                    .takeIf { V2DeclaredEncoding.parseOrNull(it) != null || it == "AUTO" }
                    ?: "AUTO"
            )

            val prevContext = V2PrevContext(
                enabled = root.booleanOr("enablePrevSrcContext", false),
                lines = root.intOr("prevSrcContextLines", 20).coerceIn(1, 100)
            )

            val promptSelection = V2PromptSelection(
                autoEnabled = root.booleanOr("enableAutoPromptOrder", false),
                autoOrderKo = root.intList("autoPromptOrderKorean").filter { it in 1..7 }.ifEmpty { listOf(3, 7) },
                autoOrderZh = root.intList("autoPromptOrderChinese").filter { it in 1..7 }.ifEmpty { listOf(1, 1) },
                autoOrderEn = root.intList("autoPromptOrderEnglish").filter { it in 1..7 }.ifEmpty { listOf(2, 7) }
            )

            val customPrompts = mutableMapOf<Int, String>()
            val rawCustomPrompts = root["customPrompts"] as? JsonObject
            if (rawCustomPrompts != null) {
                for ((k, v) in rawCustomPrompts) {
                    val num = k.toIntOrNull() ?: continue
                    val text = (v as? JsonPrimitive)?.content ?: continue
                    if (num in 1..7 && text.isNotBlank()) {
                        customPrompts[num] = text
                    }
                }
            }

            val promptPresets = mutableListOf<V2PromptPreset>()
            val rawPresets = root["promptPresets"] as? JsonArray
            if (rawPresets != null) {
                for (elem in rawPresets) {
                    val obj = elem as? JsonObject ?: continue
                    val label = obj.stringOr("label", "").trim()
                    val order = obj.intList("order").filter { it in 1..7 }
                    if (label.isNotEmpty() && order.isNotEmpty()) {
                        promptPresets.add(V2PromptPreset(id = obj.stringOr("id", ""), label = label, order = order))
                    }
                }
            }

            val sizeRatios = V2SizeRatios(
                zhMin = root.intOr("sizeRatioZhMin", 102),
                zhMax = root.intOr("sizeRatioZhMax", 200),
                koMin = root.intOr("sizeRatioKoMin", 90),
                koMax = root.intOr("sizeRatioKoMax", 150),
                enMin = root.intOr("sizeRatioEnMin", 105),
                enMax = root.intOr("sizeRatioEnMax", 220),
                jaMin = root.intOr("sizeRatioJaMin", 100),
                jaMax = root.intOr("sizeRatioJaMax", 200)
            )

            return LegacyImport(
                V2Settings(
                    geminiKeys = geminiKeys.filter { it.isNotBlank() },
                    openRouterKey = openRouterKey,
                    profiles = profiles.ifEmpty {
                        warnings.add("有効モデルなしのため既定モデルを使用")
                        listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash"))
                    },
                    dict = dict,
                    limits = limits,
                    split = split,
                    prevContext = prevContext,
                    promptSelection = promptSelection,
                    customPrompts = customPrompts,
                    promptPresets = promptPresets.ifEmpty { defaultV2PromptPresets() },
                    sizeRatios = sizeRatios,
                    geminiRotationEnabled = root.booleanOr("geminiRotationEnabled", true),
                    geminiCooldownSec = root.intOr("geminiCooldownSec", 60).coerceIn(5, 300)
                ),
                warnings
            )
        }

        private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? {
            val e = this[key] as? JsonPrimitive ?: return null
            if (e is JsonNull) return null
            return e
        }

        private fun JsonObject.stringOr(key: String, default: String): String {
            return try {
                primitiveOrNull(key)?.content ?: default
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.booleanOr(key: String, default: Boolean): Boolean {
            return try {
                primitiveOrNull(key)?.content?.toBooleanStrictOrNull() ?: default
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.intOr(key: String, default: Int): Int {
            return try {
                primitiveOrNull(key)?.content?.toIntOrNull() ?: default
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.stringList(key: String): List<String> {
            return try {
                ((this[key] as? JsonArray)?.mapNotNull {
                    ((it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content)?.trim()?.ifBlank { null }
                }) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun JsonObject.intList(key: String): List<Int> {
            return try {
                ((this[key] as? JsonArray)?.mapNotNull {
                    try {
                        ((it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content)?.toIntOrNull()
                    } catch (_: Exception) {
                        null
                    }
                }) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun JsonObject.nullableDouble(key: String): Double? {
            return try {
                primitiveOrNull(key)?.content?.toDoubleOrNull()
            } catch (_: Exception) {
                null
            }
        }

        private fun JsonObject.nullableInt(key: String): Int? {
            return try {
                primitiveOrNull(key)?.content?.toIntOrNull()
            } catch (_: Exception) {
                null
            }
        }

        private fun JsonObject.nullableBoolean(key: String): Boolean? {
            return try {
                primitiveOrNull(key)?.content?.toBooleanStrictOrNull()
            } catch (_: Exception) {
                null
            }
        }

        private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

        private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
    }
}
