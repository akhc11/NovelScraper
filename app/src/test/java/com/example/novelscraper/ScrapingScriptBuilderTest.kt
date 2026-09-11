package com.example.novelscraper

import com.example.novelscraper.scraper.*
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
    fun testBuildInspectorScript_containsTenCandidatesAndPopup() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        // 候補ポップアップ表示が含まれていること
        assertTrue("候補ポップアップ表示が含まれていること", inspectorScript.contains("showCandidatePopup(cands"))
        // 最大10候補までの提示が含まれていること
        assertTrue("最大10候補提示が含まれていること", inspectorScript.contains("cands.slice(0, 10)"))
        // 一致件数（matchCount）が行ラベルに表示されること（非一意セレクタの警告）
        assertTrue("一致件数表示が含まれていること", inspectorScript.contains("c.matchCount > 1"))
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

    @Test
    fun testBuildSearchTextScript_containsHeadTitleMetaAndDirectSelector() {
        val script = ScrapingScriptBuilder.buildSearchTextScript("仙子的修行")

        // IIFE形式であること
        assertTrue(script.trim().startsWith("(function(){"))
        assertTrue(script.trim().endsWith("})();"))

        // <title> & document.title の検査が含まれていること
        assertTrue("document.title の検査が含まれていること", script.contains("document.title"))
        assertTrue("title タグの候補追加が含まれていること", script.contains("document.querySelector('title')"))

        // <meta> タグの検査が含まれていること
        assertTrue("metaタグの属性content探索が含まれていること", script.contains("document.querySelectorAll('meta[content]')"))

        // 全DOM（document.documentElement）のテキスト探索が含まれていること
        assertTrue("全DOMツリー（document.documentElement）の走査が含まれていること", script.contains("document.createTreeWalker(document.documentElement"))

        // セレクタ直接指定の判定ロジックが含まれていること
        assertTrue("セレクタ直接指定の判定が含まれていること", script.contains("document.querySelectorAll(query)"))
    }

    @Test
    fun testBuildInspectorScript_supportsConfigUpdateWithoutReinjection() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        // 起動済み場合は updateConfig で差分同期して return（リスナー再登録なし）
        assertTrue("updateConfig呼び出しが含まれていること", inspectorScript.contains("__novelInspector.updateConfig"))
        assertTrue("updateConfig定義が含まれていること", inspectorScript.contains("updateConfig: function(base64)"))
        // 再注入ガード内で return していること
        assertTrue("起動済みガードが含まれていること", inspectorScript.contains("if (window.__novelInspectActive)"))
    }

    @Test
    fun testBuildInspectorScript_cleansUpFullyOnStop() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        // stop() でマーク・dim・styleタグを完全除去すること
        assertTrue("clearInspectorMarks定義が含まれていること", inspectorScript.contains("function clearInspectorMarks()"))
        assertTrue("stop内でclearInspectorMarksを呼ぶこと", inspectorScript.contains("clearInspectorMarks();"))
        assertTrue("styleタグにIDが付与されていること", inspectorScript.contains("__novel_inspector_style"))
        assertTrue("stop内でstyleタグを除去すること", inspectorScript.contains("getElementById('__novel_inspector_style')"))
        // removeEventListener が capture=true で呼ばれていること
        assertTrue(inspectorScript.contains("removeEventListener('click', handleClick, true)"))
    }

    @Test
    fun testBuildInspectorScript_usesCssTextForStyleInit() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        // 非標準の `el.style = '...'` 代入が残っていないこと
        assertFalse(
            "styleへの直接文字列代入が残っていないこと",
            Regex("""\w+\.style = '""").containsMatchIn(inspectorScript)
        )
        assertTrue("cssText初期化が含まれていること", inspectorScript.contains("hint.style.cssText"))
        assertTrue("popupのcssText初期化が含まれていること", inspectorScript.contains("popup.style.cssText"))
    }

    @Test
    fun testBuildSearchTextScript_prefersUniqueSelectorAndExpandedSyntax() {
        val script = ScrapingScriptBuilder.buildSearchTextScript("test")

        // 非ユニークshortSelectorの誤適用防止ヘルパー
        assertTrue("pickSelector定義が含まれていること", script.contains("function pickSelector(el)"))
        assertTrue("一意性検証が含まれていること", script.contains("querySelectorAll(s).length === 1"))
        // 複合セレクタ (, () +) を直接指定として認識できること
        assertTrue("カンマが許可文字に含まれていること", script.contains("\\,"))
        assertTrue("丸括弧が許可文字に含まれていること", script.contains("\\("))
    }

    @Test
    fun testBuildSearchTextScript_escapesSpecialCharactersSafely() {
        val dangerousQuery = "仙子'の\"修\\行\n改行"
        val script = ScrapingScriptBuilder.buildSearchTextScript(dangerousQuery)

        assertFalse("改行が除去されていること", script.contains("\n改行"))
        assertTrue("シングルクォートがエスケープされていること", script.contains("仙子\\'の"))
        assertTrue("バックスラッシュがエスケープされていること", script.contains("修\\\\行"))
    }

    @Test
    fun testBuildSearchTextScript_escapesCarriageReturnSafely() {
        val script = ScrapingScriptBuilder.buildSearchTextScript("a\rb")
        assertFalse("CRが除去されていること", script.contains("\rb"))
    }

    @Test
    fun testBuildScrapingScript_excludesDirectlyOnClonedDomWithoutRealDomPollution() {
        val config = ScraperConfig(
            body = ".chapter_content_box",
            exclude = "span[class*=\"count_\"], .ad-banner"
        )
        val script = ScrapingScriptBuilder.buildScrapingScript(config, useImages = false)

        // 実DOMを汚染する .__novel_exclude 操作が存在しないこと
        assertFalse("実DOMへの__novel_exclude付与が存在しないこと", script.contains(".classList.add('__novel_exclude')"))
        assertFalse("実DOMからの__novel_exclude除去が存在しないこと", script.contains(".classList.remove('__novel_exclude')"))

        // クローン要素に対する直接削除が含まれていること
        assertTrue("クローンに対する直接removeが含まれていること", script.contains("clone.querySelectorAll(sel).forEach(function(el) { el.remove(); });"))
    }

    @Test
    fun testBuildCandidateProbeScript_containsTraversalAndDynamicNumberingGeneralization() {
        val probeScript = ScrapingScriptBuilder.buildCandidateProbeScript("span.count_0")

        // 共通トラバーサルスニペットが含まれていること
        assertTrue("traverseCandidates定義が含まれていること", probeScript.contains("function traverseCandidates(targetEl, onCandidate, includeLink)"))
        assertTrue("traverseCandidates呼び出しが含まれていること", probeScript.contains("traverseCandidates(baseEl, function(node, label, s)"))

        // 要素自身の短縮・汎化セレクタが優先登録されていること（デッドフォールバック解消）
        assertTrue("短縮セレクタの汎用ラベル登録が含まれていること", probeScript.contains("onCandidate(targetEl, '要素自身 (汎用)', selfShort)"))

        // 連番クラス（count_0 等）を汎化するロジックが含まれていること（英字2文字以上 + グリッドガード）
        assertTrue("連番検出の正規表現が含まれていること", probeScript.contains("^([a-zA-Z]{2,}[-_])\\d+$"))
        assertTrue("グリッド/レイアウト除外ガードが含まれていること", probeScript.contains("(col|row|gap|span|grid)[-_]"))
        assertTrue("属性部分一致セレクタ生成が含まれていること", probeScript.contains("tag + '[class*=\"' + safeEscape(numMatch[1]) + '\"]'"))
    }

    @Test
    fun testBuildInspectorScript_usesTraverseCandidatesWithoutDuplication() {
        val config = ScraperConfig()
        val inspectorScript = ScrapingScriptBuilder.buildInspectorScript(config)

        // getCandidates が traverseCandidates を利用して重複排除されていること
        assertTrue("getCandidates内でtraverseCandidatesが呼ばれていること", inspectorScript.contains("traverseCandidates(el, function(node, label, s)"))
        // コピペ探索ループ（seen[s]やID祖先手動ループ）がgetCandidatesに直書きされていないこと
        assertFalse("getCandidatesにID祖先手動ループが直書きされていないこと", inspectorScript.contains("p.id && !String(p.id).match(/^[0-9]/))) p = p.parentElement;\n                    if (p && p.tagName !== 'BODY') push(p, 'ID祖先');"))
    }
}
