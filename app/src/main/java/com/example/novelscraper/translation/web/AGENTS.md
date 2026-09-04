# web翻訳 — 専用ルール

対象：`translation/web/`配下（`BaseWebTranslationTask`・`TranslationQueueManager`・`LiveTranslateScriptBuilder`・`WebTranslationStrategy`・`TextChunker`・`TranslationFileStore`）＋`TranslationPanel`。
設計図は境界変更時のみ：`docs/archify/web-translation.workflow.json`。

## Trap 1. Google入力はCtrl+V再現シーケンスを崩さない（Ask first）

`textarea.value = text`の単純代入はBot判定→簡易エンジン落ちで品質劣化する。Google経路の貼り付けは下記順序を保つ。変える場合は理由＋品質比較を先に示す：

```js
textarea.focus(); textarea.select();
textarea.dispatchEvent(new ClipboardEvent('paste', { clipboardData }));
textarea.dispatchEvent(new InputEvent('input', { inputType: 'insertFromPaste' }));
textarea.dispatchEvent(new Event('change'));
```

## Trap 2. バックグラウンドは独立WebViewを使う

```kotlin
// NG: UIのWebView流用（Activity破棄で翻訳が死ぬ）
// OK: アプリコンテキストで独立させる
WebView(context.applicationContext)
```
`BaseWebTranslationTask`派生は上記で動作させる。

## Conventions

- サービス固有DOM・セレクタは`WebTranslationStrategy.kt`に集約。新規対応サービス追加時はAsk first
- 分割上限・チャンク閾値は`TextChunker`・各Strategyの定数が正本。本ファイルに数値を複写しない

## Commands

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.example.novelscraper.TextChunkerTest" --tests "com.example.novelscraper.LiveTranslateScriptBuilderTest"
```

## Definition of Done

- 常時：上記focusedテストが全件パス
- パイプライン・DOM抽出・チャンク・保存処理を変えた時のみ：フルテスト＋実機（変更したサービスのみ）＋該当Archify JSON/HTML同期
