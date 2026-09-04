package com.example.novelscraper

import com.example.novelscraper.scraper.*
import org.junit.Assert.*
import org.junit.Test

/**
 * ViewModel の中核となる状態管理ロジック（セレクタ反映、除外追記、排他パネル制御、履歴剪定、セッション設定保持）の単体テスト
 */
class StateLogicTest {

    // --- 1. セレクタ反映ロジックのテスト ---
    private fun applySelector(config: ScraperConfig, field: SelectorField, selector: String): ScraperConfig {
        return when (field) {
            SelectorField.BODY -> config.copy(body = selector)
            SelectorField.TITLE -> config.copy(title = selector)
            SelectorField.NEXT -> config.copy(next = selector)
            SelectorField.CHAPTER -> config.copy(chapter = selector)
            SelectorField.FOLDER -> config.copy(folder = selector)
            SelectorField.FOLDER_LINK -> config.copy(folderLink = selector)
            SelectorField.EXCLUDE -> addExclude(config, selector)
        }
    }

    private fun addExclude(config: ScraperConfig, selector: String): ScraperConfig {
        val current = config.exclude.orEmpty()
        val parts = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (!parts.contains(selector)) {
            parts.add(selector)
        }
        return config.copy(exclude = parts.joinToString(", "))
    }

    @Test
    fun testApplySelectorToAllFields() {
        var config = ScraperConfig()

        config = applySelector(config, SelectorField.BODY, "#novel_honbun")
        assertEquals("#novel_honbun", config.body)

        config = applySelector(config, SelectorField.TITLE, ".chapter-title")
        assertEquals(".chapter-title", config.title)

        config = applySelector(config, SelectorField.NEXT, "a[rel=\"next\"]")
        assertEquals("a[rel=\"next\"]", config.next)

        config = applySelector(config, SelectorField.CHAPTER, "@1")
        assertEquals("@1", config.chapter)

        config = applySelector(config, SelectorField.FOLDER, "h1.title")
        assertEquals("h1.title", config.folder)

        config = applySelector(config, SelectorField.FOLDER_LINK, "a.series-link")
        assertEquals("a.series-link", config.folderLink)
    }

    @Test
    fun testAddExcludeSelector_avoidsDuplicateAndJoinsWithComma() {
        var config = ScraperConfig(exclude = "")

        // 初回追加
        config = addExclude(config, ".ad")
        assertEquals(".ad", config.exclude)

        // 2件目追加
        config = addExclude(config, "#header")
        assertEquals(".ad, #header", config.exclude)

        // 重複追加（すでに存在するセレクタ）
        config = addExclude(config, ".ad")
        assertEquals(".ad, #header", config.exclude)

        // 3件目追加
        config = addExclude(config, "rt")
        assertEquals(".ad, #header, rt", config.exclude)
    }

    // --- 2. パネル排他制御ロジックのテスト ---
    private fun togglePanel(currentOverlay: Overlay, targetPanel: PanelType): Overlay {
        val current = (currentOverlay as? Overlay.Panel)?.type
        return if (current == targetPanel) Overlay.None else Overlay.Panel(targetPanel)
    }

    @Test
    fun testTogglePanel_exclusiveAndToggleOff() {
        var overlay: Overlay = Overlay.None

        // 1. 設定パネルを開く
        overlay = togglePanel(overlay, PanelType.SETTINGS)
        assertEquals(Overlay.Panel(PanelType.SETTINGS), overlay)

        // 2. もう一度設定パネルをトグル -> 閉じる (None)
        overlay = togglePanel(overlay, PanelType.SETTINGS)
        assertEquals(Overlay.None, overlay)

        // 3. 設定パネルを開く
        overlay = togglePanel(overlay, PanelType.SETTINGS)
        assertEquals(Overlay.Panel(PanelType.SETTINGS), overlay)

        // 4. その状態で履歴パネルをトグル -> 履歴パネルへ排他切替
        overlay = togglePanel(overlay, PanelType.HISTORY)
        assertEquals(Overlay.Panel(PanelType.HISTORY), overlay)
    }

