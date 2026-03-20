package com.example.novelscraper

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewHelper {
    
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun applyStandardSettings(webView: WebView, blockImages: Boolean = false) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DEFAULT_USER_AGENT
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // パフォーマンス向上のための追加設定
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }
}
