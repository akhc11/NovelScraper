# 敵対的レビュー (Adversarial Review) - LLM翻訳設定の柔軟性と永続化

## 1. 致命的・最優先の不備 (Critical)
1. **設定の永続化の欠如 (Persistence Failure)**:
   - 現在 `LlmTranslationConfig` は `LlmTranslationEngine` 内のメモリ変数 (`var config`) に保持されているのみ。
   - DataStore (`PreferencesRepository`) へのシリアライズ保存・起動時復元が未実装のため、**アプリをタスクキル・再起動すると入力したAPIキーや設定がすべて初期化される**。

## 2. 設定項目の露出不足・ハードコード箇所の指摘 (Gaps vs Script)
1. **プロンプトフォールバック順序**:
   - スクリプト: `GEMINI_PROMPTS=(1 6 7)` のように複数プロンプトを配列で指定し、品質NG時に次へフォールバック。
   - 現状UI: 単一のプロンプト番号 (`selectedPromptNumber`) のみ入力可能。フォールバック配列 (`fallbackPromptNumbers`) をUIから編集できない。
2. **チャンク & バッチサイズパラメータ**:
   - スクリプト: `SPLIT_SIZE_BYTES=8000`, `SPLIT_THRESHOLD_BYTES=13000`, `CHUNK_SIZE_BYTES=12000`, `BATCH_MAX_BYTES=12000` を自由に調整可能。
   - 現状UI: コード内の固定値（UIから変更不可）。
3. **辞書生成パラメータ**:
   - スクリプト: `DICT_TOTAL_PARTS=100`, `DICT_BATCH_PARTS=10`, `DICT_DRV="gemini4"` を調整可能。
   - 現状UI: 有効/無効チェックボックスのみで、パート数やドライバーを変更不可。
4. **待機時間 & クールダウン**:
   - スクリプト: `SLEEP_SEC`, `ROTATION_SWITCH_SLEEP_SEC=20`, `QUOTA_SLEEP_BASE_SEC=60` 等を調整可能。
   - 現状UI: 待機時間設定欄が露出していない。
5. **OpenRouter / Groq サンプリングパラメータ**:
   - スクリプト: `temperature`, `top_p`, `top_k`, `repetition_penalty` を調整可能。
   - 現状UI: モデル名・プロバイダー固定名のみで、サンプリングパラメータ欄がない。
6. **出力サブフォルダ名**:
   - スクリプト: `OUTPUT_SUBDIR="翻訳完了"` を自由に変更可能。
   - 現状UI: `"翻訳完了_LLM"` 固定。