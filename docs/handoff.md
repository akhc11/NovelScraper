# 引き継ぎ状況 - 最終更新: 2026-08-18 (手動操作再現仕様の不変ルール化 & ドキュメント確定)

## 現在の状態
- **翻訳品質:** 手動貼り付けの完全再現イベントシーケンス（`ClipboardEvent('paste')` + `InputEvent('insertFromPaste')`）により、Google翻訳に「プログラム入力」と誤認されず、ユーザー手動時と100%同一の最高品質を維持。
- **バックグラウンド実行:** `TranslationTask` 内部で `WebView(context.applicationContext)` を生成する「独立バックグラウンドWebView」方式により、ホーム画面に戻っても他アプリを使用しても一切停止せずに翻訳が継続。
- **不変ルール化:**
  - `AGENTS.md` に「第7項: Google翻訳における手動操作再現ロジックの死守」を追加。
  - `docs/translation_specification.md` に詳細仕様書を作成。
- **ビルド・テスト:** `assembleDebug` および単体テスト全件（10/10）がすべて正常にパスすることを確認済み。

## 確定したアーキテクチャ・仕様

### 1. 手動貼り付け完全再現（TranslationTask.kt - JS_INPUT_TEMPLATE）
```javascript
ta.focus();
ta.select();
var dt = new DataTransfer();
dt.setData('text/plain', %s);
ta.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: dt }));
if (!ta.value || ta.value.trim().length === 0) ta.value = %s;
ta.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste', data: %s }));
ta.dispatchEvent(new Event('change', { bubbles: true }));
```

### 2. 独立バックグラウンドWebView（TranslationTask.kt）
- `ScrapingTask` と同様に `WebView(context.applicationContext)` で独立インスタンスを生成。
- 画面のCompose/Activityライフサイクル（UI非表示時のサスペンド）から完全に分離。
- 最初の1回だけ `translate.google.com` をロードし、以降は同一ページ上で高速に連続翻訳（ページ再読込なし）。

## ドキュメント構成
- [`AGENTS.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/AGENTS.md) : 不変の絶対遵守ルール
- [`docs/translation_specification.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/docs/translation_specification.md) : Google翻訳機能の詳細仕様書
- [`docs/handoff.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/docs/handoff.md) : 開発引き継ぎ記録
