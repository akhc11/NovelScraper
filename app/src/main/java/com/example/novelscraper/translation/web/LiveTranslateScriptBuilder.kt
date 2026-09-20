package com.example.novelscraper.translation.web

object LiveTranslateScriptBuilder {

    /**
     * Google 公式 Web 翻訳ランタイム (element.js) を活用したインプレース翻訳スクリプト。
     * - リロードなし (0回)
     * - 原文復元 (Undo)
     * - SPA (Reddit等) のページ内遷移対応
     * - 多重実行・ゾンビ再翻訳の遮断
     * - マウスホバー時のテキスト強調・バルーンツールチップの抑制
     * - element.js読込失敗時のロック固着防止 (onerror + watchdog + stale回復)
     * - .goog-te-combo出現ポーリングで誤SUCCESSを防止
     */
    fun buildToggleLiveTranslateScript(): String {
        return """
            (function() {
                try {
                    function __gtClearTimers() {
                        try {
                            if (window.__gtTimer) { clearTimeout(window.__gtTimer); window.__gtTimer = null; }
                            if (window.__gtWatchdog) { clearTimeout(window.__gtWatchdog); window.__gtWatchdog = null; }
                        } catch(e) {}
                    }
                    function __gtFail(msg) {
                        __gtClearTimers();
                        window.__liveTranslateActive = false;
                        window.__liveTranslateInProgress = false;
                        window.__liveTranslateStartTime = 0;
                        if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                            window.AndroidBridge.onLiveTranslateStatus('ERROR: ' + msg);
                        }
                    }
                    function __gtSuccess() {
                        __gtClearTimers();
                        window.__liveTranslateInProgress = false;
                        window.__liveTranslateStartTime = 0;
                        if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                            window.AndroidBridge.onLiveTranslateStatus('SUCCESS');
                        }
                    }
                    function __gtAlreadyJa() {
                        __gtClearTimers();
                        window.__liveTranslateActive = false;
                        window.__liveTranslateInProgress = false;
                        window.__liveTranslateStartTime = 0;
                        if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                            window.AndroidBridge.onLiveTranslateStatus('ALREADY_JA');
                        }
                    }
                    // URL変化検知を最初に (SPA遷移中の旧タイマーは破棄)
                    var currentHref = window.location.href;
                    if (window.__liveTranslateLastUrl !== currentHref) {
                        __gtClearTimers();
                        window.__originalBodyHtml = null;
                        window.__liveTranslateActive = false;
                        window.__liveTranslateInProgress = false;
                        window.__liveTranslateStartTime = 0;
                        window.__liveTranslateLastUrl = currentHref;
                    }
                    // 多重実行ロック (stale回復付き: 15秒超は強制リセット)
                    // 翻訳中のUndo/キャンセル要求は通過させ、重複TranslateのみBUSYで弾く。
                    if (window.__liveTranslateInProgress) {
                        var __gtStart = window.__liveTranslateStartTime || 0;
                        if (Date.now() - __gtStart < 15000) {
                            if (window.__liveTranslateActive) {
                                __gtClearTimers();
                                window.__liveTranslateInProgress = false;
                                window.__liveTranslateStartTime = 0;
                            } else {
                                if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                                    window.AndroidBridge.onLiveTranslateStatus('BUSY');
                                }
                                return;
                            }
                        } else {
                            __gtClearTimers();
                            window.__liveTranslateInProgress = false;
                            window.__liveTranslateStartTime = 0;
                        }
                    }
                    window.__liveTranslateInProgress = true;
                    window.__liveTranslateStartTime = Date.now();
                    // 世代カウンタ: SPA遷移・再トグルで旧世代の遅延コールバックを無効化する。
                    // 技術的根拠1行：タイマー/onerrorは発火時にwindow参照を引くため世代が混ざると二重SUCCESS/二重Toastになる。
                    window.__gtGen = (window.__gtGen || 0) + 1;
                    var __gtMyGen = window.__gtGen;

                    // ==========================================
                    // 1. 原文への復元 (Undo)
                    // ==========================================
                    if (window.__liveTranslateActive) {
                        try {
                            // タイマーの即時停止
                            __gtClearTimers();
                            window.__liveTranslateStartTime = 0;

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
                                try { window.scrollTo(window.__liveTranslateScrollX || 0, window.__liveTranslateScrollY || 0); } catch(e) {}
                            }

                        } catch(ex) {
                            console.error("[LiveTranslate] Undo error: " + ex);
                        }

                        window.__liveTranslateActive = false;
                        window.__liveTranslateInProgress = false;
                        window.__liveTranslateStartTime = 0;
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

                    // 事前検査1: body未構築 (早期注入・特殊スキーム) は即時失敗させる
                    if (!document.body) {
                        __gtFail('NO_BODY: ページ本文が未構築 (リロード後に再試行)');
                        return;
                    }
                    // 事前検査2: 既に日本語の頁は翻訳不要 (html lang+かな検出)
                    try {
                        var __gtLang = ((document.documentElement && document.documentElement.lang) || '').toLowerCase();
                        var __gtSample = (document.body.innerText || '').substring(0, 2000);
                        var __gtKana = (__gtSample.match(/[\u3040-\u309f\u30a0-\u30ff]/g) || []).length;
                        if (__gtLang.indexOf('ja') === 0 || __gtKana >= 10) {
                            __gtAlreadyJa();
                            return;
                        }
                    } catch(e) {}

                    // スクロール位置を保存 (UndoのinnerHTML復元で先頭に飛ぶのを防止)
                    try {
                        window.__liveTranslateScrollX = window.scrollX || 0;
                        window.__liveTranslateScrollY = window.scrollY || 0;
                    } catch(e) {}

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

                    // .goog-te-combo出現ポーリング (固定250msの誤SUCCESSを防止)
                    // ローカル別名で世代を固定し、旧世代タイマーの混入を防ぐ。
                    var __gtPoll = window.__gtAttemptCombo = function(left) {
                        if (__gtMyGen !== window.__gtGen) return;
                        if (!window.__liveTranslateActive) return;
                        var combo = document.querySelector('.goog-te-combo');
                        if (combo) {
                            try {
                                combo.value = 'ja';
                                combo.dispatchEvent(new Event('change', { bubbles: true }));
                                try {
                                    var evt = document.createEvent('HTMLEvents');
                                    evt.initEvent('change', true, true);
                                    combo.dispatchEvent(evt);
                                } catch(e2) {}
                            } catch(e) {
                                __gtFail('COMBO_DISPATCH_FAILED: ' + e.message);
                                return;
                            }
                            __gtSuccess();
                            return;
                        }
                        if (left <= 0) {
                            __gtFail('COMBO_NOT_FOUND: 翻訳UIの初期化に失敗 (CSPブロック/通信障害の可能性)');
                            return;
                        }
                        window.__gtTimer = setTimeout(function() {
                            __gtPoll(left - 1);
                        }, 500);
                    };

                    // 初期化関数 (旧世代の遅延ロード到達は無視)
                    window.googleTranslateElementInit = function() {
                        if (__gtMyGen !== window.__gtGen) return;
                        try {
                            new google.translate.TranslateElement({
                                pageLanguage: 'auto',
                                includedLanguages: 'ja',
                                layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                                autoDisplay: false,
                                multilanguagePage: true
                            }, 'google_translate_element');

                            // combo待機フェーズ用にwatchdogを延長 (読込12秒+待機12秒)
                            try { if (window.__gtWatchdog) { clearTimeout(window.__gtWatchdog); window.__gtWatchdog = null; } } catch(e) {}
                            window.__liveTranslateStartTime = Date.now();
                            window.__gtWatchdog = setTimeout(function() {
                                if (window.__liveTranslateInProgress && window.__liveTranslateActive) {
                                    __gtFail('LOAD_TIMEOUT: 12秒待機も初期化せず (CSPブロック/通信障害の可能性)');
                                }
                            }, 12000);
                            __gtPoll(20);

                        } catch(e) {
                            __gtFail('INIT_FAILED: ' + e.message);
                        }
                    };

                    // スクリプトの読み込み (googleapis優先・google.comフォールバック・watchdog付き)
                    var __gtSources = [
                        'https://translate.googleapis.com/translate_a/element.js?cb=googleTranslateElementInit',
                        'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit'
                    ];
                    var __gtLoader = window.__gtLoadIdx = function(i) {
                        if (__gtMyGen !== window.__gtGen) return;
                        if (!window.__liveTranslateActive) return;
                        if (i >= __gtSources.length) {
                            __gtFail('LOAD_FAILED: element.jsを読込不可 (CSPブロック/オフライン/廃止の可能性)');
                            return;
                        }
                        var old = document.getElementById('__gt_script');
                        if (old) { try { old.remove(); } catch(e) {} }
                        var script = document.createElement('script');
                        script.id = '__gt_script';
                        script.type = 'text/javascript';
                        script.onerror = function() {
                            __gtLoader(i + 1);
                        };
                        script.src = __gtSources[i];
                        (document.head || document.documentElement).appendChild(script);
                    };
                    // 既にランタイムが居れば再利用し、無ければ読み込む
                    if (window.google && window.google.translate && window.google.translate.TranslateElement) {
                        try {
                            window.googleTranslateElementInit();
                        } catch(e) {
                            __gtLoader(0);
                        }
                    } else {
                        __gtLoader(0);
                    }
                    // element.jsのコールバックが来ない場合の最終安全網
                    window.__gtWatchdog = setTimeout(function() {
                        if (window.__liveTranslateInProgress && window.__liveTranslateActive) {
                            __gtFail('LOAD_TIMEOUT: 12秒待機も初期化せず (CSPブロック/通信障害の可能性)');
                        }
                    }, 12000);

                } catch(e) {
                    __gtFail(e.message);
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
                    try {
                        if (window.__gtTimer) { clearTimeout(window.__gtTimer); window.__gtTimer = null; }
                        if (window.__gtWatchdog) { clearTimeout(window.__gtWatchdog); window.__gtWatchdog = null; }
                    } catch(e) {}
                    window.__gtAttemptCombo = null;
                    window.__gtLoadIdx = null;
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
                    window.__liveTranslateStartTime = 0;

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
                        try { window.scrollTo(window.__liveTranslateScrollX || 0, window.__liveTranslateScrollY || 0); } catch(e) {}
                    }

                    if (window.AndroidBridge && window.AndroidBridge.onLiveTranslateStatus) {
                        window.AndroidBridge.onLiveTranslateStatus('RESTORED');
                    }
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
