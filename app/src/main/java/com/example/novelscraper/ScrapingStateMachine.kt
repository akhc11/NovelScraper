package com.example.novelscraper

import kotlinx.serialization.json.Json

/**
 * ScrapingTaskの状態遷移ロジックをPureクラスとして分離。
 * WebViewに一切依存せず、「次に何をすべきか」をAction として返す。
 * テスト可能な設計。
 */
class ScrapingStateMachine(
    private val config: ScraperConfig,
    private val startUrl: String,
    initialFolderName: String
) {
    enum class State { INITIAL_CHECK, FETCHING_FOLDER, RETURNING, SCRAPING }

    /** StateMachineが返す指示。ScrapingTaskがWebView操作に変換する。 */
    sealed class Action {
        data class LoadUrl(val url: String) : Action()
        data class EvaluateJs(val js: String, val purpose: JsPurpose) : Action()
        data class SaveAndContinue(
            val folderName: String, val title: String,
            val content: String, val chapterNum: String,
            val nextUrl: String, val currentUrl: String
        ) : Action()
        data class UpdateHistory(
            val folderName: String, val title: String,
            val chapter: String, val url: String, val nextUrl: String
        ) : Action()
        data class UpdateStatus(val message: String) : Action()
        data class WaitAndLoad(val url: String, val delayMs: Long) : Action()
        data class WaitForCF(val delayMs: Long) : Action()
        data class Retry(val reason: String) : Action()
        data class Finish(val reason: String) : Action()
        data class Error(val message: String) : Action()
    }

    enum class JsPurpose { CHECK_FOLDER_LINK, FETCH_FOLDER_NAME, SCRAPE_PAGE }

    var state = State.INITIAL_CHECK
        private set
    var currentUrl = startUrl
    var folderName = initialFolderName
        private set
    var lastSuccessUrl = ""
        private set
    var retryCount = 0
        private set
    private var manualChapterCounter: Int? = null
    private val endCheckRegex: Regex? = try {
        if (config.endCheck.isNotEmpty()) Regex(config.endCheck) else null
    } catch (e: Exception) { null }

    init {
        if (config.chapter.startsWith("@") && config.chapter.length > 1) {
            manualChapterCounter = config.chapter.substring(1).toIntOrNull()
        }
    }

    /** ページ読み込み完了時の状態遷移を決定 */
    fun onPageLoaded(url: String, pageTitle: String): List<Action> {
        currentUrl = url
        val actions = mutableListOf<Action>()

        if (CloudflareDetector.isCloudflareChallenge(pageTitle)) {
            actions.add(Action.WaitForCF(CF_WAIT_DELAY_MS))
            actions.add(Action.UpdateStatus("認証待ち..."))
            return actions
        }

        when (state) {
            State.INITIAL_CHECK -> actions.add(buildCheckFolderLinkAction())
            State.FETCHING_FOLDER -> actions.add(buildFetchFolderNameAction())
            State.RETURNING -> {
                state = State.SCRAPING
                actions.add(buildScrapePageAction())
            }
            State.SCRAPING -> actions.add(buildScrapePageAction())
        }
        return actions
    }

    /** JS実行結果に対する状態遷移を決定 */
    fun onJsResult(purpose: JsPurpose, result: String): List<Action> {
        return when (purpose) {
            JsPurpose.CHECK_FOLDER_LINK -> handleFolderLinkResult(result)
            JsPurpose.FETCH_FOLDER_NAME -> handleFolderNameResult(result)
            JsPurpose.SCRAPE_PAGE -> handleScrapeResult(result)
        }
    }

    /** ネットワークエラー時の処理 */
    fun onNetworkError(errorCode: Int): List<Action> {
        return buildRetryActions("通信エラー: $errorCode")
    }

    /** ディレイ計算 (pure関数) */
    fun calculateDelay(): Long = calculateDelay(config.delay)

    // --- 内部ロジック ---

    private fun buildCheckFolderLinkAction(): Action {
        if (config.folderLink.isEmpty()) {
            return handleDirectFolderName()
        }
        val js = """
            (function(){
                var el = document.querySelector('${config.folderLink.replace("'", "\\'")}');
                if (!el) return '';
                var a = el.tagName === 'A' ? el : el.closest('a');
                if (a) {
                    var href = a.href || a.getAttribute('href');
                    if (href) return new URL(href, location.href).href;
                }
                return '';
            })();
        """.trimIndent().replace("\n", " ")
        return Action.EvaluateJs(js, JsPurpose.CHECK_FOLDER_LINK)
    }

    private fun buildFetchFolderNameAction(): Action {
        val js = "(function(){ var el=document.querySelector('${config.folder.replace("'", "\\'")}'); return el?el.innerText.trim():''; })();"
        return Action.EvaluateJs(js, JsPurpose.FETCH_FOLDER_NAME)
    }

    private fun buildScrapePageAction(): Action {
        if (currentUrl == lastSuccessUrl) return Action.UpdateStatus("スキップ（同一URL）")
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, true)
        return Action.EvaluateJs(jsCode, JsPurpose.SCRAPE_PAGE)
    }

    private fun handleFolderLinkResult(result: String): List<Action> {
        val link = try { Json.decodeFromString<String?>(result) ?: "" } catch (e: Exception) { "" }
        return if (link.isNotEmpty() && link != "null") {
            state = State.FETCHING_FOLDER
            listOf(Action.LoadUrl(link))
        } else {
            listOf(handleDirectFolderName())
        }
    }

    private fun handleDirectFolderName(): Action {
        if (config.folder.startsWith("@")) {
            folderName = config.folder.substring(1)
        }
        state = State.SCRAPING
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, true)
        return Action.EvaluateJs(jsCode, JsPurpose.SCRAPE_PAGE)
    }

    private fun handleFolderNameResult(result: String): List<Action> {
        val name = try { Json.decodeFromString<String?>(result) ?: "" } catch (e: Exception) { "" }
        if (name.isNotEmpty()) {
            folderName = name
        }
        state = State.RETURNING
        return listOf(
            Action.UpdateStatus("作品名取得: $folderName"),
            Action.LoadUrl(startUrl)
        )
    }

    private fun handleScrapeResult(rawJson: String): List<Action> {
        return try {
            val rawResult = Json.decodeFromString<String>(rawJson)

            if (CloudflareDetector.isCFDetectedResult(rawResult)) {
                return listOf(
                    Action.WaitForCF(CF_WAIT_DELAY_MS),
                    Action.UpdateStatus("認証待ち...")
                )
            }
            if (rawResult.startsWith("JS_ERROR")) {
                return buildRetryActions(rawResult)
            }

            val data = Json.decodeFromString<ScrapingResult>(rawResult)
            if (data.folderName.isNotEmpty()) folderName = data.folderName

            val title = data.title.ifEmpty { "無題" }
            val chapNum = ChapterNumberExtractor.extract(data.chapter, config.chapter, currentUrl, manualChapterCounter)
            if (manualChapterCounter != null) manualChapterCounter = manualChapterCounter!! + 1

            // 本文が空・空白のみの場合は代替テキストを自動補完し、エラー停止させずに保存して進行する
            val finalContent = data.content.ifBlank { "(本文なし)" }

            val actions = mutableListOf<Action>()
            actions.add(Action.SaveAndContinue(folderName, title, finalContent, chapNum, data.nextUrl, currentUrl))
            actions.add(Action.UpdateHistory(folderName, title, chapNum, currentUrl, data.nextUrl))

            lastSuccessUrl = currentUrl
            retryCount = 0
            actions.add(Action.UpdateStatus("保存: $chapNum ${title.take(10)}..."))

            val shouldStop = isEndDetected(data.nextUrl, title)
            if (data.nextUrl.isNotEmpty() && data.nextUrl != "null" && !shouldStop) {
                val delayMs = calculateDelay(config.delay)
                actions.add(Action.WaitAndLoad(data.nextUrl, delayMs))
            } else {
                val reason = if (shouldStop) "終了検知" else "完了"
                actions.add(Action.Finish(reason))
            }
            actions
        } catch (e: Exception) {
            buildRetryActions("エラー: ${e.message}")
        }
    }

    private fun buildRetryActions(reason: String): List<Action> {
        return if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            listOf(
                Action.UpdateStatus("リトライ($retryCount): $reason"),
                Action.WaitAndLoad(currentUrl, RETRY_DELAY_MS)
            )
        } else {
            listOf(
                Action.UpdateStatus("エラー停止: $reason"),
                Action.Finish("エラー停止: $reason")
            )
        }
    }

    private fun isEndDetected(nextUrl: String, title: String): Boolean {
        if (endCheckRegex != null) {
            if (endCheckRegex.containsMatchIn(nextUrl) || endCheckRegex.containsMatchIn(title)) return true
        }
        return nextUrl.contains("/null") || nextUrl.endsWith("null")
    }

    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 60_000L
        private const val CF_WAIT_DELAY_MS = 30_000L

        fun calculateDelay(delayStr: String): Long {
            return try {
                if (delayStr.contains("-")) {
                    val parts = delayStr.split("-")
                    val min = parts[0].trim().toLongOrNull() ?: DEFAULT_DELAY_SECONDS
                    val max = parts[1].trim().toLongOrNull() ?: min
                    kotlin.random.Random.nextLong(min, max + 1) * 1000
                } else {
                    (delayStr.toLongOrNull() ?: DEFAULT_DELAY_SECONDS) * 1000
                }
            } catch (e: Exception) {
                DEFAULT_DELAY_SECONDS * 1000
            }
        }

        private const val DEFAULT_DELAY_SECONDS = 2L
    }
}
