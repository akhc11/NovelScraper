# 引き継ぎ状況 - 言語別自動サイズ調整 ＆ プロンプト自動選択 (handoff_auto_language_size_and_prompt.md)

## 完了日: 2026-09-03

## 概要
検出された言語（韓国語・中国語・英語）に応じて、分割サイズを自動最適化（ユーザー調整可能）し、プロンプト順序の自動選択（ON/OFF切替可能、NSFW手動選択保護）を実装した。

## 実装内容
1. `LlmTranslationConfig.kt`:
   - `enableAutoLanguageSize: Boolean = true`
   - `langSplitKoreanKb: Int = 25`
   - `langSplitChineseKb: Int = 20`
   - `langSplitEnglishKb: Int = 15`
   - `enableAutoPromptOrder: Boolean = false` (成人向け手動選択保護のためデフォルトOFF)
   - `getEffectiveSplitThreshold`, `getEffectiveChunkSize`, `getEffectivePromptOrder` ヘルパー
2. `LargeFileTranslator.kt`:
   - チャンクサイズおよびプロンプト順序に実効値を反映
3. `LlmTranslationEngine.kt`:
   - 言語決定時にパラメータ状態をログ表示
   - `effectiveSplitThreshold` による大ファイル判定
   - `effectivePromptOrder` による単体ファイル翻訳
4. `LlmSettingsDialog.kt`:
   - 「言語別の分割サイズ自動調整 (推奨)」チェックボックスと各言語のKB入力欄
   - 「言語連動 プロンプト自動選択」チェックボックスと成人向け手動選択保護注釈
5. 検証:
   - `LlmPipelineTest` 全件合格
   - `assembleDebug` 成功
