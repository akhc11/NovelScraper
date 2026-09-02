# 引き継ぎ状況 - 辞書送信サイズ50KB最適化 & UIシンプル入力欄化 (handoff_dict_batch_size_cleanup.md)

## 概要・背景
中国語・韓国語（UTF-8 3バイト/文字）の実測トークン比（15,000トークン ≒ 約50KB）に基づき、
デフォルト値を 50,000B (50KB) に更新し、UIのボタンを撤去して自由入力欄のみに整理。

1. **`LlmTranslationConfig.kt`: `dictBatchMaxBytes: Int = 50000` に更新**
2. **`LlmSettingsDialog.kt`: ボタン撤去、自由入力欄のみにシンプル化**