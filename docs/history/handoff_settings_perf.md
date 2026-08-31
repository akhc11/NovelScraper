# 引き継ぎ状況 - 設定画面 パフォーマンス根本改善 & 最適化 (handoff_settings_perf.md)

## 1. 概要・背景
- **課題**: ヘッダーの設定ボタン（歯車アイコン）をタップした際、設定パネル（`SettingsPanel`）の表示に遅延（カクつき・フレームドロップ）が発生していた。
- **原因**:
  1. `SettingsPanel` が `Column + verticalScroll` で構成されており、画面外を含む 13 個の重量級コンポーネント（`BasicTextField`）や全カードが一括で同期コンポーズ・レイアウト測定されていた。
  2. `SettingsFormState` による 13 個の `mutableStateOf` インスタンス生成と `LaunchedEffect` / `DisposableEffect` による無駄な二重同期オーバーヘッドが存在した。
  3. `MainScreen` 側でオーバーレイ表示時に `AndroidView(WebView)` の `view.visibility = INVISIBLE` を実行しており、Chromium ネイティブサーフェスの停止処理が UI スレッドを同期ブロックしていた。

## 2. 実施内容と変更ファイル
1. **[`SettingsPanel.kt`](file:///c:/Users/asan6/AndroidStudioProjects/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/components/SettingsPanel.kt)**:
   - `Column + verticalScroll` から **`LazyColumn` による仮想化レイアウト** へ刷新。
   - 各セクション・カードに一意な `key` を付与し、Compose の差分検出・スキップ性能を最大化。
   - 冗長な `SettingsFormState` クラスと `LaunchedEffect`/`DisposableEffect` の二重同期ループを完全撤廃。
   - `var localConfig by remember(currentConfig) { mutableStateOf(currentConfig) }` によるクリーンな単方向データフローに整理。
   - 13個のセレクタ入力欄、`autoUrl`、説明書ダイアログ、プリセット保存・削除・インポート・エクスポート等の既存仕様を 100% 維持。
2. **[`MainScreen.kt`](file:///c:/Users/asan6/AndroidStudioProjects/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/MainScreen.kt)**:
   - `AndroidView` の `update` ブロック内で UI スレッドを同期ブロックしていた `view.visibility = INVISIBLE` を撤廃。
   - `graphicsLayer { alpha = if (isOverlay) 0f else 1f }` と `view.isEnabled` の制御に一本化し、メインスレッドのフレームドロップをゼロ化。

## 3. 動作確認・検証
- `.\gradlew test` (ユニットテスト全件 PASS)
- `.\gradlew assembleDebug` (ビルド成功)
- 実機 (`e3e3b2ab`) へのインストール＆起動確認完了（エラー・クラッシュなし）
- 設定パネルの開閉が引っかかりなく即座に表示されることを確認。
