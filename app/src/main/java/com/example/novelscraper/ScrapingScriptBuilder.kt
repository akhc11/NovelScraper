package com.example.novelscraper

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Base64

object ScrapingScriptBuilder {

    fun buildScrapingScript(config: ScraperConfig, useImages: Boolean): String {
        val configJson = Json.encodeToString(config)
        val configBase64 = Base64.getEncoder().encodeToString(configJson.toByteArray(Charsets.UTF_8))
        return """
            (function() {
                try {
                    if (document.title.includes("Just a moment") || document.body.innerText.includes("Verify you are human")) return "CF_DETECTED";
                    var config = JSON.parse(decodeURIComponent(escape(atob('$configBase64'))));
                    var result = { title: "", content: "", nextUrl: "", chapter: "", folderName: "" };
                    function clean(t) { return t ? t.trim() : ""; }

                    var meta = {};
                    try {
                        var scripts = document.querySelectorAll('script[type="application/ld+json"]');
                        for (var i = 0; i < scripts.length; i++) {
                            var p = JSON.parse(scripts[i].innerText);
                            if (Array.isArray(p)) p = p[0];
                            if (p.headline || (p.isPartOf && p.isPartOf.name)) { meta = p; break; }
                        }
                    } catch(e){}

                    var f = "";
                    if (config.folder && config.folder.startsWith('@')) {
                        f = config.folder.substring(1);
                    } else if (config.folder) {
                        var el = document.querySelector(config.folder);
                        if (el) f = el.innerText;
                    }
                    if (!f && config.folderLink) {
                        try {
                            var fel = document.querySelector(config.folderLink);
                            if (fel) {
                                var a = fel.tagName === 'A' ? fel : fel.closest('a');
                                if (a) {
                                    var href = a.href || a.getAttribute('href');
                                    if (href) f = '(別URL先で取得: ' + (new URL(href, location.href).href) + ')';
                                }
                            }
                        } catch(e){}
                    }
                    if (!f) {
                        var el = document.querySelector('.series-title, .novel_title, .novel-title, .p-novel__title');
                        if (el) f = el.innerText;
                    }
                    if (!f && meta.isPartOf) f = meta.isPartOf.name || meta.isPartOf;
                    result.folderName = clean(f);

                    var t = config.title ? document.querySelector(config.title) : null;
                    if (!t) t = document.querySelector('.novel_subtitle, .ep-title, .episode-title, .chapter-title, h1, h2');
                    result.title = t ? clean(t.innerText) : "";
                    if (!result.title) result.title = meta.headline || clean(document.title);

                    var c = "";
                    if (config.chapter && !config.chapter.startsWith("@")) {
                        var text = (config.chapter === "@URL") ? location.href : (document.querySelector(config.chapter)?.innerText || "");
                        if (text) {
                            if (config.chapterRegex) {
                                try { var m = text.match(new RegExp(config.chapterRegex)); if(m) c = m[1] || m[0]; } catch(e){}
                            }
                            if (!c) { var m = text.match(/(\d+)/); if(m) c = m[1]; }
                        }
                    }
                    result.chapter = c;

                    // --- プロ仕様：小説整形エンジン（字下げ正規化） ---
                    var b = config.body ? document.querySelector(config.body) : null;
                    if (!b) {
                        var cand = Array.from(document.querySelectorAll('div, article, section, main')).map(el => ({ el: el, s: el.innerText.length + (el.querySelectorAll('p').length * 40) })).sort((a,b) => b.s - a.s);
                        if (cand[0] && cand[0].s > 100) b = cand[0].el;
                    }
                    if (b) {
                        var clone = b.cloneNode(true);
                        clone.querySelectorAll('script, style, noscript, iframe, template, .ad, .ads, [class*="advertisement"]').forEach(n => n.remove());
                        if (!${useImages}) clone.querySelectorAll('img, picture, svg').forEach(n => n.remove());
                        
                        // 1. 改行の確保
                        clone.querySelectorAll('br').forEach(br => { br.after(document.createTextNode('\n')); br.remove(); });
                        clone.querySelectorAll('p, div, h1, h2, h3, h4, h5, h6, li, dt, dd').forEach(el => {
                            if (el.innerText.trim().length > 0) el.after(document.createTextNode('\n'));
                        });

                        // 2. テキストの分解とプロ仕様の整形
                        var lines = clone.innerText.split('\n');
                        var formattedLines = lines.map(function(line) {
                            // 行頭・行末の不安定な空白をすべて削除（リセット）
                            var l = line.replace(/^[　 \t\s]+|[　 \t\s]+$/g, '');
                            if (l.length === 0) return "";

                            // 小説の作法ルール適用
                            // 記号（「『（【［など）で始まる場合は字下げしない、それ以外は全角スペースを1つ付与
                            if (/^[\u300c\u300e\uff08\u28\u3010\u3014\uff3b]/.test(l)) {
                                return l;
                            } else {
                                return "\u3000" + l; // 全角スペース1文字を強制
                            }
                        });

                        // 3. 結合と仕上げ
                        result.content = formattedLines.join('\n')
                            .replace(/\n{3,}/g, '\n\n') // 連続空行を整理
                            .trim();
                    }

                    // プロパティチェーンのみ許可する安全な解決関数（eval禁止）
                    function safeResolve(expr) {
                        if (!expr || !/^[a-zA-Z_$][\w$.]*$/.test(expr)) return "";
                        return expr.split('.').reduce(function(obj, key) { return obj ? obj[key] : undefined; }, window) || "";
                    }
                    var nUrl = "";
                    if (config.next && config.next.startsWith('js:')) {
                        try { nUrl = safeResolve(config.next.substring(3)); } catch(e){}
                    }
                    if (!nUrl) {
                        var nEl = config.next ? document.querySelector(config.next) : document.querySelector('a[rel="next"]');
                        if (!nEl) {
                            var links = Array.from(document.querySelectorAll('a'));
                            var regex = /^(?:\u6b21|Next|\u7d1a\u304f|>>|\u300b|\u6b21\u3078|\u4e0b\u4e00\u7ae0|\u4e0b\u4e00\u9875)/i;
                            nEl = links.find(a => a.innerText.trim().length < 15 && regex.test(a.innerText.trim()));
                        }
                        nUrl = nEl ? nEl.href : "";
                    }
                    if (nUrl && !nUrl.startsWith('http')) { try { nUrl = new URL(nUrl, location.href).href; } catch(e){} }
                    result.nextUrl = nUrl;

                    if (!result.nextUrl && typeof window.book !== 'undefined' && window.book.chapter && window.book.chapter.nextId && window.book.chapter.nextId !== '-1') {
                        var base = window.book.chapterUrl || location.href;
                        var final = base.replace('chapterId', window.book.chapter.nextId);
                        result.nextUrl = final.startsWith('http') ? final : new URL(final, location.href).href;
                    }

                    return JSON.stringify(result);
                } catch(e) { return "JS_ERROR: " + e.message; }
            })();
        """.trimIndent()
    }

