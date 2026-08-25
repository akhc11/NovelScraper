# 引き継ぎ状況 - Cloudflare対策・WebView最適化（Google画像認証解消・端末ネイティブUA準拠） (handoff_stealth_cloudflare.md)

## 概要
Google検索における画像認証（reCAPTCHA）の頻発およびSPAサイト（DeepL等）のJSクラッシュを防止するため、不自然なJavaScriptプロパティ改ざん（`STEALTH_SCRIPT`）および固定User-Agentのハードコードを完全撤廃しました。
端末本来のOSネイティブUser-Agentに統一しつつ、実効性の高い**「3大ステルス基盤（仮想サイズ・生体タップ・Cookie共有）」**を維持する安全設計に最適化しました。

---

## 現在のWebViewアーキテクチャと仕様

### 1. 端末正規ネイティブ User-Agent の採用（偽装ゼロ・完全一致）
- **通常（モバイル）表示:**
  - `userAgentString` の上書きを廃止し、Android OS / WebView が提供する端末本来のデフォルトUA（`WebSettings.getDefaultUserAgent(context)`）をそのまま使用。
  - HTTP通信ヘッダーの `Client Hints`（`sec-ch-ua`）や `navigator.userAgentData` と端末実態が100%一致し、Google等のFingerprint Mismatch判定（不審なUA偽装判定）を完全回避。
- **PC版サイトモード時:**
  - 端末本来のデフォルトUAから `Mobile `（`Mobile Safari` → `Safari`）のみを除去したPC用UAを動的生成して適用。

### 2. 不自然な JavaScript 先行注入（`STEALTH_SCRIPT`）の完全撤廃
- `navigator.webdriver` のゲッター改ざんや `window.chrome` モック、独自のCSS注入を完全削除。
- GoogleのreCAPTCHAによる「プロパティ改ざん痕跡の逆検知」を無くし、DeepL等のSPAでのReact初期化クラッシュを解消。

### 3. 裏 WebView への「仮想サイズ（1080x1920）」付与（維持）
- バックグラウンドの `ScrapingTask` / `TranslationTask` / `DeeplTranslationTask` の裏 WebView に対して `layout(0, 0, 1080, 1920)` を適用。
- `window.innerWidth = 1080`, `window.innerHeight = 1920` が認識され、0x0 によるヘッドレスボット判定を回避。

### 4. 生体タップイベントシーケンス（`CloudflareDetector.kt`）（維持）
- `touchstart` → `touchend` → `mousedown` → `mouseup` → `click` の生体イベントシーケンスと自然なランダム待機（1.8秒〜2.4秒）を維持。

### 5. `cf_clearance` 通行手形 Cookie の完全共有（維持）
- `CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)` を全 WebView で徹底。
- `WebViewHelper.flushCookies()` による即時永続化を維持。

---

## 変更・作成ファイル一覧
1. `[MODIFY]` [`WebViewHelper.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/WebViewHelper.kt) - `STEALTH_SCRIPT` 撤廃・`getUserAgent`（端末正規UA動的生成）導入・`applyStandardSettings` 最適化
2. `[MODIFY]` [`MainScreen.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/MainScreen.kt) - `targetUA` 取得を `WebViewHelper.getUserAgent` に更新、`layoutParams = MATCH_PARENT` 明示設定
3. `[MODIFY]` [`docs/handoff_stealth_cloudflare.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/docs/handoff_stealth_cloudflare.md) - 本引き継ぎ書

