package com.example.novelscraper

/**
 * Cloudflare / Turnstile 検出・対策ロジックを集約。
 * WebViewに直接依存せず、JS文字列の生成と判定のみを担当。
 */
object CloudflareDetector {

    private val CF_TITLE_KEYWORDS = listOf("Just a moment", "Cloudflare", "Verify")

    /** ページタイトルからCloudflareチャレンジを検出 */
    fun isCloudflareChallenge(pageTitle: String): Boolean =
        CF_TITLE_KEYWORDS.any { pageTitle.contains(it) }

    /** スクレイピング結果文字列がCF検知を示すか */
    fun isCFDetectedResult(result: String): Boolean =
        result == "CF_DETECTED"

    /** Turnstile チェックボックスを自動クリックするJS */
    fun buildTurnstileClickJs(): String =
        "(function(){ if(!window.cfStarted){ window.cfStarted=true; setInterval(function(){ var btn=document.querySelector('input[type=\"checkbox\"]'); if(btn && !btn.checked) btn.click(); }, 1500); } })();"

    /** 人間的なスクロール動作を模倣するJS */
    fun buildHumanScrollJs(): String =
        "(function(){ var h=document.body.scrollHeight; var s=0; function sc(){ if(s>=h) return; s+=Math.random()*50+20; window.scrollTo(0,s); setTimeout(sc, Math.random()*100+50); } sc(); })();"

    /** ページロード完了時の初期化JS（Turnstile対策 + 必要に応じた人間的スクロール模倣）を単一スクリプトに集約 */
    fun buildPageLoadInitJs(shouldScroll: Boolean): String {
        val turnstileJs = buildTurnstileClickJs()
        return if (shouldScroll) {
            "$turnstileJs\n${buildHumanScrollJs()}"
        } else {
            turnstileJs
        }
    }
}
