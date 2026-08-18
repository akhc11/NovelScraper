package com.example.novelscraper

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewHelper {
    
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun applyStandardSettings(webView: WebView, blockImages: Boolean = false, isDesktop: Boolean = false) {
        // 白フラッシュ防止：WebViewのベース背景を完全透明にし、背後のアプリ黒レイヤーを透過
        webView.setBackgroundColor(Color.TRANSPARENT)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = if (isDesktop) DESKTOP_UA else MOBILE_UA
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            
            if (isDesktop) {
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
     * ダークモード用CSSスクリプトの生成
     */
    fun buildDarkModeJs(isDark: Boolean): String {
        return if (isDark) {
            """
            (function() {
                var id = 'novelscraper-dark-theme';
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
                        article, section, main, p, span, pre, blockquote, ul, ol, li, table, tr, td, th, h1, h2, h3, h4, h5, h6 {
                            color: #e0e0e0 !important;
                        }
                        div:not([class*="header"]):not([class*="footer"]):not([class*="nav"]):not([class*="bar"]):not([class*="menu"]):not([class*="fixed"]):not([class*="sticky"]) {
                            background-color: transparent !important;
                        }
                        header, footer, nav, aside, menu, dialog,
                        [role="banner"], [role="navigation"], [role="tablist"],
                        [class*="header"], [class*="footer"], [class*="nav"], 
                        [class*="bar"], [class*="toolbar"], [class*="menu"], 
                        [class*="fixed"], [class*="sticky"], [class*="dropdown"], [class*="popup"], [class*="modal"] {
                            background-color: #1e1e1e !important;
                            background: #1e1e1e !important;
                            color: #e0e0e0 !important;
                            border-color: #333333 !important;
                        }
                        *, *::before, *::after {
                            border-color: #333333 !important;
                            box-shadow: none !important;
                        }
                        hr, [role="separator"], div[class*="divider"], div[class*="separator"], div[class*="border"], div[class*="line"] {
                            background-color: #333333 !important;
                            border-color: #333333 !important;
                        }
                        a, a *, a:visited {
                            color: #8ab4f8 !important;
                        }
                        em, b, strong {
                            color: #ffffff !important;
                        }
                        [class*="gray"], [class*="Gray"], [class*="muted"], [class*="date"], 
                        [class*="time"], [class*="meta"], [class*="author"], [class*="sub"], 
                        [class*="info"], [class*="toc"], small, time {
                            color: #a8adb4 !important;
                        }
                        input, textarea, select {
                            background-color: #222222 !important;
                            color: #ffffff !important;
                            border: 1px solid #555555 !important;
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
                var el = document.getElementById('novelscraper-dark-theme');
                if (el) el.remove();
            })();
            """.trimIndent()
        }
    }
}
