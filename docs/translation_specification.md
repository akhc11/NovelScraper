# Google翻訳機能 仕様書 & 品質維持ガイドライン

## 1. 翻訳品質低下の原因と「手動操作再現」の重要性

### 1.1 問題の背景（なぜ単純なJS入力だと質が落ちるのか）
Google翻訳（`translate.google.com`）のWebフロントエンドは、ユーザーが手動でテキストを入力・貼り付けした時と、プログラム（Botやスクリプト）によってテキストが注入された時とで、内部の処理・翻訳パスを区別しています。

- **`textarea.value = "..."` による単純代入（NG）:**
  Google翻訳側に「プログラムによる一括書き込み」と検知され、簡易なフォールバックエンジンが適用されたり、前後の文脈考慮が欠落して翻訳品質が著しく低下します。
- **手動貼り付けの完全再現（OK・最高品質）:**
  ユーザーがブラウザ上で `Ctrl+V`（貼り付け）を行った時にブラウザが発火させる一連のネイティブDOMイベントをそのまま順序通りに発火させることで、Google翻訳は**「ユーザーが手動でWeb画面にテキストを貼り付けた」と100%同一に認識**し、最高精度の翻訳エンジンが適用されます。

---

## 2. 手動貼り付け完全再現のイベントシーケンス（絶対不変）

[`TranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/TranslationTask.kt) 内の `JS_INPUT_TEMPLATE` で以下のシーケンスを実行しています。**この手順を簡略化・改変することは厳禁です。**

```javascript
(function() {
    try {
        var ta = document.querySelector('textarea[aria-label]') || document.querySelector('textarea');
        if (!ta) return "NO_TEXTAREA";

        // 1. フォーカスと全選択（ユーザーが入力枠をクリックした状態）
        ta.focus();
        ta.select();

        // 2. DataTransferオブジェクトを構築して ClipboardEvent('paste') を発火
        //    → ユーザーがキーボードで Ctrl+V を押したのと同一のイベント
        var dt = new DataTransfer();
        dt.setData('text/plain', %s); // JSONエンコードされたテキスト
        var pe = new ClipboardEvent('paste', {
            bubbles: true,
            cancelable: true,
            clipboardData: dt
        });
        ta.dispatchEvent(pe);

        // 3. ペースト単体で反映されない場合の安全策（フォールバック）
        if (!ta.value || ta.value.trim().length === 0) {
            ta.value = %s;
        }

        // 4. insertFromPaste inputType を指定した InputEvent を発火
        //    → ブラウザが「ユーザーによる貼り付け操作」として認識
        ta.dispatchEvent(new InputEvent('input', {
            bubbles: true,
            inputType: 'insertFromPaste',
            data: %s
        }));

        // 5. change イベントを発火してフォームの変更を確定
        ta.dispatchEvent(new Event('change', { bubbles: true }));

        return "OK";
    } catch(e) {
        return "ERROR: " + e.message;
    }
})()
```

---

## 3. バックグラウンド動作アーキテクチャ

### 3.1 画面WebView vs 独立バックグラウンドWebView
- **画面表示用WebView（MainScreen）:**
  Activity/Composeツリーに所属しているため、ユーザーがホーム画面に戻ったり他アプリを開くと、OSのUIライフサイクル連動で強制的にJavaScript・レンダラーが凍結されます。
- **独立バックグラウンドWebView（TranslationTask内部）:**
  `ScrapingTask` と同様に、`WebView(context.applicationContext)` でUIツリーから完全に独立した不可視WebViewとして生成。
  これにより、**ホーム画面に戻っても、他アプリを使用していても、Service（WakeLock）のもとで一切停止せずに翻訳が進行**します。

### 3.2 ページ遷移なしの連続処理
- `translate.google.com` は最初の1回だけ読み込みます。
- チャンク毎にページを再読込（`loadUrl`）せず、同一ページ上で以下のループを高速実行します：
  1. `JS_CLEAR`: クリアボタンクリック ＋ textareaクリア
  2. `JS_CHECK_EMPTY`: 結果エリアが完全に空になるまで待機
  3. `JS_INPUT_TEMPLATE`: 手動貼り付け再現入力
  4. `JS_GET_RESULT`: プログレスバー消滅待機 ＆ 純粋な本文span抽出（安定判定）
  5. 結果を結合してファイル保存

---

## 4. 保守・改修時の注意点
1. **`JS_INPUT_TEMPLATE` の簡略化禁止**:
   将来のリファクタリングで「コードを短くしよう」として `textarea.value = ...` のみに戻してはならない。
2. **`JS_CLEAR` でのクリアボタン押下**:
   Google翻訳のWebアプリ内部状態（React/Angular的なState）をリセットするため、クリアボタン（`button[aria-label*="消去"]` 等）をクリックすることが必須。
3. **結果抽出の span 属性**:
   Google翻訳の翻訳本文は `span[jsname="W297wb"]` / `span[jsname="jqKxS"]` に出力される。アクセシビリティ案内文（「翻訳結果を利用できます」など）は除外フィルターで除去されている。
