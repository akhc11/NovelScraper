# 進捗報告 (Handoff) - ユーザー定義プロンプト順序プリセット登録・削除機能 完了

## 実装内容
1. **データ構造**:
   - `PromptOrderPreset` (`label: String`, `order: List<Int>`) を `LlmTranslationConfig` に追加。
   - DataStore で永続化され、アプリ再起動後も保存されたプリセットが保持される。
2. **UI**:
   - 「⚡ プロンプト順序 (全モデル一括適用)」エリアに「＋ 登録」ボタンを新設。
   - プリセット登録ダイアログ（プリセット名とプロンプト順序 `3, 7, 7` 等）から自由に追加可能。
   - プリセットチップをタップで全登録モデルに一括適用、長押しでプリセット削除確認ダイアログを表示して削除可能。

## 検証結果
- 単体テスト (`testDebugUnitTest`): 全50件すべて PASS
- デバッグビルド (`assembleDebug`): BUILD SUCCESSFUL (22s)