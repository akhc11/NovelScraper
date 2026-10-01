package com.example.novelscraper

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.novelscraper.scraper.ScraperConfig
import com.example.novelscraper.translation.v2.settings.DataStorePresetRepository
import com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository
import com.example.novelscraper.translation.v2.settings.V2PresetIndexEntry
import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SettingsPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

/**
 * 全体バックアップ（設定・履歴・お気に入り・LLM翻訳含む）の永続化担当。
 * 技術的根拠1行：3 DataStoreともキー→文字列のraw-mapで均一化し、検証は既存のdecodeOrAbort・隔離・未知キー無視に寄せる。
 *
 * 対象外：本文txt（端末ファイル・容量の壁）、SAF権限（OSの永続許可は移行不能のため復元時は再許可）。
 * 取込は追記（存在キーは上書き、欠落キーは維持）。APIキーは書出時のみ除外選択可。
 */
@Serializable
data class BackupEnvelope(
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val includeKeys: Boolean = false,
    val stores: Map<String, Map<String, String>> = emptyMap()
) {
    companion object {
        const val BACKUP_VERSION = 1
        const val STORE_SETTINGS = "settings"
        const val STORE_V2_SETTINGS = "v2_settings"
        const val STORE_V2_PRESETS = "v2_presets"
    }
}

data class BackupReport(
    val restored: Map<String, Int>,
    val skipped: List<String>,
    val includeKeys: Boolean
)

/** エンベロープ組立・解析・鍵抜きのpure層（JVMテスト可）。 */
object BackupEnvelopeCodec {
    fun build(
        stores: Map<String, Map<String, String>>,
        includeKeys: Boolean,
        exportedAt: Long
    ): String = dataStoreJson.encodeToString(
        BackupEnvelope(
            version = BackupEnvelope.BACKUP_VERSION,
            exportedAt = exportedAt,
            includeKeys = includeKeys,
            stores = stores
        )
    )

    fun parse(raw: String): BackupEnvelope? = try {
        dataStoreJson.decodeFromString<BackupEnvelope>(raw)
    } catch (_: Exception) {
        null
    }

    /**
     * v2生設定の鍵抜き。includeKeys時はそのまま。
     * 技術的根拠1行：鍵抜きはsnapshotForPresetと同一約束（copyで空化）に寄せる。
     */
    fun scrubSettingsValue(raw: String, includeKeys: Boolean): String? {
        if (includeKeys) return raw
        return try {
            val decoded = DataStoreSettingsRepository.v2Json.decodeFromString<V2Settings>(raw)
            DataStoreSettingsRepository.v2Json.encodeToString(
                decoded.copy(geminiKeys = emptyList(), openRouterKey = "")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** v2プリセット実体の鍵抜き（設計上抜き済みだが旧データ防衛で再適用）。 */
    fun scrubPresetValue(raw: String): String? {
        return try {
            val decoded = DataStoreSettingsRepository.v2Json.decodeFromString<V2SettingsPreset>(raw)
            val snap = decoded.snapshot
            DataStoreSettingsRepository.v2Json.encodeToString(
                decoded.copy(snapshot = snap.copy(geminiKeys = emptyList(), openRouterKey = ""))
            )
        } catch (_: Exception) {
            null
        }
    }
}

class BackupRepository(private val context: Context) {

    private val v2SettingsRepository = DataStoreSettingsRepository(context)
    private val v2PresetRepository = DataStorePresetRepository(context)

    suspend fun exportBackup(uri: Uri, includeKeys: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildBackupJson(includeKeys)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("Failed to open output stream")
        }
    }

    suspend fun importBackup(uri: Uri): Result<BackupReport> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonStr = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: throw java.io.IOException("Failed to open input stream")
            restoreBackupJson(jsonStr)
        }
    }

