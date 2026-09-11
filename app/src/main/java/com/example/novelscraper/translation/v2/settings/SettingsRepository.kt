package com.example.novelscraper.translation.v2.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.novelscraper.translation.v2.domain.ProviderId
import com.example.novelscraper.translation.v2.domain.TranslationLimits
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
        val BACKUP = stringPreferencesKey("v2_settings_json.corrupt")
    }

    override val settings: Flow<V2Settings> = context.v2DataStore.data.map { prefs ->
        val raw = prefs[Keys.SETTINGS] ?: return@map V2Settings()
        try {
            v2Json.decodeFromString(V2Settings.serializer(), raw)
        } catch (_: Exception) {
            // 破損時は既定値を流すが、上書きはしない（下記の保存時隔離で保護する）。
            V2Settings()
        }
    }

    override suspend fun save(settings: V2Settings) = withContext(Dispatchers.IO) {
        try {
            context.v2DataStore.edit { prefs ->
                // 技術的根拠1行：破損した既存値を既定値で上書き確定させないよう、保存前に隔離する（表示は既定値のまま）。
                val raw = prefs[Keys.SETTINGS]
                if (raw != null && prefs[Keys.BACKUP] == null) {
                    try {
                        v2Json.decodeFromString(V2Settings.serializer(), raw)
                    } catch (_: Exception) {
                        prefs[Keys.BACKUP] = raw
                        android.util.Log.w("V2Settings", "quarantined corrupt settings (${raw.length} chars)")
                    }
                }
                prefs[Keys.SETTINGS] = v2Json.encodeToString(V2Settings.serializer(), settings)
            }
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
            val profiles = importProfiles(root, warnings)
            val dict = importDict(root, warnings)

            return LegacyImport(
                V2Settings(
                    geminiKeys = geminiKeys.filter { it.isNotBlank() },
                    openRouterKey = openRouterKey,
                    profiles = profiles.ifEmpty {
                        warnings.add("有効モデルなしのため既定モデルを使用")
                        listOf(V2ModelProfile(providerId = "gemini", model = "gemini-3.5-flash"))
                    },
                    dict = dict,
                    limits = importLimits(root),
                    split = importSplit(root, warnings),
                    prevContext = importPrevContext(root),
                    promptSelection = importPromptSelection(root),
                    customPrompts = importCustomPrompts(root),
                    promptPresets = importPromptPresets(root).ifEmpty { defaultV2PromptPresets() },
                    sizeRatios = importSizeRatios(root, warnings),
                    geminiRotationEnabled = root.booleanOr("geminiRotationEnabled", true),
                    geminiCooldownSec = root.importBoundedInt(
                        "geminiCooldownSec", 60,
                        TranslationLimits.COOLDOWN_MIN_SEC, TranslationLimits.COOLDOWN_MAX_SEC, warnings
                    ),
                    transientRetryDelaySec = root.importBoundedInt(
                        "transientRetryDelaySec", 2,
                        0, TranslationLimits.WAIT_MAX_SEC.toInt(), warnings
                    )
                ),
                warnings
            )
        }

        /** 区分別の取込（各項目の採用／警告分岐はここに閉じる）。 */
        private fun importProfiles(root: JsonObject, warnings: MutableList<String>): List<V2ModelProfile> {
            val profiles = mutableListOf<V2ModelProfile>()
            val rawProfiles = root["modelProfiles"] as? JsonArray ?: return profiles
            for (element in rawProfiles) {
                val p = element as? JsonObject ?: continue
                val provider = ProviderId.parse(p.stringOr("provider", "GEMINI"))
                if (provider == null) {
                    warnings.add("未対応プロバイダーのため除外: ${p.stringOr("provider", "GEMINI").uppercase()}")
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
                        providerId = provider.id,
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
                            .filter { it in TranslationLimits.PROMPT_NUMBER_RANGE }.ifEmpty { listOf(1, 1) },
                        useCustomPromptOrder = p.booleanOr("useCustomPromptOrder", false),
                        maxOutputChars = p.intOr("maxOutputChars", 15000).coerceIn(
                            TranslationLimits.OUTPUT_CHARS_RANGE.first,
                            TranslationLimits.OUTPUT_CHARS_RANGE.last
                        )
                    )
                )
            }
            return profiles
        }

        private fun importDict(root: JsonObject, warnings: MutableList<String>): V2DictSettings {
            val dictProviderId = root.stringOr("dictProvider", "GEMINI").lowercase()
                .takeIf { ProviderId.parse(it) != null } ?: run {
                warnings.add("辞書プロバイダー不明のためGemini扱い")
                "gemini"
            }
            val dictModelKey = if (dictProviderId == "openrouter") "dictOpenRouterModel" else "dictGeminiModel"
            val dictMergeKey =
                if (dictProviderId == "openrouter") "dictOpenRouterMergeModel" else "dictGeminiMergeModel"
            return V2DictSettings(
                enabled = root.booleanOr("enableDictGen", false),
                providerId = dictProviderId,
                model = root.stringOr(dictModelKey, "").ifBlank { root.stringOr("dictModel", "") },
                mergeModel = root.stringOr(dictMergeKey, "").ifBlank { root.stringOr("dictMergeModel", "") },
                thinkingLevel = root.stringOr("dictThinkingLevel", "").ifBlank { null },
                providerOrder = root.stringList("dictOpenRouterProviderOrder"),
                providerAllowFallbacks = root.nullableBoolean("dictOpenRouterProviderAllowFallbacks"),
                workerCount = root.intOr("dictWorkerCount", 6).coerceIn(
                    TranslationLimits.DICT_WORKER_RANGE.first,
                    TranslationLimits.DICT_WORKER_RANGE.last
                ),
                concurrencyPerWorker = root.intOr("dictConcurrencyPerWorker", 5).coerceIn(
                    TranslationLimits.DICT_CONCURRENCY_RANGE.first,
                    TranslationLimits.DICT_CONCURRENCY_RANGE.last
                ),
                totalParts = root.intOr("dictTotalParts", 100).coerceAtLeast(0),
                batchMaxBytes = root.intOr("dictBatchMaxBytes", 100000).coerceIn(
                    TranslationLimits.DICT_BATCH_BYTES_RANGE.first,
                    TranslationLimits.DICT_BATCH_BYTES_RANGE.last
                ),
                // 技術的根拠1行：辞書走査上限を設定保存から正しく復元し、再起動時の固定値フォールバックを防ぐ。
                maxTotalScanBytes = root.intOr("dictMaxTotalScanBytes", 10000000).coerceAtLeast(100000),
                requestDelaySec = root.intOr("dictRequestDelaySec", 0).coerceAtLeast(0),
                cooldown429Sec = root.intOr("dict429CooldownSec", 60).coerceIn(
                    TranslationLimits.COOLDOWN_MIN_SEC,
                    TranslationLimits.COOLDOWN_MAX_SEC
                )
            )
        }

        private fun importLimits(root: JsonObject): V2Limits {
            return V2Limits(
                parallelWorkers = root.intOr("parallelWorkers", 3).coerceIn(
                    TranslationLimits.WORKER_COUNT_RANGE.first,
                    TranslationLimits.WORKER_COUNT_RANGE.last
                ),
                requestDelaySec = root.intOr("requestDelaySec", 10).coerceAtLeast(0),
                filesPerFolder = root.intOr("filesPerFolder", 0).coerceAtLeast(0),
                outputSubDir = root.stringOr("outputSubDir", "翻訳完了_LLM").ifBlank { "翻訳完了_LLM" }
            )
        }

        private fun importSplit(root: JsonObject, warnings: MutableList<String>): V2SplitSettings {
            val encodingRaw = root.stringOr("inputEncoding", "AUTO")
            val encoding = encodingRaw
                .takeIf { V2DeclaredEncoding.parseOrNull(it) != null || it == "AUTO" }
                ?: run {
                    warnings.add("文字コード指定が不明のためAUTO扱い ($encodingRaw)")
                    "AUTO"
                }
            return V2SplitSettings(
                enabled = root.booleanOr("enableTextSplit", false),
                splitSizeChars = root.intOr("textSplitSizeChars", 7000).coerceAtLeast(TranslationLimits.SPLIT_MIN_CHARS),
                inputEncoding = encoding
            )
        }

        private fun importPrevContext(root: JsonObject): V2PrevContext {
            return V2PrevContext(
                enabled = root.booleanOr("enablePrevSrcContext", false),
                lines = root.intOr("prevSrcContextLines", 20).coerceIn(
                    TranslationLimits.PREV_LINES_RANGE.first,
                    TranslationLimits.PREV_LINES_RANGE.last
                )
            )
        }

        private fun importPromptSelection(root: JsonObject): V2PromptSelection {
            return V2PromptSelection(
                autoEnabled = root.booleanOr("enableAutoPromptOrder", false),
                autoOrderKo = root.intList("autoPromptOrderKorean")
                    .filter { it in TranslationLimits.PROMPT_NUMBER_RANGE }.ifEmpty { listOf(3, 7) },
                autoOrderZh = root.intList("autoPromptOrderChinese")
                    .filter { it in TranslationLimits.PROMPT_NUMBER_RANGE }.ifEmpty { listOf(1, 1) },
                autoOrderEn = root.intList("autoPromptOrderEnglish")
                    .filter { it in TranslationLimits.PROMPT_NUMBER_RANGE }.ifEmpty { listOf(2, 7) }
            )
        }

        private fun importCustomPrompts(root: JsonObject): Map<Int, String> {
            val customPrompts = mutableMapOf<Int, String>()
            val rawCustomPrompts = root["customPrompts"] as? JsonObject ?: return customPrompts
            for ((k, v) in rawCustomPrompts) {
                val num = k.toIntOrNull() ?: continue
                val text = (v as? JsonPrimitive)?.content ?: continue
                if (num in TranslationLimits.PROMPT_NUMBER_RANGE && text.isNotBlank()) {
                    customPrompts[num] = text
                }
            }
            return customPrompts
        }

        private fun importPromptPresets(root: JsonObject): List<V2PromptPreset> {
            val promptPresets = mutableListOf<V2PromptPreset>()
            val rawPresets = root["promptPresets"] as? JsonArray ?: return promptPresets
            for (elem in rawPresets) {
                val obj = elem as? JsonObject ?: continue
                val label = obj.stringOr("label", "").trim()
                val order = obj.intList("order").filter { it in TranslationLimits.PROMPT_NUMBER_RANGE }
                if (label.isNotEmpty() && order.isNotEmpty()) {
                    promptPresets.add(V2PromptPreset(id = obj.stringOr("id", ""), label = label, order = order))
                }
            }
            return promptPresets
        }

        private fun importSizeRatios(root: JsonObject, warnings: MutableList<String>): V2SizeRatios {
            fun bounded(key: String, default: Int): Int =
                root.importBoundedInt(key, default, TranslationLimits.SIZE_RATIO_RANGE.first, TranslationLimits.SIZE_RATIO_RANGE.last, warnings)
            return V2SizeRatios(
                zhMin = bounded("sizeRatioZhMin", 102),
                zhMax = bounded("sizeRatioZhMax", 200),
                koMin = bounded("sizeRatioKoMin", 90),
                koMax = bounded("sizeRatioKoMax", 150),
                enMin = bounded("sizeRatioEnMin", 105),
                enMax = bounded("sizeRatioEnMax", 220),
                jaMin = bounded("sizeRatioJaMin", 100),
                jaMax = bounded("sizeRatioJaMax", 200)
            )
        }

        /**
         * 範囲付き整数の取込。欠落・不正は既定値、範囲外は丸めて警告する。
         * 技術的根拠1行：無言の既定値化・無言丸めをなくし、取込の採用／警告分岐をここに閉じる。
         */
        private fun JsonObject.importBoundedInt(
            key: String,
            default: Int,
            min: Int,
            max: Int,
            warnings: MutableList<String>
        ): Int {
            val raw = try {
                primitiveOrNull(key)?.content
            } catch (_: Exception) {
                null
            } ?: return default
            val parsed = raw.toIntOrNull()
            if (parsed == null) {
                warnings.add("$key が数値でないため既定値を使用")
                return default
            }
            if (parsed < min || parsed > max) {
                warnings.add("$key が範囲外のため丸め ($parsed)")
            }
            return parsed.coerceIn(min, max)
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
    }
}
