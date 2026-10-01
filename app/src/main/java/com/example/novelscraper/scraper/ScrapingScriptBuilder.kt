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
                    var cls = classes[i];
                    if (isUsefulClass(cls)) {
                        var numMatch = cls.match(/^([a-zA-Z]{2,}[-_])\d+$/);
                        if (numMatch && !/^(col|row|gap|span|grid)[-_]/i.test(numMatch[1])) {
                            return tag + '[class*="' + safeEscape(numMatch[1]) + '"]';
                        }
                        return tag + '.' + safeEscape(cls);
                    }
                }
            }
            return null;
        }
    """.trimIndent()

    private val JS_CANDIDATE_TRAVERSAL = """
        function traverseCandidates(targetEl, onCandidate, includeLink) {
            if (!targetEl || targetEl.nodeType !== 1) return;
            var selfShort = shortSelector(targetEl);
            if (selfShort) {
                if (onCandidate(targetEl, '要素自身 (汎用)', selfShort) === false) return;
            }
            if (onCandidate(targetEl, '要素自身', getUniqueSelector(targetEl)) === false) return;

            if (includeLink) {
                var aEl = (targetEl.tagName === 'A') ? targetEl : (targetEl.closest ? targetEl.closest('a') : null);
                if (aEl && aEl !== targetEl) {
                    if (onCandidate(aEl, 'リンク(a)', shortSelector(aEl) || getUniqueSelector(aEl)) === false) return;
                }
            }

            var p = targetEl.parentElement;
            while (p && p.tagName !== 'BODY' && !(p.id && !String(p.id).match(/^[0-9]/))) p = p.parentElement;
            if (p && p.tagName !== 'BODY') {
                if (onCandidate(p, 'ID祖先', shortSelector(p) || getUniqueSelector(p)) === false) return;
            }

            var q = targetEl.parentElement;
            while (q && q.tagName !== 'BODY' && !(typeof q.className === 'string' && q.className.trim())) q = q.parentElement;
            if (q && q.tagName !== 'BODY') {
                if (onCandidate(q, 'Class祖先', shortSelector(q) || getUniqueSelector(q)) === false) return;
            }

            var sem = null;
            try { sem = targetEl.closest('article, main, [role="main"], section, .novel_honbun, #novel_honbun'); } catch(e){}
            if (sem && sem !== p && sem !== q && sem.tagName !== 'BODY') {
                if (onCandidate(sem, '意味コンテナ', shortSelector(sem) || sem.tagName.toLowerCase()) === false) return;
            }

            var n = targetEl.parentElement;
            var depth = 1;
            while (n && n.tagName !== 'BODY' && n.tagName !== 'HTML') {
                var tag = (n.tagName || '').toLowerCase();
                var label = (depth === 1) ? '親要素' : '祖先要素(' + tag + ')';
                if (onCandidate(n, label, shortSelector(n) || getUniqueSelector(n)) === false) break;
                n = n.parentElement;
                depth++;
            }
        }
    """.trimIndent()

    // --- Shadow DOM pierce ヘルパ（open専用。closedはnullのため対象外） ---
    // 技術的根拠1行：取得系がdocument.*直叩き・対象が別ツリーshadowRootという責務不一致をpierce層で吸収する。
    // 注意: cloneNodeではshadowが複写されない（既定clonable:false）ためホストclone流用を避けshadow実体で合成する。
    private val JS_SHADOW_PIERCE = """
        function hasPierce(sel) { return !!sel && String(sel).indexOf('>>>') >= 0; }
        function splitPierce(sel) {
            return String(sel).split('>>>').map(function(s){ return s.trim(); }).filter(function(s){ return !!s; });
        }
        function queryPierceAll(sel, root) {
            if (!sel) return [];
            var startRoot = root || document;
            if (String(sel).indexOf('>>>') < 0) {
                try { return Array.from(startRoot.querySelectorAll(sel)); } catch(e){ return []; }
            }
            var parts = String(sel).split('>>>').map(function(s){ return s.trim(); }).filter(Boolean);
            if (!parts.length) return [];
            var currents = [startRoot];
            for (var i = 0; i < parts.length; i++) {
                var part = parts[i];
                var isLast = (i === parts.length - 1);
                var nextEls = [];
                for (var c = 0; c < currents.length; c++) {
                    var base = currents[c];
                    var found = [];
                    try { found = Array.from(base.querySelectorAll(part)); } catch(e){ continue; }
                    if (isLast) {
                        for (var k = 0; k < found.length; k++) nextEls.push(found[k]);
                    } else {
                        for (var k2 = 0; k2 < found.length; k2++) {
                            try { if (found[k2].shadowRoot) nextEls.push(found[k2].shadowRoot); } catch(e){}
                        }
                    }
                }
                if (isLast) return nextEls;
                currents = nextEls;
                if (!currents.length) return [];
            }
            return [];
        }
        function queryPierceFirst(sel, root) {
            try { var a = queryPierceAll(sel, root); return a.length ? a[0] : null; } catch(e){ return null; }
        }
        function qsFirst(sel) {
            if (!sel) return null;
            try {
                if (String(sel).indexOf('>>>') >= 0) return queryPierceFirst(sel);
                return document.querySelector(sel);
            } catch(e){ return null; }
        }
        function qsAll(sel) {
            if (!sel) return [];
            try {
                if (String(sel).indexOf('>>>') >= 0) return queryPierceAll(sel);
                return Array.from(document.querySelectorAll(sel));
            } catch(e){ return []; }
        }
        function isUniqueInRoot(sel, root) {
            try { return root.querySelectorAll(sel).length === 1; } catch(e){ return false; }
        }
        function getInnerSelector(el, root) {
            if (!el || el.nodeType !== 1 || !root) return '';
            var path = [];
            var cur = el;
            var guard = 0;
            while (cur && cur.nodeType === 1 && cur !== root && guard < 6) {
                guard++;
                var tag = (cur.tagName || '').toLowerCase() || '*';
                var s = null;
                try { s = shortSelector(cur); } catch(e){}
                if (s) {
                    try { if (root.querySelectorAll(s).length === 1) { path.unshift(s); break; } } catch(e){}
                }
                var sib = cur;
                var nth = 1;
                while (sib = sib.previousElementSibling) {
                    try { if ((sib.tagName || '').toLowerCase() === tag) nth++; } catch(e){}
                }
                path.unshift(tag + (nth > 1 ? ':nth-of-type(' + nth + ')' : ''));
                cur = cur.parentNode;
                if (!cur || cur === root) break;
            }
            return path.join(' > ');
        }
        function getPierceSelector(el) {
            if (!el || el.nodeType !== 1) return '';
            var parts = [];
            var cur = el;
            var guard2 = 0;
            while (cur && cur.nodeType === 1 && guard2 < 6) {
                guard2++;
                var root = null;
                try { root = cur.getRootNode ? cur.getRootNode() : null; } catch(e){}
                var inShadow = root && root.nodeType === 11 && root.host;
                if (inShadow) {
                    var inner = '';
                    try { inner = getInnerSelector(cur, root); } catch(e){}
                    if (!inner) inner = (cur.tagName || '').toLowerCase() || '*';
                    parts.unshift(inner);
                    cur = root.host;
                } else {
                    var hostSel = '';
                    try { hostSel = shortSelector(cur) || getUniqueSelector(cur); } catch(e){}
                    if (!hostSel) hostSel = (cur.tagName || '').toLowerCase() || '*';
                    parts.unshift(hostSel);
                    break;
                }
            }
            return parts.join(' >>> ');
        }
        function collectPierceTextNodes(maxNodes) {
            var out = [];
            var limit = maxNodes || 8000;
            function walkRoot(root) {
                if (!root || out.length >= limit) return;
                try {
                    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
                    var n = null;
                    while ((n = w.nextNode())) {
                        out.push(n);
                        if (out.length >= limit) return;
                    }
                } catch(e){}
                var els = null;
                try { els = root.querySelectorAll('*'); } catch(e){ return; }
                for (var i = 0; i < els.length; i++) {
                    if (out.length >= limit) return;
                    try { if (els[i].shadowRoot) walkRoot(els[i].shadowRoot); } catch(e){}
                }
            }
            try { walkRoot(document.documentElement); } catch(e){}
            return out;
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

                    // --- Regex パイプラインエンジン（>> 区切りで抽出・削除を自由に連結） ---
                    function applyRegexPipeline(text, rxSrc) {
                        if (!text || !rxSrc) return text;
                        var steps = rxSrc.split('>>');
                        var cur = text;
                        for (var si = 0; si < steps.length; si++) {
                            var step = steps[si].trim();
                            if (!step) continue;
                            try {
                                if (/^(?:del|delete|remove):/i.test(step)) {
                                    var pat = step.replace(/^(?:del|delete|remove):/i, '').trim();
                                    if (pat) cur = cur.replace(new RegExp(pat, 'g'), '');
                                } else {
                                    var m = cur.match(new RegExp(step));
                                    if (m) cur = (m[1] !== undefined && m[1] !== null) ? m[1] : m[0];
                                }
                            } catch(e){}
                        }
                        return cur;
                    }

                    // --- CSS セレクタ取得ユーティリティ（共通スニペット） ---
                    ${JS_UNIQUE_SELECTOR}
                    ${JS_SHORT_SELECTOR}
                    ${JS_SHADOW_PIERCE}


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
                        try { el = qsFirst(targetSel); } catch(e){}
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

                    // 作品名 Regex (config.regex) によるパイプライン加工
                    f = applyRegexPipeline(f, (config.regex || "").trim());
                    result.folderName = clean(f);

                    var t = config.title ? qsFirst(config.title) : null;
                    if (!t) t = document.querySelector('.novel_subtitle, .ep-title, .episode-title, .chapter-title, h1, h2');
                    result.title = t ? clean(t.innerText || t.textContent || "") : "";
                    if (!result.title) result.title = meta.headline || clean(document.title);

                    // タイトル Regex (config.fileRegex) によるパイプライン加工
                    result.title = clean(applyRegexPipeline(result.title, (config.fileRegex || "").trim()));

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
                            try { el = qsFirst(actualSelector); } catch(e){}
                            var text = el ? (el.innerText || el.textContent || "") : "";
                            if (text) {
                                if (config.chapterRegex) {
                                    var cr = applyRegexPipeline(text, (config.chapterRegex || "").trim());
                                    if (cr && cr !== text) c = cr;
                                }
                                if (!c) { var m = text.match(/(\d+)/); if(m) c = m[1]; }
                            }
                        }
                    }
                    result.chapter = c;

                    // --- プロ仕様：小説整形エンジン（字下げ正規化） ---
                    // Shadow DOM（open）対応：>>> 記法は shadowRoot 経由で解決する。cloneNodeではshadowが落ちるため実体合成する。
                    var bodySel = (config.body || "").trim();
                    var b = null;
                    if (bodySel && bodySel.indexOf('>>>') >= 0) {
                        var pierceBodyEls = [];
                        try { pierceBodyEls = queryPierceAll(bodySel); } catch(e){ pierceBodyEls = []; }
                        if (pierceBodyEls.length) {
                            var synth = document.createElement('div');
                            for (var pi = 0; pi < pierceBodyEls.length; pi++) {
                                try { synth.appendChild(pierceBodyEls[pi].cloneNode(true)); } catch(e){}
                            }
                            b = synth;
                        }
                    } else {
                        try { b = bodySel ? document.querySelector(bodySel) : null; } catch(e){ b = null; }
                        if (b && b.shadowRoot) {
                            var shadowKids = [];
                            try { shadowKids = Array.from(b.shadowRoot.querySelectorAll('p, div, h1, h2, h3, h4, li, figure')); } catch(e){}
                            var hostLen = 0;
                            try { hostLen = ((b.innerText || b.textContent) || "").trim().length; } catch(e){}
                            var shadowLen = 0;
                            try { shadowLen = (b.shadowRoot.textContent || "").length; } catch(e){}
                            if (shadowKids.length && (hostLen < 100 || shadowLen > hostLen)) {
                                var synth2 = document.createElement('div');
                                try {
                                    Array.from(b.shadowRoot.childNodes).forEach(function(n) {
                                        if (!n) return;
                                        if (n.nodeType === 1) {
                                            var tg = "";
                                            try { tg = (n.tagName || "").toUpperCase(); } catch(e){}
                                            if (tg === "STYLE" || tg === "SCRIPT" || tg === "TEMPLATE" || tg === "LINK") return;
                                            try { synth2.appendChild(n.cloneNode(true)); } catch(e){}
                                        } else if (n.nodeType === 3) {
                                            var tx = "";
                                            try { tx = n.textContent || ""; } catch(e){}
                                            if (tx && tx.trim()) { try { synth2.appendChild(document.createTextNode(tx)); } catch(e){} }
                                        }
                                    });
                                } catch(e){}
                                if (synth2.childNodes.length) b = synth2;
                            }
                        }
                    }
                    if (!b) {
                        var cand = Array.from(document.querySelectorAll('div, article, section, main')).map(el => ({ el: el, s: el.innerText.length + (el.querySelectorAll('p').length * 40) })).sort((a,b) => b.s - a.s);
                        if (cand[0] && cand[0].s > 100) b = cand[0].el;
                    }
                    if (b) {
                        var clone = b.cloneNode(true);
                        clone.querySelectorAll('script, style, noscript, iframe, template, .ad, .ads, [class*="advertisement"]').forEach(n => n.remove());
                        if (config.exclude) {
                            try {
                                config.exclude.split(',').forEach(function(s) {
                                    var sel = s.trim();
                                    if (sel) {
                                        if (sel.indexOf('>>>') >= 0) {
                                            var lastPart = sel.split('>>>').pop().trim();
                                            if (lastPart) {
                                                try { clone.querySelectorAll(lastPart).forEach(function(el) { el.remove(); }); } catch(e){}
                                            }
                                        } else {
                                            clone.querySelectorAll(sel).forEach(function(el) { el.remove(); });
                                        }
                                    }
                                });
                            } catch(e){}
                        }
                        if (!${useImages}) clone.querySelectorAll('img, picture, svg').forEach(n => n.remove());

                        // 1. 改行の確保（分離DOMでもtextContent併用で破綻防止）
                        clone.querySelectorAll('br').forEach(br => { try { br.after(document.createTextNode('\n')); } catch(e){} try { br.remove(); } catch(e){} });
                        clone.querySelectorAll('p, div, h1, h2, h3, h4, h5, h6, li, dt, dd, figure').forEach(function(el) {
                            var bt = "";
                            try { bt = (el.innerText || el.textContent || ""); } catch(e){}
                            if (bt.trim().length > 0) { try { el.after(document.createTextNode('\n')); } catch(e){} }
                        });

                        // 2. テキストの分解とプロ仕様の整形
                        var rawText = "";
                        try { rawText = clone.innerText || clone.textContent || ""; } catch(e){}
                        var lines = String(rawText).split('\n');
                        var formattedLines = [];
                        var debugLines = [];

                        var dbgTexts = [];
                        if (${isDebug}) {
                            try {
                                var dbgRoots = [b];
                                try { if (b.shadowRoot) dbgRoots.push(b.shadowRoot); } catch(e){}
                                try {
                                    Array.from(b.querySelectorAll('*')).forEach(function(h) {
                                        try { if (h.shadowRoot) dbgRoots.push(h.shadowRoot); } catch(e){}
                                    });
                                } catch(e){}
                                dbgRoots.forEach(function(r) {
                                    try {
                                        var dbgWalker = document.createTreeWalker(r, NodeFilter.SHOW_TEXT, null, false);
                                        var dbgNode = null;
                                        while ((dbgNode = dbgWalker.nextNode())) {
                                            dbgTexts.push({ t: dbgNode.textContent, el: dbgNode.parentElement });
                                        }
                                    } catch(e){}
                                });
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
                                            try {
                                                var candSel = getPierceSelector(dbgTexts[di].el);
                                                selector = candSel || getUniqueSelector(dbgTexts[di].el);
                                            } catch(e){
                                                selector = getUniqueSelector(dbgTexts[di].el);
                                            }
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
                        var nEl = config.next ? qsFirst(config.next) : document.querySelector('a[rel="next"]');
                        if (!nEl) {
                            var links = Array.from(document.querySelectorAll('a'));
                            try {
                                Array.from(document.querySelectorAll('*')).forEach(function(h) {
                                    if (h.shadowRoot) {
                                        try { Array.from(h.shadowRoot.querySelectorAll('a')).forEach(function(a) { links.push(a); }); } catch(e){}
                                    }
                                });
                            } catch(e){}
                            // タイポ修正: \u7d1a(級) ➔ \u7d9a(続く)。韓国語の次（\uB2E4\uC74C）も対象にする
                            var regex = /^(?:\u6b21|Next|\u7d9a\u304f|\uB2E4\uC74C|>>|\u300b|\u6b21\u3078|\u4e0b\u4e00\u7ae0|\u4e0b\u4e00\u9875)/i;
                            nEl = links.find(function(a) {
                                var at = "";
                                try { at = (a.innerText || a.textContent || "").trim(); } catch(e){}
                                return at.length < 15 && regex.test(at);
                            });
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
                ${JS_SHADOW_PIERCE}

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
                            try { qsAll(s).forEach(function(n){ n.style.opacity = ''; }); } catch(e){}
                        });
                    } catch(e){}
                }

                function applyCurrentConfig() {
                    clearInspectorMarks();
                    try { if (config.title) qsFirst(config.title).classList.add('__novel_mark_title'); } catch(e){}
                    try { if (config.body) { var bm = qsAll(config.body); bm.forEach(function(x){ try{ x.classList.add('__novel_mark_body'); }catch(e){} }); } } catch(e){}
                    try { if (config.next) qsFirst(config.next).classList.add('__novel_mark_next'); } catch(e){}
                    try {
                        if (config.exclude) {
                            config.exclude.split(',').forEach(function(s) {
                                var sel = s.trim();
                                if (sel) qsAll(sel).forEach(function(el){ el.style.opacity = '0.3'; });
                            });
                        }
                    } catch(e){}
                }

                function buildPreview(sel, target) {
                    var el = null;
                    try { el = qsFirst(sel); } catch(e) {}
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

                    // 本文/タイトル等: 先頭150字に加え全一致の合計字数・行数・件数を付記する
                    // 技術的根拠1行：pierce複数一致（host >>> p等）は先頭1件表示では量が判断できないため集計を分離表示する。
                    var all = [];
                    try { all = qsAll(sel); } catch(e) {}
                    if (all.length > 1) {
                        var totalChars = 0;
                        var totalLines = 0;
                        try {
                            all.forEach(function(x) {
                                var xt = ((x.innerText || x.textContent || '') + '');
                                totalChars += xt.replace(/\s+/g, '').length;
                                var parts = xt.split('\n');
                                var cnt = 0;
                                for (var li = 0; li < parts.length; li++) { if (parts[li].trim()) cnt++; }
                                totalLines += cnt || (xt.trim() ? 1 : 0);
                            });
                        } catch(e){}
                        var head = ((el.innerText || el.textContent || '') + '').trim();
                        if (head.length > 150) head = head.substring(0, 150) + '…';
                        return '(' + all.length + '件合計 約' + totalChars + '字/' + totalLines + '行) ' + (head || '(テキストなし)');
                    }
                    var t = ((el.innerText || el.textContent || '') + '').trim();
                    var chars = t.replace(/\s+/g, '').length;
                    var lns = 0;
                    try {
                        var ps = t.split('\n');
                        for (var k = 0; k < ps.length; k++) { if (ps[k].trim()) lns++; }
                    } catch(e){}
                    if (t.length > 150) t = t.substring(0, 150) + '…';
                    return '(約' + chars + '字/' + lns + '行) ' + (t || '(テキストなし)');
                }

                // 短縮セレクタ（共通スニペット）
                ${JS_SHORT_SELECTOR}
                ${JS_CANDIDATE_TRAVERSAL}

                function getCandidates(el) {
                    var cands = [];
                    var seen = {};
                    function pushCand(node, label, s) {
                        if (!s || seen[s]) return;
                        seen[s] = true;
                        var m = 0;
                        try { m = qsAll(s).length; } catch(e) {}
                        cands.push({ node: node, selector: s, label: label, matchCount: m });
                    }
                    var inShadow = false;
                    try {
                        var rt = el.getRootNode ? el.getRootNode() : null;
                        inShadow = !!(rt && rt.nodeType === 11 && rt.host);
                    } catch(e){}
                    if (inShadow) {
                        var pierceSel = "";
                        try { pierceSel = getPierceSelector(el); } catch(e){}
                        if (pierceSel) pushCand(el, 'Shadow内要素', pierceSel);
                        try {
                            var r = el.getRootNode();
                            var host = r.host;
                            var hostSel = "";
                            try { hostSel = shortSelector(host) || getUniqueSelector(host); } catch(e){}
                            if (hostSel) {
                                pushCand(host, 'Shadowホスト', hostSel);
                                var tag = (el.tagName || "").toLowerCase() || "*";
                                var generic = hostSel + " >>> " + tag;
                                if (generic !== pierceSel) pushCand(el, 'Shadow内汎用', generic);
                            }
                        } catch(e){}
                    }
                    traverseCandidates(el, function(node, label, s) {
                        pushCand(node, label, s);
                        if (cands.length >= 10) return false;
                    }, true);
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
                                    try { qsAll(c.selector).forEach(function(n){ n.style.opacity = ''; }); } catch(e){}
                                } else {
                                    var list = getExcludeList();
                                    if (list.indexOf(c.selector) < 0) list.push(c.selector);
                                    config.exclude = list.join(', ');
                                    try { window.AndroidBridge.onApplyCandidate('exclude', c.selector); } catch(e){}
                                    try { qsAll(c.selector).forEach(function(n){ n.style.opacity = '0.3'; }); } catch(e){}
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

                function resolveTapTarget(e) {
                    var t = null;
                    try {
                        if (e.composedPath && e.composedPath().length) t = e.composedPath()[0];
                    } catch(err){}
                    if (!t) { try { t = e.target; } catch(err2){} }
                    if (t && t.nodeType === 3) { try { t = t.parentElement || e.target; } catch(err3){} }
                    if (t && t.nodeType !== 1) { try { t = e.target; } catch(err4){} }
                    return t;
                }

                function handleClick(e) {
                    if (!active) return;
                    var tapEl = resolveTapTarget(e);
                    try {
                        if (tapEl && tapEl.closest && tapEl.closest('#__novel_popup')) return;
                    } catch(e){}
                    e.preventDefault();
                    e.stopPropagation();
                    var cands = getCandidates(tapEl);
                    if (!cands.length) return;
                    try { highlight(tapEl); } catch(e){}
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
                    ${JS_SHADOW_PIERCE}

                    // 短いセレクタが一意でない場合は一意セレクタにフォールバック（誤適用防止）
                    // Shadow内要素は host >>> inner 形式で返す
                    function pickSelector(el) {
                        try {
                            var rt = el.getRootNode ? el.getRootNode() : null;
                            if (rt && rt.nodeType === 11 && rt.host) {
                                try { return getPierceSelector(el); } catch(e2){}
                            }
                        } catch(e){}
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

                    // 3. セレクタ直接指定クエリとしての判定（, () + を含む複合セレクタ対応・>>> pierce対応）
                    try {
                        if (/^[a-zA-Z0-9_\-\.\#\>\s\[\]\:\=\*\^\~\$\|\"\'\,\(\)\+]+$/.test(query)) {
                            var directEls = [];
                            try { directEls = queryPierceAll(query); } catch(e){ try { directEls = Array.from(document.querySelectorAll(query)); } catch(e2){} }
                            if (directEls.length > 0) {
                                var firstEl = directEls[0];
                                var firstText = (firstEl.innerText || firstEl.textContent || '').trim().replace(/\s+/g, ' ');
                                var pText = (firstText.length > 150) ? firstText.substring(0, 150) + '…' : (firstText || '(空要素)');
                                addCandidate(firstEl, query, 'セレクタ直接指定 (' + directEls.length + '件該当)', pText);
                            }
                        }
                    } catch(e){}

                    // 4. 全DOMツリー＋open Shadow内テキストノード探索（TreeWalkerは境界を越えないため再帰走査）
                    var allTextNodes = [];
                    try { allTextNodes = collectPierceTextNodes(8000); } catch(e){}
                    if (!allTextNodes.length) {
                        try {
                            var fallbackWalker = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_TEXT, null, false);
                            var fb = null;
                            while ((fb = fallbackWalker.nextNode())) allTextNodes.push(fb);
                        } catch(e){}
                    }
                    for (var ni = 0; ni < allTextNodes.length; ni++) {
                        var node = allTextNodes[ni];
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
                                    var inShadow = false;
                                    try {
                                        var prt = p.getRootNode ? p.getRootNode() : null;
                                        inShadow = !!(prt && prt.nodeType === 11 && prt.host);
                                    } catch(e){}
                                    addCandidate(p, pSel, pTag + ' 要素' + (inShadow ? ' (Shadow内)' : ''));
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
                    var baseEl = null;
                    try { baseEl = document.querySelector('$safeSelector'); } catch(e){}
                    if (!baseEl) { try { baseEl = queryPierceFirst('$safeSelector'); } catch(e2){} }
                    if (!baseEl) return '[]';
                    ${JS_UNIQUE_SELECTOR}
                    ${JS_SHORT_SELECTOR}
                    ${JS_SHADOW_PIERCE}
                    ${JS_CANDIDATE_TRAVERSAL}
                    var cands = [];
                    var seen = {};
                    try {
                        var brt = baseEl.getRootNode ? baseEl.getRootNode() : null;
                        if (brt && brt.nodeType === 11 && brt.host) {
                            var ps = "";
                            try { ps = getPierceSelector(baseEl); } catch(e){}
                            if (ps && !seen[ps]) {
                                seen[ps] = true;
                                var pc = 0, pch = 0, pln = 0, ppv = '(テキストなし)';
                                try {
                                    pc = qsAll(ps).length;
                                    var pf = qsFirst(ps);
                                    if (pf) {
                                        var pt = ((pf.innerText || pf.textContent || '') + '').replace(/[ \t]+/g, ' ');
                                        pln = pt.split('\n').filter(function(x){ return x.trim(); }).length;
                                        pch = pt.replace(/\s+/g, '').length;
                                        var pp = pt.trim().replace(/\s+/g, ' ');
                                        ppv = (pp.length > 120) ? pp.substring(0, 120) + '…' : (pp || '(テキストなし)');
                                    }
                                } catch(e){}
                                cands.push({ label: 'Shadow内要素', selector: ps, metric: pc + '件・約' + pch + '字/' + pln + '行', preview: ppv });
                            }
                        }
                    } catch(e){}
                    traverseCandidates(baseEl, function(node, label, s) {
                        if (!s || seen[s]) return;
                        seen[s] = true;
                        var count = 0, chars = 0, lines = 0, preview = '(テキストなし)';
                        try {
                            count = qsAll(s).length;
                            var first = qsFirst(s);
                            if (first) {
                                var t = ((first.innerText || '') + '').replace(/[ \t]+/g, ' ');
                                lines = t.split('\n').filter(function(x){ return x.trim(); }).length;
                                chars = t.replace(/\s+/g, '').length;
                                var p = t.trim().replace(/\s+/g, ' ');
                                preview = (p.length > 120) ? p.substring(0, 120) + '…' : (p || '(テキストなし)');
                            }
                        } catch(e){}
                        cands.push({ label: label, selector: s, metric: count + '件・約' + chars + '字/' + lines + '行', preview: preview });
                        if (cands.length >= 10) return false;
                    }, false);
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