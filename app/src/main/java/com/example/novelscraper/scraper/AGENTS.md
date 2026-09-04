# scraper — 専用ルール

対象：`scraper/`配下＋インスペクターJS・`TextQuerySearchDialog`・`SettingsPanel`・`TestResultPanel`・`HeaderToolbar`。
設計図は境界変更時のみ：`docs/archify/scraper.workflow.json`（HTML併読可）。

## Traps（コードを読んでも分からない罠のみ）

### 1. `@JavascriptInterface`はtypeof判定しない
```js
// NG: Android WebViewでは常にfalseになる
if (typeof Native !== 'undefined' && typeof Native.onHit === 'function') Native.onHit(s);
// OK: 直接呼ぶ
Native.onHit(s);
```

### 2. インスペクター再注入しない。差分同期する
```js
// NG: リスナー二重化・ゾンビ化の原因
document.body.appendChild(script); // 2回目
// OK: 起動中の設定変更は差分同期
__novelInspector.updateConfig(next);
```
OFF時は`removeEventListener`＋注入`<style>`・マーク・dimを除去する。

### 3. 動的スタイルはcssTextで
```js
// NG: 非標準
el.style = 'color:red';
// OK
el.style.cssText = 'color:red';
```

### 4. IPCは1回に集約
`onPageFinished`のTurnstile対策＋スクロール模倣は`CloudflareDetector.buildPageLoadInitJs()`経由の単一`evaluateJavascript`にまとめる。分割呼び出しを増やさない。

### 5. 本文空でも止めない
本文が空/空白のみ→`(本文なし)`補完で保存し、`nextUrl`がある限り次へ進む。

## Specs（外部挙動。勝手に変える時はAsk first）

- Eruda歯車ボタンは常時非表示。スパナボタンでのみトグル
- 開始/停止はヘッダーの再生・停止ボタン→`ScraperServiceController`連動
- お気に入り：タップ＝追加、長押し（250ms）＝一覧開閉
- `TestResultPanel`の5項目（作品名/チャプター/タイトル/次URL/本文先頭）を欠落させない
- `autoUrl`のドメイン前方一致ロードと`SettingsPanel`最下部入力欄を維持する

## Commands

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.example.novelscraper.Scraping*Test" --tests "com.example.novelscraper.*ExtractorTest" --tests "com.example.novelscraper.PresetJsonTest" --tests "com.example.novelscraper.StateLogicTest"
```

## Definition of Done

- 常時：上記focusedテストが全件パス
- IPC・状態遷移・保存処理を変えた時のみ：フルテスト＋該当Archify JSON/HTML同期
