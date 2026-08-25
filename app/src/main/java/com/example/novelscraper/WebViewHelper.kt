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

    val DARK_BG_COLOR = Color.parseColor("#121212")

    /**
     * 端末の正規デフォルトUA（端末の実際のOS/WebViewバージョンと100%一致）を取得する。
     * isDesktop = true の場合は、端末正規UAから "Mobile " を除去してPC版サイト用UAを生成する。
     */
    fun getUserAgent(context: Context, isDesktop: Boolean = false): String {
        val defaultUA = WebSettings.getDefaultUserAgent(context)
        return if (isDesktop) {
            defaultUA.replace("Mobile Safari", "Safari").replace("Mobile ", "")
        } else {
            defaultUA
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun applyStandardSettings(webView: WebView, blockImages: Boolean = false, isDesktop: Boolean = false) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            
            // PC版サイトモード時のみUAを切り替え、通常時は端末デフォルトUAをそのまま使用（偽装ゼロ）
            if (isDesktop) {
                userAgentString = getUserAgent(webView.context, isDesktop = true)
            }
            
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
            
            // Viewport & レンダリング設定（Chrome / Kiwi と同一の標準表示）
            loadWithOverviewMode = true
            useWideViewPort = true

            if (isDesktop) {
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            } else {
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
