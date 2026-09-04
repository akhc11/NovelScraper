package com.example.novelscraper.scraper

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Base64

object ScrapingScriptBuilder {

    // --- JS共通スニペット（セレクタ生成・エスケープ・除外判定ユーティリティ） ---
    // 注意: Kotlin raw文字列のため `\s` 等はJSへそのまま渡る（二重エスケープ禁止）
    private val JS_UNIQUE_SELECTOR = """
        function safeEscape(str) {
            if (!str) return '';
            if (window.CSS && CSS.escape) return CSS.escape(str);
            return String(str).replace(/([ #;?%&,.+*~\':"!^$[\]()=>|\/@])/g, '\\$1');
        }

        function isUsefulClass(cls) {
            if (!cls || cls.length < 2 || cls.length > 40) return false;
            if (cls.startsWith('__novel') || cls.startsWith('notranslate') || cls.startsWith('skiptranslate')) return false;
            // Tailwind / 汎用ユーティリティクラス（flex, text-*, p-*, m-*, w-*, h-*, col-*, bg-* など）の除外
            if (/^(flex|inline|block|grid|text-|bg-|p[xytrbl]?-|m[xytrbl]?-|w-|h-|border|rounded|shadow|overflow|font-|items-|justify-|relative|absolute|fixed|static)/.test(cls)) return false;
            // 短いランダムハッシュ系（例: sc-1234, css-abc1234, svelte-xyz）の除外
            if (/^(css|sc|svelte|styled|jsx)-[a-zA-Z0-9]+$/.test(cls)) return false;
            return true;
        }

        function getUniqueSelector(el) {
            if (!el || el.nodeType !== 1) return '';
            var path = [];
            var curr = el;
            while (curr && curr.nodeType === 1) {
                var tag = curr.nodeName.toLowerCase();
                if (curr.id && !String(curr.id).match(/^[0-9]/)) {
                    path.unshift(tag + '#' + safeEscape(curr.id));
                    break;
                }
                var testId = curr.getAttribute('data-testid') || curr.getAttribute('data-id');
                if (testId) {
                    path.unshift(tag + '[data-testid="' + safeEscape(testId) + '"]');
                    break;
                }
                var rel = curr.getAttribute('rel');
                if (rel) {
                    path.unshift(tag + '[rel="' + safeEscape(rel) + '"]');
                    break;
                }
                var usefulClass = null;
                if (typeof curr.className === 'string' && curr.className.trim()) {
                    var classes = curr.className.trim().split(/\s+/);
                    for (var i = 0; i < classes.length; i++) {
                        if (isUsefulClass(classes[i])) { usefulClass = classes[i]; break; }
                    }
                }
                if (usefulClass) {
                    var clsSel = tag + '.' + safeEscape(usefulClass);
                    try {
                        if (document.querySelectorAll(clsSel).length === 1) {
                            path.unshift(clsSel);
                            break;
                        }
                    } catch(e){}
                    var sib = curr, nth = 1;
                    while (sib = sib.previousElementSibling) {
                        if (sib.nodeName.toLowerCase() === tag) nth++;
                    }
                    path.unshift(clsSel + (nth > 1 ? ':nth-of-type(' + nth + ')' : ''));
                } else {
                    var sib = curr, nth = 1;
                    while (sib = sib.previousElementSibling) {
                        if (sib.nodeName.toLowerCase() === tag) nth++;
                    }
                    path.unshift(tag + (nth > 1 ? ':nth-of-type(' + nth + ')' : ''));
                }
                curr = curr.parentNode;
                if (path.length > 4 || (curr && (curr.tagName === 'BODY' || curr.tagName === 'HTML'))) break;
            }
            return path.join(' > ');
        }
    """.trimIndent()

    private val JS_SHORT_SELECTOR = """
        function shortSelector(node) {
            if (!node || node.nodeType !== 1) return null;
            var tag = node.tagName.toLowerCase();
            if (node.id && !String(node.id).match(/^[0-9]/)) return tag + '#' + safeEscape(node.id);
            var testId = node.getAttribute('data-testid') || node.getAttribute('data-id');
            if (testId) return tag + '[data-testid="' + safeEscape(testId) + '"]';
            var rel = node.getAttribute('rel');
            if (rel) return tag + '[rel="' + safeEscape(rel) + '"]';
            if (typeof node.className === 'string' && node.className.trim()) {
                var classes = node.className.trim().split(/\s+/);
                for (var i = 0; i < classes.length; i++) {
                    if (isUsefulClass(classes[i])) return tag + '.' + safeEscape(classes[i]);
                }
            }
            return null;
        }
    """.trimIndent()


