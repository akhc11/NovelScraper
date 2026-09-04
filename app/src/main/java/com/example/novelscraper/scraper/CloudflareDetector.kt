package com.example.novelscraper.scraper

import com.example.novelscraper.WebViewHelper

/**
 * Cloudflare / Turnstile 検出・生体模倣ロジックを集約。
 * 機械的クリックを完全排除し、人間的な生体タッチイベントを再現する。
 */
object CloudflareDetector {

    private val CF_TITLE_KEYWORDS = listOf("Just a moment", "Cloudflare", "Verify", "セキュリティ確認", "人間であることを確認")

    /** ページタイトルからCloudflareチャレンジを検出 */
    fun isCloudflareChallenge(pageTitle: String): Boolean =
        CF_TITLE_KEYWORDS.any { pageTitle.contains(it, ignoreCase = true) }

    /** スクレイピング結果文字列がCF検知を示すか */
    fun isCFDetectedResult(result: String): Boolean =
        result == "CF_DETECTED"

    /**
     * Turnstile / Checkbox に対する人間的な生体タップイベントシーケンス。
     * 単純な .click() だと e.isTrusted=false で即バレするため、
     * touchstart -> touchend -> mousedown -> mouseup -> click の順で自然に発火させる。
     */
    fun buildTurnstileHumanTapJs(): String = """
    (function(){
        if(window.cfHumanTapStarted) return;
        window.cfHumanTapStarted = true;

        setTimeout(function(){
            try {
                var el = document.querySelector('input[type="checkbox"]') ||
                         document.querySelector('#turnstile-wrapper input') ||
                         document.querySelector('.cf-turnstile-wrapper input') ||
                         document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                
                if(el && !el.checked) {
                    var rect = el.getBoundingClientRect();
                    var x = rect.left + (rect.width / 2) + (Math.random() * 4 - 2);
                    var y = rect.top + (rect.height / 2) + (Math.random() * 4 - 2);

                    var touchObj = new Touch({
                        identifier: Date.now(),
                        target: el,
                        clientX: x,
                        clientY: y,
                        screenX: x,
                        screenY: y,
                        pageX: x,
                        pageY: y
                    });

                    var touchStart = new TouchEvent('touchstart', { cancelable: true, bubbles: true, touches: [touchObj], targetTouches: [touchObj], changedTouches: [touchObj] });
                    var touchEnd = new TouchEvent('touchend', { cancelable: true, bubbles: true, touches: [], targetTouches: [], changedTouches: [touchObj] });

                    var mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true, clientX: x, clientY: y });
                    var mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true, clientX: x, clientY: y });
                    var clickEvt = new MouseEvent('click', { bubbles: true, cancelable: true, clientX: x, clientY: y });

                    el.dispatchEvent(touchStart);
                    setTimeout(function(){
                        el.dispatchEvent(touchEnd);
                        el.dispatchEvent(mouseDown);
                        setTimeout(function(){
                            el.dispatchEvent(mouseUp);
                            el.dispatchEvent(clickEvt);
                            if(typeof el.click === 'function') el.click();
                        }, 60 + Math.random() * 40);
                    }, 80 + Math.random() * 50);
                }
            } catch(e) {}
        }, 1800 + Math.random() * 600);
    })();
    """.trimIndent()

    /** 人間的なスクロール動作を模倣するJS */
    fun buildHumanScrollJs(): String = """
    (function(){
        var h = document.body.scrollHeight;
        var s = 0;
        function sc(){
            if(s >= h) return;
            s += Math.random() * 50 + 20;
            window.scrollTo(0, s);
            setTimeout(sc, Math.random() * 100 + 50);
        }
        sc();
    })();
    """.trimIndent()

    /**
     * ページロード完了時の初期化JS（生体タップ + 必要に応じた人間的スクロール模倣 + PC Viewport除去）
     * 単一の evaluateJavascript 呼び出しに集約して IPC 通信コストを最小化する。
     */
    fun buildPageLoadInitJs(shouldScroll: Boolean, isDesktop: Boolean = false): String {
        val tapJs = buildTurnstileHumanTapJs()
        val scrollJs = if (shouldScroll) "\n${buildHumanScrollJs()}" else ""
        val desktopJs = if (isDesktop) "\n${WebViewHelper.buildDesktopViewportJs(true)}" else ""
        return "$tapJs$scrollJs$desktopJs"
    }
}
