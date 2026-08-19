package com.example.novelscraper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object WebViewHelper {

    private const val TAG = "WebViewHelper"

    // Chrome / Kiwi と完全に一致する正規 Android Chrome モバイル UA
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    // Android Chrome の PC版サイトモード正規 UA（Mobile のみ除去）
    const val DESKTOP_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val DARK_BG_COLOR = Color.parseColor("#121212")

    /**
     * 安全で堅牢なステルス先行注入スクリプト。
     * WebサイトのReactやフロントエンドJS（DeepL等）を破壊せず、
     * navigator.webdriver の偽装と window.chrome の補完、
     * およびWebView特有のCSSコンテナ高さ折りたたみバグ（DeepLの入力欄潰れ）を安全に補正する。
     */
    const val STEALTH_SCRIPT = """
    (function() {
        try {
            // 1. navigator.webdriver の偽装（Bot判定フラグを完全解除）
            if ('webdriver' in navigator) {
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined,
                    configurable: true
                });
            }

            // 2. window.chrome オブジェクトの安全なモック
            if (!window.chrome) {
                window.chrome = {
                    app: { isInstalled: false },
                    runtime: {},
                    loadTimes: function() {},
                    csi: function() {}
                };
            }

            // 3. DeepL等のコンテナクエリ/高さ折りたたみバグの安全な補正（Kiwi/Chromeと同等のレイアウト保証）
            var applyStyle = function() {
                if (document.getElementById('novelscraper-responsive-fix')) return;
                var style = document.createElement('style');
                style.id = 'novelscraper-responsive-fix';
                style.textContent = `
                    main[data-layout-id="mainSection"],
                    [data-layout-id="mainSectionWrapper"],
                    div[id^="headlessui-tabs-panel"] {
                        min-height: 480px !important;
                        height: auto !important;
                    }
                `;
                if (document.head) {
                    document.head.appendChild(style);
                } else if (document.documentElement) {
                    document.documentElement.appendChild(style);
                }
            };
            applyStyle();
            document.addEventListener('DOMContentLoaded', applyStyle);
        } catch (e) {}
    })();
    """

    @SuppressLint("SetJavaScriptEnabled")
    fun applyStandardSettings(webView: WebView, blockImages: Boolean = false, isDesktop: Boolean = false) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = if (isDesktop) DESKTOP_UA else MOBILE_UA
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
            
            // Viewport & レンダリング設定（Chrome / Kiwi と同一のモバイル自然表示）
            if (isDesktop) {
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            } else {
                // モバイル表示時は WideViewPort を無効化し、Webサイトのレスポンシブ meta viewport を忠実に再現
                loadWithOverviewMode = false
                useWideViewPort = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // 最速タイミング（Document Start）での安全なステルススクリプト先行注入
        injectDocumentStartStealthScript(webView)
    }

    /**
     * Document Start 時点（HTMLパース前）にステルススクリプトを先行注入する。
     */
    fun injectDocumentStartStealthScript(webView: WebView) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, STEALTH_SCRIPT, setOf("*"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject document start script", e)
        }
    }

    /**
     * バックグラウンド（裏）WebView に対して仮想解像度をレイアウトする。
     */
    fun applyVirtualSize(webView: WebView, width: Int = 1080, height: Int = 1920) {
        try {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply virtual size to WebView", e)
        }
    }

    /**
     * Cookie を即時永続化ストレージにフラッシュする。
     */
    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flush cookies", e)
        }
    }

    /**
     * WebView にダークモード設定を適用する
     */
    fun applyDarkMode(webView: WebView, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, enabled)
        }
        webView.setBackgroundColor(if (enabled) DARK_BG_COLOR else Color.WHITE)
    }
}
