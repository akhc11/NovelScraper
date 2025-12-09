package com.example.novelscraper

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Patterns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var mainWebView: WebView
    private lateinit var settingsPanel: ScrollView
    private lateinit var fabAction: FloatingActionButton

    private lateinit var editUrl: EditText
    private lateinit var editBody: EditText
    private lateinit var editTitle: EditText
    private lateinit var editFileRegex: EditText
    private lateinit var editFolder: EditText
    private lateinit var editFolderLink: EditText
    private lateinit var editFolderRegex: EditText
    private lateinit var editNext: EditText
    private lateinit var editChapter: EditText
    private lateinit var editChapterRegex: EditText
    private lateinit var editDelay: EditText
    private lateinit var editEndCheck: EditText
    private lateinit var editAutoUrl: EditText

    private lateinit var spinnerPresets: Spinner
    private lateinit var toggleImages: ToggleButton
    private lateinit var toggleInspectMode: ToggleButton

    private var presets = JSONObject()
    private var favorites = JSONObject()
    private var historyData = JSONObject()

    private val PREFS_NAME = "NovelScraperPrefs"
    private val PREFS_KEY_DATA = "presets_json"
    private val PREFS_KEY_FAVORITES = "favorites_json"
    private val PREFS_KEY_HISTORY_JSON = "history_json_map_v3"
    private val PREFS_KEY_SETUP_DONE = "is_setup_done"

    private var currentPresetName = ""
    private val activeTasks = CopyOnWriteArrayList<ScrapingTask>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInspectMode = false

    enum class TaskState { INITIAL_CHECK, FETCHING_FOLDER, RETURNING, SCRAPING }

    inner class ScrapingTask(
        val startUrl: String,
        val config: JSONObject,
        val useImages: Boolean,
        val onTaskFinish: (ScrapingTask) -> Unit
    ) {
        val webView = WebView(this@MainActivity)
        var currentUrl = startUrl
        var lastSuccessUrl = ""
        var retryCount = 0
        var status = "準備中..."
        var folderName = "(取得中...)"
        var isRunning = true
        var state = TaskState.INITIAL_CHECK
        private var taskRunnable: Runnable? = null

        init {
            setupWebViewSettings(webView, useImages)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!isRunning) return
                    val loadedUrl = url ?: return
                    currentUrl = loadedUrl

                    val title = view?.title ?: ""
                    verifyTurnstile(view)

                    if (isCloudflareTitle(title)) {
                        triggerCFWait(loadedUrl)
                        return
                    }
                    if (state == TaskState.SCRAPING) {
                        performHumanLikeScroll(view)
                    }

                    taskRunnable?.let { mainHandler.removeCallbacks(it) }
                    val delay = if (state == TaskState.SCRAPING) 5000L else 2000L
                    taskRunnable = Runnable {
                        if (!isRunning) return@Runnable
                        when (state) {
                            TaskState.INITIAL_CHECK -> checkFolderLink()
                            TaskState.FETCHING_FOLDER -> fetchFolderName()
                            TaskState.RETURNING -> { state = TaskState.SCRAPING; processPage() }
                            TaskState.SCRAPING -> processPage()
                        }
                    }
                    mainHandler.postDelayed(taskRunnable!!, delay)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (!isRunning) return
                    if (request?.isForMainFrame == false) return
                    handleTaskRetry("通信エラー: ${error?.errorCode}")
                }
            }
        }

        private fun verifyTurnstile(view: WebView?) {
            val js = """
                (function() {
                    function findAndClickCheckbox(root) {
                        if (!root) return;
                        var shadow = root.shadowRoot;
                        var target = shadow ? shadow : root;
                        var inputs = target.querySelectorAll('input[type="checkbox"]');
                        for (var i = 0; i < inputs.length; i++) {
                            if (!inputs[i].checked) {
                                inputs[i].click();
                            }
                        }
                        var children = target.children;
                        for (var i = 0; i < children.length; i++) {
                            findAndClickCheckbox(children[i]);
                        }
                    }
                    if (!window.cfClickerStarted) {
                        window.cfClickerStarted = true;
                        setInterval(function() { findAndClickCheckbox(document.body); }, 1500);
                    }
                })();
            """
            view?.evaluateJavascript(js, null)
        }

        private fun performHumanLikeScroll(view: WebView?) {
            val js = """
                (function() {
                    var totalHeight = document.body.scrollHeight;
                    var currentScroll = window.scrollY;
                    function humanScroll() {
                        if (currentScroll >= totalHeight - window.innerHeight) return;
                        var step = Math.floor(Math.random() * 60) + 20;
                        if (Math.random() < 0.05) step = -step; 
                        var delay = Math.floor(Math.random() * 150) + 50;
                        if (Math.random() < 0.03) delay += 1500;
                        currentScroll += step;
                        if (currentScroll < 0) currentScroll = 0; 
                        window.scrollTo(0, currentScroll);
                        setTimeout(humanScroll, delay);
                    }
                    humanScroll();
                })();
            """
            view?.evaluateJavascript(js, null)
        }

        private fun isCloudflareTitle(title: String): Boolean {
            return title.contains("Just a moment") || title.contains("Cloudflare") || title.contains("Verify")
        }

        private fun triggerCFWait(url: String) {
            status = "⚠️ 認証待機中..."
            updateServiceStatus()
            taskRunnable?.let { mainHandler.removeCallbacks(it) }
            taskRunnable = Runnable { if (isRunning) webView.reload() }
            mainHandler.postDelayed(taskRunnable!!, 30000)
        }

        fun start() {
            status = "開始: リンク確認中"
            state = TaskState.INITIAL_CHECK
            webView.loadUrl(startUrl)
            updateServiceStatus()
        }

        fun stop() {
            isRunning = false
            taskRunnable?.let { mainHandler.removeCallbacks(it) }
            webView.stopLoading()
            webView.destroy()
        }

        private fun handleTaskRetry(reason: String) {
            taskRunnable?.let { mainHandler.removeCallbacks(it) }
            if (retryCount < 3) {
                retryCount++
                status = "リトライ($retryCount): $reason"
                updateServiceStatus()
                taskRunnable = Runnable { if (isRunning) webView.loadUrl(currentUrl) }
                mainHandler.postDelayed(taskRunnable!!, 60000)
            } else {
                status = "エラー停止: $reason"
                isRunning = false
                onTaskFinish(this)
            }
        }

        private fun checkFolderLink() {
            val folderLinkSel = config.optString("folderLink")
            if (folderLinkSel.isEmpty()) { fetchFolderNameInPlace(); return }
            val js = "(function(){ var el = document.querySelector('$folderLinkSel'); return el ? el.href : ''; })();"
            webView.evaluateJavascript(js) { res ->
                if (!isRunning) return@evaluateJavascript
                val link = res?.replace("\"", "") ?: ""
                if (link.isNotEmpty() && link != "null" && link != "undefined") {
                    status = "作品名取得へ移動..."
                    updateServiceStatus()
                    state = TaskState.FETCHING_FOLDER
                    webView.loadUrl(link)
                } else { fetchFolderNameInPlace() }
            }
        }

        private fun fetchFolderName() {
            val folderSel = config.optString("folder")
            val folderRegex = config.optString("regex")
            val js = "(function(){ var el = document.querySelector('$folderSel'); return el ? el.innerText.trim() : ''; })();"
            webView.evaluateJavascript(js) { res ->
                if (!isRunning) return@evaluateJavascript
                var name = res?.replace("\"", "") ?: ""
                name = cleanText(name.replace("\\u003C", "<"), folderRegex)
                if (name.isNotEmpty()) { folderName = name; status = "作品名取得: $folderName" }
                else { status = "作品名取得失敗(維持)" }
                updateServiceStatus()
                state = TaskState.RETURNING
                webView.loadUrl(startUrl)
            }
        }

        private fun fetchFolderNameInPlace() {
            val folderSel = config.optString("folder")
            val folderRegex = config.optString("regex")
            if (folderSel.startsWith("@")) { folderName = folderSel.substring(1) }
            else if (folderSel.isNotEmpty()) {
                val js = "(function(){ var el = document.querySelector('$folderSel'); return el ? el.innerText.trim() : ''; })();"
                webView.evaluateJavascript(js) { res ->
                    var name = res?.replace("\"", "") ?: ""
                    name = cleanText(name.replace("\\u003C", "<"), folderRegex)
                    if (name.isNotEmpty()) folderName = name
                }
            }
            state = TaskState.SCRAPING
            processPage()
        }

        private fun processPage() {
            if (!isRunning) return
            if (currentUrl == lastSuccessUrl) return

            val bodySel = config.optString("body").replace("'", "\\'")
            val titleSel = config.optString("title").replace("'", "\\'")
            val nextSel = config.optString("next").replace("'", "\\'")
            val chapterSel = config.optString("chapter").replace("'", "\\'")
            val chapterRegex = config.optString("chapterRegex").replace("\\", "\\\\").replace("'", "\\'")
            val fileRegexPattern = config.optString("fileRegex")
            val endCheckPattern = config.optString("endCheck")

            val jsCode = """
                (function() {
                    try {
                        if (document.title.includes("Just a moment") || document.body.innerText.includes("Verify you are human")) return "CF_DETECTED";
                        var result = {};
                        var titleElem = document.querySelector('$titleSel');
                        result.title = titleElem ? titleElem.innerText.trim() : "NoTitle_" + Date.now();
                        var chapNum = "";
                        if ('$chapterSel' !== "") {
                            var chapElem = document.querySelector('$chapterSel');
                            if (chapElem) {
                                var text = chapElem.innerText;
                                var nums = null;
                                if ('$chapterRegex' !== "") {
                                    try { var re = new RegExp('$chapterRegex'); var match = text.match(re); if(match) nums = match[1] || match[0]; } catch(e){}
                                } 
                                if (!nums) { var match = text.match(/\d+/); if(match) nums = match[0]; }
                                if (nums) chapNum = nums.padStart(4, '0');
                            }
                        }
                        result.chapter = chapNum;
                        var bodyElem = document.querySelector('$bodySel');
                        if (!bodyElem) {
                             var ps = document.querySelectorAll('p');
                             var txt = "";
                             for(var i=0; i<ps.length; i++) txt += ps[i].innerText + "\n\n";
                             result.content = txt;
                        } else { result.content = bodyElem.innerText; }
                        var nextElem = document.querySelector('$nextSel');
                        result.nextUrl = nextElem ? nextElem.href : "";
                        return JSON.stringify(result);
                    } catch(e) { return "JS_ERROR: " + e.message; }
                })();
            """

            webView.evaluateJavascript(jsCode) { jsonResult ->
                if (!isRunning) return@evaluateJavascript
                try {
                    if (jsonResult == null || jsonResult == "null") { handleTaskRetry("解析失敗(null)"); return@evaluateJavascript }
                    val rawResult = org.json.JSONTokener(jsonResult).nextValue().toString()
                    if (rawResult == "CF_DETECTED") { triggerCFWait(currentUrl); return@evaluateJavascript }
                    if (rawResult.startsWith("JS_ERROR")) { handleTaskRetry(rawResult); return@evaluateJavascript }

                    val data = JSONObject(rawResult)
                    val title = data.optString("title", "無題")
                    val content = data.optString("content", "")
                    val nextUrl = data.optString("nextUrl", "")
                    val chapNum = data.optString("chapter", "")

                    if (content.length < 20) { handleTaskRetry("本文短過"); return@evaluateJavascript }

                    val safeTitle = cleanText(title, fileRegexPattern)
                    saveToDownloads(folderName, safeTitle, content, chapNum)

                    if (folderName != "(取得中...)") {
                        saveHistoryMap(folderName, safeTitle, chapNum, currentUrl, config)
                    }

                    lastSuccessUrl = currentUrl
                    retryCount = 0
                    status = "保存: ${if(chapNum.isNotEmpty()) "$chapNum " else ""}${safeTitle.take(10)}..."
                    updateServiceStatus()

                    var shouldStop = false
                    if (endCheckPattern.isNotEmpty()) {
                        try {
                            val regex = Regex(endCheckPattern)
                            if (regex.containsMatchIn(nextUrl) || regex.containsMatchIn(title)) {
                                shouldStop = true
                            }
                            if (nextUrl.contains("/null") || nextUrl.endsWith("null")) {
                                shouldStop = true
                            }
                        } catch(e:Exception){}
                    }

                    if (nextUrl.isNotEmpty() && nextUrl != "null" && !shouldStop) {
                        val delayStr = config.optString("delay", "15-30")
                        var delaySec = 2L
                        try {
                            if (delayStr.contains("-")) {
                                val parts = delayStr.split("-")
                                val min = parts[0].trim().toLongOrNull() ?: 2L
                                val max = parts[1].trim().toLongOrNull() ?: min
                                delaySec = Random.nextLong(min, max + 1)
                            } else { delaySec = delayStr.toLongOrNull() ?: 2L }
                        } catch(e: Exception) {}

                        status = "待機(${delaySec}s): $folderName"
                        updateServiceStatus()
                        taskRunnable = Runnable { if(isRunning) webView.loadUrl(nextUrl) }
                        mainHandler.postDelayed(taskRunnable!!, delaySec * 1000)
                    } else {
                        status = if(shouldStop) "終了検知: $folderName" else "完了: $folderName"
                        isRunning = false
                        onTaskFinish(this)
                        Toast.makeText(this@MainActivity, status, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) { handleTaskRetry("エラー: ${e.message}") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.BLACK
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
            WindowCompat.setDecorFitsSystemWindows(window, true)
        } catch (e: Exception) { }

        checkNotificationPermission()

        settingsPanel = findViewById(R.id.settingsPanel)
        mainWebView = findViewById(R.id.myWebView)
        txtStatus = findViewById(R.id.txtStatus)
        editUrl = findViewById(R.id.editUrl)
        fabAction = findViewById(R.id.fabAction)

        editBody = findViewById(R.id.editBodySelector)
        editTitle = findViewById(R.id.editTitleSelector)
        editFileRegex = findViewById(R.id.editFileRegex)
        editFolder = findViewById(R.id.editFolderSelector)
        editFolderLink = findViewById(R.id.editFolderLinkSelector)
        editFolderRegex = findViewById(R.id.editFolderRegex)
        editNext = findViewById(R.id.editNextSelector)
        editChapter = findViewById(R.id.editChapterSelector)
        editChapterRegex = findViewById(R.id.editChapterRegex)
        editDelay = findViewById(R.id.editDelay)
        editEndCheck = findViewById(R.id.editEndCheck)
        editAutoUrl = findViewById(R.id.editAutoUrl)

        spinnerPresets = findViewById(R.id.spinnerPresets)

        toggleImages = findViewById(R.id.toggleImages)
        toggleInspectMode = findViewById(R.id.toggleInspectMode)
        val btnTestRun = findViewById<Button>(R.id.btnTestRun)

        val btnStar = findViewById<ImageButton>(R.id.btnStar)
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnForward = findViewById<ImageButton>(R.id.btnForward)
        val btnCloseSettings = findViewById<Button>(R.id.btnCloseSettings)
        val btnSavePreset = findViewById<Button>(R.id.btnSavePreset)
        val btnDeletePreset = findViewById<Button>(R.id.btnDeletePreset)

        setupWebViewSettings(mainWebView, true)
        loadPresets()
        loadFavorites()
        loadHistoryMap()

        editUrl.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = v.text.toString().trim()
                if (input.isNotEmpty()) {
                    if (Patterns.WEB_URL.matcher(input).matches() || URLUtil.isValidUrl(input)) {
                        var target = input
                        if (!target.startsWith("http")) target = "https://$target"
                        mainWebView.loadUrl(target)
                    } else {
                        val searchUrl = "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
                        mainWebView.loadUrl(searchUrl)
                    }
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
                true
            } else false
        }

        mainWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) {
                    editUrl.setText(url)
                    checkAutoApplyPreset(url)
                }
                if (isInspectMode) injectInspector(view)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPanel.visibility == View.VISIBLE) {
                    toggleSettingsPanel(false)
                } else if (mainWebView.canGoBack()) {
                    mainWebView.goBack()
                } else {
                    finish()
                }
            }
        })

        btnStar.setOnClickListener { showAddFavoriteDialog() }
        setupChromeMenu(btnMenu)
        btnCloseSettings.setOnClickListener { toggleSettingsPanel(false) }

        btnBack.setOnClickListener { if (mainWebView.canGoBack()) mainWebView.goBack() }
        btnForward.setOnClickListener { if (mainWebView.canGoForward()) mainWebView.goForward() }

        toggleImages.setOnCheckedChangeListener { _, isChecked ->
            mainWebView.settings.blockNetworkImage = !isChecked
            mainWebView.reload()
        }

        toggleInspectMode.setOnCheckedChangeListener { _, isChecked ->
            isInspectMode = isChecked
            if (isChecked) {
                injectInspector(mainWebView)
                Toast.makeText(this, "要素をタップして情報を取得できます", Toast.LENGTH_SHORT).show()
            } else {
                mainWebView.reload()
            }
        }

        btnTestRun.setOnClickListener { performTestRun() }

        fabAction.setOnClickListener {
            val targetUrl = mainWebView.url ?: editUrl.text.toString()
            if (targetUrl.isEmpty()) return@setOnClickListener

            checkNotificationPermission()
            val config = buildConfigFromUI()
            val currentImageSetting = toggleImages.isChecked

            val newTask = ScrapingTask(targetUrl, config, currentImageSetting) { task ->
                activeTasks.remove(task)
                updateServiceStatus()
                val intent = Intent(this, ScraperService::class.java)
                intent.action = ScraperService.ACTION_SHOW_COMPLETE
                intent.putExtra(ScraperService.EXTRA_TITLE, "ダウンロード完了")
                intent.putExtra(ScraperService.EXTRA_MSG, "${task.folderName} の処理が終了しました")
                startService(intent)
            }
            activeTasks.add(newTask)
            newTask.start()
            updateServiceStatus()
            toggleSettingsPanel(false)
            Toast.makeText(this, "DL開始", Toast.LENGTH_SHORT).show()
        }

        // -----------------------------------------------------
        // ★★★ プリセット保存ロジック (自動命名 & 重複回避) ★★★
        // -----------------------------------------------------
        btnSavePreset.setOnClickListener {
            val input = EditText(this)
            input.hint = "設定名 (空欄で自動命名)"
            AlertDialog.Builder(this).setTitle("設定保存").setView(input).setPositiveButton("保存") { _, _ ->
                var name = input.text.toString().trim()

                // 空欄なら現在のURLからドメインを取得して名前にする
                if (name.isEmpty()) {
                    val currentUrl = mainWebView.url ?: ""
                    if (currentUrl.isNotEmpty()) {
                        try {
                            val uri = Uri.parse(currentUrl)
                            name = uri.host ?: "NewPreset"
                            // "www." は邪魔なので消す
                            if (name.startsWith("www.")) name = name.substring(4)
                        } catch(e:Exception) { name = "NewPreset" }
                    } else {
                        name = "NewPreset"
                    }

                    // 重複チェック: 既存の名前に (1), (2) をつけて被らないようにする
                    var originalName = name
                    var counter = 1
                    while (presets.has(name)) {
                        name = "$originalName ($counter)"
                        counter++
                    }
                }

                if (name.isNotEmpty()) {
                    val newConfig = buildConfigFromUI()
                    // 自動保存の場合、URLの一部を自動適用キーワードにも入れてあげると親切
                    if (input.text.toString().isEmpty()) {
                        val currentUrl = mainWebView.url ?: ""
                        val host = try { Uri.parse(currentUrl).host } catch(e:Exception){null}
                        if (!host.isNullOrEmpty() && newConfig.optString("autoUrl").isEmpty()) {
                            val key = if(host.startsWith("www.")) host.substring(4) else host
                            newConfig.put("autoUrl", key)
                            editAutoUrl.setText(key) // UIにも反映
                        }
                    }

                    presets.put(name, newConfig)
                    savePresetsToStorage()
                    updatePresetSpinner()
                    val adapter = spinnerPresets.adapter as ArrayAdapter<String>
                    val pos = adapter.getPosition(name)
                    if(pos >= 0) spinnerPresets.setSelection(pos)
                    Toast.makeText(this, "保存: $name", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("キャンセル", null).show()
        }

        btnDeletePreset.setOnClickListener {
            val currentName = spinnerPresets.selectedItem as? String
            if (currentName != null) {
                presets.remove(currentName)
                savePresetsToStorage()
                updatePresetSpinner()
            }
        }

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = spinnerPresets.getItemAtPosition(position).toString()
                currentPresetName = name
                if (presets.has(name)) {
                    loadConfigToUI(presets.getJSONObject(name))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun checkAutoApplyPreset(url: String) {
        val keys = presets.keys()
        while(keys.hasNext()){
            val key = keys.next()
            val conf = presets.getJSONObject(key)
            val autoUrl = conf.optString("autoUrl")
            if (autoUrl.isNotEmpty() && url.contains(autoUrl)) {
                if (currentPresetName != key) {
                    currentPresetName = key
                    loadConfigToUI(conf)
                    val adapter = spinnerPresets.adapter as ArrayAdapter<String>
                    val pos = adapter.getPosition(key)
                    if(pos >= 0) spinnerPresets.setSelection(pos)
                    Toast.makeText(this, "設定: [$key] を適用しました", Toast.LENGTH_SHORT).show()
                }
                break
            }
        }
    }

    // --------------------------------------------------------------------------
    // ★★★ スライドメニューの復活 + タッチ対応 ★★★
    // --------------------------------------------------------------------------
    private fun setupChromeMenu(anchorView: View) {
        val popupWindow = PopupWindow(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(10, 10, 10, 10)
        }
        fun createItem(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val itemFav = createItem("★ ブックマーク")
        val itemHistory = createItem("🕒 履歴 / タスク")
        val itemInspect = createItem("🔍 解析ツール起動")
        val itemSettings = createItem("🔧 解析設定")
        val menuItems = listOf(itemFav, itemHistory, itemInspect, itemSettings)
        menuItems.forEach { layout.addView(it) }

        popupWindow.contentView = layout
        popupWindow.width = 550
        popupWindow.height = WindowManager.LayoutParams.WRAP_CONTENT
        popupWindow.isFocusable = true
        popupWindow.isOutsideTouchable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) popupWindow.elevation = 20f

        val performAction = { view: View ->
            popupWindow.dismiss()
            when (view) {
                itemFav -> { toggleSettingsPanel(false); showFavoritesListDialog() }
                itemHistory -> { toggleSettingsPanel(false); showHistoryBottomSheet() }
                itemInspect -> {
                    toggleSettingsPanel(false)
                    val script = """
                        (function(){
                            if (window.eruda) { eruda.show(); return; }
                            var s=document.createElement('script');
                            s.src='https://cdn.jsdelivr.net/npm/eruda';
                            document.body.appendChild(s);
                            s.onload=function(){ eruda.init(); eruda.show(); };
                        })();
                    """
                    mainWebView.evaluateJavascript(script) { Toast.makeText(this, "解析ツール起動", Toast.LENGTH_SHORT).show() }
                }
                itemSettings -> toggleSettingsPanel(true)
            }
        }

        // メニュー項目への個別クリックリスナー（タップ操作用）
        menuItems.forEach { item -> item.setOnClickListener { performAction(item) } }

        // スライド操作ロジック
        anchorView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!popupWindow.isShowing) {
                        popupWindow.showAsDropDown(v, -300, 0)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (popupWindow.isShowing) {
                        val rawX = event.rawX
                        val rawY = event.rawY
                        menuItems.forEach { item ->
                            val itemLoc = IntArray(2)
                            item.getLocationOnScreen(itemLoc)
                            val rect = Rect(itemLoc[0], itemLoc[1], itemLoc[0] + item.width, itemLoc[1] + item.height)
                            if (rect.contains(rawX.toInt(), rawY.toInt())) item.setBackgroundColor(Color.parseColor("#555555"))
                            else item.setBackgroundColor(Color.TRANSPARENT)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    var selected: View? = null
                    if (popupWindow.isShowing) {
                        val rawX = event.rawX
                        val rawY = event.rawY
                        menuItems.forEach { item ->
                            val itemLoc = IntArray(2)
                            item.getLocationOnScreen(itemLoc)
                            val rect = Rect(itemLoc[0], itemLoc[1], itemLoc[0] + item.width, itemLoc[1] + item.height)
                            if (rect.contains(rawX.toInt(), rawY.toInt())) selected = item
                            item.setBackgroundColor(Color.TRANSPARENT)
                        }
                    }
                    // ボタンの上で離した(タップした)だけならメニューを閉じない
                    val btnRect = Rect()
                    v.getGlobalVisibleRect(btnRect)
                    val isClickOnButton = btnRect.contains(event.rawX.toInt(), event.rawY.toInt())

                    if (selected != null) {
                        performAction(selected!!)
                    } else if (!isClickOnButton) {
                        popupWindow.dismiss()
                    }
                    // isClickOnButtonなら何もしない＝メニュー開きっぱなし
                    true
                }
                else -> false
            }
        }
    }

    private fun showHistoryBottomSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val context = this

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7).toInt())
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val tabLayout = TabLayout(context).apply {
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            setTabTextColors(Color.GRAY, Color.WHITE)
            setSelectedTabIndicatorColor(Color.parseColor("#B2FF59"))
        }

        val viewPager = ViewPager2(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2
            override fun getItemViewType(position: Int): Int = position

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val container = FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                return object : RecyclerView.ViewHolder(container) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as FrameLayout
                container.removeAllViews()

                if (position == 0) {
                    val listRunning = ListView(context).apply { divider = null; dividerHeight = 0 }
                    val adapterRunning = object : BaseAdapter() {
                        override fun getCount(): Int = activeTasks.size
                        override fun getItem(p: Int): Any = activeTasks[p]
                        override fun getItemId(p: Int): Long = p.toLong()
                        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                            val task = activeTasks[p]
                            val row = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(30, 30, 30, 30)
                                gravity = Gravity.CENTER_VERTICAL
                                background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = 15f; setStroke(2, Color.parseColor("#333333")) }
                            }
                            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                            info.addView(TextView(context).apply { text = task.folderName; textSize = 16f; setTextColor(Color.parseColor("#B2FF59")); setTypeface(null, Typeface.BOLD); maxLines = 1 })
                            info.addView(TextView(context).apply { text = task.status; textSize = 12f; setTextColor(Color.WHITE) })
                            row.addView(info)
                            row.addView(ImageButton(context).apply {
                                setImageResource(android.R.drawable.ic_media_pause)
                                background = null; setColorFilter(Color.parseColor("#FF5252"))
                                setOnClickListener { task.stop(); activeTasks.remove(task); updateServiceStatus(); notifyDataSetChanged(); Toast.makeText(context, "停止しました", Toast.LENGTH_SHORT).show() }
                            })
                            return FrameLayout(context).apply { setPadding(15, 10, 15, 10); addView(row) }
                        }
                    }
                    listRunning.adapter = adapterRunning
                    container.addView(listRunning)

                    val h = Handler(Looper.getMainLooper())
                    val r = object : Runnable {
                        override fun run() {
                            if(bottomSheet.isShowing) { adapterRunning.notifyDataSetChanged(); h.postDelayed(this, 1000) }
                        }
                    }
                    h.post(r)

                } else {
                    val listHistory = ListView(context).apply { divider = null; dividerHeight = 0 }
                    val keys = historyData.keys()
                    val hList = ArrayList<JSONObject>()
                    while (keys.hasNext()) { val k = keys.next(); historyData.optJSONObject(k)?.let { it.put("folderName", k); hList.add(it) } }
                    hList.sortByDescending { it.optString("time") }

                    val adapterHistory = object : BaseAdapter() {
                        override fun getCount(): Int = hList.size
                        override fun getItem(p: Int): Any = hList[p]
                        override fun getItemId(p: Int): Long = p.toLong()
                        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                            val item = hList[p]
                            val row = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(30, 30, 30, 30)
                                gravity = Gravity.CENTER_VERTICAL
                                background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = 15f }
                            }
                            val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                            info.addView(TextView(context).apply { text = item.optString("folderName"); textSize = 16f; setTextColor(Color.parseColor("#80DEEA")); setTypeface(null, Typeface.BOLD); maxLines = 1 })
                            val chap = item.optString("chapter"); val t = item.optString("title")
                            info.addView(TextView(context).apply { text = if(chap.isNotEmpty()) "第${chap}話 $t" else t; textSize = 12f; setTextColor(Color.LTGRAY); maxLines = 1 })
                            row.addView(info)
                            row.addView(ImageButton(context).apply {
                                setImageResource(android.R.drawable.ic_menu_delete)
                                background = null; setColorFilter(Color.GRAY)
                                setOnClickListener {
                                    val fName = item.getString("folderName")
                                    historyData.remove(fName)
                                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY_HISTORY_JSON, historyData.toString()).apply()
                                    hList.removeAt(p); notifyDataSetChanged(); Toast.makeText(context, "削除しました", Toast.LENGTH_SHORT).show()
                                }
                            })
                            // ★★★ 履歴クリック時の動作変更 ★★★
                            row.setOnClickListener {
                                if(item.has("url")) mainWebView.loadUrl(item.getString("url"))
                                // 以前あった config 復元ロジックは削除済み。
                                // ページ読込完了時に自動適用が走るため、そちらに任せる。
                                bottomSheet.dismiss()
                            }
                            return FrameLayout(context).apply { setPadding(15, 10, 15, 10); addView(row) }
                        }
                    }
                    listHistory.adapter = adapterHistory
                    container.addView(listHistory)

                    val btnClear = Button(context).apply {
                        text = "履歴をすべて削除"; setTextColor(Color.RED); background = null
                        setOnClickListener {
                            AlertDialog.Builder(context).setTitle("確認").setMessage("全削除しますか？").setPositiveButton("はい"){_,_->
                                historyData = JSONObject(); hList.clear(); adapterHistory.notifyDataSetChanged()
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY_HISTORY_JSON, "{}").apply()
                            }.show()
                        }
                    }
                    listHistory.addFooterView(btnClear)
                }
            }
        }

        rootLayout.addView(tabLayout)
        rootLayout.addView(viewPager)
        bottomSheet.setContentView(rootLayout)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "🚀 実行中 (${activeTasks.size})" else "📜 履歴"
        }.attach()

        bottomSheet.show()
    }

    private fun injectInspector(view: WebView?) {
        val js = """
            (function() {
                document.body.addEventListener('click', function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    var el = e.target;
                    var info = {
                        tag: el.tagName.toLowerCase(),
                        id: el.id,
                        className: el.className,
                        text: el.innerText.substring(0, 50)
                    };
                    alert("INSPECT:" + JSON.stringify(info));
                }, true);
            })();
        """
        view?.evaluateJavascript(js, null)

        view?.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                if (message?.startsWith("INSPECT:") == true) {
                    val jsonStr = message.substring(8)
                    try {
                        val info = JSONObject(jsonStr)
                        val id = info.optString("id")
                        val cls = info.optString("className")
                        val tag = info.optString("tag")
                        var selector = tag
                        if (id.isNotEmpty()) selector += "#$id"
                        if (cls.isNotEmpty()) selector += ".${cls.replace(" ", ".")}"
                        showInspectResultDialog(selector, info.optString("text"))
                    } catch(e:Exception){}
                    result?.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }
        }
    }

    private fun showInspectResultDialog(selector: String, textPreview: String) {
        val input = EditText(this)
        input.setText(selector)
        AlertDialog.Builder(this)
            .setTitle("要素取得")
            .setMessage("テキスト: $textPreview\n\nコピーして設定に使えます。")
            .setView(input)
            .setPositiveButton("コピー") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Selector", input.text.toString()))
                Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("閉じる", null)
            .show()
    }

    private fun performTestRun() {
        val config = buildConfigFromUI()
        val jsCode = """
            (function() {
                var res = {};
                try {
                    res.title = document.querySelector('${config.optString("title").replace("'", "\\'")}').innerText.trim();
                } catch(e) { res.title = "取得失敗"; }
                try {
                    var body = document.querySelector('${config.optString("body").replace("'", "\\'")}');
                    res.content = body ? body.innerText.substring(0, 100) + "..." : "取得失敗";
                } catch(e) { res.content = "取得失敗"; }
                try {
                    var next = document.querySelector('${config.optString("next").replace("'", "\\'")}');
                    res.next = next ? next.href : "なし";
                } catch(e) { res.next = "なし"; }
                return JSON.stringify(res);
            })();
        """
        mainWebView.evaluateJavascript(jsCode) { res ->
            if (res != null) {
                try {
                    val raw = org.json.JSONTokener(res).nextValue().toString()
                    val json = JSONObject(raw)
                    val msg = "タイトル: ${json.optString("title")}\n\n本文(冒頭): ${json.optString("content")}\n\n次URL: ${json.optString("next")}"
                    AlertDialog.Builder(this).setTitle("テスト結果").setMessage(msg).setPositiveButton("OK", null).show()
                } catch(e:Exception) {
                    Toast.makeText(this, "解析エラー", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildConfigFromUI(): JSONObject {
        val config = JSONObject()
        config.put("body", editBody.text.toString())
        config.put("title", editTitle.text.toString())
        config.put("fileRegex", editFileRegex.text.toString())
        config.put("folder", editFolder.text.toString())
        config.put("folderLink", editFolderLink.text.toString())
        config.put("regex", editFolderRegex.text.toString())
        config.put("next", editNext.text.toString())
        config.put("chapter", editChapter.text.toString())
        config.put("chapterRegex", editChapterRegex.text.toString())
        config.put("delay", editDelay.text.toString())
        config.put("endCheck", editEndCheck.text.toString())
        config.put("autoUrl", editAutoUrl.text.toString())
        return config
    }

    private fun loadConfigToUI(data: JSONObject) {
        editBody.setText(data.optString("body"))
        editTitle.setText(data.optString("title"))
        editFileRegex.setText(data.optString("fileRegex", ""))
        editFolder.setText(data.optString("folder"))
        editFolderLink.setText(data.optString("folderLink", ""))
        editFolderRegex.setText(data.optString("regex", ""))
        editNext.setText(data.optString("next"))
        editChapter.setText(data.optString("chapter", ""))
        editChapterRegex.setText(data.optString("chapterRegex", ""))
        editDelay.setText(data.optString("delay", "15-30"))
        editEndCheck.setText(data.optString("endCheck", "list|index|toc|javascript|null"))
        editAutoUrl.setText(data.optString("autoUrl", ""))
    }

    private fun updateServiceStatus() {
        mainHandler.post {
            val count = activeTasks.size
            val intent = Intent(this, ScraperService::class.java)
            if (count > 0) {
                intent.action = ScraperService.ACTION_UPDATE_STATUS
                intent.putExtra(ScraperService.EXTRA_MSG, "実行中: $count 件")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                txtStatus.text = activeTasks.lastOrNull()?.status ?: "実行中..."
            } else {
                txtStatus.text = "待機中"
                stopService(intent)
            }
        }
    }

    private fun cleanText(original: String, pattern: String): String {
        var text = original
        if (pattern.isNotEmpty()) {
            try { text = text.replace(Regex(pattern), "") } catch (e: Exception) { }
        }
        return text.trim()
    }

    private fun saveToDownloads(folderName: String, fileName: String, content: String, chapterNum: String) {
        var cleanFileName = fileName.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "").trim()
        if (chapterNum.isNotEmpty()) cleanFileName = "${chapterNum}_${cleanFileName}"
        cleanFileName += ".txt"
        var safeFolderName = folderName.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "").trim()
        if (safeFolderName.isEmpty()) safeFolderName = "NovelScraper_Others"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DOWNLOADS + "/NovelScraper/" + safeFolderName + "/"
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(cleanFileName, relativePath)
                val cursor = contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uriToDelete = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        try { contentResolver.delete(uriToDelete, null, null) } catch (e: SecurityException) {}
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, cleanFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri).use { it?.write(content.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NovelScraper/$safeFolderName")
                if (!dir.exists()) dir.mkdirs()
                File(dir, cleanFileName).writeText(content)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showFavoritesListDialog() {
        val keys = favorites.keys()
        val list = ArrayList<String>()
        while (keys.hasNext()) list.add(keys.next())
        Collections.sort(list)
        if(list.isEmpty()){ Toast.makeText(this,"なし",Toast.LENGTH_SHORT).show(); return }

        val dialog = Dialog(this)
        dialog.setTitle("ブックマーク")
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = list.size
            override fun getItem(p: Int): Any = list[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val layout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(30,20,30,20)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = list[p]
                layout.addView(TextView(this@MainActivity).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                layout.addView(ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    background = null
                    setOnClickListener {
                        favorites.remove(name)
                        saveFavoritesToStorage()
                        list.removeAt(p)
                        notifyDataSetChanged()
                    }
                })
                layout.setOnClickListener {
                    val url = favorites.optString(name)
                    if(url.isNotEmpty()) { mainWebView.loadUrl(url); dialog.dismiss() }
                }
                return layout
            }
        }
        val listView = ListView(this).apply { this.adapter = adapter; setBackgroundColor(Color.DKGRAY) }
        dialog.setContentView(listView)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showAddFavoriteDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this).setTitle("追加").setView(input).setPositiveButton("OK") {_,_ ->
            if(input.text.isNotEmpty()) {
                favorites.put(input.text.toString(), editUrl.text.toString())
                saveFavoritesToStorage()
            }
        }.show()
    }

    private fun toggleSettingsPanel(show: Boolean) { settingsPanel.visibility = if (show) View.VISIBLE else View.GONE }
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
    private fun setupWebViewSettings(wv: WebView, imagesOn: Boolean) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        wv.settings.blockNetworkImage = !imagesOn
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }
    }
    private fun loadPresets() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(PREFS_KEY_DATA, "{}")
            presets = JSONObject(jsonStr)

            val isSetupDone = prefs.getBoolean(PREFS_KEY_SETUP_DONE, false)
            if (!isSetupDone) {
                if (!presets.has("Booktoki")) {
                    val def = JSONObject()
                    def.put("body", "#novel_content")
                    def.put("title", ".toon-title")
                    def.put("fileRegex", "")
                    def.put("folder", ".toon-title")
                    def.put("folderLink", "")
                    def.put("regex", "\\s*[-].*|\\(.*\\)|\\[.*\\]")
                    def.put("next", "#goNextBtn")
                    def.put("chapter", ".toon-title")
                    def.put("chapterRegex", "(\\d+)화")
                    def.put("endCheck", "list|index|toc|javascript")
                    def.put("autoUrl", "booktoki")
                    presets.put("Booktoki", def)
                    savePresetsToStorage()
                }
                prefs.edit().putBoolean(PREFS_KEY_SETUP_DONE, true).apply()
            }
            updatePresetSpinner()
        } catch (e: Exception) { presets = JSONObject() }
    }
    private fun savePresetsToStorage() { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY_DATA, presets.toString()).apply() }

    private fun updatePresetSpinner() {
        val list = ArrayList<String>()
        val keys = presets.keys()
        while(keys.hasNext()) list.add(keys.next())
        Collections.sort(list)

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.textSize = 14f
                view.setPadding(10, 10, 10, 10)
                view.setTextColor(Color.WHITE)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.textSize = 16f
                view.setPadding(20, 25, 20, 25)
                view.setTextColor(Color.WHITE)
                view.setBackgroundColor(Color.parseColor("#444444"))
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPresets.adapter = adapter
    }
    private fun loadFavorites() { try { favorites = JSONObject(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREFS_KEY_FAVORITES, "{}")) } catch(e:Exception){} }
    private fun saveFavoritesToStorage() { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY_FAVORITES, favorites.toString()).apply() }
    private fun loadHistoryMap() { try { historyData = JSONObject(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREFS_KEY_HISTORY_JSON, "{}")) } catch(e:Exception){} }
    private fun saveHistoryMap(folder:String, title:String, chap:String, url:String, config:JSONObject) {
        val item = JSONObject().apply{ put("title", title); put("chapter", chap); put("url", url); put("config", config); put("time", SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())) }
        historyData.put(folder, item)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY_HISTORY_JSON, historyData.toString()).apply()
    }
}