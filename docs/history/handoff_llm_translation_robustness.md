# 引き継ぎ状況 - AI/LLM翻訳 堅牢化・レジューム（途中再開）・完全仕様準拠 (handoff_llm_translation_robustness.md)

## 概要・背景
Google AI Studio (Gemini Developer API)、OpenRouter、Groq の最新仕様と、仕様元である bash スクリプト `openrouter-v29-0-5-10-0.sh`（v29.0.5.10.0）を精査。
敵対的レビュー（Adversarial Review）により特定された以下の致命的欠陥および未実装機能を根本治療する。

1. **大ファイル分割翻訳の永続化 & レジュームエンジン（クラッシュ耐性）**
   - チャンク毎の中間ファイル（`.parts_${filename}/in`, `out`）をストレージ上に永続化し、中断後の再開を可能にする。
2. **非同期通信とキャンセル安全性**
   - OkHttp の同期ブロッキング `execute()` を廃止し、`suspendCancellableCoroutine` + `Call.cancel()` による安全な非同期コルーチン通信へ移行。
3. **人名辞書生成の並列化 & 途中再開（`batch_NNNN.json`）**
   - Coroutines 並列バッチ（`async`/`awaitAll`）で高速化し、各バッチの中間保存で途中再開を可能にする。
4. **前口上除去 & 品質バリデーションの精密化**
   - `stripPreamble` の `startsWith` による小説本文・台詞の誤削除を根絶（定型句完全一致またはコロン完結のみ対象）。
5. **バッチ翻訳失敗時の単体フォールバック**
   - バッチパース失敗・全モデル失敗時に、各ファイルを自動的に単体分割翻訳へフォールバックして救済。
6. **OpenRouter / Gemini 設定パラメータの型厳密化**
   - `reasoningEnabled: Boolean?` を追加（DeepSeek V3.2 向け）。Gemini の `thinkingBudget` vs `thinkingLevel` 排他制御。

## 変更・作成ファイル一覧
- `translation/llm/api/LlmApiClient.kt`
- `translation/llm/api/GeminiApiClient.kt`
- `translation/llm/api/OpenAiCompatibleClient.kt`
- `translation/llm/engine/LlmTranslationConfig.kt`
- `translation/llm/pipeline/LargeFileTranslator.kt`
- `translation/llm/pipeline/NovelDictionaryGenerator.kt`
- `translation/llm/pipeline/TranslationQualityValidator.kt`
- `translation/llm/engine/LlmTranslationEngine.kt`
- `translation/llm/rotation/LlmRotationManager.kt`