package com.example.novelscraper

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // --- Constants & Helpers ---
    companion object {
        const val PREFS_NAME = "NovelScraperPrefs"
        const val PREFS_KEY_DATA = "presets_json"
        const val PREFS_KEY_FAVORITES = "favorites_json"
        const val PREFS_KEY_HISTORY = "history_json_map_v3"
        const val PREFS_KEY_SETUP = "is_setup_done"

        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        fun createItemBg() = GradientDrawable().apply {
            setColor(Color.parseColor("#2D2D2D"))
            cornerRadius = 15f
        }
    }

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
    private var currentPresetName = ""
    private var isInspectMode = false
    private val activeTasks = CopyOnWriteArrayList<ScrapingTask>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupWindow()
        initializeViews()
        loadAllData()
        setupEventHandlers()
        checkNotificationPermission()
    }

    private fun setupWindow() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.BLACK
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
            WindowCompat.setDecorFitsSystemWindows(window, true)
        } catch (_: Exception) { }
    }

    private fun initializeViews() {
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
        setupWebViewSettings(mainWebView, true)

        mainWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let {
                    editUrl.setText(it)
                    checkAndApplyAutoPreset(it)
                }
                if (isInspectMode) injectInspector(view)
            }
        }
    }

    private fun setupEventHandlers() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPanel.visibility == View.VISIBLE) toggleSettingsPanel(false)
                else if (mainWebView.canGoBack()) mainWebView.goBack()
                else finish()
            }
        })

        editUrl.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val input = v.text.toString().trim()
                if (input.isNotEmpty()) {
                    if (input.startsWith("http") || (input.contains(".") && !input.contains(" "))) {
                        var target = input
                        if (!target.startsWith("http")) target = "https://$target"
                        mainWebView.loadUrl(target)
                    } else mainWebView.loadUrl("https://www.google.com/search?q=$input")
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
                true
            } else false
        }

        findViewById<ImageButton>(R.id.btnStar).setOnClickListener { showAddFavoriteDialog() }
        findViewById<ImageButton>(R.id.btnMenu).let { setupChromeSlideMenu(it) }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { if (mainWebView.canGoBack()) mainWebView.goBack() }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { if (mainWebView.canGoForward()) mainWebView.goForward() }

        findViewById<Button>(R.id.btnCloseSettings).setOnClickListener { toggleSettingsPanel(false) }
        findViewById<Button>(R.id.btnTestRun).setOnClickListener { performTestRun() }
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener { showSavePresetDialog() }
        findViewById<Button>(R.id.btnDeletePreset).setOnClickListener { deleteCurrentPreset() }

        toggleImages.setOnCheckedChangeListener { _, isChecked -> mainWebView.settings.blockNetworkImage = !isChecked; mainWebView.reload() }
        toggleInspectMode.setOnCheckedChangeListener { _, isChecked ->
            isInspectMode = isChecked
            if (isChecked) { injectInspector(mainWebView); Toast.makeText(this, "要素をタップして情報を取得", Toast.LENGTH_SHORT).show() }
            else mainWebView.reload()
        }
        fabAction.setOnClickListener { startScrapingTask() }
        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = spinnerPresets.getItemAtPosition(pos).toString()
                if (currentPresetName != name) {
                    currentPresetName = name
                    if (presets.has(name)) loadConfigToUI(presets.getJSONObject(name))
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun checkAndApplyAutoPreset(currentUrl: String) {
        if (settingsPanel.visibility == View.VISIBLE) return
        val keys = presets.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val preset = presets.optJSONObject(key) ?: continue
            val autoKeyword = preset.optString("autoUrl", "")
            if (autoKeyword.isNotEmpty()) {
                try {
                    if (currentUrl.contains(autoKeyword) || Regex(autoKeyword).containsMatchIn(currentUrl)) {
                        if (currentPresetName != key) {
                            currentPresetName = key
                            loadConfigToUI(preset)
                            val adapter = spinnerPresets.adapter as? ArrayAdapter<String>
                            val pos = adapter?.getPosition(key) ?: -1
                            if (pos >= 0) spinnerPresets.setSelection(pos)
                            Toast.makeText(this, "設定適用: $key", Toast.LENGTH_SHORT).show()
                        }
                        break
                    }
                } catch(e:Exception){}
            }
        }
    }

    private fun showTaskManagementSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val context = this
        val displayMetrics = Resources.getSystem().displayMetrics
        val height = (displayMetrics.heightPixels * 0.6).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val headerLayout = FrameLayout(context).apply {
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#1F1F1F"))
        }
        val txtTitle = TextView(context).apply { text = "タスク管理"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        val btnDeleteSelected = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_delete); background = null; imageTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
            visibility = View.GONE; layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        }
        headerLayout.addView(txtTitle); headerLayout.addView(btnDeleteSelected)
        rootLayout.addView(headerLayout)

        val tabLayout = TabLayout(context).apply { setBackgroundColor(Color.parseColor("#1F1F1F")); setTabTextColors(Color.GRAY, Color.WHITE); setSelectedTabIndicatorColor(Color.parseColor("#00897B")) }
        val viewPager = ViewPager2(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        rootLayout.addView(tabLayout); rootLayout.addView(viewPager)

        val historyList = ArrayList<JSONObject>()
        val keys = historyData.keys()
        while (keys.hasNext()) { val key = keys.next(); historyData.optJSONObject(key)?.let { it.put("folderName", key); historyList.add(it) } }
        historyList.sortByDescending { it.optString("time") }

        val runningAdapter = RunningTaskAdapter(activeTasks) { updateServiceStatus() }
        val historyAdapter = HistoryAdapter(historyList,
            onItemClick = { item ->
                bottomSheetDialog.dismiss()
                if (item.has("url")) mainWebView.loadUrl(item.getString("url"))
                if (item.has("config")) { loadConfigToUI(item.getJSONObject("config")); toggleSettingsPanel(true); Toast.makeText(context, "設定を復元しました", Toast.LENGTH_SHORT).show() }
            },
            onSelectionChanged = { count ->
                if (count > 0) { txtTitle.text = "$count 件選択中"; btnDeleteSelected.visibility = View.VISIBLE }
                else { txtTitle.text = "タスク管理"; btnDeleteSelected.visibility = View.GONE }
            }
        )

        viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2
            override fun getItemViewType(position: Int): Int = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val recyclerView = RecyclerView(parent.context).apply { layoutManager = LinearLayoutManager(parent.context); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
                if (viewType == 0) recyclerView.adapter = runningAdapter else recyclerView.adapter = historyAdapter
                return object : RecyclerView.ViewHolder(recyclerView) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, position -> tab.text = if (position == 0) "🚀 実行中 (${activeTasks.size})" else "📜 履歴" }.attach()

        btnDeleteSelected.setOnClickListener {
            val selected = historyAdapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                AlertDialog.Builder(context).setTitle("削除確認").setMessage("${selected.size} 件の履歴を削除しますか？")
                    .setPositiveButton("削除") { _, _ -> selected.forEach { folderName -> historyData.remove(folderName) }; saveHistoryToStorage(); historyAdapter.removeSelected(); txtTitle.text = "タスク管理"; btnDeleteSelected.visibility = View.GONE }
                    .setNegativeButton("キャンセル", null).show()
            }
        }
        val updateRunnable = object : Runnable {
            override fun run() {
                if (bottomSheetDialog.isShowing) { runningAdapter.notifyDataSetChanged(); tabLayout.getTabAt(0)?.text = "🚀 実行中 (${activeTasks.size})"; mainHandler.postDelayed(this, 1000) }
            }
        }
        mainHandler.post(updateRunnable)
        bottomSheetDialog.setContentView(rootLayout)
        val behavior = BottomSheetBehavior.from(rootLayout.parent as View)
        behavior.peekHeight = height
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetDialog.show()
    }

    private fun setupChromeSlideMenu(anchorView: View) {
        val popupWindow = PopupWindow(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#333333")); setPadding(10, 10, 10, 10) }
        fun createItem(text: String) = TextView(this).apply { this.text = text; textSize = 16f; setTextColor(Color.WHITE); setPadding(30, 30, 30, 30); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT) }

        val items = listOf(
            createItem("★ ブックマーク").apply { setOnClickListener { popupWindow.dismiss(); toggleSettingsPanel(false); showFavoritesListDialog() } },
            createItem("🕒 タスク管理").apply { setOnClickListener { popupWindow.dismiss(); toggleSettingsPanel(false); showTaskManagementSheet() } },
            // ★ここをEruda起動に戻しました
            createItem("🔍 解析ツール起動").apply {
                setOnClickListener {
                    popupWindow.dismiss()
                    toggleSettingsPanel(false)
                    val js = "if(!window.eruda){var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/eruda';document.body.appendChild(s);s.onload=function(){eruda.init();eruda.show()}}else{eruda.show()}"
                    mainWebView.evaluateJavascript(js, null)
                    Toast.makeText(this@MainActivity, "開発者ツール起動", Toast.LENGTH_SHORT).show()
                }
            },
            createItem("🔧 解析設定").apply { setOnClickListener { popupWindow.dismiss(); toggleSettingsPanel(true) } }
        )
        items.forEach { layout.addView(it) }
        popupWindow.contentView = layout; popupWindow.width = 550; popupWindow.height = WRAP_CONTENT; popupWindow.isFocusable = true; popupWindow.isOutsideTouchable = true; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) popupWindow.elevation = 20f

        anchorView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { if (!popupWindow.isShowing) popupWindow.showAsDropDown(v, -300, 0); true }
                MotionEvent.ACTION_MOVE -> { if (popupWindow.isShowing) { val rx = event.rawX; val ry = event.rawY; items.forEach { val loc = IntArray(2); it.getLocationOnScreen(loc); val r = Rect(loc[0], loc[1], loc[0]+it.width, loc[1]+it.height); it.setBackgroundColor(if (r.contains(rx.toInt(), ry.toInt())) Color.parseColor("#555555") else Color.TRANSPARENT) } }; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (popupWindow.isShowing) {
                        var clicked: View? = null; val rx = event.rawX; val ry = event.rawY
                        items.forEach { val loc = IntArray(2); it.getLocationOnScreen(loc); val r = Rect(loc[0], loc[1], loc[0]+it.width, loc[1]+it.height); if (r.contains(rx.toInt(), ry.toInt())) clicked = it; it.setBackgroundColor(Color.TRANSPARENT) }
                        if (clicked != null) clicked.performClick() else { val r = Rect(); v.getGlobalVisibleRect(r); if(!r.contains(rx.toInt(), ry.toInt())) popupWindow.dismiss() }
                    }
                    true
                }
                else -> false
            }
        }
    }

    class RunningTaskAdapter(private val tasks: List<ScrapingTask>, private val onStop: () -> Unit) : RecyclerView.Adapter<RunningTaskAdapter.ViewHolder>() {
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val t1:TextView=v.findViewById(1); val t2:TextView=v.findViewById(2); val b:ImageButton=v.findViewById(3); val d:View=v.findViewById(4) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): ViewHolder {
            val l = LinearLayout(p.context).apply { orientation=LinearLayout.HORIZONTAL; setPadding(30,20,30,20); gravity=Gravity.CENTER_VERTICAL; background=createItemBg() }
            val dot = View(p.context).apply { id=4; layoutParams=LinearLayout.LayoutParams(20,20).apply{marginEnd=30}; background=GradientDrawable().apply{shape=GradientDrawable.OVAL} }
            val info = LinearLayout(p.context).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
            info.addView(TextView(p.context).apply{id=1; textSize=16f; setTextColor(Color.WHITE); setTypeface(null,Typeface.BOLD)})
            info.addView(TextView(p.context).apply{id=2; textSize=12f; setTextColor(Color.LTGRAY)})
            l.addView(dot); l.addView(info); l.addView(ImageButton(p.context).apply{id=3; setImageResource(android.R.drawable.ic_media_pause); background=null; setColorFilter(Color.parseColor("#FF5252")); setPadding(20,20,20,20)})
            return ViewHolder(l)
        }
        override fun onBindViewHolder(h: ViewHolder, p: Int) { val t = tasks[p]; h.t1.text=t.folderName; h.t2.text=t.status; (h.d.background as GradientDrawable).setColor(if(t.isRunning) Color.parseColor("#00E676") else Color.RED); h.b.setOnClickListener { t.stop(); (tasks as CopyOnWriteArrayList).remove(t); notifyItemRemoved(p); onStop() } }
        override fun getItemCount() = tasks.size
    }

    class HistoryAdapter(private val items: ArrayList<JSONObject>, private val onItemClick: (JSONObject)->Unit, private val onSelectionChanged: (Int)->Unit) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private val selected = HashSet<String>(); private var selectionMode = false
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val c:LinearLayout=v.findViewById(100); val t1:TextView=v.findViewById(1); val t2:TextView=v.findViewById(2); val d:View=v.findViewById(4) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): ViewHolder {
            val c = LinearLayout(p.context).apply { id=100; orientation=LinearLayout.HORIZONTAL; setPadding(30,25,30,25); gravity=Gravity.CENTER_VERTICAL; background=createItemBg(); layoutParams=RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply{setMargins(0,5,0,5)} }
            val dot = View(p.context).apply { id=4; layoutParams=LinearLayout.LayoutParams(20,20).apply{marginEnd=30}; background=GradientDrawable().apply{shape=GradientDrawable.OVAL} }
            val info = LinearLayout(p.context).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
            info.addView(TextView(p.context).apply{id=1; textSize=16f; setTextColor(Color.WHITE); setTypeface(null,Typeface.BOLD)})
            info.addView(TextView(p.context).apply{id=2; textSize=12f; setTextColor(Color.GRAY)})
            c.addView(dot); c.addView(info)
            return ViewHolder(c)
        }
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val i = items[p]; val f = i.optString("folderName")
            h.t1.text=f; val ch=i.optString("chapter"); val ti=i.optString("title"); h.t2.text=if(ch.isNotEmpty()) "第${ch}話 $ti" else ti
            val isSel = selected.contains(f); h.c.setBackgroundColor(if(isSel) Color.parseColor("#3E2723") else Color.TRANSPARENT); (h.d.background as GradientDrawable).setColor(if(isSel) Color.parseColor("#FFAB40") else Color.parseColor("#29B6F6"))
            h.itemView.setOnClickListener { if(selectionMode) toggle(f) else onItemClick(i) }; h.itemView.setOnLongClickListener { if(!selectionMode) { selectionMode=true; toggle(f) }; true }
        }
        private fun toggle(k:String) { if(selected.contains(k)) selected.remove(k) else selected.add(k); if(selected.isEmpty()) selectionMode=false; onSelectionChanged(selected.size); notifyDataSetChanged() }
        fun getSelectedItems() = selected.toList(); fun removeSelected() { items.removeAll { selected.contains(it.optString("folderName")) }; selected.clear(); selectionMode=false; notifyDataSetChanged() }
        override fun getItemCount() = items.size
    }

    private fun startScrapingTask() {
        val u = mainWebView.url ?: editUrl.text.toString(); if(u.isEmpty()) return
        checkNotificationPermission(); val c = buildConfigFromUI()
        val t = ScrapingTask(u, c, toggleImages.isChecked) { fin -> activeTasks.remove(fin); updateServiceStatus(); notifyTaskComplete(fin) }
        activeTasks.add(t); t.start(); updateServiceStatus(); toggleSettingsPanel(false); Toast.makeText(this,"DL開始: ${t.folderName}",Toast.LENGTH_SHORT).show()
    }
    private fun notifyTaskComplete(t: ScrapingTask) { val i=Intent(this,ScraperService::class.java); i.action=ScraperService.ACTION_SHOW_COMPLETE; i.putExtra(ScraperService.EXTRA_TITLE,"完了"); i.putExtra(ScraperService.EXTRA_MSG,"${t.folderName} 完了"); startService(i) }
    private fun updateServiceStatus() { mainHandler.post { val c=activeTasks.size; val i=Intent(this,ScraperService::class.java); if(c>0){i.action=ScraperService.ACTION_UPDATE_STATUS; i.putExtra(ScraperService.EXTRA_MSG,"実行中: $c"); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); txtStatus.text=activeTasks.lastOrNull()?.status?:"実行中"}else{txtStatus.text="待機中"; stopService(i)} } }

    inner class ScrapingTask(val startUrl:String, val config:JSONObject, val useImg:Boolean, val onFinish:(ScrapingTask)->Unit) {
        val webView = WebView(this@MainActivity); var currentUrl=startUrl; var lastSuccessUrl=""; var retryCount=0; var status="準備..."; var folderName="(取得中...)"; var isRunning=true; var state=TaskState.INITIAL_CHECK; var r:Runnable?=null
        init {
            setupWebViewSettings(webView, useImg)
            webView.webViewClient = object:WebViewClient() {
                override fun onPageFinished(v:WebView?, u:String?) {
                    if(!isRunning)return; currentUrl=u?:return; verifyTurnstile(v)
                    if(v?.title?.contains("Just a moment")==true || v?.title?.contains("Verify")==true){ status="CF待機"; updateServiceStatus(); r=Runnable{if(isRunning)v?.reload()}; mainHandler.postDelayed(r!!,30000); return }
                    if(state==TaskState.SCRAPING) performScroll(v)
                    val d=if(state==TaskState.SCRAPING)5000L else 2000L; r?.let{mainHandler.removeCallbacks(it)}; r=Runnable{if(isRunning)step()}; mainHandler.postDelayed(r!!,d)
                }
                override fun onReceivedError(v:WebView?,req:WebResourceRequest?,e:WebResourceError?) { if(req?.isForMainFrame==true) retry("Error:${e?.errorCode}") }
            }
        }
        fun start(){ status="開始"; webView.loadUrl(startUrl); updateServiceStatus() }
        fun stop(){ isRunning=false; r?.let{mainHandler.removeCallbacks(it)}; webView.destroy() }
        fun step(){ when(state){ TaskState.INITIAL_CHECK->checkLink(); TaskState.FETCHING_FOLDER->getFolder(); TaskState.RETURNING->{state=TaskState.SCRAPING;scrape()}; TaskState.SCRAPING->scrape() } }
        fun retry(m:String){ if(retryCount++<3){status="リトライ: $m"; updateServiceStatus(); r=Runnable{if(isRunning)webView.loadUrl(currentUrl)}; mainHandler.postDelayed(r!!,60000)}else{status="停止: $m"; isRunning=false; onFinish(this)} }
        fun verifyTurnstile(v:WebView?){v?.evaluateJavascript("(function(){function c(r){if(!r)return;var s=r.shadowRoot;var t=s?s:r;var i=t.querySelectorAll('input[type=\"checkbox\"]');for(var j=0;j<i.length;j++)if(!i[j].checked)i[j].click();var ch=t.children;for(var k=0;k<ch.length;k++)c(ch[k])}c(document.body)})()",null)}
        fun performScroll(v:WebView?){v?.evaluateJavascript("(function(){var h=document.body.scrollHeight;var c=window.scrollY;function s(){if(c>=h-window.innerHeight)return;c+=Math.floor(Math.random()*60)+20;window.scrollTo(0,c);setTimeout(s,Math.floor(Math.random()*150)+50)}s()})()",null)}
        fun checkLink() { val s=config.optString("folderLink"); if(s.isEmpty()){getFolderInPlace();return}; webView.evaluateJavascript("(function(){var e=document.querySelector('$s');return e?e.href:''})()"){r-> val l=r?.replace("\"","")?:""; if(l.length>5){state=TaskState.FETCHING_FOLDER;webView.loadUrl(l)}else getFolderInPlace()} }
        fun getFolder(){ webView.evaluateJavascript("(function(){var e=document.querySelector('${config.optString("folder")}');return e?e.innerText.trim():''})()"){r->folderName=clean(r,config.optString("regex")); if(folderName.isEmpty())folderName="Unknown"; state=TaskState.RETURNING; webView.loadUrl(startUrl)} }
        fun getFolderInPlace(){ val s=config.optString("folder"); if(s.startsWith("@"))folderName=s.substring(1) else webView.evaluateJavascript("(function(){var e=document.querySelector('$s');return e?e.innerText.trim():''})()"){r->folderName=clean(r,config.optString("regex")); if(folderName.isEmpty())folderName="Unknown"}; state=TaskState.SCRAPING; scrape() }

        fun scrape(){
            if(!isRunning || currentUrl==lastSuccessUrl)return
            val js="(function(){if(document.title.includes('Just a moment'))return'CF';var r={};r.t=document.querySelector('${config.optString("title").replace("'","\\'")}')?.innerText.trim()||'NoT';var b=document.querySelector('${config.optString("body").replace("'","\\'")}');r.c=b?b.innerText:Array.from(document.querySelectorAll('p')).map(p=>p.innerText).join('\\n\\n');r.n=document.querySelector('${config.optString("next").replace("'","\\'")}')?.href||'';r.ch=document.querySelector('${config.optString("chapter").replace("'","\\'")}')?.innerText||'';return JSON.stringify(r)})()"
            webView.evaluateJavascript(js){res->
                if(!isRunning)return@evaluateJavascript; try{
                if(res=="\"CF\""){ status="CF待機"; updateServiceStatus(); r=Runnable{if(isRunning)webView.reload()}; mainHandler.postDelayed(r!!,30000); return@evaluateJavascript }
                val j=JSONObject(org.json.JSONTokener(res).nextValue().toString())
                val ti=clean(j.optString("t"),config.optString("fileRegex")); val co=j.optString("c"); val ne=j.optString("n"); var ch=j.optString("ch")
                val cr=config.optString("chapterRegex"); if(cr.isNotEmpty())try{ch=Regex(cr).find(ch)?.groupValues?.getOrElse(1){""}?:ch}catch(_:Exception){}else ch=Regex("\\d+").find(ch)?.value?:ch
                if(ch.isNotEmpty()) ch=ch.padStart(4,'0')
                if(co.length<20){ retry("本文短過"); return@evaluateJavascript }
                saveToDownloads(folderName,ti,co,ch)
                if(folderName!="(取得中...)") addToHistory(folderName,ti,ch,currentUrl,config)
                lastSuccessUrl=currentUrl; retryCount=0; status="保存: $ch $ti"; updateServiceStatus()

                val ec=config.optString("endCheck"); var st=false; if(ec.isNotEmpty()&&(Regex(ec).containsMatchIn(ne)||ne.contains("null")))st=true
                if(ne.length>5 && !st){
                    val ds=config.optString("delay","15-30"); var w=2L; try{val p=ds.split("-"); w=Random.nextLong(p[0].toLong(),p[1].toLong()+1)}catch(_:Exception){}
                    status="待機(${w}s)..."; updateServiceStatus(); r=Runnable{if(isRunning)webView.loadUrl(ne)}; mainHandler.postDelayed(r!!,w*1000)
                } else { status="完了"; isRunning=false; onFinish(this) }
            }catch(e:Exception){ retry("JS解析エラー") }
            }
        }
    }
    enum class TaskState { INITIAL_CHECK, FETCHING_FOLDER, RETURNING, SCRAPING }

    private fun loadAllData() { val p=getSharedPreferences(PREFS_NAME,0); try{presets=JSONObject(p.getString(PREFS_KEY_DATA,"{}"))}catch(_:Exception){}; try{favorites=JSONObject(p.getString(PREFS_KEY_FAVORITES,"{}"))}catch(_:Exception){}; try{historyData=JSONObject(p.getString(PREFS_KEY_HISTORY,"{}"))}catch(_:Exception){}; if(!p.getBoolean(PREFS_KEY_SETUP,false))p.edit().putBoolean(PREFS_KEY_SETUP,true).apply(); updateSpinner() }
    private fun savePref(k:String,v:String)=getSharedPreferences(PREFS_NAME,0).edit().putString(k,v).apply()
    private fun saveHistoryToStorage() { savePref(PREFS_KEY_HISTORY, historyData.toString()) }
    private fun addToHistory(f:String,t:String,c:String,u:String,cfg:JSONObject){ val i=JSONObject().apply{put("title",t);put("chapter",c);put("url",u);put("config",cfg);put("time",SimpleDateFormat("MM/dd HH:mm",Locale.getDefault()).format(Date()))}; historyData.put(f,i); saveHistoryToStorage() }

    private fun buildConfigFromUI() = JSONObject().apply {
        put("body",editBody.text); put("title",editTitle.text); put("fileRegex",editFileRegex.text)
        put("folder",editFolder.text); put("folderLink",editFolderLink.text); put("regex",editFolderRegex.text)
        put("next",editNext.text); put("chapter",editChapter.text); put("chapterRegex",editChapterRegex.text)
        put("delay",editDelay.text); put("endCheck",editEndCheck.text); put("autoUrl",editAutoUrl.text)
    }
    private fun loadConfigToUI(j:JSONObject) {
        editBody.setText(j.optString("body")); editTitle.setText(j.optString("title")); editFileRegex.setText(j.optString("fileRegex"))
        editFolder.setText(j.optString("folder")); editFolderLink.setText(j.optString("folderLink")); editFolderRegex.setText(j.optString("regex"))
        editNext.setText(j.optString("next")); editChapter.setText(j.optString("chapter")); editChapterRegex.setText(j.optString("chapterRegex"))
        editDelay.setText(j.optString("delay","15-30")); editEndCheck.setText(j.optString("endCheck","list|index|toc|javascript")); editAutoUrl.setText(j.optString("autoUrl",""))
    }
    private fun updateSpinner(){ val l=ArrayList<String>(); val k=presets.keys(); while(k.hasNext())l.add(k.next()); Collections.sort(l); spinnerPresets.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,l) }
    private fun clean(s:String?,r:String):String{ var t=s?.replace("\"","")?:""; if(r.isNotEmpty())try{t=t.replace(Regex(r),"")}catch(_:Exception){}; return t.trim() }
    private fun saveToDownloads(f:String,t:String,c:String,ch:String) {
        var fn=t.replace(Regex("[\\\\/:*?\"<>|]"),"").trim(); if(ch.isNotEmpty())fn="${ch}_$fn"; fn+=".txt"
        var fd=f.replace(Regex("[\\\\/:*?\"<>|]"),"").trim(); if(fd.isEmpty())fd="NovelScraper_DL"
        try{
            if(Build.VERSION.SDK_INT>=29){
                val cv=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,fn);put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/NovelScraper/$fd/")}
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv)?.let{contentResolver.openOutputStream(it)?.use{o->o.write(c.toByteArray())}}
            }else{ val d=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"NovelScraper/$fd"); if(!d.exists())d.mkdirs(); File(d,fn).writeText(c) }
        }catch(_:Exception){}
    }
    private fun showFavoritesListDialog(){
        val k=favorites.keys(); val l=ArrayList<String>(); while(k.hasNext())l.add(k.next()); Collections.sort(l); if(l.isEmpty()){Toast.makeText(this,"なし",Toast.LENGTH_SHORT).show();return}
        val d=Dialog(this); d.setTitle("ブックマーク"); val ad=object:BaseAdapter(){
            override fun getCount()=l.size; override fun getItem(p:Int)=l[p]; override fun getItemId(p:Int)=p.toLong()
            override fun getView(p:Int,c:View?,pv:ViewGroup?):View{
                val ll=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL; setPadding(30,20,30,20); gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#333333"))}
                ll.addView(TextView(this@MainActivity).apply{text=l[p]; setTextColor(Color.WHITE); textSize=16f; layoutParams=LinearLayout.LayoutParams(0,WRAP_CONTENT,1f)})
                ll.addView(ImageButton(this@MainActivity).apply{setImageResource(android.R.drawable.ic_menu_delete); background=null; setColorFilter(Color.parseColor("#FF5252")); setOnClickListener{favorites.remove(l[p]); savePref(PREFS_KEY_FAVORITES,favorites.toString()); l.removeAt(p); notifyDataSetChanged(); if(l.isEmpty())d.dismiss()}})
                ll.setOnClickListener{mainWebView.loadUrl(favorites.optString(l[p])); d.dismiss()}; return ll
            }
        }; val lv=ListView(this).apply{adapter=ad; setBackgroundColor(Color.parseColor("#222222")); divider=GradientDrawable().apply{setColor(Color.DKGRAY);setSize(1,1)}; dividerHeight=1}
        d.setContentView(lv); d.window?.setLayout((resources.displayMetrics.widthPixels*0.9).toInt(),WRAP_CONTENT); d.show()
    }
    private fun showAddFavoriteDialog(){ val e=EditText(this); AlertDialog.Builder(this).setTitle("追加").setView(e).setPositiveButton("OK"){_,_->favorites.put(e.text.toString(),editUrl.text.toString()); savePref(PREFS_KEY_FAVORITES,favorites.toString())}.show() }
    private fun showSavePresetDialog(){ val e=EditText(this); AlertDialog.Builder(this).setTitle("保存").setView(e).setPositiveButton("OK"){_,_->presets.put(e.text.toString(),buildConfigFromUI()); savePref(PREFS_KEY_DATA,presets.toString()); updateSpinner()}.show() }
    private fun deleteCurrentPreset(){ if(currentPresetName.isNotEmpty()){presets.remove(currentPresetName); savePref(PREFS_KEY_DATA,presets.toString()); updateSpinner()} }
    private fun toggleSettingsPanel(s:Boolean){ settingsPanel.visibility=if(s)View.VISIBLE else View.GONE }
    private fun checkNotificationPermission(){ if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),101) }
    private fun setupWebViewSettings(v:WebView,i:Boolean){ v.settings.apply{javaScriptEnabled=true; domStorageEnabled=true; userAgentString="Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"; blockNetworkImage=!i}; CookieManager.getInstance().setAcceptThirdPartyCookies(v,true) }
    private fun injectInspector(v:WebView?){ v?.evaluateJavascript("(function(){document.body.addEventListener('click',function(e){e.preventDefault();alert('INSPECT:'+JSON.stringify({tag:e.target.tagName,id:e.target.id,cls:e.target.className}))},true)})()",null); v?.webChromeClient=object:WebChromeClient(){override fun onJsAlert(v:WebView?,u:String?,m:String?,r:JsResult?):Boolean{if(m?.startsWith("INSPECT:")==true){showInspectDialog(m.substring(8));r?.confirm();return true};return super.onJsAlert(v,u,m,r)}} }
    private fun showInspectDialog(j:String){ try{val o=JSONObject(j); val s="${o.optString("tag")}#${o.optString("id")}.${o.optString("cls")}".replace("..","."); val e=EditText(this); e.setText(s); AlertDialog.Builder(this).setTitle("要素").setView(e).setPositiveButton("コピー"){_,_->(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sel",s))}.show()}catch(_:Exception){} }
    private fun performTestRun(){
        val c=buildConfigFromUI(); val js="(function(){var r={};try{r.t=document.querySelector('${c.optString("title").replace("'","\\'")}').innerText.trim()}catch(e){r.t='失敗'} try{r.c=document.querySelector('${c.optString("body").replace("'","\\'")}').innerText.substring(0,100)+'...'}catch(e){r.c='失敗'} try{r.n=document.querySelector('${c.optString("next").replace("'","\\'")}').href}catch(e){r.n='なし'} return JSON.stringify(r)})()"
        mainWebView.evaluateJavascript(js){r->if(r!=null&&r!="null"){try{val j=JSONObject(org.json.JSONTokener(r).nextValue().toString()); AlertDialog.Builder(this).setTitle("結果").setMessage("T:${j.optString("t")}\n\nC:${j.optString("c")}\n\nN:${j.optString("n")}").setPositiveButton("OK",null).show()}catch(e:Exception){Toast.makeText(this,"解析エラー",Toast.LENGTH_SHORT).show()}}else Toast.makeText(this,"失敗",Toast.LENGTH_SHORT).show()}
    }
}