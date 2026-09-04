# LLM翻訳作業用ルール (translation/llm 直下)

本フォルダ (`api/ engine/ pipeline/ prompt/ rotation/ ui/`) および
`translation/common/NovelPhysicalSplitter.kt` を触る際はこのルールを適用する。

## 0. 作業前必読
1. 本フォルダの `LLM_BEHAVIOR_STANDARD.md` (振る舞いの標準・全体像)
2. [`docs/archify/llm-translation.workflow.json`](../../../../../../../../../docs/archify/llm-translation.workflow.json)（HTML可視化: [`docs/archify/llm-translation.workflow.html`](../../../../../../../../../docs/archify/llm-translation.workflow.html)）(全体フロー・パイプライン構造の把握)
3. 対象ファイル自体の全文 Read (推測禁止)

---

## 1. 外部仕様と不変条件
- **外部仕様は維持**: フォルダ構成 (`分割済み/翻訳完了_LLM/.parts_*`)、SAF 経由 I/O、並列ワーカー、バッチ/大ファイル/単体の3経路、辞書→翻訳フロー、設定の DataStore 永続化。
- **現状維持**:
  - `LlmTranslationConfig` 内ハードコード API キー (利便性のため残す)
  - `.failed` は手動削除運用 (自動再試行を作らない。二重 `.failed` は作らない)
- **内部は刷新可**: 内部テーブル・閾値・分岐・クライアント組立は必要に応じて書き直してよい。

---

## 2. 確定仕様
- **`.lang_cache`**: 正式採用。出力先フォルダに判定言語（ZH/KO/EN/JA）をキャッシュし、SAF再判定オーバーヘッドを削減する。
- **`dictBatchMaxBytes`**: 辞書生成時の1回あたりAPI送信最大バイト数。UIとエンジンで 200,000 (200KB) に統一（デフォルト 50,000、設定範囲 4,000〜200,000）。

---

## 3. 実装原則とチェックリスト
- **無言継続の禁止**: `?: continue`、`catch → null`、`save` 戻り値無視は厳禁。境界ガードは「理由付き Failure + ログ + テスト」で行う。
- **スコープベース解放 (`FileClaimManager`)**: job 単位で生成。`claim-first → read → try/finally 解放` の順序で二重読み・TOCTOU を防止。バッチは確定分のみ一括 claim。
- **保存検証**: `save` 成否を必須検証。完了判定は `exists && length > 0`（0 バイト破損は未完了扱い）。
- **大ファイル責務分離 (`LargeFileTranslator`)**: 結合完了まで親直下に `.failed` を作らない。進捗は `onChunkProgress` で伝播。
- **品質検証 (`TranslationQualityValidator`)**: `LanguageDetector` (かな率) 主軸。常用漢字で誤検知させない。EN サイズ比はコード既定値に委ねる。
- **サニタイズ順序**: ```剥離 → 前口上除去 → 末尾後口上除去 → `CompletionMarkerHelper` 検証。
- **API パラメータ透過**: nullable を維持（固定の決め打ち既定値埋め込みを避ける）。
- **KISS**: 過剰な共通化・新規外部ライブラリ導入を避ける。

---

## 4. 禁止アンチパターン
- チャンク2以降への原文二重注入 (`prevTranslatedSummary` に一本化)。
- バッチの単体化 (束ね→失敗時のみ単体フォールバックを維持)。
- 中断時の `.failed` 作成。
- ループ内の `findFile/listFiles` 連打 (起動時 `existingOutputNames` キャッシュ)。
- 末尾極小チャンクの孤立 (`NovelTextSplitter` マージ維持)。

---

## 5. 完了の定義 (DoD)
- `LlmPipelineTest` を含む関連テスト全件合格 (`./gradlew testDebugUnitTest`)。
- 実機検証（並列翻訳、中断→再開、長編、人名等）で欠落がないこと。
- **アーキテクチャ・設計図の同期 (Archify)**:
  - パイプライン構成やデータフローに変更を加えた場合、必ず [`docs/archify/llm-translation.workflow.json`](../../../../../../../../../docs/archify/llm-translation.workflow.json) を同期更新し、以下のコマンドで Showcase 品質検証および HTML 再生成を行うこと：
    ```bash
    node .agents/skills/archify/bin/archify.mjs deliver workflow docs/archify/llm-translation.workflow.json docs/archify/llm-translation.workflow.html --quality showcase
    ```