    fun buildScrapingScript(config: ScraperConfig, useImages: Boolean, isDebug: Boolean = false): String {
        val configJson = Json.encodeToString(config)
        val configBase64 = Base64.getEncoder().encodeToString(configJson.toByteArray(Charsets.UTF_8))
        return """
            (function() {
                try {
                    if (document.title.includes("Just a moment") || document.body.innerText.includes("Verify you are human")) return "CF_DETECTED";
                    var config = JSON.parse(decodeURIComponent(escape(atob('$configBase64'))));
                    var result = { title: "", content: "", nextUrl: "", chapter: "", folderName: "", debugLines: null };
                    function clean(t) { return t ? t.trim() : ""; }

                    // --- CSS セレクタ取得ユーティリティ（共通スニペット） ---
                    ${JS_UNIQUE_SELECTOR}

                    // --- 除外処理 ---
                    try {
                        document.querySelectorAll('.__novel_exclude').forEach(function(el) { el.classList.remove('__novel_exclude'); });
                    } catch(e){}
                    if (config.exclude) {
                        try {
                            config.exclude.split(',').forEach(function(s) {
                                var sel = s.trim();
                                if (sel) {
                                    document.querySelectorAll(sel).forEach(function(el) { el.classList.add('__novel_exclude'); });
                                }
                            });
                        } catch(e){}
                    }

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
                        var targetSel = config.folder.trim();
                        var el = null;
                        try { el = document.querySelector(targetSel); } catch(e){}
                        if (el) {
                            f = (el.tagName === 'TITLE') ? (document.title || el.textContent || "") : (el.innerText || el.textContent || "");
                        } else if (targetSel.toLowerCase() === 'title') {
                            f = document.title || "";
                        }
                    }
                    if (!f) {
                        var el = document.querySelector('.series-title, .novel_title, .novel-title, .p-novel__title');
                        if (el) f = el.innerText || el.textContent || "";
                    }
                    if (!f && meta.isPartOf) f = meta.isPartOf.name || meta.isPartOf;
                    if (!f && config.folder && config.folder.trim().toLowerCase() === 'title') {
                        f = document.title || "";
                    }

                    // 作品名 Regex (config.regex) による抽出・加工
                    if (f && config.regex) {
                        try {
                            if (/^(?:del|delete|remove):/i.test(config.regex)) {
                                var pat = config.regex.replace(/^(?:del|delete|remove):/i, '').trim();
                                f = f.replace(new RegExp(pat, 'g'), '');
                            } else {
                                var m = f.match(new RegExp(config.regex));
                                if (m) f = (m[1] !== undefined && m[1] !== null) ? m[1] : m[0];
                            }
                        } catch(e){}
                    }
                    result.folderName = clean(f);

                    var t = config.title ? document.querySelector(config.title) : null;
                    if (!t) t = document.querySelector('.novel_subtitle, .ep-title, .episode-title, .chapter-title, h1, h2');
                    result.title = t ? clean(t.innerText || t.textContent || "") : "";
                    if (!result.title) result.title = meta.headline || clean(document.title);

                    // タイトル Regex (config.fileRegex) による抽出・加工
                    if (result.title && config.fileRegex) {
                        try {
                            if (/^(?:del|delete|remove):/i.test(config.fileRegex)) {
                                var pat = config.fileRegex.replace(/^(?:del|delete|remove):/i, '').trim();
                                result.title = clean(result.title.replace(new RegExp(pat, 'g'), ''));
                            } else {
                                var m = result.title.match(new RegExp(config.fileRegex));
                                if (m) result.title = clean((m[1] !== undefined && m[1] !== null) ? m[1] : m[0]);
                            }
                        } catch(e){}
                    }

                    // --- チャプター番号抽出（@URLバグ修正 & ハイブリッド対応） ---
                    var c = "";
                    var chapSetting = (config.chapter || "").trim().replace('＠', '@');
                    if (chapSetting.toUpperCase() === "@URL") {
                        var pathOnly = "";
                        try { pathOnly = location.pathname; } catch(e){ pathOnly = location.href; }
                        var m = pathOnly.match(/(\d+)/g);
                        if (m && m.length > 0) c = m[m.length - 1];
                    } else if (chapSetting) {
                        var actualSelector = chapSetting.split('||')[0].trim();
                        if (actualSelector && !actualSelector.startsWith('@')) {
                            var el = null;
                            try { el = document.querySelector(actualSelector); } catch(e){}
                            var text = el ? (el.innerText || el.textContent || "") : "";
                            if (text) {
                                if (config.chapterRegex) {
                                    try { var m = text.match(new RegExp(config.chapterRegex)); if(m) c = m[1] || m[0]; } catch(e){}
                                }
                                if (!c) { var m = text.match(/(\d+)/); if(m) c = m[1]; }
                            }
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
                        clone.querySelectorAll('script, style, noscript, iframe, template, .ad, .ads, [class*="advertisement"], .__novel_exclude').forEach(n => n.remove());
                        if (!${useImages}) clone.querySelectorAll('img, picture, svg').forEach(n => n.remove());
                        
                        // 1. 改行の確保
                        clone.querySelectorAll('br').forEach(br => { br.after(document.createTextNode('\n')); br.remove(); });
                        clone.querySelectorAll('p, div, h1, h2, h3, h4, h5, h6, li, dt, dd').forEach(el => {
                            if (el.innerText.trim().length > 0) el.after(document.createTextNode('\n'));
                        });

                        // 2. テキストの分解とプロ仕様の整形
                        var lines = clone.innerText.split('\n');
                        var formattedLines = [];
                        var debugLines = [];

                        var dbgTexts = [];
                        if (${isDebug}) {
                            try {
                                var dbgWalker = document.createTreeWalker(b, NodeFilter.SHOW_TEXT, null, false);
                                var dbgNode;
                                while ((dbgNode = dbgWalker.nextNode())) {
                                    dbgTexts.push({ t: dbgNode.textContent, el: dbgNode.parentElement });
                                }
                            } catch(e){}
                        }

                        lines.forEach(function(line) {
                            var l = line.replace(/^[　 \t\s]+|[　 \t\s]+$/g, '');
                            if (l.length === 0) return;

                            var processed = "";
                            if (/^[\u300c\u300e\uff08\u28\u3010\u3014\uff3b]/.test(l)) {
                                processed = l;
                            } else {
                                processed = "\u3000" + l;
                            }
                            formattedLines.push(processed);

                            if (${isDebug}) {
                                var selector = "";
                                try {
                                    for (var di = 0; di < dbgTexts.length; di++) {
                                        if (dbgTexts[di].t.includes(l)) {
                                            selector = getUniqueSelector(dbgTexts[di].el);
                                            break;
                                        }
                                    }
                                } catch(e){}
                                debugLines.push({ text: processed, selector: selector });
                            }
                        });

                        // 3. 結合と仕上げ
                        result.content = formattedLines.join('\n')
                            .replace(/\n{3,}/g, '\n\n')
                            .trim();
                        if (${isDebug}) result.debugLines = debugLines;
                    }

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
                            // タイポ修正: \u7d1a(級) ➔ \u7d9a(続く)
                            var regex = /^(?:\u6b21|Next|\u7d9a\u304f|>>|\u300b|\u6b21\u3078|\u4e0b\u4e00\u7ae0|\u4e0b\u4e00\u9875)/i;
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
     * - Android WebView のクリップボードAPI遮断を突破し、AndroidBridge経由で確実にネイティブクリップボードにコピー
     */
    fun buildErudaScript(): String {
        return """
            (function() {
                try {
                    // クリップボードAPIのネイティブブリッジ直結（Android WebView の navigator.clipboard 遮断を突破）
                    if (window.AndroidBridge && window.AndroidBridge.onCopySelector) {
                        var nativeCopy = function(text) {
                            try { window.AndroidBridge.onCopySelector(text); return true; } catch(e){ return false; }
                        };
                        try {
                            if (!navigator.clipboard) navigator.clipboard = {};
                            navigator.clipboard.writeText = function(text) {
                                nativeCopy(text);
                                return Promise.resolve();
                            };
                        } catch(e){}
                        try {
                            var origExec = document.execCommand;
                            document.execCommand = function(cmd) {
                                if (cmd === 'copy') {
                                    var sel = window.getSelection ? window.getSelection().toString() : '';
                                    if (sel) nativeCopy(sel);
                                }
                                return origExec ? origExec.apply(document, arguments) : true;
                            };
                        } catch(e){}
                    }

                    if (window.__erudaLoaded) {
                        if (window.eruda) {
                            if (window.__erudaVisible) {
                                eruda.hide();
                                window.__erudaVisible = false;
                            } else {
                                eruda.show();
                                window.__erudaVisible = true;
                            }
                        }
                        return;
                    }
                    var s = document.createElement('script');
                    s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                    s.onload = function() {
                        if (window.eruda) {
                            eruda.init();
                            eruda.show();
                            window.__erudaLoaded = true;
                            window.__erudaVisible = true;
                            // フローティング歯車アイコンをAPI・CSS双方で完全非表示
                            try {
                                eruda.scale(0.85);
                                var entry = document.querySelector('.eruda-entry-btn');
                                if (entry) entry.style.display = 'none';
                            } catch(e){}
                        }
                    };
                    document.head.appendChild(s);
                } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * インスペクター（虫眼鏡モード）のJSスクリプト。
     * - 各種要素候補のタップでコピー＆反映ボタン付きカードを表示（最大10候補）
     * - リンク要素の自動親昇格
     * - 全7フィールド対応
     */
    fun buildInspectorScript(config: ScraperConfig): String {
        val configJson = Json.encodeToString(config)
        val configBase64 = Base64.getEncoder().encodeToString(configJson.toByteArray(Charsets.UTF_8))
        return """
            (function() {
                if (window.__novelInspectActive) {
                    try {
                        if (window.__novelInspector && window.__novelInspector.updateConfig) {
                            window.__novelInspector.updateConfig('$configBase64');
                        }
                    } catch(e){}
                    return;
                }
                window.__novelInspectActive = true;
                var config = JSON.parse(decodeURIComponent(escape(atob('$configBase64'))));
                var active = true;
                var prevHighlight = null;
                var lastTarget = 'body';
                var popup = null;

                // 全7フィールドの完全定義（SelectorField と完全対応・ユーザーが使いやすい並び順）
                var TARGETS = [
                    { key: 'body',        label: '本文' },
                    { key: 'title',       label: 'タイトル' },
                    { key: 'next',        label: '次ページ' },
                    { key: 'chapter',     label: 'チャプター番号' },
                    { key: 'folder',      label: '作品名' },
                    { key: 'folder_link', label: '別URL取得' },
                    { key: 'exclude',     label: '除外' }
                ];

                // 翻訳 Cookie のパージ（インスペクター起動時の安全確保）
                try {
                    document.cookie = "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;";
                    document.cookie = "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 UTC;";
                    document.cookie = "googtrans=/auto/null; path=/;";
                } catch(e){}

                var style = document.createElement('style');
                style.id = '__novel_inspector_style';
                style.innerHTML = '.__novel_notranslate, .notranslate, .skiptranslate { -webkit-user-select: auto; } .__novel_mark_title { outline: 3px solid #2196F3 !important; } .__novel_mark_body { outline: 3px solid #4CAF50 !important; } .__novel_mark_next { outline: 3px solid #FF9800 !important; }';
                document.head.appendChild(style);

                var hint = document.createElement('div');
                hint.id = '__novel_hint';
                hint.className = '__novel_notranslate notranslate skiptranslate';
                hint.setAttribute('translate', 'no');
                hint.innerText = '要素をタップして候補を確認';
                hint.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.85);color:#fff;padding:6px 14px;border-radius:16px;z-index:2147483647;font-size:12px;pointer-events:none;';
                document.body.appendChild(hint);
                setTimeout(function(){ if (hint.parentNode) hint.parentNode.removeChild(hint); }, 3000);

                // CSS セレクタ取得ユーティリティ（共通スニペット）
                ${JS_UNIQUE_SELECTOR}

                function highlight(el) {
                    if (prevHighlight) prevHighlight.style.outline = '';
                    if (el && el.style) {
                        el.style.outline = '3px solid #FF5722';
                        prevHighlight = el;
                    }
                }

                function clearHighlight() {
                    if (prevHighlight) { try { prevHighlight.style.outline = ''; } catch(e){} prevHighlight = null; }
                }

                function getExcludeList() {
                    return (config.exclude || '').split(',').map(function(s){ return s.trim(); }).filter(Boolean);
                }

                function isExcluded(sel) { return getExcludeList().indexOf(sel) > -1; }

                function clearInspectorMarks() {
                    try {
                        document.querySelectorAll('.__novel_mark_title, .__novel_mark_body, .__novel_mark_next').forEach(function(el){
                            el.style.outline = '';
                            el.classList.remove('__novel_mark_title', '__novel_mark_body', '__novel_mark_next');
                        });
                    } catch(e){}
                    try {
                        getExcludeList().forEach(function(s) {
                            try { document.querySelectorAll(s).forEach(function(n){ n.style.opacity = ''; }); } catch(e){}
                        });
                    } catch(e){}
                }

                function applyCurrentConfig() {
                    clearInspectorMarks();
                    try { if (config.title) document.querySelector(config.title).classList.add('__novel_mark_title'); } catch(e){}
                    try { if (config.body) document.querySelector(config.body).classList.add('__novel_mark_body'); } catch(e){}
                    try { if (config.next) document.querySelector(config.next).classList.add('__novel_mark_next'); } catch(e){}
                    try {
                        if (config.exclude) {
                            config.exclude.split(',').forEach(function(s) {
                                var sel = s.trim();
                                if (sel) document.querySelectorAll(sel).forEach(function(el){ el.style.opacity = '0.3'; });
                            });
                        }
                    } catch(e){}
                }

                function buildPreview(sel, target) {
                    var el = null;
                    try { el = document.querySelector(sel); } catch(e) {}
                    if (!el) return '(一致なし)';

                    // 次ページ または 別URL取得: リンク先URLをプレビュー
                    if (target === 'next' || target === 'folder_link') {
                        var a = (el.tagName === 'A') ? el : (el.querySelector ? el.querySelector('a') : null);
                        if (!a && el.closest) a = el.closest('a');
                        var href = a ? (a.href || a.getAttribute('href') || '') : '';
                        if (href) { try { href = new URL(href, location.href).href; } catch(e){} }
                        return href ? 'リンクURL: ' + href : '(リンク先URLが取得できません - aタグまたは親のaタグを選択してください)';
                    }

                    // チャプター番号: 数字抽出結果をプレビュー
                    if (target === 'chapter') {
                        var raw = (el.innerText || el.textContent || '').trim();
                        var digits = raw.match(/(\d+)/);
                        var chapNum = digits ? digits[1].padStart(4, '0') : '(数字なし)';
                        return '抽出話数: ' + chapNum + '  (元テキスト: ' + (raw.length > 50 ? raw.substring(0, 50) + '…' : raw) + ')';
                    }

                    var t = ((el.innerText || el.textContent || '') + '').trim();
                    if (t.length > 150) t = t.substring(0, 150) + '…';
                    return t || '(テキストなし)';
                }

                // 短縮セレクタ（共通スニペット）
                ${JS_SHORT_SELECTOR}

                function getCandidates(el) {
                    var cands = [];
                    var seen = {};
                    function push(node, label, sel) {
                        if (!node || node.nodeType !== 1) return;
                        var tag = (node.tagName || '').toUpperCase();
                        if (tag === 'BODY' || tag === 'HTML') return;
                        var s = sel || shortSelector(node);
                        if (!s) s = getUniqueSelector(node);
                        if (!s || seen[s]) return;
                        seen[s] = true;
                        var m = 0;
                        try { m = document.querySelectorAll(s).length; } catch(e) {}
                        cands.push({ node: node, selector: s, label: label, matchCount: m });
                    }

                    // 1. 要素自身
                    push(el, '要素自身', getUniqueSelector(el));

                    // 2. リンク要素 (自身または祖先に <a> があれば提示)
                    var aEl = (el.tagName === 'A') ? el : (el.closest ? el.closest('a') : null);
                    if (aEl && aEl !== el) {
                        push(aEl, 'リンク(a)', shortSelector(aEl) || getUniqueSelector(aEl));
                    }

                    // 3. 有用属性・ID祖先
                    var p = el.parentElement;
                    while (p && p.tagName !== 'BODY' && !(p.id && !String(p.id).match(/^[0-9]/))) p = p.parentElement;
                    if (p && p.tagName !== 'BODY') push(p, 'ID祖先');

                    // 4. Class祖先
                    var q = el.parentElement;
                    while (q && q.tagName !== 'BODY' && !(typeof q.className === 'string' && q.className.trim())) q = q.parentElement;
                    if (q && q.tagName !== 'BODY') push(q, 'Class祖先');

                    // 5. 意味コンテナ（article, main, section, #novel_honbun等）
                    var sem = null;
                    try { sem = el.closest('article, main, [role="main"], section, .novel_honbun, #novel_honbun'); } catch(e){}
                    if (sem && sem !== p && sem !== q && sem.tagName !== 'BODY') {
                        push(sem, '意味コンテナ', shortSelector(sem) || sem.tagName.toLowerCase());
                    }

                    // フォールバック: 親要素・祖先要素連鎖 (最大10候補まで探索)
                    var n = el.parentElement;
                    var depth = 1;
                    while (n && n.tagName !== 'BODY' && n.tagName !== 'HTML' && cands.length < 10) {
                        var tag = (n.tagName || '').toLowerCase();
                        var label = (depth === 1) ? '親要素' : '祖先要素(' + tag + ')';
                        push(n, label);
                        n = n.parentElement;
                        depth++;
                    }
                    // 最大10候補まで提示（ユーザー要望）
                    return cands.slice(0, 10);
                }

                function closePopup() {
                    if (popup && popup.parentNode) popup.parentNode.removeChild(popup);
                    popup = null;
                }

                function showCandidatePopup(cands, x, y) {
                    closePopup();
                    var pendingTarget = lastTarget;

                    popup = document.createElement('div');
                    popup.id = '__novel_popup';
                    popup.className = '__novel_notranslate notranslate skiptranslate';
                    popup.setAttribute('translate', 'no');
                    popup.style.cssText = 'position:fixed;background:#1e1e1e;color:#fff;z-index:2147483646;font-size:12px;border-radius:8px;box-shadow:0 4px 16px rgba(0,0,0,0.7);max-width:480px;width:min(96vw,480px);max-height:88vh;display:flex;flex-direction:column;overflow:hidden;border:1px solid #444;';

                    var header = document.createElement('div');
                    header.style.cssText = 'display:flex;align-items:center;padding:6px 10px;background:#2c2c2c;flex-shrink:0;border-bottom:1px solid #3d3d3d;';
                    var title = document.createElement('span');
                    title.innerText = '要素候補のコピー / 反映';
                    title.style.cssText = 'flex:1;color:#eee;font-weight:bold;';
                    var closeBtn = document.createElement('button');
                    closeBtn.innerText = '×';
                    closeBtn.style.cssText = 'background:#e53935;color:#fff;border:none;border-radius:4px;padding:2px 8px;cursor:pointer;font-size:14px;font-weight:bold;';
                    closeBtn.onclick = function(ev) { ev.stopPropagation(); closePopup(); };
                    header.appendChild(title);
                    header.appendChild(closeBtn);
                    popup.appendChild(header);

                    var chips = document.createElement('div');
                    chips.style.cssText = 'display:flex;flex-wrap:wrap;gap:4px;padding:6px 10px;background:#252525;flex-shrink:0;border-bottom:1px solid #333;';
                    var chipBtns = {};
                    TARGETS.forEach(function(t) {
                        var b = document.createElement('button');
                        b.innerText = t.label;
                        b.style.cssText = 'background:#424242;color:#bbb;border:none;border-radius:4px;padding:4px 8px;cursor:pointer;font-size:11px;';
                        b.onclick = function(ev) {
                            ev.stopPropagation();
                            pendingTarget = t.key;
                            updateChips();
                            refreshRows();
                        };
                        chipBtns[t.key] = b;
                        chips.appendChild(b);
                    });
                    function updateChips() {
                        TARGETS.forEach(function(t) {
                            var isSelected = (pendingTarget === t.key);
                            chipBtns[t.key].style.background = isSelected ? '#00897B' : '#424242';
                            chipBtns[t.key].style.color = isSelected ? '#ffffff' : '#bbb';
                            chipBtns[t.key].style.fontWeight = isSelected ? 'bold' : 'normal';
                        });
                    }
                    updateChips();
                    popup.appendChild(chips);

                    var rowsBox = document.createElement('div');
                    rowsBox.style.cssText = 'overflow-y:auto;min-height:0;padding:4px 0;';
                    popup.appendChild(rowsBox);

                    function applyCandidateAction(c, isRemovable) {
                        if (window.AndroidBridge) {
                            if (pendingTarget === 'exclude') {
                                if (isRemovable) {
                                    config.exclude = getExcludeList().filter(function(s){ return s !== c.selector; }).join(', ');
                                    try { window.AndroidBridge.onRemoveExclude(c.selector); } catch(e){}
                                    try { document.querySelectorAll(c.selector).forEach(function(n){ n.style.opacity = ''; }); } catch(e){}
                                } else {
                                    var list = getExcludeList();
                                    if (list.indexOf(c.selector) < 0) list.push(c.selector);
                                    config.exclude = list.join(', ');
                                    try { window.AndroidBridge.onApplyCandidate('exclude', c.selector); } catch(e){}
                                    try { document.querySelectorAll(c.selector).forEach(function(n){ n.style.opacity = '0.3'; }); } catch(e){}
                                }
                            } else {
                                config[pendingTarget] = c.selector;
                                try { window.AndroidBridge.onApplyCandidate(pendingTarget, c.selector); } catch(e){}
                            }
                        }
                        lastTarget = pendingTarget;
                        clearHighlight();
                        applyCurrentConfig();
                        closePopup();
                    }

                    function refreshRows() {
                        rowsBox.innerHTML = '';
                        cands.forEach(function(c) {
                            var removable = (pendingTarget === 'exclude') && isExcluded(c.selector);
                            var row = document.createElement('div');
                            row.style.cssText = 'padding:8px 10px;border-top:1px solid #333;display:flex;flex-direction:column;gap:4px;';
                            
                            var head = document.createElement('div');
                            head.style.cssText = 'display:flex;gap:6px;align-items:center;';
                            
                            var lab = document.createElement('b');
                            lab.innerText = removable ? '除外中' : c.label;
                            if (c.matchCount > 1) lab.innerText += ' (' + c.matchCount + '件)';
                            lab.style.cssText = 'color:' + (removable ? '#81c784' : '#4dd0e1') + ';font-size:11px;flex-shrink:0;';
                            
                            var selSpan = document.createElement('span');
                            selSpan.innerText = c.selector;
                            selSpan.style.cssText = 'flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#ccc;font-family:monospace;font-size:11px;';
                            
                            // 操作ボタン群（コピーボタン と 反映ボタン を独立配置）
                            var actionBox = document.createElement('div');
                            actionBox.style.cssText = 'display:flex;gap:4px;flex-shrink:0;';

                            var copyBtn = document.createElement('button');
                            copyBtn.innerText = 'コピー';
                            copyBtn.style.cssText = 'background:#455a64;color:#fff;border:none;border-radius:3px;padding:3px 7px;cursor:pointer;font-size:11px;transition:background 0.2s;';
                            copyBtn.onclick = function(ev) {
                                ev.stopPropagation();
                                if (window.AndroidBridge) {
                                    try {
                                        window.AndroidBridge.onCopySelector(c.selector);
                                    } catch(e){}
                                }
                                copyBtn.innerText = 'コピー済!';
                                copyBtn.style.background = '#2e7d32';
                                setTimeout(function(){
                                    copyBtn.innerText = 'コピー';
                                    copyBtn.style.background = '#455a64';
                                }, 700);
                            };

                            var applyBtn = document.createElement('button');
                            applyBtn.innerText = removable ? '除外解除' : '反映';
                            applyBtn.style.cssText = 'background:' + (removable ? '#2e7d32' : '#FF5722') + ';color:#fff;border:none;border-radius:3px;padding:3px 8px;cursor:pointer;font-size:11px;font-weight:bold;';
                            applyBtn.onclick = function(ev) {
                                ev.stopPropagation();
                                applyCandidateAction(c, removable);
                            };

                            actionBox.appendChild(copyBtn);
                            actionBox.appendChild(applyBtn);

                            head.appendChild(lab);
                            head.appendChild(selSpan);
                            head.appendChild(actionBox);

                            var prev = document.createElement('div');
                            prev.innerText = buildPreview(c.selector, pendingTarget);
                            prev.style.cssText = 'color:#aaa;max-height:80px;overflow-y:auto;line-height:15px;padding:5px 8px;background:#111;border-radius:4px;white-space:pre-wrap;word-break:break-all;font-size:11px;border:1px solid #282828;';

                            row.appendChild(head);
                            row.appendChild(prev);
                            rowsBox.appendChild(row);
                        });
                    }
                    refreshRows();

                    document.body.appendChild(popup);

                    var w = popup.offsetWidth || 340;
                    var h = popup.offsetHeight || 220;
                    var maxL = (window.innerWidth || 360) - w - 8;
                    var left = Math.min(Math.max(8, x - w / 2), Math.max(8, maxL));
                    var maxY = (window.innerHeight || 640) - h - 8;
                    var top = (y + 12 > maxY) ? Math.max(8, y - h - 12) : y + 12;
                    popup.style.left = left + 'px';
                    popup.style.top = top + 'px';
                }

                function handleClick(e) {
                    if (!active) return;
                    try {
                        var t = e.target;
                        if (t && t.closest && t.closest('#__novel_popup')) return;
                    } catch(e){}
                    e.preventDefault();
                    e.stopPropagation();
                    var cands = getCandidates(e.target);
                    if (!cands.length) return;
                    try { highlight(e.target); } catch(e){}
                    showCandidatePopup(cands, e.clientX || 0, e.clientY || 0);
                }

                document.addEventListener('click', handleClick, true);
                applyCurrentConfig();

                window.__novelInspector = {
                    updateConfig: function(base64) {
                        try {
                            config = JSON.parse(decodeURIComponent(escape(atob(base64))));
                            clearHighlight();
                            applyCurrentConfig();
                        } catch(e){}
                    },
                    stop: function() {
                        active = false;
                        try { document.removeEventListener('click', handleClick, true); } catch(e){}
                        closePopup();
                        var h = document.getElementById('__novel_hint');
                        if (h && h.parentNode) h.parentNode.removeChild(h);
                        clearHighlight();
                        try { clearInspectorMarks(); } catch(e){}
                        var st = document.getElementById('__novel_inspector_style');
                        if (st && st.parentNode) st.parentNode.removeChild(st);
                        window.__novelInspectActive = false;
                    }
                };
            })();
        """.trimIndent()
    }

    /**
     * テキストおよびセレクタによる要素逆引き検索スクリプト。
     * - <head> の <title> や <meta> タグを含む HTML 全体を探索
     * - セレクタ直接指定（title, h1, #content 等）のマッチ判定
     * - document.documentElement 全体のテキストノード探索
     * - 候補セレクタ、タグ、プレビューテキスト、マッチ種別を JSON 配列で返却
     */
    fun buildSearchTextScript(query: String): String {
        val safeQuery = query.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ").replace("\u2028", " ").replace("\u2029", " ")
        return """
            (function(){
                try {
                    var query = '$safeQuery'.trim();
                    if (!query) return '[]';
                    var qLower = query.toLowerCase();
                    
                    ${JS_UNIQUE_SELECTOR}
                    ${JS_SHORT_SELECTOR}

                    // 短いセレクタが一意でない場合は一意セレクタにフォールバック（誤適用防止）
                    function pickSelector(el) {
                        try {
                            var s = shortSelector(el);
                            if (s && document.querySelectorAll(s).length === 1) return s;
                        } catch(e){}
                        return getUniqueSelector(el);
                    }

                    var results = [];
                    var seen = {};

                    function addCandidate(el, customSelector, matchType, customPreview) {
                        if (!el && !customSelector) return;
                        var sel = customSelector;
                        if (!sel && el) {
                            sel = pickSelector(el);
                        }
                        if (!sel || seen[sel]) return;
                        seen[sel] = true;

                        var tag = el ? (el.tagName || '').toUpperCase() : '';
                        var preview = customPreview;
                        if (preview === undefined && el) {
                            var t = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ');
                            preview = (t.length > 150) ? t.substring(0, 150) + '…' : (t || '(テキストなし)');
                        }
                        results.push({
                            selector: sel,
                            tag: tag,
                            preview: preview || '',
                            matchType: matchType
                        });
                    }

                    // 1. document.title & <title> タグの検査
                    var docTitle = (document.title || '').trim();
                    var titleEl = document.querySelector('title');
                    if (titleEl || docTitle) {
                        if (docTitle.toLowerCase().indexOf(qLower) >= 0 || qLower === 'title' || qLower === '<title>') {
                            addCandidate(titleEl, 'title', 'ページタイトル (<title>)', docTitle);
                        }
                    }

                    // 2. <meta> タグの検査 (og:title, og:novel:book_name, keywords, description など)
                    var metaEls = document.querySelectorAll('meta[content]');
                    for (var i = 0; i < metaEls.length; i++) {
                        var m = metaEls[i];
                        var c = (m.getAttribute('content') || '').trim();
                        if (c && c.toLowerCase().indexOf(qLower) >= 0) {
                            var name = m.getAttribute('name') || m.getAttribute('property') || m.getAttribute('itemprop') || '';
                            var mSel = name ? 'meta[' + (m.hasAttribute('property') ? 'property' : (m.hasAttribute('itemprop') ? 'itemprop' : 'name')) + '="' + name + '"]' : '';
                            if (mSel) {
                                addCandidate(m, mSel, 'メタ情報 (' + (name || 'meta') + ')', c);
                            }
                        }
                    }

                    // 3. セレクタ直接指定クエリとしての判定（, () + を含む複合セレクタ対応）
                    try {
                        if (/^[a-zA-Z0-9_\-\.\#\>\s\[\]\:\=\*\^\~\$\|\"\'\,\(\)\+]+$/.test(query)) {
                            var directEls = document.querySelectorAll(query);
                            if (directEls.length > 0) {
                                var firstEl = directEls[0];
                                var firstText = (firstEl.innerText || firstEl.textContent || '').trim().replace(/\s+/g, ' ');
                                var pText = (firstText.length > 150) ? firstText.substring(0, 150) + '…' : (firstText || '(空要素)');
                                addCandidate(firstEl, query, 'セレクタ直接指定 (' + directEls.length + '件該当)', pText);
                            }
                        }
                    } catch(e){}

                    // 4. 全DOMツリー（document.documentElement）のテキストノード探索
                    var walker = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_TEXT, null, false);
                    var node;
                    while ((node = walker.nextNode())) {
                        var text = (node.textContent || '').trim();
                        if (text && text.toLowerCase().indexOf(qLower) >= 0) {
                            var p = node.parentElement;
                            if (p) {
                                var pTag = (p.tagName || '').toUpperCase();
                                if (pTag === 'SCRIPT' || pTag === 'STYLE' || pTag === 'NOSCRIPT' || (p.closest && p.closest('#__novel_popup, #__novel_hint, #eruda'))) {
                                    continue;
                                }
                                if (pTag === 'TITLE') {
                                    addCandidate(p, 'title', 'ページタイトル (<title>)', text);
                                } else {
                                    var pSel = pickSelector(p);
                                    addCandidate(p, pSel, pTag + ' 要素');
                                }
                            }
                        }
                        if (results.length >= 20) break;
                    }

                    return JSON.stringify(results.slice(0, 15));
                } catch(err) {
                    return JSON.stringify([{ selector: '', tag: 'ERROR', preview: err.toString(), matchType: 'エラー' }]);
                }
            })();
        """.trimIndent()
    }

    /**
     * 除外候補プローブ: 指定セレクタの要素から4種類の候補を
     * JSON配列で返す（evaluateJavascript の戻り値として受領。WebViewへの注入不要）。
     */
    fun buildCandidateProbeScript(baseSelector: String): String {
        val safeSelector = baseSelector.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        return """
            (function(){
                try {
                    var baseEl = document.querySelector('$safeSelector');
                    if (!baseEl) return '[]';
                    ${JS_UNIQUE_SELECTOR}
                    ${JS_SHORT_SELECTOR}
                    var cands = [];
                    var seen = {};
                    function push(node, label, sel) {
                        if (!node || node.nodeType !== 1) return;
                        var tag = (node.tagName || '').toUpperCase();
                        if (tag === 'BODY' || tag === 'HTML') return;
                        var s = sel || shortSelector(node);
                        if (!s) s = getUniqueSelector(node);
                        if (!s || seen[s]) return;
                        seen[s] = true;
                        var count = 0, chars = 0, lines = 0, preview = '(テキストなし)';
                        try {
                            count = document.querySelectorAll(s).length;
                            var first = document.querySelector(s);
                            if (first) {
                                var t = ((first.innerText || '') + '').replace(/[ \t]+/g, ' ');
                                lines = t.split('\n').filter(function(x){ return x.trim(); }).length;
                                chars = t.replace(/\s+/g, '').length;
                                var p = t.trim().replace(/\s+/g, ' ');
                                preview = (p.length > 120) ? p.substring(0, 120) + '…' : (p || '(テキストなし)');
                            }
                        } catch(e){}
                        cands.push({ label: label, selector: s, metric: count + '件・約' + chars + '字/' + lines + '行', preview: preview });
                    }
                    push(baseEl, '要素自身', getUniqueSelector(baseEl));
                    var p = baseEl.parentElement;
                    while (p && p.tagName !== 'BODY' && !(p.id && !String(p.id).match(/^[0-9]/))) p = p.parentElement;
                    if (p && p.tagName !== 'BODY') push(p, 'ID祖先');
                    var q = baseEl.parentElement;
                    while (q && q.tagName !== 'BODY' && !(typeof q.className === 'string' && q.className.trim())) q = q.parentElement;
                    if (q && q.tagName !== 'BODY') push(q, 'Class祖先');
                    var sem = null;
                    try { sem = baseEl.closest('article, main, [role="main"], section'); } catch(e){}
                    if (sem && sem !== p && sem !== q && sem.tagName !== 'BODY') {
                        push(sem, '意味コンテナ', sem.tagName.toLowerCase());
                    }
                    // フォールバック: 親要素・祖先要素連鎖 (最大10候補まで探索)
                    var n = baseEl.parentElement;
                    var depth = 1;
                    while (n && n.tagName !== 'BODY' && n.tagName !== 'HTML' && cands.length < 10) {
                        var tag = (n.tagName || '').toLowerCase();
                        var label = (depth === 1) ? '親要素' : '祖先要素(' + tag + ')';
                        push(n, label);
                        n = n.parentElement;
                        depth++;
                    }
                    return JSON.stringify(cands.slice(0, 10));
                } catch(e) { return '[]'; }
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