# 引き継ぎ状況 - プロンプト設定の一元化 ＆ 言語別自動プロンプトの柔軟なカスタマイズ (handoff_prompt_settings_unification.md)

## 完了日: 2026-09-03

## 概要
APIキータブとモデルタブに散らばっていたプロンプト設定を「モデル」タブ最上部に一元化し、言語連動自動選択時の各言語プロンプト順序（韓・中・英）をユーザーが自由に変更・保存できるように改修した。

## 実装内容
1. `LlmTranslationConfig.kt`:
   - `autoPromptOrderKorean: List<Int> = listOf(3, 7)`
   - `autoPromptOrderChinese: List<Int> = listOf(1, 1)`
   - `autoPromptOrderEnglish: List<Int> = listOf(2, 7)`
   - `getEffectivePromptOrder` でユーザー設定値を動的返却
2. `LlmSettingsDialog.kt`:
   - 「APIキー」タブからプロンプト自動選択を完全撤去
   - 「モデル」タブ最上部のカード内に「言語連動 プロンプト自動選択」を統合
   - OFF時: 手動一括プロンプト順序 ＋ プリセット選択
   - ON時: 韓国語・中国語・英語それぞれのプロンプト順序入力欄を表示
   - 保存処理に対応
3. `LlmPipelineTest.kt`:
   - `testEffectivePromptOrder_AutoAndManual` にカスタム順序の検証を追加
4. 検証:
   - 全単体テスト合格
   - `assembleDebug` 成功
