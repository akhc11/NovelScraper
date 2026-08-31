package com.example.novelscraper

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Base64

object ScrapingScriptBuilder {

    // --- JS共通スニペット（3スクリプト間の重複排除。仕様変更はここ1箇所のみ） ---
    // 注意: Kotlin raw文字列のため `\s` 等はJSへそのまま渡る（二重エスケープ禁止）
    private val JS_UNIQUE_SELECTOR = """
        function getUniqueSelector(el) {
            if (!el || el.nodeType !== 1) return '';
            var path = [];
            while (el && el.nodeType === 1) {
                var sel = el.nodeName.toLowerCase();
                if (el.id && !String(el.id).match(/^[0-9]/)) {
                    sel += '#' + el.id;
                    path.unshift(sel);
                    break;
                } else {
                    var sib = el, nth = 1;
                    while (sib = sib.previousElementSibling) {
                        if (sib.nodeName.toLowerCase() === el.nodeName.toLowerCase()) nth++;
                    }
                    if (nth !== 1) sel += ':nth-of-type(' + nth + ')';
                }
                path.unshift(sel);
                el = el.parentNode;
                if (path.length > 5 || (el && el.tagName === 'BODY')) break;
            }
            return path.join(' > ');
        }
    """.trimIndent()

    private val JS_SHORT_SELECTOR = """
        function shortSelector(node) {
            var tag = node.tagName.toLowerCase();
            if (node.id && !String(node.id).match(/^[0-9]/)) return tag + '#' + node.id;
            if (typeof node.className === 'string' && node.className.trim()) {
                var cls = node.className.trim().split(/\s+/)[0];
                if (cls) return tag + '.' + cls;
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
                    // 前回実行で付与した除外マークを必ず剥離してから現configを適用する
                    // （設定から除外セレクタを削除した際に古いマークが残留するバグの根因修正）
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

                        // デバッグ用テキストノードを1回だけ事前収集（行ごとのTreeWalker再走査を排除）。
                        // 行→セレクタの意味論は変更しない（文書順で最初に一致したテキストノードの親）
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
                                // 元の要素のセレクタを探す（事前収集配列を文書順に走査し最初の一致を採用）
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
                            .replace(/\n{3,}/g, '\n\n') // 連続空行を整理
                            .trim();
                        if (${isDebug}) result.debugLines = debugLines;
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
     * インスペクター v3（候補ポップアップ方式・即反映）
     * - WebView内ツールバー廃止。終了は虫眼鏡トグル/戻る（buildInspectorStopScript）
     * - タップ → 候補3種（要素自身/近い祖先/上位祖先）＋プレビュー → 割当先チップで即適用
     * - 割当先: body/title/next/folder/exclude（SelectorField と対応）
     */
    fun buildInspectorScript(config: ScraperConfig): String {
        val configJson = Json.encodeToString(config)
        val configBase64 = Base64.getEncoder().encodeToString(configJson.toByteArray(Charsets.UTF_8))
        return """
            (function(){
                if (window.__novelInspectActive) return;
                window.__novelInspectActive = true;
                var config = JSON.parse(decodeURIComponent(escape(atob('$configBase64'))));
                var active = true;
                var prevHighlight = null;
                var lastTarget = 'body';
                var popup = null;

                var TARGETS = [
                    { key: 'body',    label: '本文' },
                    { key: 'title',   label: 'タイトル' },
                    { key: 'next',    label: '次へ' },
                    { key: 'folder',  label: '作品名' },
                    { key: 'exclude', label: '除外' }
                ];

                var style = document.createElement('style');
                style.innerHTML = '.__novel_mark_title { outline: 3px solid #2196F3 !important; } .__novel_mark_body { outline: 3px solid #4CAF50 !important; } .__novel_mark_next { outline: 3px solid #FF9800 !important; }';
                document.head.appendChild(style);

                var hint = document.createElement('div');
                hint.id = '__novel_hint';
                hint.innerText = '要素をタップして候補から設定';
                hint.style = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.85);color:#fff;padding:6px 14px;border-radius:16px;z-index:2147483647;font-size:12px;';
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

                function applyCurrentConfig() {
                    document.querySelectorAll('.__novel_mark_title, .__novel_mark_body, .__novel_mark_next').forEach(function(el){
                        el.style.outline = '';
                        el.classList.remove('__novel_mark_title', '__novel_mark_body', '__novel_mark_next');
                    });
                    try {
                        if (config.title) document.querySelector(config.title).classList.add('__novel_mark_title');
                        if (config.body) document.querySelector(config.body).classList.add('__novel_mark_body');
                        if (config.next) document.querySelector(config.next).classList.add('__novel_mark_next');
                        if (config.exclude) {
                            config.exclude.split(',').forEach(function(s) {
                                var sel = s.trim();
                                if (sel) document.querySelectorAll(sel).forEach(function(el){ el.style.opacity = '0.3'; });
                            });
                        }
                    } catch(e){}
                }

                function getExcludeList() {
                    return config.exclude ? config.exclude.split(',').map(function(s){ return s.trim(); }).filter(Boolean) : [];
                }
                function isExcluded(sel) { return getExcludeList().indexOf(sel) > -1; }

                function buildPreview(sel, target) {
                    var el = null;
                    try { el = document.querySelector(sel); } catch(e) {}
                    if (!el) return '(一致なし)';
                    if (target === 'next') {
                        var a = (el.tagName === 'A') ? el : (el.querySelector ? el.querySelector('a') : null);
                        if (!a && el.closest) a = el.closest('a');
                        var href = a ? (a.href || a.getAttribute('href') || '') : '';
                        if (href) { try { href = new URL(href, location.href).href; } catch(e){} }
                        return href || '(リンクなし)';
                    }
                    var t = ((el.innerText || el.textContent || '') + '').replace(/\s+/g, ' ').trim();
                    if (t.length > 100) t = t.substring(0, 100) + '…';
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
                    push(el, '要素自身', getUniqueSelector(el));
                    var p = el.parentElement;
                    while (p && p.tagName !== 'BODY' && !(p.id && !String(p.id).match(/^[0-9]/))) p = p.parentElement;
                    if (p && p.tagName !== 'BODY') push(p, 'ID祖先');
                    var q = el.parentElement;
                    while (q && q.tagName !== 'BODY' && !(typeof q.className === 'string' && q.className.trim())) q = q.parentElement;
                    if (q && q.tagName !== 'BODY') push(q, 'Class祖先');
                    var sem = null;
                    try { sem = el.closest('article, main, [role="main"], section'); } catch(e){}
                    if (sem && sem !== p && sem !== q && sem.tagName !== 'BODY') {
                        push(sem, '意味コンテナ', sem.tagName.toLowerCase());
                    }
                    // フォールバック: id/class/semantic が見つからない構造では親要素連鎖で補完
                    if (cands.length < 3) {
                        var n = el.parentElement;
                        while (n && n.tagName !== 'BODY' && cands.length < 3) {
                            push(n, '親要素');
                            n = n.parentElement;
                        }
                    }
                    return cands.slice(0, 4);
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
                    popup.style = 'position:fixed;background:#222;color:#fff;z-index:2147483646;font-size:12px;border-radius:6px;box-shadow:0 3px 10px rgba(0,0,0,0.6);max-width:360px;width:min(92vw,360px);max-height:70vh;display:flex;flex-direction:column;overflow:hidden;';

                    var header = document.createElement('div');
                    header.style = 'display:flex;align-items:center;padding:5px 8px;background:#333;flex-shrink:0;';
                    var title = document.createElement('span');
                    title.innerText = '候補を選択して適用';
                    title.style = 'flex:1;color:#ddd;';
                    var closeBtn = document.createElement('button');
                    closeBtn.innerText = '×';
                    closeBtn.style = 'background:#f44336;color:#fff;border:none;border-radius:3px;padding:2px 8px;cursor:pointer;';
                    closeBtn.onclick = function(ev) { ev.stopPropagation(); closePopup(); };
                    header.appendChild(title);
                    header.appendChild(closeBtn);
                    popup.appendChild(header);

                    var chips = document.createElement('div');
                    chips.style = 'display:flex;flex-wrap:wrap;gap:4px;padding:5px 8px;background:#2a2a2a;flex-shrink:0;';
                    var chipBtns = {};
                    TARGETS.forEach(function(t) {
                        var b = document.createElement('button');
                        b.innerText = t.label;
                        b.style = 'background:#555;color:#fff;border:none;border-radius:3px;padding:3px 7px;cursor:pointer;';
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
                            chipBtns[t.key].style.background = (pendingTarget === t.key) ? '#FF5722' : '#555';
                        });
                    }
                    updateChips();
                    popup.appendChild(chips);

                    var rowsBox = document.createElement('div');
                    rowsBox.style = 'overflow-y:auto;min-height:0;';
                    popup.appendChild(rowsBox);

                    function refreshRows() {
                        rowsBox.innerHTML = '';
                        cands.forEach(function(c) {
                            var removable = (pendingTarget === 'exclude') && isExcluded(c.selector);
                            var row = document.createElement('div');
                            row.style = 'padding:6px 8px;border-top:1px solid #444;cursor:pointer;';
                            var head = document.createElement('div');
                            head.style = 'display:flex;gap:6px;align-items:center;';
                            var lab = document.createElement('b');
                            lab.innerText = removable ? 'この除外を解除' : c.label;
                            lab.style = 'color:' + (removable ? '#4CAF50' : '#8ab4f8') + ';flex-shrink:0;';
                            var selSpan = document.createElement('span');
                            selSpan.innerText = c.selector;
                            selSpan.style = 'flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#aaa;';
                            var cnt = document.createElement('span');
                            if (pendingTarget === 'exclude') {
                                cnt.innerText = '一致' + c.matchCount;
                            } else if (pendingTarget === 'next') {
                                cnt.innerText = '';
                            } else {
                                var tEl = null;
                                try { tEl = document.querySelector(c.selector); } catch(e) {}
                                var txt = ((tEl && tEl.innerText) || '') + '';
                                var lineCount = txt ? txt.split('\n').filter(function(x){ return x.trim(); }).length : 0;
                                cnt.innerText = txt.replace(/\s+/g, '').length + '字/' + lineCount + '行';
                            }
                            cnt.style = 'flex-shrink:0;color:#888;';
                            head.appendChild(lab);
                            head.appendChild(selSpan);
                            head.appendChild(cnt);
                            var prev = document.createElement('div');
                            prev.innerText = buildPreview(c.selector, pendingTarget);
                            prev.style = 'margin-top:4px;color:#ddd;max-height:110px;overflow-y:auto;line-height:16px;padding:4px;background:#111;border-radius:4px;white-space:pre-wrap;word-break:break-all;';
                            row.appendChild(head);
                            row.appendChild(prev);
                            row.onclick = function(ev) {
                                ev.stopPropagation();
                                if (window.AndroidBridge) {
                                    if (pendingTarget === 'exclude') {
                                        if (removable) {
                                            config.exclude = getExcludeList().filter(function(s){ return s !== c.selector; }).join(', ');
                                            window.AndroidBridge.onRemoveExclude(c.selector);
                                            try { document.querySelectorAll(c.selector).forEach(function(n){ n.style.opacity = ''; }); } catch(e){}
                                        } else {
                                            var list = getExcludeList();
                                            if (list.indexOf(c.selector) < 0) list.push(c.selector);
                                            config.exclude = list.join(', ');
                                            window.AndroidBridge.onApplyCandidate('exclude', c.selector);
                                            try { document.querySelectorAll(c.selector).forEach(function(n){ n.style.opacity = '0.3'; }); } catch(e){}
                                        }
                                    } else {
                                        config[pendingTarget] = c.selector;
                                        window.AndroidBridge.onApplyCandidate(pendingTarget, c.selector);
                                        highlight(c.node);
                                    }
                                }
                                lastTarget = pendingTarget;
                                applyCurrentConfig();
                                closePopup();
                            };
                            rowsBox.appendChild(row);
                        });
                    }
                    refreshRows();

                    document.body.appendChild(popup);

                    var w = popup.offsetWidth || 320;
                    var h = popup.offsetHeight || 200;
                    var maxL = (window.innerWidth || 360) - w - 8;
                    var left = Math.min(Math.max(8, x - w / 2), Math.max(8, maxL));
                    var maxY = (window.innerHeight || 640) - h - 8;
                    var top = (y + 12 > maxY) ? Math.max(8, y - h - 12) : y + 12;
                    popup.style.left = left + 'px';
                    popup.style.top = top + 'px';
                }