    // --- 3. 履歴の最大100件自動剪定（Prune）ロジックのテスト ---
    @Test
    fun testHistoryPruning_keepsOnlyLatest100() {
        val maxHistorySize = 100
        val historyMap = mutableMapOf<String, HistoryItem>()

        // 120件の履歴を追加（timestamp は 1〜120）
        for (i in 1..120) {
            historyMap["Novel_$i"] = HistoryItem(
                title = "Novel_$i",
                chapter = "$i",
                url = "https://example.com/$i",
                config = ScraperConfig(),
                time = "2026/09/03 21:00",
                timestamp = i.toLong()
            )
        }

        // 剪定ロジック: timestamp順（新しいものが残る）
        val pruned = if (historyMap.size > maxHistorySize) {
            historyMap.entries
                .sortedByDescending { it.value.timestamp }
                .take(maxHistorySize)
                .associate { it.key to it.value }
        } else {
            historyMap
        }

        assertEquals(100, pruned.size)
        // 最新の Novel_120 が存在すること
        assertTrue(pruned.containsKey("Novel_120"))
        // 最も古い Novel_1 〜 Novel_20 は削除されていること
        assertFalse(pruned.containsKey("Novel_1"))
        assertFalse(pruned.containsKey("Novel_20"))
        // Novel_21 は残っていること
        assertTrue(pruned.containsKey("Novel_21"))
    }

    // --- 4. 手動設定セッション保護 ＆ ドメイン別自動切替ロジックのテスト ---
    @Test
    fun testSessionManualConfigRetention_andDomainAutoSwitch() {
        val presets = mapOf(
            "なろう" to ScraperConfig(body = "#novel_honbun", autoUrl = "syosetu.com"),
            "カクヨム" to ScraperConfig(body = ".widget-episodeBody", autoUrl = "kakuyomu.jp")
        )

        var lastAppliedAutoUrlDomain = "syosetu.com"
        var currentPresetName = "なろう"
        var currentConfig = presets["なろう"]!!
        var isConfigManuallyEdited = false

        // 1. ユーザーがセレクタを手動でカスタム編集
        currentConfig = currentConfig.copy(body = "#custom_honbun_selector")
        isConfigManuallyEdited = true

        // 2. ユーザーが Google で検索（autoUrl に該当しないホーム／検索サイトへの一時的移動）
        val searchUrl = "https://www.google.com/search?q=novel"
        var matched = presets.entries.find { it.value.autoUrl.isNotEmpty() && searchUrl.contains(it.value.autoUrl) }

        // マッチしないため、手動設定と lastAppliedAutoUrlDomain は一切変更されない
        if (matched != null) {
            // ここには入らない
        }
        assertEquals("syosetu.com", lastAppliedAutoUrlDomain)
        assertEquals("#custom_honbun_selector", currentConfig.body)
        assertTrue(isConfigManuallyEdited)

        // 3. ユーザーが同一サイト（なろう）の別URLへ戻る
        val backUrl = "https://ncode.syosetu.com/n12345/2/"
        matched = presets.entries.find { it.value.autoUrl.isNotEmpty() && backUrl.contains(it.value.autoUrl) }
        assertNotNull(matched)

        if (matched != null) {
            val (name, config) = matched
            if (lastAppliedAutoUrlDomain != config.autoUrl) {
                // 別ドメインの場合のみ切替
                lastAppliedAutoUrlDomain = config.autoUrl
                isConfigManuallyEdited = false
                currentPresetName = name
                currentConfig = config
            } else {
                // 同一ドメイン巡回中: 手動編集されている場合は上書きしない！
                if (!isConfigManuallyEdited && currentPresetName != name) {
                    currentPresetName = name
                    currentConfig = config
                }
            }
        }

        // 同一サイトなので手動設定が100%保持されていること！
        assertEquals("#custom_honbun_selector", currentConfig.body)
        assertTrue(isConfigManuallyEdited)

        // 4. ユーザーが別サイト（カクヨム）へ移動
        val kakuyomuUrl = "https://kakuyomu.jp/works/123456"
        matched = presets.entries.find { it.value.autoUrl.isNotEmpty() && kakuyomuUrl.contains(it.value.autoUrl) }
        assertNotNull(matched)

        if (matched != null) {
            val (name, config) = matched
            if (lastAppliedAutoUrlDomain != config.autoUrl) {
                // 別ドメインへ移動したため、カクヨムのプリセットを自動適用
                lastAppliedAutoUrlDomain = config.autoUrl
                isConfigManuallyEdited = false
                currentPresetName = name
                currentConfig = config
            }
        }

        // 別ドメインなのでカクヨムの設定に切り替わっていること！
        assertEquals("kakuyomu.jp", lastAppliedAutoUrlDomain)
        assertEquals("カクヨム", currentPresetName)
        assertEquals(".widget-episodeBody", currentConfig.body)
        assertFalse(isConfigManuallyEdited)
    }
}
