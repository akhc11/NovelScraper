package com.example.novelscraper.translation.web

object LiveTranslateScriptBuilder {

    /**
     * Google 公式 Web 翻訳ランタイム (element.js) を活用した完全無欠のインプレース翻訳スクリプト。
     * - リロードなし (0回)
     * - 0.5秒前後の爆速翻訳
     * - 完全な原文復元 (Undo)
     * - SPA (Reddit等) のページ内遷移対応
     * - 多重実行・ゾンビ再翻訳の完全遮断
     * - マウスホバー時のテキスト強調・バルーンツールチップの完全抹殺
     */
    fun buildToggleLiveTranslateScript(): String {
        return """
            (function() {
                try {
                    // 多重実行ロック
                    if (window.__liveTranslateInProgress) {
                        return;
                    }
                    window.__liveTranslateInProgress = true;

                    // SPA等でURLが変化した場合はバックアップを更新
                    var currentHref = window.location.href;
                    if (window.__liveTranslateLastUrl !== currentHref) {
                        window.__originalBodyHtml = null;
                        window.__liveTranslateActive = false;
                        window.__liveTranslateLastUrl = currentHref;
                    }

                    // ==========================================
                    // 1. 原文への復元 (Undo)
                    // ==========================================
                    if (window.__liveTranslateActive) {
                        try {
                            // タイマーの即時停止
                            if (window.__gtTimer) {
                                clearTimeout(window.__gtTimer);
                                window.__gtTimer = null;
                            }

                            // Cookie の完全無効化
                            function clearGoogTrans() {
                                var domains = ['', '.' + document.domain, document.domain, location.hostname, '.' + location.hostname];
                                var paths = ['/', '', location.pathname];
                                for (var d = 0; d < domains.length; d++) {
                                    for (var p = 0; p < paths.length; p++) {
                                        var cookieStr = "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC;";
                                        if (domains[d]) cookieStr += " domain=" + domains[d] + ";";
                                        if (paths[p]) cookieStr += " path=" + paths[p] + ";";
                                        document.cookie = cookieStr;
                                    }
                                }
                                document.cookie = "googtrans=/auto/null; path=/;";
                            }
                            clearGoogTrans();

                            // Google Translate 要素・iframe・スタイルの完全クリーンアップ
                            var s = document.getElementById('__gt_script');
                            if (s) s.remove();
                            var gtElem = document.getElementById('google_translate_element');
                            if (gtElem) gtElem.remove();
                            var gtStyle = document.getElementById('__gt_custom_style');
                            if (gtStyle) gtStyle.remove();
                            var frames = document.querySelectorAll('.goog-te-banner-frame, iframe[id*=":1."], .skiptranslate, #goog-gt-tt, .goog-te-balloon-frame');
                            for (var i = 0; i < frames.length; i++) {
                                try { frames[i].remove(); } catch(e) {}
                            }

                            // Google ランタイム変数の破棄 (ゾンビ自動監視の停止)
                            window.google = null;
                            window.googleTranslateElementInit = null;

                            // 完全な原文 HTML の復元
                            if (typeof window.__originalBodyHtml === 'string' && window.__originalBodyHtml.length > 0) {
                                document.body.innerHTML = window.__originalBodyHtml;
                            }

                        } catch(ex) {
                            console.error("[LiveTranslate] Undo error: " + ex);
                        }

                        window.__liveTranslateActive = false;
                        window.__liveTranslateInProgress = false;
                        if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                            window.AndroidBridge.onLiveTranslateStatus('RESTORED');
                        }
                        return;
                    }

                    // ==========================================
                    // 2. 日本語への翻訳 (Translate)
                    // ==========================================
                    if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                        window.AndroidBridge.onLiveTranslateStatus('START');
                    }

                    // 原文 HTML をメモリに完全バックアップ (初回のみ)
                    if (typeof window.__originalBodyHtml !== 'string' || window.__originalBodyHtml.length === 0) {
                        window.__originalBodyHtml = document.body.innerHTML;
                    }

                    // Cookie の設定
                    document.cookie = "googtrans=/auto/ja; path=/;";
                    document.cookie = "googtrans=/auto/ja; domain=" + document.domain + "; path=/;";
                    window.__liveTranslateActive = true;

                    // 余計な Google バナー・ハイライト・ツールチップを隠す CSS & インスペクターUIの保護
                    if (!document.getElementById('__gt_custom_style')) {
                        var style = document.createElement('style');
                        style.id = '__gt_custom_style';
                        style.innerHTML = `
                            .goog-te-banner-frame { visibility: hidden !important; position: absolute !important; top: -9999px !important; left: -9999px !important; width: 0 !important; height: 0 !important; }
                            body { top: 0px !important; position: static !important; }
                            .skiptranslate { display: none !important; }
                            #google_translate_element { display: none !important; }
                            #goog-gt-tt, .goog-te-balloon-frame, .goog-tooltip, .goog-tooltip:hover { display: none !important; visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }
                            .goog-text-highlight, font.goog-text-highlight, span.goog-text-highlight { background-color: transparent !important; background: none !important; border: none !important; box-shadow: none !important; text-decoration: none !important; }
                            .__novel_notranslate, #__novel_popup, #__novel_hint, .__novel_exclude { -webkit-user-select: auto; }
                        `;
                        (document.head || document.documentElement).appendChild(style);
                    }

                    // マウント要素
                    var container = document.getElementById('google_translate_element');
                    if (!container) {
                        container = document.createElement('div');
                        container.id = 'google_translate_element';
                        container.style.display = 'none';
                        (document.body || document.documentElement).appendChild(container);
                    }

                    // 初期化関数
                    window.googleTranslateElementInit = function() {
                        try {
                            new google.translate.TranslateElement({
                                pageLanguage: 'auto',
                                includedLanguages: 'ja',
                                layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                                autoDisplay: false,
                                multilanguagePage: true
                            }, 'google_translate_element');

                            // 言語適用タイマー
                            window.__gtTimer = setTimeout(function() {
                                if (!window.__liveTranslateActive) return;
                                var combo = document.querySelector('.goog-te-combo');
                                if (combo) {
                                    combo.value = 'ja';
                                    combo.dispatchEvent(new Event('change'));
                                }
                                window.__liveTranslateInProgress = false;
                                if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                                    window.AndroidBridge.onLiveTranslateStatus('SUCCESS');
                                }
                            }, 250);

                        } catch(e) {
                            window.__liveTranslateInProgress = false;
                            if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                                window.AndroidBridge.onLiveTranslateStatus('ERROR: ' + e.message);
                            }
                        }
                    };

                    // スクリプトの読み込み
                    var script = document.createElement('script');
                    script.id = '__gt_script';
                    script.type = 'text/javascript';
                    script.src = 'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
                    (document.head || document.documentElement).appendChild(script);

                } catch(e) {
                    window.__liveTranslateInProgress = false;
                    if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                        window.AndroidBridge.onLiveTranslateStatus('ERROR: ' + e.message);
                    }
                }
            })();
        """.trimIndent()
    }

