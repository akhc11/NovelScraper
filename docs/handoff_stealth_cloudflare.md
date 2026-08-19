# 引き継ぎ状況 - Cloudflare対策・WebViewステルス防御基盤 実装完了 (handoff_stealth_cloudflare.md)

## 概要
Cloudflare Turnstile や Bot Management による検知・ブロックを回避し、小説スクレイピングおよびWeb翻訳（Google / DeepL）を安定稼働させるための**「5大ステルス防御基盤」**をアプリ全体に実装しました。

---

## 主な実装内容と仕様

### 1. `WebViewCompat.addDocumentStartJavaScript` による最速ステルス先行注入
- **実行タイミング:** HTMLパース前（V8エンジン起動直後）に最速実行。
- **効果:** Cloudflare や Google の検知スクリプトが走り出す前に、`navigator.webdriver = undefined` や `window.chrome` モックを安全に設定し、先回りで偽装を完了。
- **iframe への自動適用:** `challenges.cloudflare.com` 等の Turnstile 子フレーム内部にも自動適用。

### 2. 端末ネイティブ正規 Chrome UA への完全最適化
- **Windows UA の完全廃止:** ハードウェア実態（Android / ARM / タッチスクリーン）と矛盾する Windows UA を撤廃。
- **正規 Android Chrome UA:**
  - 通常/モバイル用: `Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36`
  - PC版サイト用 (DeepL等): `Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36`（`Mobile` のみ除去）

### 3. 裏 WebView への「仮想サイズ（1080x1920）」付与（ヘッドレス解除）
- バックグラウンドの `ScrapingTask` / `TranslationTask` / `DeeplTranslationTask` の裏 WebView に対して `layout(0, 0, 1080, 1920)` を実行。
- `window.innerWidth = 1080`, `window.innerHeight = 1920` が認識され、0x0 によるヘッドレスボット判定を完全に回避。

### 4. 生体タップイベントシーケンス（`CloudflareDetector.kt`）
- 機械的な `btn.click()` 連打を廃止。
- `touchstart` → `touchend` → `mousedown` → `mouseup` → `click` の生体イベントシーケンスと自然なランダム待機（1.8秒〜2.4秒）に刷新。

### 5. `cf_clearance` 通行手形 Cookie の完全共有
- `CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)` を全 WebView で徹底。
- `WebViewHelper.flushCookies()` による即時永続化。

---

## 変更・作成ファイル一覧
1. `[MODIFY]` [`WebViewHelper.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/WebViewHelper.kt) - ステルス先行注入・正規UA・仮想サイズ付与・Cookieフラッシュ
2. `[MODIFY]` [`CloudflareDetector.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/CloudflareDetector.kt) - 生体タップイベントシーケンス
3. `[MODIFY]` [`ScrapingTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ScrapingTask.kt) - 仮想サイズ・ステルス設定適用
4. `[MODIFY]` [`DeeplTranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/DeeplTranslationTask.kt) - 仮想サイズ・正規PC版UA適用
5. `[MODIFY]` [`TranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/TranslationTask.kt) - 仮想サイズ適用
6. `[NEW]` [`docs/handoff_stealth_cloudflare.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/docs/handoff_stealth_cloudflare.md) - 本引き継ぎ書

---

## ロールバックポイント
- **バックアップタグ:** `backup_before_stealth_v1`
- **バックアップブランチ:** `backup_before_stealth_v1`
- **コミットハッシュ:** `657e821`
