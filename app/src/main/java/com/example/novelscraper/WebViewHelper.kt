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
     * Google/DarkReader標準のプロ仕様ダークモードCSS。
     * - カクヨム等の上下バーを不透明サーフェス（#1e1e1e）化して文字透けを解消
     * - Google検索のタブ下白線をダーク境界線（#333333）化
     * - テキストコントラストを最高峰に維持
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
                        /* 1. ページ全体のベース背景と文字色 */
                        html, body, #main, #cnt, #rcnt, .srp, .g, #search, [role="main"], #center_col, #rso {
                            background-color: #121212 !important;
                            background: #121212 !important;
                            color: #e0e0e0 !important;
                        }
                        /* 2. 本文・記事コンテンツ要素（背景透明・文字オフホワイト） */
                        article, section, main, p, span, pre, blockquote, ul, ol, li, table, tr, td, th, h1, h2, h3, h4, h5, h6 {
                            color: #e0e0e0 !important;
                        }
                        div:not([class*="header"]):not([class*="footer"]):not([class*="nav"]):not([class*="bar"]):not([class*="menu"]):not([class*="fixed"]):not([class*="sticky"]) {
                            background-color: transparent !important;
                        }
                        /* 3. 【文字透け防止】カクヨム・なろう・Webサイトの上下固定バー・ヘッダー・フッター・ナビ・メニュー（不透明サーフェス化） */
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
                        /* 4. 【白線・白枠除去】すべての境界線・区切り線・Google検索タブ下横線をシックなダークグレーに統一 */
                        *, *::before, *::after {
                            border-color: #333333 !important;
                            box-shadow: none !important;
                        }
                        hr, [role="separator"], div[class*="divider"], div[class*="separator"], div[class*="border"], div[class*="line"] {
                            background-color: #333333 !important;
                            border-color: #333333 !important;
                        }
                        /* 5. リンクカラー（Google/Chrome標準の明るく見やすいライトブルー） */
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
                var el = document.getElementById('novelscraper-dark-mode-style');
                if (el) el.remove();
            })();
            """.trimIndent()
        }
    }
}
