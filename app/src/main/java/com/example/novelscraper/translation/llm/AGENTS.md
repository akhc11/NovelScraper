# LLM翻訳作業用ルール (translation/llm 直下・最優先)

本フォルダ (`api/ engine/ pipeline/ prompt/ rotation/ ui/`) と
`translation/common/NovelPhysicalSplitter.kt` を触る際は、
root `AGENTS.md` より本ファイルを優先する。

## 0. 作業前必読 (毎回必読)

1. 本フォルダの `LLM_BEHAVIOR_STANDARD.md` (振る舞いの標準。最優先で全体像を把握)
2. `graphify-out/GRAPH_REPORT.md` (構造把握)
3. 本フォルダの `README_ARCHITECTURE.md` (全体仕様。ただし §3.1/§9/§10 の一部記述は現行コードと乖離あり。下記「既知の乖離」を優先)
4. 対象ファイル自体を全文 Read (推測禁止)

## 1. スコープと不変条件 (ユーザー決定)

- 外部仕様は維持: フォルダ構成 (`分割済み/翻訳完了_LLM/.parts_*`)、SAF 経由 I/O、並列ワーカー、バッチ/大ファイル/単体の3経路、辞書→翻訳フロー、設定の DataStore 永続化。
- 現状維持 (直さない):
  - `LlmTranslationConfig` 内ハードコード API キー (利便性のため残す)
  - `.failed` は手動削除運用 (自動再試行を作らない。ただし二重 `.failed` でレジュームを塞ぐ構造は直す)
- 中身は刷新可: 内部テーブル・閾値・分岐・クライアント組立は捨てて書き直してよい。現行コードの無理な再利用禁止。
- root `AGENTS.md` のうち LLM には適用しないもの: §2 (Eruda/インスペクター)、§3 (Test Run 表示)、§4 (autoUrl)、§6 の WebView/Scraper 部分、§7 (Google 翻訳貼付け再現)。

## 2. 既知の乖離 (README より現行コードを優先しないもの)

- `TextCleanser` は改行統一・全角正規化をしていない (実装は NULL/BOM/ゼロ幅除去のみ)。
- `.lang_cache` は現行エンジンに存在しない概念。持ち込まない。
- EN サイズ比: ASCII→UTF-8 の自然膨張（2〜3倍）を考慮する必要があるが、具体値は AGENTS.md で固定せずコードのデフォルト値 + UI 設定で調整する。値を大きくしすぎるとハルシネーション検知が効かなくなる点に留意。
- `dictBatchMaxBytes` は UI 100万 vs エンジン 10万。100000 に統一する。

## 3. 再実装原則 (root §5 の LLM 解釈)

- 無言継続の禁止: `?: continue`、`catch → null`、`save` 戻り値無視は厳禁。境界ガードは「理由付き Failure + ログ + テスト」なら許可する。
- スコープベース解放: `FileClaimManager` は job 単位で新規生成。`claim-first → read → try/finally 解放` の順序（二重読みと TOCTOU を回避）。バッチは採用確定分のみ一括 claim。`ConcurrentHashMap.putIfAbsent/remove` で suspend 不要にする。
- 保存検証: `save` 成否を必須チェック。完了判定は `exists && length > 0`。0 バイト破損は未完了扱い。
- 大ファイル責務分離: 結合完了まで親直下に `.failed` を作らない。進捗は `onChunkProgress` で StateFlow に伝播。
- 品質判定は `LanguageDetector` (かな率主軸) に一本化。簡体字 char 配列マッチは廃止方向。常用漢字で落とさない。
- サニタイズ順序: ```剥離 → 前口上除去 → 末尾後口上除去 → `CompletionMarkerHelper` 検証。
- API パラメータは nullable 透過 (`temperature ?: 0.5` のような固定既定値の埋め込み禁止)。
- KISS: 過剰な共通化・新ライブラリ禁止。閾値の単一化程度の集約のみ許可。デッドガードは削除。

## 4. 機能別チェックリスト

- ① `pipeline/TranslationQualityValidator.kt`: 常用漢字誤検出なし / EN サイズ比はコードデフォルト値に委ねる / ```・中韓前口上・末尾後口上除去。
- ② `pipeline/FileClaimManager.kt` + Engine 走査部: 読み失敗・バッチ break でもリークなし / 停止→即再開で二重化なし。
- ③ Engine I/O (`readFileContent/saveFileContent`): 戻り値検証 / 0 バイト再訳。
- ④ `pipeline/LargeFileTranslator.kt`: 親子二重 `.failed` 解消 / `chunkProgress` 更新。
- ⑤ `api/OpenAiCompatibleClient.kt` + Engine `callApiForProfile`: `temperature=null` はキー省略 / `qwen/` 特例のベンダー結合を持ち込まない。
- ⑥ `prompt/TranslationPrompts.kt` (PROMPT_1_ZH に OUTPUT ONLY 追加) + `prompt/PromptBuilder.kt` (不一致時の例 10 件 → 5 件に削減)。
- ⑦ `ui/LlmSettingsDialog.kt` + `ui/LlmTranslationPanel.kt`: 空キー保存の受入 / モデル0件・キー空で開始不可 / クランプ統一 / 範囲外プリセット不可。

## 5. 禁止アンチパターン (抜粋)

- チャンク2以降への原文二重注入 (`prevTranslatedSummary` に一本化)。
- バッチの単体化 (束ね→失敗時のみ単体フォールバックを維持)。
- 中断時の `.failed` 作成。
- ループ内の `findFile/listFiles` 連打 (起動時 `existingOutputNames` キャッシュ)。
- 末尾極小チャンクの孤立 (`NovelTextSplitter` マージ維持)。

## 6. DoD

- `LlmPipelineTest` (+ 追加ケース) と関連テストが合格 (`./gradlew testDebugUnitTest`)。
- `graphify-out/graph.json` で `ScrapingViewModel` / `TranslationQueueManager` からの呼び出しを確認。
- 実機 4 手順 (10件×並列2 / 中断→再開 / EN長編 / ZH人名回) で欠落なし。
