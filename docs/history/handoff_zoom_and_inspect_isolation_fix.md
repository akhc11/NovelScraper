# 引き継ぎ状況 - 画面拡大（ピンチズーム）常時許可 ＆ インスペクター・即時翻訳の完全隔離・根本修正

## 1. 概要・現象
1. **ブラウザ画面の拡大不可**:
   - WebViewHelper.kt で isDesktop == false のときに setSupportZoom(false) が設定されており、モバイルモードでピンチズームが封殺されていた。
2. **要素選択時に勝手に翻訳が走る**:
   - 即時翻訳（LiveTranslateScriptBuilder）でセットされた googtrans Cookie および Google翻訳ランタイム（element.js）が残存。
   - インスペクター（ScrapingScriptBuilder）が要素タップ時にDOM操作（ポップアップ挿入やアウトライン付与）を行うと、Google翻訳がDOM変化を検知して自動翻訳を連鎖発火させていた。
   - 翻訳によってHTMLタグが分断され、セレクタ取得が破壊される二次被害も発生。

## 2. 修正方針
1. **ズーム機能の常時有効化 (WebViewHelper.kt)**:
   - isDesktop の有無に関わらず、setSupportZoom(true), uiltInZoomControls = true, displayZoomControls = false を常時設定。
2. **インスペクターUIの翻訳完全隔離 (ScrapingScriptBuilder.kt)**:
   - インスペクターのポップアップ・ヒント要素に 	ranslate="no" / class="notranslate skiptranslate" を付与。
   - インスペクター起動時に翻訳残存状態をリセット。
3. **インスペクター起動時の安全復元 (MainActivity.kt)**:
   - インスペクターON時に即時翻訳が有効なら強制的に原文復元（Undo）を実行し、CookieManagerからも googtrans を消去。
4. **即時翻訳クリーンアップの強化 (LiveTranslateScriptBuilder.kt)**:
   - Cookie 消去ロジックの強化と、インスペクター関連要素の翻訳除外。

## 3. 検証・ビルド手順
- 	estDebugUnitTest で単体テスト実行
- ssembleDebug でビルド確認