# 引き継ぎ状況 - 1キー1ワーカー専有（Exclusive Key Claim）アーキテクチャ (handoff_worker_key_exclusive_claim.md)

## 概要・背景
bash スクリプト `openrouter-v29-0-5-10-0.sh`（`_gemini_claim_new_key`, `_advance_gemini_rotation`）の並列モード仕様に 100% 準拠。
複数ワーカーが同一の Gemini API キーを共有・重複使用することを完全に防ぎ、各ワーカーが 1 つのキーを排他専有してモデル巡回するアーキテクチャを実装する。

1. **APIキー排他プールマネージャー (`ApiKeyPoolManager.kt`) の新設**
2. **専有型ローテーションマネージャー (`LlmRotationManager.kt`) の刷新**
3. **並列エンジンの連携 (`LlmTranslationEngine.kt`)**