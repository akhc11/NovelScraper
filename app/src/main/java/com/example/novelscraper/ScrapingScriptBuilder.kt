package com.example.novelscraper

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

object ScrapingScriptBuilder {

    fun buildScrapingScript(config: ScraperConfig, useImages: Boolean): String {
        val configJson = Json.encodeToString(config).replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function() {
                try {
                    if (document.title.includes("Just a moment") || document.body.innerText.includes("Verify you are human")) return "CF_DETECTED";
                    var config = JSON.parse('$configJson');
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
                    if (config.folder && !config.folder.startsWith('@')) {
                        var el = document.querySelector(config.folder);
                        if (el) f = el.innerText;
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

    fun buildErudaScript(): String {
        return """
            (function() {
                if (window.eruda) {
                    if (window.__eruda_visible) { eruda.hide(); window.__eruda_visible = false; }
                    else { eruda.show(); window.__eruda_visible = true; }
                    return;
                }
                var s = document.createElement('script');
                s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                document.body.appendChild(s);
                s.onload = function() {
                    eruda.init();
                    var st = document.createElement('style');
                    st.innerHTML = 'div[class*="eruda-entry-btn"] { display: none !important; }';
                    document.head.appendChild(st);
                    eruda.show();
                    window.__eruda_visible = true;
                };
            })();
        """.trimIndent()
    }

    fun buildInspectorScript(): String {
        return """
            (function(){
                var prev=null;
                function getCss(el){
                    if(!(el instanceof Element))return "";
                    var path=[];
                    while(el.nodeType===Node.ELEMENT_NODE){
                        var sel=el.nodeName.toLowerCase();
                        if(el.id&&!el.id.match(/^[0-9]/)){ sel+='#'+el.id; path.unshift(sel); break; }
                        else {
                            var cls=el.className;
                            if(typeof cls==='string'&&cls.trim()!==''){
                                var valid=cls.trim().split(/\s+/).filter(c=>!c.match(/[0-9:\[\]\.]/));
                                if(valid.length>0)sel+='.'+valid.join('.');
                            }
                            var sib=el,nth=1; while(sib=sib.previousElementSibling) if(sib.nodeName.toLowerCase()==el.nodeName.toLowerCase())nth++;
                            if(nth!=1)sel+=':nth-of-type('+nth+')';
                        }
                        path.unshift(sel); el=el.parentNode; if(path.length>3)break;
                    }
                    return path.join(' > ');
                }
                document.body.addEventListener('mouseover',function(e){
                    if(prev)prev.style.outline=''; e.target.style.outline='2px solid red'; prev=e.target;
                },true);
                document.body.addEventListener('click',function(e){
                    e.preventDefault(); e.stopPropagation();
                    var info={selector:getCss(e.target)||'?',text:e.target.innerText.substring(0,100)};
                    alert('INSPECT:'+JSON.stringify(info));
                },true);
            })();
        """.trimIndent()
    }
}