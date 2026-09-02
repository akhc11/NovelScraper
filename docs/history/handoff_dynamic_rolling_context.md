# 引き継ぎ状況 - 動的ローリングコンテキスト実装 (handoff_dynamic_rolling_context.md)

## 完了日: 2026-09-03

## 概要・背景
静的辞書（名前＋性別）を不変のアンカーとして維持しつつ、物語の展開に合わせて直近エピソードのキャラの立場・関係性・口調を Gemma 4 31B で先行解析（ゼロ遅延プリフェッチ）し、本文翻訳プロンプトに動的注入する「動的ローリングコンテキスト（2層ハイブリッド辞書）」を実装した。

## 実装内容
1. `LlmTranslationConfig.kt`:
   - `enableDynamicRollingContext: Boolean = false`
   - `rollingContextLookaheadChapters: Int = 2`
   - `rollingContextModel: String = "gemma-4-31b-it"`
2. `DynamicRollingContextManager.kt` (新設):
   - 先行解析用プロンプト `ROLLING_EXTRACT_PROMPT`
   - メモリキャッシュ `ConcurrentHashMap<String, Map<String, String>>`
   - バックグラウンド先読み `prefetchUpcoming(...)`
   - キャッシュ取得 `getRollingContext(fileName)`
   - 失敗時の空マップ返却（優雅なフォールバック）
3. `PromptBuilder.kt`:
   - `buildPrompt` / `buildBatchPrompt` に `rollingContextMap: Map<String, String>? = null` を追加
   - `[直近の状況・関係性 (本エピソード限定の文脈補足)]` セクションを合成
4. `LlmTranslationEngine.kt`:
   - 翻訳ループ内で次話の先行プリフェッチをトリガー
   - 当該話の動的コンテキストを `PromptBuilder` に伝達
5. `LargeFileTranslator.kt`:
   - 大ファイル分割翻訳でも `rollingContextMap` を伝達
6. `LlmSettingsDialog.kt`:
   - 設定UIに「動的キャラ関係性ローリング」トグルスイッチおよび先読み話数・モデル設定を追加
7. 検証:
   - ユニットテスト (`testDynamicRollingContext_JsonParsing`, `testPromptBuilder_WithDynamicRollingContext`) 全件合格
   - `assembleDebug` ビルド成功
