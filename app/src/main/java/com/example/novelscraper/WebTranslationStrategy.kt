package com.example.novelscraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 翻訳DOM解析結果
 */
@Serializable
data class DomTranslationResult(
    val status: String = "",
    val text: String = ""
)

/**
 * Web翻訳エンジンの動作戦略（Strategy パターン）。
 * Google 翻訳 / DeepL 翻訳 / Papago 翻訳固有の DOM セレクタ、URL、文字数制限、JS テンプレートをカプセル化する。
 */
interface WebTranslationStrategy {
    val engineName: String
    val outputFolderName: String
    val maxChunkSize: Int
    val defaultChunkDelaySec: Double
    val defaultFileDelaySec: Double
    val isDesktop: Boolean
    val pageLoadTimeoutMs: Long
    val domReadyTimeoutMs: Long
    val resultWaitTimeoutMs: Long
    val resultCheckIntervalMs: Long
    val requiredStableCount: Int

    fun buildTargetUrl(sourceLang: String, targetLang: String): String
    fun isTargetPageUrl(url: String?): Boolean

    val checkDomReadyJs: String
    val clearInputJs: String
    val checkResultEmptyJs: String
    val focusAndSelectJs: String
    fun buildPasteAndInputJs(jsonEncodedText: String): String
    val getResultJs: String
    fun postInputDelayRange(): LongRange
}

/**
 * Google 翻訳戦略。
 * 【重要・ルール7死守】手動操作再現シーケンス (focus → select → paste → input:insertFromPaste → change) を完全不変で維持。
 */
class GoogleTranslationStrategy : WebTranslationStrategy {
    override val engineName: String = "Google"
    override val outputFolderName: String = TranslationFileStore.GOOGLE_OUTPUT_FOLDER
    override val maxChunkSize: Int = 3500
    override val defaultChunkDelaySec: Double = 1.5
    override val defaultFileDelaySec: Double = 1.0
    override val isDesktop: Boolean = false
    override val pageLoadTimeoutMs: Long = 25000L
    override val domReadyTimeoutMs: Long = 20000L
    override val resultWaitTimeoutMs: Long = 25000L
    override val resultCheckIntervalMs: Long = 600L
    // 長文の非同期追記タイムラグを自然に吸収し文章切り落としを防ぐため、5回連続（約3.0秒）安定で確定
    override val requiredStableCount: Int = 5

    override fun buildTargetUrl(sourceLang: String, targetLang: String): String =
        "https://translate.google.com/?sl=$sourceLang&tl=$targetLang&op=translate"

    override fun isTargetPageUrl(url: String?): Boolean =
        url?.contains("translate.google.com") == true

    override val checkDomReadyJs: String =
        """(function(){var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');return ta?"READY":"WAIT"})()"""

