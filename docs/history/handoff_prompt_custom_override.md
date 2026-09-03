# 引き継ぎ状況 - プロンプト設定の全面改修・個別優先保護・プリセット登録 一元化 (handoff_prompt_custom_override.md)

## 完了日: 2026-09-03

## 概要
「一括設定が個別設定を破壊する」「個別設定をプリセット登録できない」「言語自動選択で全モデルが画一化される」という構造的欠陥を根本治療し、モデル個別設定の主権保護（オーバーライド構造）と個別カード内でのプリセット呼出・新規登録を完全実装した。

## 実装内容
1. `ModelProfile`:
   - `val useCustomPromptOrder: Boolean = false` を追加
2. `LlmTranslationConfig`:
   - `getEffectivePromptOrder` で `profile.useCustomPromptOrder == true` の時は言語自動選択よりもモデル固有設定を100%最優先
3. `LlmSettingsDialog`:
   - 最上部一括プロンプト順序変更時、`useCustomPromptOrder == true` のモデルを保護（スキップ）
   - `ModelProfileCard` 内に個別プロンプト設定カード（保護スイッチ、入力欄、プリセット呼出▼、プリセット登録＋）を新設
   - 各モデルで調整したプロンプト順序を、その場でプリセット追加ダイアログを呼び出して新規登録可能に
4. 検証:
   - `LlmPipelineTest.testEffectivePromptOrder_ModelCustomOverride` で個別保護が言語自動選択を上回ることを検証・合格
   - `assembleDebug` 成功
