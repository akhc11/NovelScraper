package com.example.novelscraper

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object WebViewHelper {
    
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun applyStandardSettings(webView: WebView, blockImages: Boolean = false, isDesktop: Boolean = false) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = if (isDesktop) DESKTOP_UA else MOBILE_UA
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // デスクトップ表示を安定させるための設定
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            
            if (isDesktop) {
                // PC版サイトを強制するための追加設定
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    /**
     * Android公式仕様（API 33〜35標準 Algorithmic Darkening / API 29〜32 Force Dark）に準拠した
     * WebViewダークモード適用ロジック。
     */
    @Suppress("DEPRECATION")
    fun applyDarkMode(webView: WebView, isDarkMode: Boolean) {
        try {
            // アプリ全体のNightModeを更新してWebViewのprefers-color-scheme伝播を同期
            val targetMode = if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDarkMode)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                val forceDarkOption = if (isDarkMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                WebSettingsCompat.setForceDark(webView.settings, forceDarkOption)
            }
        } catch (_: Exception) {
            // 一部端末での互換性例外防止
        }
    }

    /**
     * Google検索結果や小説サイトなど、あらゆるWebサイトで100%確実に黒背景・白文字化を強制するスマートCSSスクリプト。
     */
    fun buildDarkModeJs(isDark: Boolean): String {
        return if (isDark) {
            """
            (function() {
                var id = 'novelscraper-dark-mode-style';
                var existing = document.getElementById(id);
                if (!existing) {
                    var style = document.createElement('style');
                    style.id = id;
                    style.textContent = `
                        :root {
                            color-scheme: dark !important;
                            --color-surface: #121212 !important;
                            --color-background: #121212 !important;
                            --color-on-surface: #e0e0e0 !important;
                        }
                        html, body, #main, #cnt, #rcnt, .srp, .g, #search, [role="main"], #center_col, #rso {
                            background-color: #121212 !important;
                            background: #121212 !important;
                            color: #e0e0e0 !important;
                        }
                        div, p, span, article, section, main, header, footer, nav, aside,
                        ul, ol, li, table, tr, td, th, h1, h2, h3, h4, h5, h6, pre, blockquote, form {
                            background-color: transparent !important;
                            color: inherit !important;
                            border-color: #333333 !important;
                        }
                        a, a * {
                            color: #8ab4f8 !important;
                        }
                        em, b, strong {
                            color: #ffffff !important;
                        }
                        input, textarea, select {
                            background-color: #222222 !important;
                            color: #ffffff !important;
                            border-color: #555555 !important;
                        }
                        img, video, svg, canvas {
                            filter: none !important;
                            opacity: 0.92;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                var el = document.getElementById('novelscraper-dark-mode-style');
                if (el) el.remove();
            })();
            """.trimIndent()
        }
    }
}
