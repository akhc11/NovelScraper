# 引き継ぎ状況 - 辞書スマートバッチング（サイズ/トークン基準） & ユーザー設定 (handoff_smart_dict_token_batching.md)

## 概要・背景
ファイル数固定を廃止し、バイト数/トークン数基準のスマートバッチングエンジンを実装。
巨大ファイルは自動分割、小ファイルは自動結合し、ユーザーが 1 回あたりの送信サイズ（トークン目安）を設定可能にする。

1. **設定モデルの拡張 (`LlmTranslationConfig.kt`)**
   - `dictBatchMaxBytes: Int = 18000` (8KB〜40KB)
2. **スマートバッチ構築ロジック (`NovelDictionaryGenerator.kt`)**
   - 巨大ファイル自動分割 ＋ 小ファイル自動結合
3. **設定 UI への反映 (`LlmSettingsDialog.kt`)**