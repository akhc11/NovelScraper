package com.example.novelscraper

import com.example.novelscraper.translation.v2.settings.V2Settings
import com.example.novelscraper.translation.v2.settings.V2SettingsPreset
import com.example.novelscraper.translation.v2.settings.DataStoreSettingsRepository
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BackupEnvelopeTest {

    @Test
    fun testEnvelopeRoundTrip() {
        val stores = mapOf(
            BackupEnvelope.STORE_SETTINGS to mapOf("presets_json_v2" to "{}"),
            BackupEnvelope.STORE_V2_SETTINGS to mapOf("v2_settings_json" to "{}"),
            BackupEnvelope.STORE_V2_PRESETS to emptyMap()
        )
        val raw = BackupEnvelopeCodec.build(stores, includeKeys = false, exportedAt = 123L)
        val parsed = BackupEnvelopeCodec.parse(raw)
        assertNotNull(parsed)
        assertEquals(BackupEnvelope.BACKUP_VERSION, parsed!!.version)
        assertEquals(123L, parsed.exportedAt)
        assertFalse(parsed.includeKeys)
        assertEquals(stores, parsed.stores)
    }

    @Test
    fun testScrubRemovesKeysByDefault() {
        val v2Json = DataStoreSettingsRepository.v2Json
        val raw = v2Json.encodeToString(
            V2Settings(geminiKeys = listOf("SECRET1"), openRouterKey = "SECRET2")
        )
        val scrubbed = BackupEnvelopeCodec.scrubSettingsValue(raw, includeKeys = false)
        assertNotNull(scrubbed)
        assertFalse(scrubbed!!.contains("SECRET1"))
        assertFalse(scrubbed.contains("SECRET2"))
        val decoded = v2Json.decodeFromString<V2Settings>(scrubbed)
        assertTrue(decoded.geminiKeys.isEmpty())
        assertEquals("", decoded.openRouterKey)
    }

    @Test
    fun testScrubKeepsKeysWhenIncluded() {
        val v2Json = DataStoreSettingsRepository.v2Json
        val raw = v2Json.encodeToString(
            V2Settings(geminiKeys = listOf("SECRET1"), openRouterKey = "SECRET2")
        )
        assertEquals(raw, BackupEnvelopeCodec.scrubSettingsValue(raw, includeKeys = true))
    }

    @Test
    fun testScrubCorruptReturnsNull() {
        assertNull(BackupEnvelopeCodec.scrubSettingsValue("{broken", includeKeys = false))
        assertNull(BackupEnvelopeCodec.parse("{broken"))
        assertNull(BackupEnvelopeCodec.parse(""))
    }

    @Test
    fun testPresetScrubRemovesLegacyKeys() {
        val v2Json = DataStoreSettingsRepository.v2Json
        val raw = v2Json.encodeToString(
            V2SettingsPreset(
                id = "p1",
                label = "test",
                snapshot = V2Settings(geminiKeys = listOf("OLDKEY"), openRouterKey = "OLDKEY2")
            )
        )
        val scrubbed = BackupEnvelopeCodec.scrubPresetValue(raw)
        assertNotNull(scrubbed)
        assertFalse(scrubbed!!.contains("OLDKEY"))
        val decoded = v2Json.decodeFromString<V2SettingsPreset>(scrubbed)
        assertTrue(decoded.snapshot.geminiKeys.isEmpty())
        assertEquals("p1", decoded.id)
    }
}
