# 進捗報告 (Handoff) - LLM翻訳設定の完全柔軟化・永続化・3タブUI統合 完了

## 実装内容
1. **設定の完全永続化 (DataStore)**:
   - `PreferencesRepository.kt` に `PreferencesKeys.LLM_CONFIG` を追加。
   - JSON シリアライズ/デコードによる `LlmTranslationConfig` の安全な読み書きを実装。
   - `ScrapingViewModel` 起動時の自動反映および設定変更時の非同期保存を接続。
2. **全パラメータのUI露出 (3タブダイアログ `LlmSettingsDialog.kt`)**:
   - **基本設定タブ**: プロバイダー、APIキープール、モデル巡回リスト、429ローテーション、プロンプト順序（フォールバック配列 `1, 6, 7`）、4大トグル（完了マーカー、物理分割、人名辞書、直前文脈注入）。
   - **詳細チューニングタブ**: 出力サブフォルダ名、リクエスト間隔秒、429クールダウン秒、物理分割サイズ(B)、大ファイル判定閾値(B)、チャンクサイズ(B)、バッチ上限(B)、辞書生成モデル・総パート数・バッチパート数、OpenRouter/Groqサンプリングパラメータ（Temperature、Top-P、Repetition Penalty）。
   - **プロンプト編集タブ**: 1〜7番のプロンプト本文を閲覧・直接エディタで編集・「初期値に戻す」リセット機能。
3. **PromptBuilder & エンジン連携**:
   - `customPrompts` を `PromptBuilder` に渡し、ユーザー編集プロンプトを優先使用。
   - `config.outputSubDir` による動的フォルダ出力。
   - サンプリングパラメータ・待機時間の完全反映。

## 検証結果
- **単体テスト (`testDebugUnitTest`)**: 全46件すべて PASS
- **ビルド (`assembleDebug`)**: BUILD SUCCESSFUL (51s)