    /**
     * 強制的に原文へ復元するスクリプト
     */
    fun buildRestoreScript(): String {
        return """
            (function() {
                try {
                    if (window.__gtTimer) {
                        clearTimeout(window.__gtTimer);
                        window.__gtTimer = null;
                    }
                    function clearGoogTrans() {
                        var domains = ['', '.' + document.domain, document.domain, location.hostname, '.' + location.hostname];
                        var paths = ['/', '', location.pathname];
                        for (var d = 0; d < domains.length; d++) {
                            for (var p = 0; p < paths.length; p++) {
                                var cookieStr = "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC;";
                                if (domains[d]) cookieStr += " domain=" + domains[d] + ";";
                                if (paths[p]) cookieStr += " path=" + paths[p] + ";";
                                document.cookie = cookieStr;
                            }
                        }
                        document.cookie = "googtrans=/auto/null; path=/;";
                    }
                    clearGoogTrans();
                    window.__liveTranslateActive = false;
                    window.__liveTranslateInProgress = false;

                    var s = document.getElementById('__gt_script');
                    if (s) s.remove();
                    var gtElem = document.getElementById('google_translate_element');
                    if (gtElem) gtElem.remove();
                    var gtStyle = document.getElementById('__gt_custom_style');
                    if (gtStyle) gtStyle.remove();
                    var frames = document.querySelectorAll('.goog-te-banner-frame, iframe[id*=":1."], .skiptranslate, #goog-gt-tt, .goog-te-balloon-frame');
                    for (var i = 0; i < frames.length; i++) {
                        try { frames[i].remove(); } catch(e) {}
                    }
                    window.google = null;
                    window.googleTranslateElementInit = null;

                    if (typeof window.__originalBodyHtml === 'string' && window.__originalBodyHtml.length > 0) {
                        document.body.innerHTML = window.__originalBodyHtml;
                    }

                    if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                        window.AndroidBridge.onLiveTranslateStatus('RESTORED');
                    }
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
