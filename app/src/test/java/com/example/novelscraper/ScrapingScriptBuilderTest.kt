package com.example.novelscraper

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ScrapingScriptBuilderTest {

    @Test
    fun testBuildScrapingScript_containsValidBase64AndUtf8() {
        val config = ScraperConfig(
            folder = "無職転生 〜異世界行ったら本気だす〜",
            title = ".chapter-title",
            body = "#novel_honbun",
            next = ".next-btn",
            chapter = "第(\\d+)話 || @1",
            exclude = ".advertisement, #前書き"
        )

        val script = ScrapingScriptBuilder.buildScrapingScript(config, useImages = false, isDebug = true)

        // スクリプトが空でなく、IIFE形式であること
        assertTrue(script.trim().startsWith("(function() {"))
        assertTrue(script.trim().endsWith("})();"))

        // Base64デコードで日本語が壊れていないか検証
        val b64Pattern = "atob\\('([A-Za-z0-9+/=]+)'\\)".toRegex()
        val match = b64Pattern.find(script)
        assertNotNull("Base64文字列が含まれていること", match)

        val base64Str = match!!.groupValues[1]
        val decodedBytes = Base64.getDecoder().decode(base64Str)
        val decodedJson = String(decodedBytes, Charsets.UTF_8)

        val decodedConfig = Json.decodeFromString<ScraperConfig>(decodedJson)
        assertEquals("無職転生 〜異世界行ったら本気だす〜", decodedConfig.folder)
        assertEquals(".advertisement, #前書き", decodedConfig.exclude)
        assertEquals("第(\\d+)話 || @1", decodedConfig.chapter)
    }

    @Test
    fun testBuildScrapingScript_folderLinkDoesNotPolluteFolderName() {
        val config = ScraperConfig(
            folderLink = ".index-link a"
        )
        val script = ScrapingScriptBuilder.buildScrapingScript(config, useImages = false, isDebug = false)

        // フォルダ名汚染ダミー文字列 (別URL先で取得: ...) が混入していないこと
        assertFalse("folderName にダミー文字列が混入していないこと", script.contains("(別URL先で取得:"))
    }

    @Test
    fun testBuildScrapingScript_nextRegexContainsTsuduku() {
        val config = ScraperConfig()
        val script = ScrapingScriptBuilder.buildScrapingScript(config, useImages = false, isDebug = false)

        // タイポ修復の恒久保証: \u7d9a\u304f (続く) が含まれていること
        assertTrue("次ページ探索正規表現に「続く」が含まれていること", script.contains("\\u7d9a\\u304f"))
        assertFalse("誤字「級く」が含まれていないこと", script.contains("\\u7d1a\\u304f"))
    }

    @Test
    fun testBuildCandidateProbeScript_escapesSpecialCharactersSafely() {
        // シングルクォート、バックスラッシュ、改行を含む危険なセレクタ
        val dangerousSelector = "div[data-title='O\\'Reilly'] > span\n.highlight"
        val probeScript = ScrapingScriptBuilder.buildCandidateProbeScript(dangerousSelector)

        // 改行が除去され、シングルクォートがエスケープされていること
        assertFalse("改行が除去されていること", probeScript.contains("\n.highlight"))
        assertTrue("document.querySelector に渡されていること", probeScript.contains("document.querySelector('div[data-title=\\'O\\\\\\'Reilly\\'] > span .highlight')"))
    }

    @Test
    fun testBuildErudaScript_doesNotContainKotlinDollarVariable() {
        val erudaScript = ScrapingScriptBuilder.buildErudaScript()
        assertTrue(erudaScript.contains("https://cdn.jsdelivr.net/npm/eruda"))
        // Kotlin の $el 誤展開が再発しないことを保証
        assertFalse(erudaScript.contains("eruda._$"))
    }

    @Test
    fun testBuildErudaScript_containsNativeClipboardBridge() {
        val erudaScript = ScrapingScriptBuilder.buildErudaScript()
        // AndroidBridge 経由のクリップボード直結フックが含まれていること
        assertTrue("AndroidBridge.onCopySelector 参照が含まれていること", erudaScript.contains("AndroidBridge.onCopySelector"))
        assertTrue("navigator.clipboard.writeText フックが含まれていること", erudaScript.contains("navigator.clipboard.writeText"))
        assertTrue("document.execCommand('copy') フックが含まれていること", erudaScript.contains("execCommand"))
    }

    @Test
    fun testBuildInspectorScript_containsAllTargetsInOrder() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        assertTrue(inspectorScript.contains("key: 'body'"))
        assertTrue(inspectorScript.contains("key: 'title'"))
        assertTrue(inspectorScript.contains("key: 'next'"))
        assertTrue(inspectorScript.contains("key: 'chapter'"))
        assertTrue(inspectorScript.contains("key: 'folder'"))
        assertTrue(inspectorScript.contains("key: 'folder_link'"))
        assertTrue(inspectorScript.contains("key: 'exclude'"))
    }

    @Test
    fun testBuildInspectorScript_containsTextQuerySearchWithoutScroll() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        // テキスト逆引き検索関数が含まれていること
        assertTrue("searchByText 関数が含まれていること", inspectorScript.contains("function searchByText("))
        assertTrue("ハイライト処理が含まれていること", inspectorScript.contains("highlight(bestEl)"))
        assertTrue("候補ポップアップ表示が含まれていること", inspectorScript.contains("showCandidatePopup(cands"))

        // ユーザー要望の確認: 自動スクロール（scrollIntoView）は含まれていないこと
        assertFalse("自動スクロールは含まれていないこと", inspectorScript.contains("scrollIntoView"))
    }

    @Test
    fun testBuildSearchTextInInspectorScript_escapesProperly() {
        val dangerousQuery = "第1話 'プロローグ' \\ \"引用\"\n改行"
        val script = ScrapingScriptBuilder.buildSearchTextInInspectorScript(dangerousQuery)

        assertFalse("改行が除去されていること", script.contains("\n改行"))
        assertTrue("シングルクォートがエスケープされていること", script.contains("\\'プロローグ\\'"))
        assertTrue("searchByText を呼び出していること", script.contains("__novelInspector.searchByText"))
    }

    @Test
    fun testBuildScrapingScript_folderRegexAndTitleTagSupport() {
        val config = ScraperConfig(
            folder = "title",
            regex = "_(.*)$",
            title = "h1",
            fileRegex = "^第\\d+話\\s*(.*)$"
        )
        val script = ScrapingScriptBuilder.buildScrapingScript(config, useImages = false, isDebug = false)

        // titleタグまたはdocument.title安全抽出ロジックが含まれていること
        assertTrue("titleタグのdocument.title参照が含まれていること", script.contains("document.title || el.textContent"))
        assertTrue("targetSel.toLowerCase() === 'title' の分岐が含まれていること", script.contains("targetSel.toLowerCase() === 'title'"))

        // 作品名 Regex (config.regex) 処理が含まれていること
        assertTrue("作品名Regexのmatch処理が含まれていること", script.contains("f.match(new RegExp(config.regex))"))
        assertTrue("キャプチャグループのフォールバックが含まれていること", script.contains("m[1] !== undefined && m[1] !== null"))

        // タイトル Regex (config.fileRegex) 処理が含まれていること
        assertTrue("タイトルRegexのmatch処理が含まれていること", script.contains("result.title.match(new RegExp(config.fileRegex))"))
    }

    @Test
    fun testBuildScrapingScript_delPrefixReplacementSupport() {
        val config = ScraperConfig(
            folder = "title",
            regex = "del:カクヨム",
            title = "h1",
            fileRegex = "del:\\[完結\\]"
        )
        val script = ScrapingScriptBuilder.buildScrapingScript(config, useImages = false, isDebug = false)

        // del: 置換削除処理が含まれていること
        assertTrue("del: 判定正規表現が含まれていること", script.contains("/^(?:del|delete|remove):/i"))
        assertTrue("作品名del置換ロジックが含まれていること", script.contains("f.replace(new RegExp(pat, 'g'), '')"))
        assertTrue("タイトルdel置換ロジックが含まれていること", script.contains("result.title.replace(new RegExp(pat, 'g'), '')"))
    }
}
