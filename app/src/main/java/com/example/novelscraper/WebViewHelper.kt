package com.example.novelscraper

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewHelper {
    
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val DARK_BG_COLOR = 0xFF121212.toInt()
    private const val LIGHT_BG_COLOR = 0xFFFFFFFF.toInt()

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
     * ハードウェア背景色の切り替え（白フラッシュ・チラつきを0ミリ秒で根絶）。
     */
    fun applyDarkMode(webView: WebView, isDarkMode: Boolean) {
        webView.setBackgroundColor(if (isDarkMode) DARK_BG_COLOR else LIGHT_BG_COLOR)
        webView.evaluateJavascript(buildDarkModeJs(isDarkMode), null)
    }

    /**
     * 洗練された単一のDynamic Semantic Dark Theme スクリプト。
     * - カクヨム等の上下バーを不透明サーフェス（#1e1e1e）化して文字透けを完全防止
     * - Google検索のタブ下白線・ボーダーをダーク化（#333333）
     * - テキストコントラストを最高峰（#e0e0e0 / #a8adb4 / #8ab4f8）に維持
     * - 白フラッシュゼロで瞬時に切り替え
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
                        /* 1. ページ全体のベース背景と文字色 */
                        html, body, #main, #cnt, #rcnt, .srp, .g, #search, [role="main"], #center_col, #rso {
                            background-color: #121212 !important;
                            background: #121212 !important;
                            color: #e0e0e0 !important;
                        }
                        /* 2. 本文・記事コンテンツ要素（文字オフホワイト） */
                        article, section, main, p, span, pre, blockquote, ul, ol, li, table, tr, td, th, h1, h2, h3, h4, h5, h6 {
                            color: #e0e0e0 !important;
                        }
                        div:not([class*="header"]):not([class*="footer"]):not([class*="nav"]):not([class*="bar"]):not([class*="menu"]):not([class*="fixed"]):not([class*="sticky"]) {
                            background-color: transparent !important;
                        }
                        /* 3. 【文字透け防止】カクヨム・なろう・Webサイトの上下固定バー・ヘッダー・フッター・ナビ（不透明サーフェス化） */
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
                        /* 4. 【白線・白枠除去】境界線・区切り線・Google検索タブ下横線をシックなダークグレーに統一 */
                        *, *::before, *::after {
                            border-color: #333333 !important;
                            box-shadow: none !important;
                        }
                        hr, [role="separator"], div[class*="divider"], div[class*="separator"], div[class*="border"], div[class*="line"] {
                            background-color: #333333 !important;
                            border-color: #333333 !important;
                        }
                        /* 5. リンクカラー（明るく見やすいライトブルー） */
                        a, a *, a:visited {
                            color: #8ab4f8 !important;
                        }
                        /* 6. 強調テキスト */
                        em, b, strong {
                            color: #ffffff !important;
                        }
                        /* 7. 小説サイト・ブログのサブテキスト（グレー文字・日付・作者名等）の同化防止 */
                        [class*="gray"], [class*="Gray"], [class*="muted"], [class*="date"], 
                        [class*="time"], [class*="meta"], [class*="author"], [class*="sub"], 
                        [class*="info"], [class*="toc"], small, time {
                            color: #a8adb4 !important;
                        }
                        /* 8. フォーム入力欄 */
                        input, textarea, select {
                            background-color: #222222 !important;
                            color: #ffffff !important;
                            border: 1px solid #555555 !important;
                        }
                        /* 9. メディア */
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
