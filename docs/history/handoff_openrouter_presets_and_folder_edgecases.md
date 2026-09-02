# 引き継ぎ状況 - OpenRouterプリセット整理 & フォルダ処理堅牢化 (handoff_openrouter_presets_and_folder_edgecases.md)

## 概要・背景
1. **OpenRouter プリセットの整理 (`LlmSettingsDialog.kt`)**
   - 不要なモデルを削除し、`DeepSeek V3.2` (`deepseek/deepseek-v3.2`) と `GLM 5.3 Flash` (`z-ai/glm-5.3-flash`) のみに厳選。
2. **フォルダ指定時のエッジケース対応・堅牢化 (`LlmTranslationEngine.kt`)**
   - 0バイト空ファイルのスキップガード
   - 物理分割後の分割済みサブフォルダの安全な処理