    override val clearInputJs: String =
        """(function(){try{var b=document.querySelector('button[aria-label*="Clear"],button[aria-label*="消去"],button[aria-label*="クリア"],button[jsname="RPbQ0e"]');if(b)b.click();var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');if(ta){ta.value='';ta.dispatchEvent(new Event('input',{bubbles:true}));ta.dispatchEvent(new Event('change',{bubbles:true}))}return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override val checkResultEmptyJs: String =
        """(function(){var s=document.querySelectorAll('span[jsname="W297wb"],span[jsname="jqKxS"],div[data-result-index] span,span[data-language-to-translate-into]');if(!s||s.length===0)return"EMPTY";var t="";for(var i=0;i<s.length;i++)t+=(s[i].innerText||s[i].textContent||'');return t.trim().length===0?"EMPTY":"NOT_EMPTY"})()"""

    override val focusAndSelectJs: String =
        """(function(){try{var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');if(!ta)return"NO_TEXTAREA";ta.focus();ta.select();return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    /**
     * ルール7厳守: ClipboardEvent('paste') → InputEvent('input', {inputType: 'insertFromPaste'}) → change
     */
    override fun buildPasteAndInputJs(jsonEncodedText: String): String =
        """(function(){try{var ta=document.querySelector('textarea[aria-label]')||document.querySelector('textarea');if(!ta)return"NO_TEXTAREA";var dt=new DataTransfer();dt.setData('text/plain',$jsonEncodedText);var pe=new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt});ta.dispatchEvent(pe);if(!ta.value||ta.value.trim().length===0)ta.value=$jsonEncodedText;ta.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:$jsonEncodedText}));ta.dispatchEvent(new Event('change',{bubbles:true}));return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override val getResultJs: String =
        """(function(){try{var pb=document.querySelectorAll('div[role="progressbar"],div[aria-valuemin]');for(var i=0;i<pb.length;i++){var s=window.getComputedStyle(pb[i]);if(s.display!=='none'&&s.visibility!=='hidden'&&pb[i].offsetParent!==null)return JSON.stringify({status:"TRANSLATING",text:""})}var sp=document.querySelectorAll('span[jsname="W297wb"],span[jsname="jqKxS"]');if(!sp||sp.length===0){sp=document.querySelectorAll('div[data-result-index] span,span[data-language-to-translate-into],div[role="region"] span,div[aria-live="polite"] span')}if(sp&&sp.length>0){var tp=[];for(var i=0;i<sp.length;i++){var t=sp[i].innerText||sp[i].textContent||'';if(t&&t.indexOf("翻訳結果を利用できます")===-1&&t.indexOf("Translation result")===-1&&t.indexOf("翻訳中")===-1)tp.push(t)}var c=tp.join('');if(c.trim().length>0)return JSON.stringify({status:"OK",text:c})}return JSON.stringify({status:"WAITING",text:""})}catch(e){return JSON.stringify({status:"ERROR",text:""})}})()"""

    override fun postInputDelayRange(): LongRange = 1800L..2400L
}

/**
 * DeepL 翻訳戦略。
 * PC版サイト表示（正規Android Chrome PC版UA）で確実にWebUIをロードし、手動操作を模倣する。
 */
class DeeplTranslationStrategy : WebTranslationStrategy {
    override val engineName: String = "DeepL"
    override val outputFolderName: String = TranslationFileStore.DEEPL_OUTPUT_FOLDER
    override val maxChunkSize: Int = 1300
    override val defaultChunkDelaySec: Double = 3.0
    override val defaultFileDelaySec: Double = 2.0
    override val isDesktop: Boolean = true
    override val pageLoadTimeoutMs: Long = 30000L
    override val domReadyTimeoutMs: Long = 25000L
    override val resultWaitTimeoutMs: Long = 28000L
    override val resultCheckIntervalMs: Long = 700L
    override val requiredStableCount: Int = 3 // 3回連続（約2.1秒）安定で確定

    override fun buildTargetUrl(sourceLang: String, targetLang: String): String =
        "https://www.deepl.com/ja/translator#$sourceLang/$targetLang/"

    override fun isTargetPageUrl(url: String?): Boolean =
        url?.contains("deepl.com") == true

    override val checkDomReadyJs: String =
        """(function(){var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea[aria-label*="Source"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');return el?"READY":"WAIT"})()"""

    override val clearInputJs: String =
        """(function(){try{var clearBtn=document.querySelector('button[data-testid="translator-source-clear-button"]')||document.querySelector('button[aria-label*="Clear"]')||document.querySelector('button[aria-label*="消去"]')||document.querySelector('button[aria-label*="クリア"]');if(clearBtn)clearBtn.click();var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');if(el){if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){el.value='';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}else{var t=el.querySelector('[contenteditable="true"]')||el;t.innerText='';t.innerHTML='<p><br></p>';t.dispatchEvent(new Event('input',{bubbles:true}));t.dispatchEvent(new Event('change',{bubbles:true}))}}return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override val checkResultEmptyJs: String =
        """(function(){var targetEl=document.querySelector('div[data-testid="translator-target-input"]')||document.querySelector('d-textarea[name="target"]')||document.querySelector('section[aria-label*="Translation"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Translation"]')||document.querySelector('div[aria-label*="訳文"]')||document.querySelector('#target-dummydiv');if(!targetEl)return"EMPTY";var text=targetEl.innerText||targetEl.textContent||'';return text.trim().length===0?"EMPTY":"NOT_EMPTY"})()"""

    override val focusAndSelectJs: String =
        """(function(){try{var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');if(!el)return"NO_INPUT_ELEMENT";var targetInput=(el.getAttribute('contenteditable')==='true')?el:(el.querySelector('[contenteditable="true"]')||el);targetInput.focus();if(targetInput.tagName==='TEXTAREA'||targetInput.tagName==='INPUT'){targetInput.select()}else{var range=document.createRange();range.selectNodeContents(targetInput);var sel=window.getSelection();sel.removeAllRanges();sel.addRange(range)}return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override fun buildPasteAndInputJs(jsonEncodedText: String): String =
        """(function(){try{var el=document.querySelector('div[data-testid="translator-source-input"]')||document.querySelector('d-textarea[name="source"]')||document.querySelector('section[aria-label*="Source"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Source text"]')||document.querySelector('div[aria-label*="原文"]')||document.querySelector('textarea')||document.querySelector('div[contenteditable="true"]');if(!el)return"NO_INPUT_ELEMENT";var targetInput=(el.getAttribute('contenteditable')==='true')?el:(el.querySelector('[contenteditable="true"]')||el);var text=$jsonEncodedText;var dt=new DataTransfer();dt.setData('text/plain',text);var pe=new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt});targetInput.dispatchEvent(pe);var currentVal=(targetInput.tagName==='TEXTAREA'||targetInput.tagName==='INPUT')?targetInput.value:targetInput.innerText;if(!currentVal||currentVal.trim().length===0){if(targetInput.tagName==='TEXTAREA'||targetInput.tagName==='INPUT'){targetInput.value=text}else{targetInput.innerText=text}}targetInput.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:text}));targetInput.dispatchEvent(new Event('change',{bubbles:true}));return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override val getResultJs: String =
        """(function(){try{var loaders=document.querySelectorAll('div[role="progressbar"],div[class*="loading"],div[class*="spinner"],svg[class*="spin"]');for(var i=0;i<loaders.length;i++){var s=window.getComputedStyle(loaders[i]);if(s.display!=='none'&&s.visibility!=='hidden'&&loaders[i].offsetParent!==null){return JSON.stringify({status:"TRANSLATING",text:""})}}var targetEl=document.querySelector('div[data-testid="translator-target-input"]')||document.querySelector('d-textarea[name="target"]')||document.querySelector('section[aria-label*="Translation"] [contenteditable="true"]')||document.querySelector('div[aria-label*="Translation"]')||document.querySelector('div[aria-label*="訳文"]')||document.querySelector('#target-dummydiv');if(targetEl){var tt=(targetEl.innerText||targetEl.textContent||'').trim();if(tt.length>0&&tt!=="翻訳"&&tt!=="Translation"&&tt.indexOf("翻訳中")===-1){return JSON.stringify({status:"OK",text:tt})}}return JSON.stringify({status:"WAITING",text:""})}catch(e){return JSON.stringify({status:"ERROR",text:""})}})()"""

    override fun postInputDelayRange(): LongRange = 2200L..2800L
}

/**
 * Naver Papago 翻訳戦略。
 * 最新Web UI（Lexical エディタ）の段落・空行直接パースエンジンを搭載。
 */
class PapagoTranslationStrategy : WebTranslationStrategy {
    override val engineName: String = "Papago"
    override val outputFolderName: String = TranslationFileStore.PAPAGO_OUTPUT_FOLDER
    override val maxChunkSize: Int = 1800
    override val defaultChunkDelaySec: Double = 3.0
    override val defaultFileDelaySec: Double = 2.0
    override val isDesktop: Boolean = true
    override val pageLoadTimeoutMs: Long = 30000L
    override val domReadyTimeoutMs: Long = 25000L
    override val resultWaitTimeoutMs: Long = 28000L
    override val resultCheckIntervalMs: Long = 700L
    override val requiredStableCount: Int = 3 // 3回連続（約2.1秒）安定で確定

    override fun buildTargetUrl(sourceLang: String, targetLang: String): String =
        "https://papago.naver.com/?sk=$sourceLang&tk=$targetLang"

    override fun isTargetPageUrl(url: String?): Boolean =
        url?.contains("papago.naver.com") == true

    override val checkDomReadyJs: String =
        """(function(){var el=document.querySelector('div[data-testid="source-editor"]')||document.querySelector('div[contenteditable="true"]');return el?"READY":"WAIT"})()"""

    override val clearInputJs: String =
        """(function(){try{var clearBtn=document.querySelector('button[class*="btn-close"]')||document.querySelector('button[aria-label*="닫기"]')||document.querySelector('button[aria-label*="クリア"]')||document.querySelector('button[aria-label*="消去"]');if(clearBtn)clearBtn.click();var el=document.querySelector('div[data-testid="source-editor"]')||document.querySelector('div[contenteditable="true"]');if(el){el.focus();el.innerHTML='<p><br></p>';el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));el.dispatchEvent(new Event('change',{bubbles:true}))}return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override val checkResultEmptyJs: String =
        """(function(){var targetEl=document.querySelector('div[data-testid="target-editor"]')||document.querySelector('div[contenteditable="false"]');if(!targetEl)return"EMPTY";var text=targetEl.innerText||targetEl.textContent||'';return text.trim().length===0?"EMPTY":"NOT_EMPTY"})()"""

    override val focusAndSelectJs: String =
        """(function(){try{var el=document.querySelector('div[data-testid="source-editor"]')||document.querySelector('div[contenteditable="true"]');if(!el)return"NO_INPUT_ELEMENT";el.focus();var range=document.createRange();range.selectNodeContents(el);var sel=window.getSelection();sel.removeAllRanges();sel.addRange(range);return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    override fun buildPasteAndInputJs(jsonEncodedText: String): String =
        """(function(){try{var el=document.querySelector('div[data-testid="source-editor"]')||document.querySelector('div[contenteditable="true"]');if(!el)return"NO_INPUT_ELEMENT";el.focus();var range=document.createRange();range.selectNodeContents(el);var sel=window.getSelection();sel.removeAllRanges();sel.addRange(range);var text=$jsonEncodedText;var dt=new DataTransfer();dt.setData('text/plain',text);var pe=new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:dt});el.dispatchEvent(pe);var currentVal=el.innerText||'';if(!currentVal||currentVal.trim().length===0){el.innerText=text}el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertFromPaste',data:text}));el.dispatchEvent(new Event('change',{bubbles:true}));return"OK"}catch(e){return"ERROR: "+e.message}})()"""

    /**
     * Lexical段落直接走査エンジン。
     * <p><br></p>（空行）を単一の空行として忠実に復元し、多重改行増殖（\n\n\n\n）を完全根絶する。
     */
    override val getResultJs: String =
        """(function(){try{var loaders=document.querySelectorAll('div[role="progressbar"],div[class*="loading"],div[class*="spinner"],svg[class*="spin"]');for(var i=0;i<loaders.length;i++){var s=window.getComputedStyle(loaders[i]);if(s.display!=='none'&&s.visibility!=='hidden'&&loaders[i].offsetParent!==null){return JSON.stringify({status:"TRANSLATING",text:""})}}var targetEl=document.querySelector('div[data-testid="target-editor"]')||document.querySelector('div[contenteditable="false"]');if(targetEl){var ps=targetEl.querySelectorAll('p');if(ps&&ps.length>0){var lines=[];for(var p=0;p<ps.length;p++){var curP=ps[p];if(curP.children.length===1&&curP.children[0].tagName==='BR'){lines.push("")}else{var pt=(curP.innerText||curP.textContent||'').replace(/\r?\n/g,'');lines.push(pt)}}var joined=lines.join('\n');if(joined.trim().length>0&&joined!=="翻訳中"&&joined!=="Translating"&&joined!=="内容を入力してください。"){return JSON.stringify({status:"OK",text:joined})}}var raw=(targetEl.innerText||targetEl.textContent||'').trim();if(raw.length>0&&raw!=="翻訳中"&&raw!=="Translating"&&raw!=="内容を入力してください。"){return JSON.stringify({status:"OK",text:raw})}}return JSON.stringify({status:"WAITING",text:""})}catch(e){return JSON.stringify({status:"ERROR",text:""})}})()"""

    override fun postInputDelayRange(): LongRange = 2000L..2600L
}