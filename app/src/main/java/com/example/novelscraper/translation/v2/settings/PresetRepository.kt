package com.example.novelscraper.translation.v2.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.novelscraper.translation.v2.domain.TranslationLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.v2PresetDataStore by preferencesDataStore(name = "v2_presets")

/**
 * 全設定プリセット置き場。実働設定とは別fileにし、一覧は索引だけ・実体は適用時の単発読込にする。
 * 技術的根拠1行：DataStoreは全文書換・常駐キャッシュのため、ホットな実働設定と切って保存の巻き添えをなくす。
 */
interface PresetRepository {
    /** 索引のみ（軽量・常駐可）。全文は含まない。 */
    val index: Flow<List<V2PresetIndexEntry>>
    /** 現在設定を保存しIDを返す。上限超過時はnull（呼び元が案内表示する）。 */
    suspend fun save(label: String, settings: V2Settings): String?
    /** 実体1件だけ decode する（IO・遅延）。破損時はnull＋当該キーを掃除する。 */
    suspend fun load(id: String): V2SettingsPreset?
    suspend fun delete(id: String)
}

class DataStorePresetRepository(private val context: Context) : PresetRepository {

    private object Keys {
        val INDEX = stringPreferencesKey("v2_preset_index_json")
        fun preset(id: String) = stringPreferencesKey("v2_preset_" + id)
    }

    private val json = DataStoreSettingsRepository.v2Json

    override val index: Flow<List<V2PresetIndexEntry>> = context.v2PresetDataStore.data.map { prefs ->
        readIndex(prefs[Keys.INDEX])
    }

    override suspend fun save(label: String, settings: V2Settings): String? = withContext(Dispatchers.IO) {
        val clean = label.trim().take(TranslationLimits.PRESET_LABEL_MAX_CHARS)
        val now = System.currentTimeMillis()
        val id = newPresetId()
        val preset = V2SettingsPreset(id, clean.ifBlank { "プリセット" }, now, snapshotForPreset(settings))
        var saved = false
        try {
            context.v2PresetDataStore.edit { prefs ->
                val current = readIndex(prefs[Keys.INDEX])
                if (current.size >= TranslationLimits.PRESET_MAX_COUNT) return@edit
                prefs[Keys.preset(id)] = json.encodeToString(preset)
                prefs[Keys.INDEX] = json.encodeToString(current + V2PresetIndexEntry(id, preset.label, now))
                saved = true
            }
        } catch (e: Exception) {
            android.util.Log.w("V2Presets", "save failed", e)
            return@withContext null
        }
        if (saved) id else null
    }

    override suspend fun load(id: String): V2SettingsPreset? = withContext(Dispatchers.IO) {
        val raw: String? = try {
            context.v2PresetDataStore.data.map { it[Keys.preset(id)] }.first()
        } catch (e: Exception) {
            android.util.Log.w("V2Presets", "load failed", e)
            return@withContext null
        }
        if (raw == null) return@withContext null
        try {
            json.decodeFromString<V2SettingsPreset>(raw)
        } catch (_: Exception) {
            android.util.Log.w("V2Presets", "quarantined corrupt preset ($id)")
            try {
                context.v2PresetDataStore.edit { prefs ->
                    prefs.remove(Keys.preset(id))
                    prefs[Keys.INDEX] = json.encodeToString(readIndex(prefs[Keys.INDEX]).filter { it.id != id })
                }
            } catch (e: Exception) {
                android.util.Log.w("V2Presets", "quarantine failed", e)
            }
            null
        }
    }

    /**
     * 全体バックアップ用：全エントリの排出（索引＋実体。鍵は設計上抜き済み）。
     * 技術的根拠1行：動的キー群のため固定キー列挙ではなくasMap列挙にする。
     */
    suspend fun dumpAll(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            context.v2PresetDataStore.data.map { prefs ->
                prefs.asMap().entries.mapNotNull { (k, v) ->
                    if (v is String) k.name to v else null
                }.toMap()
            }.first()
        } catch (e: Exception) {
            android.util.Log.w("V2Presets", "dump failed", e)
            emptyMap()
        }
    }

    /**
     * 全体バックアップ用：1エントリ検証して採用。不正・不明キーは書かずfalse。
     */
    suspend fun importEntry(key: String, raw: String): Boolean = withContext(Dispatchers.IO) {
        val valid = try {
            if (key == "v2_preset_index_json") {
                json.decodeFromString<List<V2PresetIndexEntry>>(raw)
                true
            } else if (key.startsWith("v2_preset_")) {
                json.decodeFromString<V2SettingsPreset>(raw)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        if (!valid) return@withContext false
        try {
            context.v2PresetDataStore.edit { prefs ->
                prefs[stringPreferencesKey(key)] = raw
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("V2Presets", "import entry failed ($key)", e)
            false
        }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        try {
            context.v2PresetDataStore.edit { prefs ->
                prefs.remove(Keys.preset(id))
                prefs[Keys.INDEX] = json.encodeToString(readIndex(prefs[Keys.INDEX]).filter { it.id != id })
            }
        } catch (e: Exception) {
            android.util.Log.w("V2Presets", "delete failed", e)
        }
        Unit
    }

    private fun readIndex(raw: String?): List<V2PresetIndexEntry> {
        if (raw == null) return emptyList()
        return try {
            json.decodeFromString<List<V2PresetIndexEntry>>(raw)
        } catch (_: Exception) {
            android.util.Log.w("V2Presets", "corrupt preset index")
            emptyList()
        }
    }
}
