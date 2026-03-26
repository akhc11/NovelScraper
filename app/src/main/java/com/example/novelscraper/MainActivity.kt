package com.example.novelscraper

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.novelscraper.ui.MainScreen
import com.example.novelscraper.ui.theme.NovelScraperTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ScrapingViewModel
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        viewModel = ViewModelProvider(this)[ScrapingViewModel::class.java]

        setupSystemUI()
        checkNotificationPermission()

        setContent {
            NovelScraperTheme {
                MainScreen(
                    viewModel = viewModel,
                    onStartScraping = { url -> startScrapingTask(url) },
                    onInspectResult = { sel, text -> showInspectResultDialog(sel, text) },
                    onLaunchAnalysisTool = { view -> launchAnalysisTool(view) },
                    onInjectInspector = { view -> injectInspector(view) },
                    onNavigate = { url, view -> performNavigation(url, view) },
                    onShowAddFavorite = { title, url -> showAddFavoriteDialog(title, url) },
                    onShowSavePreset = { showSavePresetDialog() },
                    onTestRun = { view -> performTestRun(view) }
                )
            }
        }

        observeViewModel()
    }

    private fun setupSystemUI() {
        window.statusBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        // ComposeのScaffoldがWindowInsets(ステータスバー等)を正しく計算できるように false に設定します。
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun performNavigation(input: String, view: WebView) {
        if (input.isEmpty()) return
        val target = if (android.util.Patterns.WEB_URL.matcher(input).matches() || android.webkit.URLUtil.isValidUrl(input)) {
            if (!input.startsWith("http")) "https://$input" else input
        } else { "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}" }
        view.loadUrl(target)
    }

    private fun showAddFavoriteDialog(title: String, url: String) {
        val input = EditText(this)
        input.setText(title)
        AlertDialog.Builder(this)
            .setTitle("ブックマークに追加")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    viewModel.saveFavorite(name, url)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this)
        input.hint = "設定名 (空欄で自動命名)"
        AlertDialog.Builder(this)
            .setTitle("設定保存")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val currentUrl = viewModel.uiState.value.currentUrl
                val host = try { Uri.parse(currentUrl).host ?: "NewPreset" } catch (e: Exception) { "NewPreset" }
                var baseName = if (host.startsWith("www.")) host.substring(4) else host
                var name = input.text.toString().trim().ifEmpty { baseName }
                val original = name
                var c = 1
                while (viewModel.presets.value.containsKey(name)) { 
                    name = "$original ($c)"
                    c++ 
                }
                viewModel.savePreset(name, viewModel.uiState.value.currentConfig)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun performTestRun(view: WebView) {
        val config = viewModel.uiState.value.currentConfig
        val jsCode = """
            (function() {
                var res = { title: '', content: '', nextUrl: '', chapter: '', folderName: '' };
                try {
                    function extractText(el) {
                        if (!el) return '';
                        var clone = el.cloneNode(true);
                        clone.querySelectorAll('script, style, noscript, iframe').forEach(n => n.remove());
                        clone.querySelectorAll('br').forEach(n => n.outerHTML = '\n');
                        clone.querySelectorAll('p, div, h1, h2, h3').forEach(n => n.after('\n'));
                        return clone.innerText.trim();
                    }

                    var metaData = {};
                    try {
                        var ldJsons = document.querySelectorAll('script[type="application/ld+json"]');
                        for (var i = 0; i < ldJsons.length; i++) {
                            var p = JSON.parse(ldJsons[i].innerText);
                            if (Array.isArray(p)) p = p[0];
                            if (p.headline || (p.isPartOf && p.isPartOf.name)) { metaData = p; break;}
                        }
                    } catch(e) {}

                    var fSel = '${config.folder.replace("'", "\\'")}';
                    var fTitle = '';
                    if (fSel && !fSel.startsWith('@')) {
                        var fElem = document.querySelector(fSel);
                        if (fElem) fTitle = fElem.innerText.trim();
                    }
                    if (!fTitle) {
                        var nTitleElem = document.querySelector('.series-title, .novel_title, .novel-title, .p-novel__title');
                        fTitle = nTitleElem ? nTitleElem.innerText.trim() : '';
                    }
                    res.folderName = fTitle || '取得失敗';

                    var tSel = '${config.title.replace("'", "\\'")}';
                    var tElem = tSel ? document.querySelector(tSel) : null;
                    if (!tElem) tElem = document.querySelector('.novel_subtitle, .ep-title, .chapter-title, h1, h2');
                    res.title = tElem ? tElem.innerText.trim() : '取得失敗';

                    var bSel = '${config.body.replace("'", "\\'")}';
                    var bElem = bSel ? document.querySelector(bSel) : null;
                    if (!bElem) {
                        var candidates = Array.from(document.querySelectorAll('div, article, section')).map(el => ({ el: el, count: el.querySelectorAll('p').length })).sort((a,b) => b.count - a.count);
                        if (candidates.length > 0) bElem = candidates[0].el;
                    }
                    var fullText = extractText(bElem);
                    res.content = fullText.substring(0, 200) + (fullText.length > 200 ? '...' : '');

                    var nSel = '${config.next.replace("'", "\\'")}';
                    var nElem = nSel ? document.querySelector(nSel) : document.querySelector('a[rel="next"]');
                    res.nextUrl = nElem ? nElem.href : '';
                } catch(e) { res.title = 'Error'; res.content = e.message; }
                return JSON.stringify(res);
            })();
        """.trimIndent()
        view.evaluateJavascript(jsCode) { res ->
            if (res != null) {
                try {
                    val data = Json.decodeFromString<ScrapingResult>(Json.decodeFromString<String>(res))
                    var chapNum = data.chapter

                    // --- 1. URLから数字を抽出 (任意の場所から一番最後の数字の塊を取得) ---
                    if (chapNum.isEmpty()) {
                        val currentUrl = view.url ?: ""
                        val urlNumbers = Regex("(\\d+)").findAll(currentUrl).map { it.value }.toList()
                        if (urlNumbers.isNotEmpty()) {
                            chapNum = urlNumbers.last().padStart(4, '0') + " (URLから自動抽出)"
                        }
                    }

                    val chapterStr = if (chapNum.isNotEmpty()) "\n\nチャプター: $chapNum" else ""
                    val msg = "作品名(フォルダ): ${data.folderName}${chapterStr}\n\nタイトル: ${data.title}\n\n本文(冒頭): ${data.content}\n\n次URL: ${data.nextUrl}"
                    AlertDialog.Builder(this).setTitle("テスト結果").setMessage(msg).setPositiveButton("OK", null).show()
                } catch (e: Exception) { Toast.makeText(this, "解析エラー", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.presets.collect { presets ->
                if (viewModel.uiState.value.currentPresetName.isEmpty() && presets.containsKey("自動検出(推奨)")) {
                    presets["自動検出(推奨)"]?.let { viewModel.applyPresetState("自動検出(推奨)", it) }
                }
            }
        }
    }

    private fun launchAnalysisTool(view: WebView) {
        val script = """
            (function() {
                if (window.eruda) {
                    if (window.__eruda_is_open) { eruda.hide(); window.__eruda_is_open = false; }
                    else { eruda.show(); window.__eruda_is_open = true; }
                    return;
                }
                var s = document.createElement('script');
                s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                document.body.appendChild(s);
                s.onload = function() {
                    eruda.init();
                    eruda.show();
                    window.__eruda_is_open = true;
                    // ErudaのUIを拡大するためのCSSを注入
                    var style = document.createElement('style');
                    style.innerHTML = '#eruda .eruda-container { font-size: 16px !important; } #eruda .eruda-dev-tools { height: 70% !important; }';
                    document.querySelector('#eruda').shadowRoot.appendChild(style);
                };
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun startScrapingTask(targetUrl: String) {
        if (targetUrl.isEmpty()) return
        val config = viewModel.uiState.value.currentConfig
        val useImages = !viewModel.uiState.value.blockImages
        val newTask = ScrapingTask(this, targetUrl, config, useImages, object : ScrapingTask.TaskListener {
            override fun onStatusUpdate(task: ScrapingTask, status: String) { viewModel.updateStatus(); updateServiceStatus() }
            override fun onTaskFinished(task: ScrapingTask) { viewModel.removeTask(task); updateServiceStatus(); notifyTaskComplete(task) }
            override fun onSaveResult(folderName: String, title: String, content: String, chapterNum: String) { saveToDownloads(folderName, title, content, chapterNum) }
            override fun onUpdateHistory(folderName: String, title: String, chapter: String, url: String, config: ScraperConfig) { viewModel.updateHistory(folderName, title, chapter, url, config) }
        })
        viewModel.addTask(newTask); newTask.start(); updateServiceStatus(); viewModel.closePanels()
    }

    private fun notifyTaskComplete(task: ScrapingTask) {
        val intent = Intent(this, ScraperService::class.java).apply {
            action = ScraperService.ACTION_SHOW_COMPLETE
            putExtra(ScraperService.EXTRA_TITLE, "ダウンロード完了")
            putExtra(ScraperService.EXTRA_MSG, "${task.folderName} の処理が終了しました")
        }
        startService(intent)
    }

    private fun injectInspector(view: WebView?) {
        val js = "(function(){var prev=null;document.body.addEventListener('mouseover',function(e){if(prev)prev.style.outline='';e.target.style.outline='2px solid red';prev=e.target;},true);document.body.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();function getCss(el){if(!(el instanceof Element))return;var path=[];while(el.nodeType===Node.ELEMENT_NODE){var sel=el.nodeName.toLowerCase();if(el.id&&!el.id.match(/^[0-9]/)){sel+='#'+el.id;path.unshift(sel);break;}else{var cls=el.className;if(typeof cls==='string'&&cls.trim()!==''){var valid=cls.trim().split(/\\s+/).filter(c=>!c.match(/[0-9:\\[\\]\\.]/));if(valid.length>0)sel+='.'+valid.join('.');}var sib=el,nth=1;while(sib=sib.previousElementSibling)if(sib.nodeName.toLowerCase()==el.nodeName.toLowerCase())nth++;if(nth!=1)sel+=':nth-of-type('+nth+')';}path.unshift(sel);el=el.parentNode;if(path.length>3)break;}return path.join(' > ');}var info={selector:getCss(e.target)||'失敗',text:e.target.innerText.substring(0,100)};alert('INSPECT:'+JSON.stringify(info));},true);})();"
        view?.evaluateJavascript(js, null)
        view?.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, msg: String?, res: JsResult?): Boolean {
                if (msg?.startsWith("INSPECT:") == true) {
                    val info = JSONObject(msg.substring(8))
                    showInspectResultDialog(info.getString("selector"), info.getString("text"))
                    res?.confirm(); return true
                }
                return super.onJsAlert(view, url, msg, res)
            }
        }
    }

    private fun showInspectResultDialog(selector: String, textPreview: String) {
        val input = EditText(this); input.setText(selector)
        AlertDialog.Builder(this).setTitle("要素取得").setMessage("テキスト: $textPreview\n\n設定にコピーできます。").setView(input).setPositiveButton("コピー") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Selector", input.text.toString()))
        }.setNeutralButton("閉じる", null).show()
    }

    private fun updateServiceStatus() {
        mainHandler.post {
            val count = viewModel.activeTasks.value.size; val intent = Intent(this, ScraperService::class.java)
            if (count > 0) {
                intent.action = ScraperService.ACTION_UPDATE_STATUS; intent.putExtra(ScraperService.EXTRA_MSG, "実行中: $count 件")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            } else { stopService(intent) }
        }
    }

    private fun saveToDownloads(f: String, t: String, c: String, ch: String) {
        var fn = t.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "").trim(); if (ch.isNotEmpty()) fn = "${ch}_${fn}"; fn += ".txt"
        var sn = f.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "").trim().ifEmpty { "NovelScraper_Others" }
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveWithMediaStore(sn, fn, c) else { val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NovelScraper/$sn"); if (!dir.exists()) dir.mkdirs(); File(dir, fn).writeText(c) } } catch (e: Exception) {}
    }

    private fun saveWithMediaStore(f: String, fn: String, c: String) {
        val path = Environment.DIRECTORY_DOWNLOADS + "/NovelScraper/" + f + "/"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fn); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain"); put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let { contentResolver.openOutputStream(it).use { os -> os?.write(c.toByteArray()) }; values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); contentResolver.update(it, values, null, null) }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
    }
}