    private suspend fun buildBackupJson(includeKeys: Boolean): String {
        val settingsMap = context.appDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (k, v) ->
                if (v is String) k.name to v else null
            }.toMap()
        }.first()

        val v2SettingsMap = mutableMapOf<String, String>()
        v2SettingsRepository.exportValue()?.let { raw ->
            val out = BackupEnvelopeCodec.scrubSettingsValue(raw, includeKeys)
            if (out != null) v2SettingsMap[V2_SETTINGS_KEY] = out
        }

        val v2PresetsMap = mutableMapOf<String, String>()
        v2PresetRepository.dumpAll().forEach { (k, v) ->
            val out = if (k == V2_PRESET_INDEX_KEY || includeKeys) {
                v
            } else {
                BackupEnvelopeCodec.scrubPresetValue(v)
            }
            if (out != null) v2PresetsMap[k] = out
        }

        return BackupEnvelopeCodec.build(
            stores = mapOf(
                BackupEnvelope.STORE_SETTINGS to settingsMap,
                BackupEnvelope.STORE_V2_SETTINGS to v2SettingsMap,
                BackupEnvelope.STORE_V2_PRESETS to v2PresetsMap
            ),
            includeKeys = includeKeys,
            exportedAt = System.currentTimeMillis()
        )
    }

    private suspend fun restoreBackupJson(jsonStr: String): BackupReport {
        val envelope = BackupEnvelopeCodec.parse(jsonStr)
            ?: throw IllegalArgumentException("無効なバックアップファイルです")
        if (envelope.version > BackupEnvelope.BACKUP_VERSION) {
            throw IllegalArgumentException("新しい形式のため読めません (v${envelope.version})")
        }

        val restored = mutableMapOf<String, Int>()
        val skipped = mutableListOf<String>()

        envelope.stores[BackupEnvelope.STORE_SETTINGS]?.forEach { (key, raw) ->
            if (isValidSettingsEntry(key, raw)) {
                context.appDataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = raw }
                restored[BackupEnvelope.STORE_SETTINGS] = (restored[BackupEnvelope.STORE_SETTINGS] ?: 0) + 1
            } else {
                skipped.add("${BackupEnvelope.STORE_SETTINGS}/$key")
            }
        }

        envelope.stores[BackupEnvelope.STORE_V2_SETTINGS]?.forEach { (key, raw) ->
            if (v2SettingsRepository.importValue(raw)) {
                restored[BackupEnvelope.STORE_V2_SETTINGS] = (restored[BackupEnvelope.STORE_V2_SETTINGS] ?: 0) + 1
            } else {
                skipped.add("${BackupEnvelope.STORE_V2_SETTINGS}/$key")
            }
        }

        envelope.stores[BackupEnvelope.STORE_V2_PRESETS]?.forEach { (key, raw) ->
            if (v2PresetRepository.importEntry(key, raw)) {
                restored[BackupEnvelope.STORE_V2_PRESETS] = (restored[BackupEnvelope.STORE_V2_PRESETS] ?: 0) + 1
            } else {
                skipped.add("${BackupEnvelope.STORE_V2_PRESETS}/$key")
            }
        }

        return BackupReport(restored, skipped, envelope.includeKeys)
    }

    private fun isValidSettingsEntry(key: String, raw: String): Boolean {
        return try {
            when (key) {
                PrefKeys.PRESETS.name -> {
                    dataStoreJson.decodeFromString<Map<String, ScraperConfig>>(raw); true
                }
                PrefKeys.HISTORY.name -> {
                    dataStoreJson.decodeFromString<Map<String, HistoryItem>>(raw); true
                }
                PrefKeys.FAVORITES.name -> {
                    dataStoreJson.decodeFromString<Map<String, String>>(raw); true
                }
                PrefKeys.PICKER_ROOTS.name -> {
                    dataStoreJson.parseToJsonElement(raw) is JsonObject; true
                }
                // 遅延・encoding等のスカラーは読取時に既定値へ丸めるためそのまま採用する。
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val V2_SETTINGS_KEY = "v2_settings_json"
        private const val V2_PRESET_INDEX_KEY = "v2_preset_index_json"
    }
}
