# 引き継ぎ状況 - マルチワーカー並列翻訳 & 辞書並列度 ユーザー設定 (handoff_parallel_translation.md)

## 概要・背景
bash スクリプト `openrouter-v29-0-5-10-0.sh`（`PARALLEL_WORKERS=6`, `_try_claim_file`）に準拠したマルチワーカー並列翻訳アーキテクチャを実装する。

1. **設定モデルの拡張 (`LlmTranslationConfig.kt`)**
   - `parallelWorkers: Int = 2` (1〜6)
   - `dictParallelCount: Int = 4` (1〜8)
2. **排他ファイルクレーム管理 (`FileClaimManager.kt`)**
   - 複数ワーカー間での重複翻訳防止 Mutex + Set
3. **マルチワーカー並列翻訳エンジン (`LlmTranslationEngine.kt`)**
   - `parallelWorkers` 個の Coroutine Worker が同時にファイルをクレームして並列翻訳
4. **辞書生成並列度制御 (`NovelDictionaryGenerator.kt`)**
   - `Semaphore(parallelCount)` による並列数制限
5. **設定 UI への反映 (`LlmSettingsDialog.kt`)**