    /**
     * デバッグツール (Eruda) 起動スクリプト。
     * 【重要】画面右下のフローティングボタン (eruda-entry-btn) は画面の邪魔になるため、
     * Shadow DOM を含め CSS および API (eruda._entryBtn.hide()) で完全に非表示化し、
     * アプリ上部のスパナボタンのみでトグル開閉させること。
     */
    fun buildErudaScript(): String {
        return """
            (function() {
                if (window.eruda) {
                    if (window.__eruda_visible) {
                        eruda.hide();
                        if (eruda._entryBtn) eruda._entryBtn.hide();
                        window.__eruda_visible = false;
                    } else {
                        eruda.show();
                        if (eruda._entryBtn) eruda._entryBtn.hide();
                        window.__eruda_visible = true;
                    }
                    return;
                }
                var s = document.createElement('script');
                s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                document.body.appendChild(s);
                s.onload = function() {
                    eruda.init({ autoShow: false });
                    var hideCss = '.eruda-entry-btn, div[class*="eruda-entry-btn"] { display: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; }';
                    var st = document.createElement('style');
                    st.innerHTML = hideCss;
                    document.head.appendChild(st);
                    if (eruda._shadowRoot) {
                        var st2 = document.createElement('style');
                        st2.innerHTML = hideCss;
                        eruda._shadowRoot.appendChild(st2);
                    }
                    if (eruda._entryBtn) eruda._entryBtn.hide();
                    eruda.show();
                    if (eruda._entryBtn) eruda._entryBtn.hide();
                    window.__eruda_visible = true;
                };
            })();
        """.trimIndent()
    }

