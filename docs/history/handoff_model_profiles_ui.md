# 進捗報告 (Handoff) - モデル個別プロファイル型アーキテクチャ ＆ UI刷新 完了

## 実装内容
1. **モデル個別プロファイル (`ModelProfile`) の導入**:
   - `LlmTranslationConfig.kt` に `ModelProfile` データクラスを新設。
   - Thinking Level, Temperature, チャンクサイズ, バッチ上限, 大ファイル判定閾値, プロンプト順序（例: `1, 1`）をモデル単位で完全独立保持。
   - `LlmRotationManager`, `LlmTranslationEngine`, `LargeFileTranslator` が現在アクティブな `ModelProfile` を参照して動的にパラメータを適用するように刷新。
2. **「＋ モデルを追加」ダイアログ ＆ カード型UIの完全刷新**:
   - 押しにくい小チップを全廃。
   - 「＋ モデルを追加」ボタンから、Google AI Studio プリセット（3.5-flash, 3.6-flash, 3.7-flash, 3.1-flash-lite, 3.5-flash-lite, gemma-4-31b-it）、OpenRouter プリセット（gemma4, solar-pro4, deepseek, llama-3.3）、Groq プリセット、手動カスタム入力をワンタップ選択可能。
   - 登録モデルはカード一覧で並び、アコーディオン展開で各パラメータを直接編集、上下並び替え、削除が可能。
3. **3タブへの明確な整理**:
   - ① モデル設定: 巡回モデル一覧 ＆ 個別パラメータ設定 ＋ 追加ボタン
   - ② 共通・APIキー: APIキープール、429自動ローテーション、人名辞書、出力フォルダ名、トグル類
   - ③ プロンプト編集: 1〜7番プロンプト本文閲覧・編集・リセット

## 検証結果
- 単体テスト (`testDebugUnitTest`): 全46件すべて PASS
- デバッグビルド (`assembleDebug`): BUILD SUCCESSFUL (29s)