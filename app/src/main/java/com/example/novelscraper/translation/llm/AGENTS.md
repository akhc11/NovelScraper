# LLM翻訳 — 専用ルール

対象：`translation/llm/`配下（`api/`・`engine/`・`pipeline/`・`prompt/`・`rotation/`・`ui/`）＋`translation/common/NovelPhysicalSplitter.kt`。
全体像が必要な時のみ：`LLM_BEHAVIOR_STANDARD.md`→`docs/archify/llm-translation.workflow.json`の順で読む（毎回全読みしない）。

## 外部仕様（変える時はAsk first）

- フォルダ構成（`分割済み/翻訳完了_LLM/.parts_*`）、SAF経由I/O、並列ワーカー、バッチ/大ファイル/単体の3経路、辞書→翻訳フロー、DataStore永続化
- 現状維持：
  - `LlmTranslationConfig` 内ハードコード API キー (利便性のため残す)
  - `.failed` は手動削除運用 (自動再試行を作らない。二重 `.failed` は作らない)
- 内部テーブル・閾値・分岐・クライアント組立は必要に応じて書き直してよい

## Invariants（手段は問わない。不変条件だけ守る）

### 1. I/O境界は無言で落とさない
```kotlin
// NG: 失敗を消して次へ
val text = readFileContent(f) ?: continue
save(out, translated) // 戻り値無視
// OK: 理由付きFailure＋ログ＋テスト。save失敗は未完了扱い
```
完了判定は`exists && length > 0`。0バイトは破損＝未完了。

### 2. claimは必ず解放する
`FileClaimManager`はjob単位で新規生成。取得→`try/finally`で解放。`clear()`全消去は使わない。バッチは確定分のみ一括claim。

### 3. 大ファイルは結合まで親に`.failed`を作らない
失敗は`out/chunk_N.failed`のみ。進捗は`onChunkProgress`で伝播。`.parts_*`の存在自体が中断シグナル。

### 4. 文脈注入は一本化
チャンク2以降は直前訳文末尾（`prevTranslatedSummary`）のみ。原文末尾の重ね注入はしない。バッチは束ね→失敗時のみ単体フォールバック。

### 5. 品質判定はかな率主軸
`LanguageDetector`に一本化し常用漢字で誤検知させない。サイズ比の値は`TranslationQualityValidator`の定数が正本（本ファイルに複写しない）。

## Conventions

- サニタイズ順序：```剥離→前口上除去→末尾後口上除去→`CompletionMarkerHelper`検証
- APIパラメータはnullable透過（`?: 0.5`等の決め打ち既定値埋め込みをしない）
- 走査ループ内の`findFile/listFiles`連打はしない（起動時キャッシュ参照）

## 数値はコードが正本

`.lang_cache`・`dictBatchMaxBytes`等の上限・既定値は`LlmTranslationConfig`＋設定画面が正本。本ファイル・BEHAVIORに複写しない（ドリフト防止）。

## Commands

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.example.novelscraper.LlmPipelineTest"
```

## Definition of Done

- 常時：`LlmPipelineTest`を含むfocusedテストが全件パス
- パイプライン構成・データフローを変えた時のみ：フルテスト＋実機（並列・中断→再開・長編）＋該当Archify JSON/HTML同期
