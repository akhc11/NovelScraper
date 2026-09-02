# 引き継ぎ状況 - 辞書生成リクエスト待機ディレイ & 429待機時間設定 (handoff_dict_rate_limit_and_cooldown.md)

## 概要・背景
Gemma 4 31B など TPM 制限（16k TPM）の厳しいモデルにおいて辞書生成を行う際、レート制限超過（HTTP 429 Too Many Requests）を回避し、仮に 429 が発生した場合でも枠が回復するまで待機して自動復旧できるよう、待機ディレイおよび 429 クールダウン時間をアプリ内設定UIで自由に変更可能にした。

## 実装内容
1. **`LlmTranslationConfig.kt`**:
   - `dictRequestDelaySec: Int = 0` (0=待機なし, Gemma等では10〜15秒推奨)
   - `dict429CooldownSec: Int = 60` (429 Quota Exceeded 検知時の待機秒数、デフォルト60秒)
2. **`NovelDictionaryGenerator.kt`**:
   - `generate` 引数に `requestDelaySec`, `cooldown429Sec` を追加。
   - バッチ抽出、マージ、最終レビューにおいて `LlmApiResult.QuotaExceeded`（429）を検知した場合に `cooldown429Sec` 秒待機して再試行するバックオフ処理を実装。
   - リクエスト成功後に `requestDelaySec` 秒待機するスロットリング処理を実装。
3. **`LlmTranslationEngine.kt`**:
   - `config.dictRequestDelaySec` と `config.dict429CooldownSec` を `NovelDictionaryGenerator.generate` へ渡すよう更新。
4. **`LlmSettingsDialog.kt`**:
   - 「人名辞書自動生成」セクションに「リクエスト待機 (秒)」と「429検知時の待機時間 (秒)」の入力欄を追加。
   - 設定保存時に正しく `LlmTranslationConfig` に反映・永続化。

## 検証結果
- `.\gradlew testDebugUnitTest`: BUILD SUCCESSFUL (合格)
- `.\gradlew assembleDebug`: BUILD SUCCESSFUL (合格)