                function handleClick(e) {
                    if (!active) return;
                    if (e.target.closest('#__novel_popup')) return;
                    e.preventDefault();
                    e.stopPropagation();
                    var cands = getCandidates(e.target);
                    if (!cands.length) return;
                    showCandidatePopup(cands, e.clientX || 0, e.clientY || 0);
                }

                document.addEventListener('click', handleClick, true);
                applyCurrentConfig();

                window.__novelInspector = {
                    stop: function() {
                        active = false;
                        document.removeEventListener('click', handleClick, true);
                        closePopup();
                        var h = document.getElementById('__novel_hint');
                        if (h && h.parentNode) h.parentNode.removeChild(h);
                        if (prevHighlight) prevHighlight.style.outline = '';
                        window.__novelInspectActive = false;
                    }
                };
            })();
        """.trimIndent()
    }

    /**
     * 除外候補プローブ: 指定セレクタの要素から4種類の候補（要素自身/ID祖先/Class祖先/意味コンテナ）を
     * JSON配列で返す（evaluateJavascript の戻り値として受領。WebViewへの注入不要）。
     * metric は除外用に「一致数・消える文字数」を含む。
     */
    fun buildCandidateProbeScript(baseSelector: String): String {
        val safeSelector = baseSelector.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        return """
            (function(){
                try {
                    var baseEl = document.querySelector('$safeSelector');
                if (!baseEl) return '[]';
                // 共通スニペット
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
                    // フォールバック: id/class/semantic が見つからない構造では親要素連鎖で補完
                    if (cands.length < 3) {
                        var n = baseEl.parentElement;
                        while (n && n.tagName !== 'BODY' && cands.length < 3) {
                            push(n, '親要素');
                            n = n.parentElement;
                        }
                    }
                    return JSON.stringify(cands.slice(0, 4));
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