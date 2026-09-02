# 引き継ぎ状況 - 辞書キープール分散並列 & 最終レビュー & スケーラビリティ (handoff_dict_roundrobin_and_review.md)

## 概要・背景
1. **キープール分散並列 (`NovelDictionaryGenerator.kt`)**
   - 登録された全 Gemini API キーに抽出バッチを均等分散
2. **最終レビュー (`NovelDictionaryGenerator.kt`)**
   - `REVIEW_PROMPT` で地名・役職・一般名詞を最終クレンジング
3. **総パート数の自由設定 (`LlmSettingsDialog.kt`)**
   - 100 / 200 / 300 / 500 クイックボタン ＋ 自由入力欄