    /**
     * 要素インスペクター（CSSセレクタ調査）スクリプト。
     * 
     * 【超重要・開発時の苦戦ポイント & 絶対遵守事項】
     * 1. Android WebView の @JavascriptInterface メソッドは、JavaScript 側で `typeof window.AndroidBridge.onInspectResult`
     *    を実行すると 'function' を返さず 'object' や 'unknown' になる仕様（バグ）が存在する。
     *    そのため、`typeof === 'function'` などの存在チェックは絶対に書いてはならない（判定が false になりダイアログが出なくなる）。
     *    必ず `if (window.AndroidBridge) window.AndroidBridge.onInspectResult(selector);` のように直接呼び出すこと！
     * 2. OFF時（stop()時）に `removeEventListener` でイベントリスナーを完全に破棄すること。
     *    これを怠るとリスナーがゾンビ化し、通常のWeb操作（タップ・リンク移動）が不能になり多重発火バグの原因となる。
     */
    fun buildInspectorScript(): String {
        return """
            (function(){
                if (!window.__novelInspector) {
                    var prev = null;
                    var active = false;

                    function getCss(el) {
                        if (!el || el.nodeType !== Node.ELEMENT_NODE) return "";
                        var path = [];
                        while (el && el.nodeType === Node.ELEMENT_NODE) {
                            var sel = el.nodeName.toLowerCase();
                            if (el.id && !el.id.match(/^[0-9]/)) {
                                sel += '#' + el.id;
                                path.unshift(sel);
                                break;
                            } else {
                                var cls = el.className;
                                if (typeof cls === 'string' && cls.trim() !== '') {
                                    var valid = cls.trim().split(/\s+/).filter(function(c) {
                                        return !c.match(/[0-9:\[\]\.]/);
                                    });
                                    if (valid.length > 0) sel += '.' + valid.join('.');
                                }
                                var sib = el, nth = 1;
                                while (sib = sib.previousElementSibling) {
                                    if (sib.nodeName.toLowerCase() === el.nodeName.toLowerCase()) nth++;
                                }
                                if (nth !== 1) sel += ':nth-of-type(' + nth + ')';
                            }
                            path.unshift(sel);
                            el = el.parentNode;
                            if (path.length > 3) break;
                        }
                        return path.join(' > ');
                    }

                    function highlight(el) {
                        if (prev && prev !== el) {
                            prev.style.outline = '';
                        }
                        if (el && el.style) {
                            el.style.outline = '3px solid #FF5722';
                            prev = el;
                        }
                    }

                    function clearHighlight() {
                        if (prev) {
                            prev.style.outline = '';
                            prev = null;
                        }
                    }

                    function handleClick(e) {
                        if (!active) return;
                        e.preventDefault();
                        e.stopPropagation();
                        try {
                            var target = e.target;
                            highlight(target);
                            var selector = getCss(target) || '?';
                            // 【注意】typeof チェックは禁止。直接呼び出すこと
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onInspectResult(selector);
                            }
                        } catch(err) {}
                    }

                    window.__novelInspector = {
                        start: function() {
                            this.stop();
                            active = true;
                            document.addEventListener('click', handleClick, true);
                        },
                        stop: function() {
                            active = false;
                            clearHighlight();
                            document.removeEventListener('click', handleClick, true);
                        }
                    };
                }
                window.__novelInspector.start();
            })();
        """.trimIndent()
    }

    fun buildInspectorStopScript(): String {
        return """
            (function(){
                if (window.__novelInspector) {
                    window.__novelInspector.stop();
                }
            })();
        """.trimIndent()
    }
}