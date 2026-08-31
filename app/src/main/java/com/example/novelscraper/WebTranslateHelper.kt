package com.example.novelscraper

/**
 * ブラウザ (WebView) 画面のワンタッチ全ページ翻訳 (Google Translate Element 動的注入) ヘルパー。
 *
 * 【設計方針】
 * - Cookie (googtrans) への書き込みを完全撤廃（スクレイピングタスクへのCookie汚染・ゾンビ化を100%防止）
 * - Google Translate の内部セレクトボックス (goog-te-combo) に対する Event('change', { bubbles: true }) 発火によるインプレース翻訳
 * - MutationObserver による最速ウィジェット生成検知 ＆ 最大8秒タイムアウト（超低速回線対応）
 * - SPA無限スクロールサイトでの後続チャプター自動追従翻訳 (Google翻訳自身のDOM追加を除外する自己ループ防止ガード付き)
 * - 邪魔な上部バナー帯 (.goog-te-banner-frame) をCSSで非表示化しクリーンなUIを維持
 * - 原文復帰時のスクロール位置保護 (最大2.0秒待機による長文・重いサイト対応)
 * - CSP等のブロック検知 (AndroidBridge.onTranslateError 連携)
 */
object WebTranslateHelper {

    /**
     * ページ全体を日本語に翻訳するための JavaScript コードを生成します。
     * Cookie は一切使用せず、DOMイベント駆動で日本語化をトリガーします。
     */
    fun buildTranslateScript(): String {
        return """
            (function() {
                try {
                    // 1. スタイルの注入 (Google翻訳の邪魔な上部バナーやツールチップを非表示化)
                    if (!document.getElementById('custom-google-translate-style')) {
                        var style = document.createElement('style');
                        style.id = 'custom-google-translate-style';
                        style.textContent = '.goog-te-banner-frame.skiptranslate, .goog-te-banner-frame, #goog-gt-tt, .goog-tooltip, .goog-tooltip:hover { display: none !important; visibility: hidden !important; } body { top: 0px !important; position: static !important; } .goog-text-highlight { background: none !important; box-shadow: none !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }

                    var isTriggerCoolingDown = false;

                    // 2. セレクトボックスをトリガーする補助関数
                    function triggerCombo() {
                        var combo = document.querySelector('select.goog-te-combo');
                        if (combo) {
                            if (combo.value !== 'ja') {
                                combo.value = 'ja';
                            }
                            isTriggerCoolingDown = true;
                            combo.dispatchEvent(new Event('change', { bubbles: true }));
                            setTimeout(function() { isTriggerCoolingDown = false; }, 1200);
                            startDynamicObserver();
                            return true;
                        }
                        return false;
                    }

                    // SPA無限スクロール等で後から追加されたDOMを自動で日本語化する監視機能
                    // （Google翻訳自身の<font>タグやgoog-クラスの追加による自己無限ループを完全防止）
                    function startDynamicObserver() {
                        if (window._googleTranslateDynamicObserver) return;
                        var debounceTimer = null;
                        var observer = new MutationObserver(function(mutations) {
                            if (isTriggerCoolingDown) return;
                            var hasRealNewContent = false;
                            for (var i = 0; i < mutations.length; i++) {
                                var nodes = mutations[i].addedNodes;
                                if (!nodes || nodes.length === 0) continue;
                                for (var j = 0; j < nodes.length; j++) {
                                    var node = nodes[j];
                                    if (node.nodeType === 1) { // Elementノード
                                        var tagName = node.tagName.toUpperCase();
                                        var className = (typeof node.className === 'string') ? node.className : '';
                                        // Google翻訳自身が生成したタグは無視
                                        if (tagName === 'FONT' || 
                                            className.indexOf('goog-') !== -1 || 
                                            node.id === 'google_translate_element' || 
                                            node.id === 'custom-google-translate-style' ||
                                            (node.closest && (node.closest('.skiptranslate') || node.closest('#google_translate_element')))) {
                                            continue;
                                        }
                                        hasRealNewContent = true;
                                        break;
                                    }
                                }
                                if (hasRealNewContent) break;
                            }
                            if (hasRealNewContent) {
                                clearTimeout(debounceTimer);
                                debounceTimer = setTimeout(function() {
                                    if (isTriggerCoolingDown) return;
                                    var combo = document.querySelector('select.goog-te-combo');
                                    if (combo) {
                                        isTriggerCoolingDown = true;
                                        combo.dispatchEvent(new Event('change', { bubbles: true }));
                                        setTimeout(function() { isTriggerCoolingDown = false; }, 1200);
                                    }
                                }, 400);
                            }
                        });
                        observer.observe(document.body, { childList: true, subtree: true });
                        window._googleTranslateDynamicObserver = observer;
                    }

                    // すでにウィジェットが存在する場合は即時発火
                    if (triggerCombo()) {
                        return 'TRIGGERED_EXISTING';
                    }

                    // 3. コンテナ div の生成
                    var container = document.getElementById('google_translate_element');
                    if (!container) {
                        container = document.createElement('div');
                        container.id = 'google_translate_element';
                        container.style.display = 'none';
                        (document.body || document.documentElement).appendChild(container);
                    }

                    // 4. 初期化関数の定義（ウィジェット生成完了時に自動で日本語化をトリガー）
                    window.googleTranslateElementInit = function() {
                        try {
                            new google.translate.TranslateElement({
                                pageLanguage: 'auto',
                                includedLanguages: 'ja',
                                layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                                autoDisplay: false
                            }, 'google_translate_element');

                            // MutationObserver でコンボボックスの生成をミリ秒単位で最速検知
                            var mo = new MutationObserver(function(mutations, obs) {
                                if (triggerCombo()) {
                                    obs.disconnect();
                                }
                            });
                            if (container) {
                                mo.observe(container, { childList: true, subtree: true });
                            }

                            // 最大8秒 (50ms x 160回) のフォールバック・ポーリング
                            var attempts = 0;
                            var interval = setInterval(function() {
                                attempts++;
                                if (triggerCombo() || attempts > 160) {
                                    clearInterval(interval);
                                    mo.disconnect();
                                }
                            }, 50);
                        } catch (e) {
                            console.error('TranslateElement init error', e);
                        }
                    };

                    // 5. element.js スクリプトの動的注入 (CSPブロック検知付き)
                    if (!document.getElementById('google-translate-script')) {
                        var script = document.createElement('script');
                        script.id = 'google-translate-script';
                        script.type = 'text/javascript';
                        script.src = 'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
                        script.onerror = function() {
                            console.error('Failed to load Google Translate script. CSP or network block.');
                            if (window.AndroidBridge) {
                                try { window.AndroidBridge.onTranslateError('CSP_BLOCKED'); } catch(e) {}
                            }
                        };
                        (document.head || document.documentElement).appendChild(script);
                    } else {
                        // スクリプトタグが既にあるがコンボボックス待ちの場合
                        window.googleTranslateElementInit();
                    }

                    return 'TRANSLATE_INITIALIZING';
                } catch (err) {
                    return 'ERROR: ' + err.message;
                }
            })();
        """.trimIndent()
    }

