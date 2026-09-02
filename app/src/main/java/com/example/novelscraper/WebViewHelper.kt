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
import androidx.webkit.WebViewFeature

object WebViewHelper {

    private const val TAG = "WebViewHelper"

    val DARK_BG_COLOR = Color.parseColor("#121212")

    /** 本物の Windows PC 版 Google Chrome の User-Agent */
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * User-Agent を取得する。
     * - isDesktop = true: サーバーにPC専用HTMLを返させるため、完全な Windows PC 版 Chrome UA を返す。
     * - isDesktop = false: 端末の正規デフォルトUA（端末のOS/WebViewバージョンと100%一致）を返す。
     */
    fun getUserAgent(context: Context, isDesktop: Boolean = false): String {
        return if (isDesktop) {
            DESKTOP_USER_AGENT
        } else {
            WebSettings.getDefaultUserAgent(context)
        }
    }

    /**
     * PCモード時にモバイル用 Viewport メタタグを無力化・完全除去するスクリプト。
     * PCブラウザには本来 viewport タグは存在しないため、タグを除去することで
     * Chromium の CSS エンジンが標準の PC デスクトップ幅（980px〜1280px）でメディアクエリを展開し、
     * 本物の PC 用レイアウト（ヘッダー・サイドバー・横長2カラム段組等）がレンダリングされる。
     */
    fun buildDesktopViewportJs(isDesktop: Boolean): String {
        return if (isDesktop) {
            """(function(){
                try {
                    var metas = document.querySelectorAll('meta[name="viewport"]');
                    for (var i = 0; i < metas.length; i++) {
                        metas[i].parentNode.removeChild(metas[i]);
                    }
                } catch(e) {}
            })()"""
        } else {
            """(function(){
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = 'width=device-width, initial-scale=1.0';
                        document.head.appendChild(meta);
                    }
                } catch(e) {}
            })()"""
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun applyStandardSettings(webView: WebView, blockImages: Boolean = false, isDesktop: Boolean = false) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            
            // PC版モード時は本物の Windows PC UA を適用
            userAgentString = getUserAgent(webView.context, isDesktop = isDesktop)
            
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
            
            // PCレイアウトを画面全体に自動フィット（OverviewMode）
            loadWithOverviewMode = true
            useWideViewPort = true

            // ピンチズーム（拡大縮小）を常時許可
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
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
     * Google 翻訳（LiveTranslate）の永続 Cookie (googtrans) を完全パージする。
     */
    fun clearGoogleTranslateCookies(url: String? = null) {
        try {
            val cookieManager = CookieManager.getInstance()
            if (!url.isNullOrEmpty()) {
                cookieManager.setCookie(url, "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;")
                cookieManager.setCookie(url, "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC;")
                cookieManager.setCookie(url, "googtrans=/auto/null; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;")
            }
            cookieManager.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear Google Translate cookies", e)
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
