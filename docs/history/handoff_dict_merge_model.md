# 引き継ぎ状況 - 辞書マージ＆レビュー用AIモデル設定機能 (handoff_dict_merge_model.md)

## 概要・背景
ユーザーの要望「最後のマージとReviewでaimodelを変更できるようにしてほしい」に基づき、辞書生成の統合マージ・最終レビュー処理で使用するAIモデル（`dictMergeModel`）を独立して設定・変更できるハイブリッドアーキテクチャを設計・実装・検証した。

## 実施内容
1. **`LlmTranslationConfig.kt`**:
   - `dictMergeModel: String = "gemini-2.5-flash"` を追加（デフォルト値。空欄時は `dictModel` へ自動フォールバック）。
2. **`NovelDictionaryGenerator.kt`**:
   - `generate` 引数に `mergeModel: String = model` を追加。
   - 統合マージ（`MERGE_PROMPT`）および最終レビュー（`REVIEW_PROMPT`）の `executeLlmRequest` において、`model = mergeModel` を適用。
3. **`LlmTranslationEngine.kt`**:
   - `val mergeModel = config.dictMergeModel.trim().ifBlank { dictModel }` を算出し、`NovelDictionaryGenerator.generate` に受け渡し。
4. **`LlmSettingsDialog.kt`**:
   - 状態変数 `dictMergeModel` を追加。
   - 辞書設定UIに「マージ・レビュー用モデル (例: gemini-2.5-flash / 空欄で抽出と同一)」入力欄を追加。
   - 設定保存処理（`onSaveConfig`）に `dictMergeModel = dictMergeModel.trim()` を追加。

## 検証結果
- **実機データを用いたハイブリッドテスト (`GemmaStressTest.kt`)**:
  - 抽出: `gemma-4-31b-it` (6キー並列、各約10KBバッチ)
  - マージ: `gemini-2.5-flash`
  - 結果: マージ所要時間 **わずか14.5秒**、余分な思考ログを含まない **純度100%のJSON出力**、**パース成功率100%（8名完全抽出・ノイズ除去）**！
- **ユニットテスト**: `.\gradlew testDebugUnitTest` ➔ **BUILD SUCCESSFUL** (全件合格)
- **ビルド検証**: `.\gradlew assembleDebug` ➔ **BUILD SUCCESSFUL** (APK生成正常完了)
