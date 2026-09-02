# 引き継ぎ状況 - 辞書への性別属性追加機能 (handoff_dict_gender_support.md)

## 完了日: 2026-09-03

## 概要・背景
小説翻訳時に頻発する「男性キャラの女性口調化（語尾の性別逆転）」や「三人称の誤認」を確実に防止するため、登場人物辞書に不変の属性である「性別（男/女）」の抽出・マージ・プロンプト注入サポートを追加した。物語の展開を壊さないよう、可変の「ロール（立場・職業）」や「セリフ」は含めず、純粋な「人名対訳＋性別」のみに厳格に限定した。

## 実装内容
1. `NovelDictionaryGenerator.kt`:
   - `NovelDictionary` に `val genders: Map<String, String> = emptyMap()` を追加（100% 後方互換性担保）
   - `BATCH_PROMPT`, `MERGE_PROMPT`, `REVIEW_PROMPT` の出力指示に `genders` を追加
   - `parseDictionaryJson` での `genders` パースおよび JsonElement を用いた柔軟なフォールバック対応
2. `PromptBuilder.kt`:
   - `buildPrompt` / `buildBatchPrompt` に `dictionaryGenders: Map<String, String>? = null` を追加
   - `buildDictionarySection` で `・原文 → 訳名 (性別: 男)` 形式の注入と、性別に応じた語尾ルールの明記
3. `LlmTranslationEngine.kt`:
   - `PromptBuilder.buildPrompt` 呼び出し時に `dictionaryGenders = novelDict?.genders` を伝達
   - `LargeFileTranslator.translateLargeFile` に `dictGenders` を伝達
4. `LargeFileTranslator.kt`:
   - `translateLargeFile` に `dictGenders` を追加し `PromptBuilder.buildPrompt` に渡す
5. 検証:
   - ユニットテスト (`testDictionaryJson_WithGenders`, `testPromptBuilder_WithDictionaryGenders`, 後方互換性テスト) 全件合格
   - `gemini-3.1-flash-lite` ➔ `gemini-2.5-flash` 実データテストにて 27名全員の性別が完璧に判定・統合されることを確認
   - `assembleDebug` ビルド成功
