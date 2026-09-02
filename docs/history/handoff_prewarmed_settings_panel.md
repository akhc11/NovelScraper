# 引き継ぎ状況 - 設定画面（SettingsPanel）の事前ウォームアップ（Pre-warmed）によるオープン速度 0ms 化

## 1. 概要・現象
- 設定画面を開く際、13個の BasicTextField を含む巨大コンポジションツリーがメインスレッドで一斉に初期化されるため、初期オープン時に数十ミリ秒の遅延（もたつき）が発生していた。

## 2. 修正方針
1. **Pre-warmed アーキテクチャの導入 (MainScreen.kt)**:
   - SettingsPanel を起動時に常時コンポジション（ウォームアップ）させておく。
   - 非表示時は graphicsLayer { alpha = 0f; translationX = 10000f } で画面外退避。
   - オープン時は 	ranslationX = 0f; alpha = 1f に切り替え、開く瞬間の初期化コストを完全ゼロ（0ms）化。
2. **静的 Modifier / Style の最適化 (SettingsPanel.kt)**:
   - InputBoxModifier などの定数化による GC / メモリアロケーションの削減。

## 3. 検証・ビルド手順
- 	estDebugUnitTest で単体テスト実行
- ssembleDebug でビルド確認