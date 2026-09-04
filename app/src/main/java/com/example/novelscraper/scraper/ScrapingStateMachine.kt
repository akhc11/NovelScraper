package com.example.novelscraper.scraper

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
        data class WaitAndScrapeAgain(val delayMs: Long) : Action()
        data class WaitForCF(val delayMs: Long) : Action()
        data class Finish(val reason: String) : Action()
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
    private val manualSpec = ChapterNumberExtractor.parseManualSpec(config.chapter)
    private val padLength = manualSpec?.padLength ?: 4
    private var manualChapterCounter: Int? = null
    private val endCheckRegex: Regex? = try {
        if (config.endCheck.isNotEmpty()) Regex(config.endCheck) else null
    } catch (e: Exception) { null }

    // 循環ループ検知（A ➔ B ➔ C ➔ A などの無限ループを完全防止）
    private val visitedUrls = mutableSetOf<String>()

    // SPA本文空振り防止（本文が非同期で遅れて届く場合の段階的待機カウンタ）
    var emptyContentRetryCount = 0
        private set

    init {
        if (manualSpec != null) {
            manualChapterCounter = manualSpec.startNumber
        }
    }

    /** ページ読み込み完了時の状態遷移を決定 */
    fun onPageLoaded(url: String, pageTitle: String): List<Action> {
        currentUrl = url
        emptyContentRetryCount = 0 // 新ページ読み込み時は空本文リトライをリセット
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

    /** 同一ページでの再スクレイピング用Actionを生成 */
    fun buildScrapePageAction(): Action {
        if (currentUrl == lastSuccessUrl) return Action.UpdateStatus("スキップ（同一URL）")
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, true, false)
        return Action.EvaluateJs(jsCode, JsPurpose.SCRAPE_PAGE)
    }

    // --- 内部ロジック ---

    private fun buildCheckFolderLinkAction(): Action {
        if (config.folderLink.isEmpty()) {
            return handleDirectFolderName()
        }
        val safeLink = config.folderLink.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function(){
                var el = document.querySelector('$safeLink');
                if (!el) return '';
                var a = el.tagName === 'A' ? el : el.closest('a');
                if (!a && el.querySelector) a = el.querySelector('a');
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
        val safeFolder = config.folder.replace("\\", "\\\\").replace("'", "\\'")
        val js = "(function(){ var el=document.querySelector('$safeFolder'); return el?el.innerText.trim():''; })();"
        return Action.EvaluateJs(js, JsPurpose.FETCH_FOLDER_NAME)
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
        val jsCode = ScrapingScriptBuilder.buildScrapingScript(config, true, false)
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

            // SPA本文空振り防止スマートリトライ:
            // 本文が空・空白のみであり、かつ次ページURLが存在する場合、
            // 「まだSPAでDOMに本文が流し込まれていない可能性」を考慮して、同一ページで最大3回まで再スクレイピングを試みる
            // （超低速回線対応: 最低1.5秒を保証しつつ、ユーザーの指定した待機時間に合わせてゆったり待機）
            if (data.content.isBlank() && data.nextUrl.isNotBlank() && emptyContentRetryCount < MAX_EMPTY_CONTENT_RETRIES) {
                emptyContentRetryCount++
                val retryDelayMs = maxOf(MIN_EMPTY_CONTENT_RETRY_DELAY_MS, calculateDelay(config.delay))
                val actions = mutableListOf<Action>()
                actions.add(Action.UpdateStatus("本文待機中... ($emptyContentRetryCount/$MAX_EMPTY_CONTENT_RETRIES)"))
                actions.add(Action.WaitAndScrapeAgain(retryDelayMs))
                return actions
            }
            emptyContentRetryCount = 0 // 正常に本文が取れた、またはリトライ上限到達時はリセット

            if (folderName.isEmpty() && data.folderName.isNotEmpty()) {
                folderName = data.folderName
            } else if (data.folderName.isNotEmpty() && !data.folderName.startsWith("(") && !data.folderName.startsWith("別URL")) {
                folderName = data.folderName
            }

            val title = data.title.ifEmpty { "無題" }
            val normChapter = ChapterNumberExtractor.normalize(config.chapter)
            val isHybrid = normChapter.contains("||")

            val digitsFromJs = data.chapter.filter { it.isDigit() }
            val jsNum = digitsFromJs.toIntOrNull()

            val chosenNum = if (manualChapterCounter != null) {
                if (isHybrid && jsNum != null) {
                    val maxVal = maxOf(manualChapterCounter!!, jsNum)
                    ChapterNumberExtractor.formatNumber(maxVal, padLength)
                } else {
                    ChapterNumberExtractor.formatNumber(manualChapterCounter!!, padLength)
                }
            } else if (jsNum != null) {
                ChapterNumberExtractor.formatNumber(jsNum, padLength)
            } else if (data.chapter.isNotEmpty()) {
                val digits = data.chapter.filter { it.isDigit() }
                val num = digits.toIntOrNull()
                if (num != null) {
                    ChapterNumberExtractor.formatNumber(num, padLength)
                } else {
                    digits.padStart(padLength, '0')
                }
            } else {
                ChapterNumberExtractor.formatNumber(1, padLength)
            }
            val chapNum = chosenNum

            if (manualChapterCounter != null) {
                val currentInt = chapNum.toIntOrNull() ?: manualChapterCounter!!
                manualChapterCounter = currentInt + 1
            } else if (isHybrid && jsNum != null) {
                manualChapterCounter = jsNum + 1
            }

            // 本文が空・空白のみの場合は代替テキストを自動補完し、エラー停止させずに保存して進行する
            val finalContent = data.content.ifBlank { "(本文なし)" }

            val actions = mutableListOf<Action>()
            actions.add(Action.SaveAndContinue(folderName, title, finalContent, chapNum, data.nextUrl, currentUrl))
            actions.add(Action.UpdateHistory(folderName, title, chapNum, currentUrl, data.nextUrl))

            visitedUrls.add(currentUrl)
            lastSuccessUrl = currentUrl
            retryCount = 0
            actions.add(Action.UpdateStatus("保存: $chapNum ${title.take(10)}..."))

            val shouldStop = isEndDetected(data.nextUrl, title)
            val isLoopDetected = data.nextUrl.isNotEmpty() && visitedUrls.contains(data.nextUrl)

            if (isLoopDetected) {
                actions.add(Action.Finish("循環参照ループ検出により終了"))
            } else if (data.nextUrl.isNotEmpty() && data.nextUrl != "null" && !shouldStop) {
                val delayMs = calculateDelay(config.delay)
                actions.add(Action.WaitAndLoad(data.nextUrl, delayMs))
            } else {
                val reason = if (shouldStop) "終了条件合致" else "次ページなし"
                actions.add(Action.Finish(reason))
            }
            actions
        } catch (e: Exception) {
            buildRetryActions("JSONパースエラー: ${e.message}")
        }
    }

    private fun buildRetryActions(reason: String): List<Action> {
        retryCount++
        return if (retryCount <= MAX_RETRIES) {
            listOf(
                Action.UpdateStatus("リトライ ($retryCount/$MAX_RETRIES): $reason"),
                Action.WaitAndLoad(currentUrl, RETRY_DELAY_MS)
            )
        } else {
            listOf(Action.Finish("エラー停止 (上限到達): $reason"))
        }
    }

    private fun isEndDetected(nextUrl: String, title: String): Boolean {
        if (nextUrl.isEmpty() || nextUrl == "null") return true
        val regex = endCheckRegex ?: return false
        return regex.containsMatchIn(nextUrl) || regex.containsMatchIn(title)
    }

    companion object {
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 10000L
        private const val CF_WAIT_DELAY_MS = 5000L

        // SPA本文待機スマートリトライ設定（超低速回線対応: 最低1.5秒保証）
        private const val MAX_EMPTY_CONTENT_RETRIES = 3
        private const val MIN_EMPTY_CONTENT_RETRY_DELAY_MS = 1500L

        fun calculateDelay(delayConfig: String): Long {
            val parts = delayConfig.split("-").mapNotNull { it.trim().toLongOrNull() }
            return when (parts.size) {
                1 -> parts[0] * 1000L
                2 -> {
                    val min = minOf(parts[0], parts[1])
                    val max = maxOf(parts[0], parts[1])
                    (min + (Math.random() * (max - min + 1)).toLong()) * 1000L
                }
                else -> 5000L
            }
        }
    }
}
