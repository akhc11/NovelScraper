package com.example.novelscraper

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScrapingStateMachineTest {

    @Test
    fun testSequentialChapterNumbering() {
        val config = ScraperConfig(
            chapter = "@1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/1", "TestNovel")
        
        // 1ページ目
        sm.onPageLoaded("https://example.com/1", "Title 1")
        val raw1 = """{"title":"第1話","content":"本文1","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""
        val jsResult1 = Json.encodeToString(raw1)
        val actions1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, jsResult1)
        val saveAction1 = actions1.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0001", saveAction1.chapterNum)

        // 2ページ目
        sm.onPageLoaded("https://example.com/2", "Title 2")
        val raw2 = """{"title":"第2話","content":"本文2","nextUrl":"https://example.com/3","chapter":"","folderName":"TestNovel"}"""
        val jsResult2 = Json.encodeToString(raw2)
        val actions2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, jsResult2)
        val saveAction2 = actions2.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0002", saveAction2.chapterNum)
    }

    @Test
    fun testHybridChapterNumbering_autoSyncsWhenSelectorFails() {
        // ハイブリッド指定: セレクタで取れればそれを使用、取れなくなったら連番フォールバック
        val config = ScraperConfig(
            chapter = ".chapter || @1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/1", "TestNovel")

        // 1ページ目: セレクタから「15」が取れた
        sm.onPageLoaded("https://example.com/1", "Title 1")
        val raw1 = """{"title":"第15話","content":"本文1","nextUrl":"https://example.com/2","chapter":"15","folderName":"TestNovel"}"""
        val jsResult1 = Json.encodeToString(raw1)
        val actions1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, jsResult1)
        val saveAction1 = actions1.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0015", saveAction1.chapterNum)

        // 2ページ目: セレクタから話数が取れなかった（chapterが空文字）
        // 自動同期追従により 0016 に進む
        sm.onPageLoaded("https://example.com/2", "Title 2")
        val raw2 = """{"title":"第16話 (セレクタなし)","content":"本文2","nextUrl":"https://example.com/3","chapter":"","folderName":"TestNovel"}"""
        val jsResult2 = Json.encodeToString(raw2)
        val actions2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, jsResult2)
        val saveAction2 = actions2.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0016", saveAction2.chapterNum)

        // 3ページ目: 再びセレクタから「17」が取れた
        sm.onPageLoaded("https://example.com/3", "Title 3")
        val raw3 = """{"title":"第17話","content":"本文3","nextUrl":"https://example.com/4","chapter":"17","folderName":"TestNovel"}"""
        val jsResult3 = Json.encodeToString(raw3)
        val actions3 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, jsResult3)
        val saveAction3 = actions3.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0017", saveAction3.chapterNum)
    }

    @Test
    fun testHybridChapterNumbering_sideStoryBetweenChapters_noRewind() {
        // 案B（最大値保証）の検証：
        // 第15話 ➔ 番外編1 ➔ 番外編2 ➔ 第16話 でファイル番号が決して巻き戻らないこと
        val config = ScraperConfig(
            chapter = ".chapter || @1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/15", "TestNovel")

        // 1. 第15話
        sm.onPageLoaded("https://example.com/15", "第15話")
        val res1 = Json.encodeToString("""{"title":"第15話","content":"...","nextUrl":"https://example.com/ex1","chapter":"15","folderName":"TestNovel"}""")
        val act1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res1)
        val s1 = act1.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0015", s1.chapterNum)

        // 2. 番外編1 (数字なし)
        sm.onPageLoaded("https://example.com/ex1", "番外編1")
        val res2 = Json.encodeToString("""{"title":"番外編1","content":"...","nextUrl":"https://example.com/ex2","chapter":"","folderName":"TestNovel"}""")
        val act2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res2)
        val s2 = act2.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0016", s2.chapterNum)

        // 3. 番外編2 (数字なし)
        sm.onPageLoaded("https://example.com/ex2", "番外編2")
        val res3 = Json.encodeToString("""{"title":"番外編2","content":"...","nextUrl":"https://example.com/16","chapter":"","folderName":"TestNovel"}""")
        val act3 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res3)
        val s3 = act3.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0017", s3.chapterNum)

        // 4. 第16話 本編再開 (セレクタから16)
        // 案Bにより maxOf(18, 16) = 18 が採用され、番外編1(0016)を上書きしない！
        sm.onPageLoaded("https://example.com/16", "第16話")
        val res4 = Json.encodeToString("""{"title":"第16話 本編再開","content":"...","nextUrl":"https://example.com/17","chapter":"16","folderName":"TestNovel"}""")
        val act4 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res4)
        val s4 = act4.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0018", s4.chapterNum)

        // 5. 第17話 本編 (セレクタから17)
        // maxOf(19, 17) = 19
        sm.onPageLoaded("https://example.com/17", "第17話")
        val res5 = Json.encodeToString("""{"title":"第17話 本編","content":"...","nextUrl":"","chapter":"17","folderName":"TestNovel"}""")
        val act5 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res5)
        val s5 = act5.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0019", s5.chapterNum)
    }

    @Test
    fun testHybridChapterNumbering_prologueAtStart_noDuplicate() {
        // 案B（最大値保証）の検証：
        // プロローグ（数字なし） ➔ まえがき（数字なし） ➔ 第1話（数字1）で重複なく進行すること
        val config = ScraperConfig(
            chapter = ".chapter || @1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/p1", "TestNovel")

        // 1. プロローグ
        sm.onPageLoaded("https://example.com/p1", "プロローグ")
        val res1 = Json.encodeToString("""{"title":"プロローグ","content":"...","nextUrl":"https://example.com/p2","chapter":"","folderName":"TestNovel"}""")
        val act1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res1)
        val s1 = act1.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0001", s1.chapterNum)

        // 2. まえがき
        sm.onPageLoaded("https://example.com/p2", "まえがき")
        val res2 = Json.encodeToString("""{"title":"まえがき","content":"...","nextUrl":"https://example.com/1","chapter":"","folderName":"TestNovel"}""")
        val act2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res2)
        val s2 = act2.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0002", s2.chapterNum)

        // 3. 第1話 (セレクタから1)
        // maxOf(3, 1) = 3
        sm.onPageLoaded("https://example.com/1", "第1話")
        val res3 = Json.encodeToString("""{"title":"第1話 旅立ち","content":"...","nextUrl":"","chapter":"1","folderName":"TestNovel"}""")
        val act3 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res3)
        val s3 = act3.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0003", s3.chapterNum)
    }

    @Test
    fun testEmptyContent_retriesBeforeFallback() {
        // SPA空本文防止スマートリトライの検証：
        // 初回で本文が空の場合、即座に(本文なし)で進行せず、WaitAndScrapeAgain が発行されること
        val config = ScraperConfig(
            chapter = "@1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/empty", "TestNovel")

        sm.onPageLoaded("https://example.com/empty", "SPAページ")
        val rawEmpty = """{"title":"第1話","content":"","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""

        // 1回目の試行 (本文空) ➔ WaitAndScrapeAgain が発行される
        val actions1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawEmpty))
        assertTrue(actions1.any { it is ScrapingStateMachine.Action.WaitAndScrapeAgain })
        assertEquals(1, sm.emptyContentRetryCount)

        // 2回目の試行 (本文空) ➔ WaitAndScrapeAgain が発行される
        val actions2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawEmpty))
        assertTrue(actions2.any { it is ScrapingStateMachine.Action.WaitAndScrapeAgain })
        assertEquals(2, sm.emptyContentRetryCount)

        // 3回目の試行でSPAの本文が届いた！
        val rawLoaded = """{"title":"第1話","content":"非同期で読み込まれた本文","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""
        val actions3 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawLoaded))
        val saveAction = actions3.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("非同期で読み込まれた本文", saveAction.content)
        assertEquals(0, sm.emptyContentRetryCount) // リセットされていること
    }

    @Test
    fun testEmptyContent_fallsBackAfterMaxRetries() {
        // 3回リトライしても本文が空だった場合（画像のみページ等）、ルール6に従って (本文なし) を補完して進行すること
        val config = ScraperConfig(
            chapter = "@1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/empty", "TestNovel")

        sm.onPageLoaded("https://example.com/empty", "画像のみページ")
        val rawEmpty = """{"title":"第1話 画像のみ","content":"","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""

        // リトライ 1〜3回
        sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawEmpty))
        sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawEmpty))
        sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawEmpty))

        // 4回目 (リトライ上限到達後): (本文なし) で保存して進行
        val actionsFinal = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawEmpty))
        val saveAction = actionsFinal.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("(本文なし)", saveAction.content)
        assertEquals("https://example.com/2", saveAction.nextUrl)
    }

    @Test
    fun testCircularLoopDetection_finishesGracefully() {
        // 循環参照ループ（最新話の「次へ」が第1話に戻るカルーセルサイト等）で安全に終了することの検証
        val config = ScraperConfig(
            chapter = "@1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/1", "TestNovel")

        // 第1話 (https://example.com/1 -> next: /2)
        sm.onPageLoaded("https://example.com/1", "第1話")
        val raw1 = """{"title":"第1話","content":"本文1","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""
        val act1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(raw1))
        assertTrue(act1.any { it is ScrapingStateMachine.Action.WaitAndLoad })

        // 第2話 (https://example.com/2 -> next: /1 循環！)
        sm.onPageLoaded("https://example.com/2", "第2話")
        val raw2 = """{"title":"第2話","content":"本文2","nextUrl":"https://example.com/1","chapter":"","folderName":"TestNovel"}"""
        val act2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(raw2))

        // 第1話(既に訪問済み)へ戻ろうとしたため、無限ループを未然に阻止して安全終了すること！
        val finish = act2.filterIsInstance<ScrapingStateMachine.Action.Finish>().first()
        assertTrue("循環参照ループ検出で終了すること", finish.reason.contains("循環参照ループ"))
    }

    @Test
    fun testTenConsecutiveSideStories_noRewind() {
        // 番外編が10話連続した場合の最大値保証テスト
        val config = ScraperConfig(
            chapter = ".chapter || @1",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/10", "TestNovel")

        // 第10話
        sm.onPageLoaded("https://example.com/10", "第10話")
        val res10 = Json.encodeToString("""{"title":"第10話","content":"...","nextUrl":"https://example.com/ex1","chapter":"10","folderName":"TestNovel"}""")
        val s10 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res10).filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0010", s10.chapterNum)

        // 番外編 1〜10 (各話番号なし)
        for (i in 1..10) {
            val url = "https://example.com/ex$i"
            val next = if (i < 10) "https://example.com/ex${i + 1}" else "https://example.com/11"
            sm.onPageLoaded(url, "番外編$i")
            val resEx = Json.encodeToString("""{"title":"番外編$i","content":"...","nextUrl":"$next","chapter":"","folderName":"TestNovel"}""")
            val sEx = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, resEx).filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
            val expectedNum = (10 + i).toString().padStart(4, '0')
            assertEquals("番外編$i のファイル番号", expectedNum, sEx.chapterNum)
        }

        // 第11話 本編 (セレクタから11)
        // maxOf(21, 11) = 21 が採用され、決して過去の 0011 に巻き戻らない！
        sm.onPageLoaded("https://example.com/11", "第11話")
        val res11 = Json.encodeToString("""{"title":"第11話 本編再開","content":"...","nextUrl":"","chapter":"11","folderName":"TestNovel"}""")
        val s11 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, res11).filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("0021", s11.chapterNum)
    }

    @Test
    fun testFolderLink_preservesFetchedFolderNameOnPageScrape() {
        // 別URL先で作品名を取得した後、本文ページでのスクレイピングで作品名が上書き・消去されないことの検証
        val config = ScraperConfig(
            folder = "h1.title",
            folderLink = "a.index-link",
            chapter = "@1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/novel/1", "(取得中...)")

        // 1. 第1話ロード -> folderLink の存在をチェックする Action を発行
        val act1 = sm.onPageLoaded("https://example.com/novel/1", "第1話")
        assertEquals(1, act1.size)
        assertTrue(act1.first() is ScrapingStateMachine.Action.EvaluateJs)

        // 2. CHECK_FOLDER_LINK の結果、目次URL "https://example.com/index" が返る
        val resLink = Json.encodeToString("https://example.com/index")
        val actLink = sm.onJsResult(ScrapingStateMachine.JsPurpose.CHECK_FOLDER_LINK, resLink)
        assertEquals(1, actLink.size)
        val loadIndex = actLink.first() as ScrapingStateMachine.Action.LoadUrl
        assertEquals("https://example.com/index", loadIndex.url)
        assertEquals(ScrapingStateMachine.State.FETCHING_FOLDER, sm.state)

        // 3. 目次ページロード -> 作品名を取得する Action を発行
        val actIndexLoaded = sm.onPageLoaded("https://example.com/index", "目次")
        assertEquals(1, actIndexLoaded.size)

        // 4. FETCH_FOLDER_NAME の結果、作品名 "ダンジョンに出会いを求めるのは間違っているだろうか" が返る
        val resFolderName = Json.encodeToString("ダンジョンに出会いを求めるのは間違っているだろうか")
        val actFolderFetched = sm.onJsResult(ScrapingStateMachine.JsPurpose.FETCH_FOLDER_NAME, resFolderName)
        assertEquals(2, actFolderFetched.size)
        assertEquals("ダンジョンに出会いを求めるのは間違っているだろうか", sm.folderName)
        assertEquals(ScrapingStateMachine.State.RETURNING, sm.state)

        // 5. 第1話へ戻る
        sm.onPageLoaded("https://example.com/novel/1", "第1話")
        assertEquals(ScrapingStateMachine.State.SCRAPING, sm.state)

        // 6. 本文ページ解析完了（本文ページからは folderName が空文字または取れなかった）
        val rawPage = """{"title":"第1話 冒険者","content":"本文テキスト","nextUrl":"https://example.com/novel/2","chapter":"","folderName":""}"""
        val actScrape = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(rawPage))
        val save = actScrape.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()

        // 目次で取得した本物の作品名が保持されていること！
        assertEquals("ダンジョンに出会いを求めるのは間違っているだろうか", save.folderName)
        assertEquals("ダンジョンに出会いを求めるのは間違っているだろうか", sm.folderName)
    }

    @Test
    fun testManualChapterNumbering_twoDigitPadding() {
        // @01 指定時は2桁パディング (01, 02) でフォーマットされること
        val config = ScraperConfig(
            chapter = "@01",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/1", "TestNovel")

        sm.onPageLoaded("https://example.com/1", "Title 1")
        val raw1 = """{"title":"第1話","content":"本文1","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""
        val actions1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(raw1))
        val save1 = actions1.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("01", save1.chapterNum)

        sm.onPageLoaded("https://example.com/2", "Title 2")
        val raw2 = """{"title":"第2話","content":"本文2","nextUrl":"https://example.com/3","chapter":"","folderName":"TestNovel"}"""
        val actions2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(raw2))
        val save2 = actions2.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("02", save2.chapterNum)
    }

    @Test
    fun testManualChapterNumbering_rawNoPadding() {
        // @1# 指定時はゼロ埋めなし (1, 2) でフォーマットされること
        val config = ScraperConfig(
            chapter = "@1#",
            delay = "1"
        )
        val sm = ScrapingStateMachine(config, "https://example.com/1", "TestNovel")

        sm.onPageLoaded("https://example.com/1", "Title 1")
        val raw1 = """{"title":"第1話","content":"本文1","nextUrl":"https://example.com/2","chapter":"","folderName":"TestNovel"}"""
        val actions1 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(raw1))
        val save1 = actions1.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("1", save1.chapterNum)

        sm.onPageLoaded("https://example.com/2", "Title 2")
        val raw2 = """{"title":"第2話","content":"本文2","nextUrl":"https://example.com/3","chapter":"","folderName":"TestNovel"}"""
        val actions2 = sm.onJsResult(ScrapingStateMachine.JsPurpose.SCRAPE_PAGE, Json.encodeToString(raw2))
        val save2 = actions2.filterIsInstance<ScrapingStateMachine.Action.SaveAndContinue>().first()
        assertEquals("2", save2.chapterNum)
    }
}
