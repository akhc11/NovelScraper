# 引き継ぎ状況 - 辞書AI Studio標準化・KB単位化・カード全撤去 (handoff_dict_ui_and_aistudio_defaults.md)

## 概要・背景
1. 辞書プロバイダーのデフォルトを GEMINI、モデルを gemma-4-31b-it に修正
2. 送信サイズを bytes から KB (デフォルト 50KB) に変更
3. パート数の下の不要なカードボタンを完全撤去

1. **`LlmTranslationConfig.kt`: dictProvider=GEMINI, dictModel=gemma-4-31b-it**
2. **`LlmSettingsDialog.kt`: KB化 ＆ カード削除**