    /**
     * 翻訳を解除して元の言語 (原文) に復帰するための JavaScript コードを生成します。
     * スクロール位置 (scrollY) を退避してリロードします。
     */
    fun buildRestoreScript(): String {
        return """
            (function() {
                try {
                    // 1. 動的DOM監視の停止
                    if (window._googleTranslateDynamicObserver) {
                        try { window._googleTranslateDynamicObserver.disconnect(); } catch (e) {}
                        window._googleTranslateDynamicObserver = null;
                    }

                    // 2. スクロール位置の退避
                    try {
                        sessionStorage.setItem('restore_scroll_y', window.scrollY.toString());
                    } catch (e) {}

                    // 3. ページをリロードして確実に元の純粋なDOMに復元
                    window.location.reload();
                    return 'RESTORE_RELOADED';
                } catch (err) {
                    return 'ERROR: ' + err.message;
                }
            })();
        """.trimIndent()
    }

    /**
     * ページ読み込み完了時 (onPageFinished) に、退避されていたスクロール位置があれば復元するスクリプト。
     * 最大2.0秒 (50ms x 40回) かけて、遅延ロードされる長文ページでも確実に元の読書位置へ復元します。
     */
    fun buildScrollRestoreScript(): String {
        return """
            (function() {
                try {
                    var savedY = sessionStorage.getItem('restore_scroll_y');
                    if (savedY !== null) {
                        sessionStorage.removeItem('restore_scroll_y');
                        var y = parseInt(savedY, 10);
                        if (!isNaN(y) && y > 0) {
                            var attempts = 0;
                            var scrollInterval = setInterval(function() {
                                attempts++;
                                window.scrollTo(0, y);
                                if (Math.abs(window.scrollY - y) < 15 || attempts > 40) {
                                    clearInterval(scrollInterval);
                                }
                            }, 50);
                        }
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }
}
