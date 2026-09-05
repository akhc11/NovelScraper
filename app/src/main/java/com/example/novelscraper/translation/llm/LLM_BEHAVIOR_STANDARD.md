# LLM翻訳 振る舞いメモ（必要時のみ読む）

実装ではなく振る舞いの記録。作業ルールは同フォルダ`AGENTS.md`、詳細手順は`docs/handoff_llm_rebuild_plan.md`。
数値（サイズ比・件数・閾値）はコード定数・設定画面が正本。ここに複写しない。

## 流れ

1. フォルダ選択 → 開始 (`ui/LlmTranslationPanel`)
2. 物理分割ON時：親直下の生テキストを`分割済み/<小説名>/part_*.txt`に事前分割 (`common/NovelPhysicalSplitter`)
3. 言語判定 (`pipeline/LanguageDetector`)＋`.lang_cache`があれば再利用
4. 辞書ON時のみ人名辞書生成 (`pipeline/NovelDictionaryGenerator`)。失敗時はフォルダ全体スキップ
5. 並列ワーカー起動 (`engine/LlmTranslationEngine`)。1ワーカーがGeminiキー1本を専有
6. ファイルごとに3経路へ分岐：
   - 小 → 後続と束ねてバッチ翻訳 (`pipeline/BatchTranslator`、`<doc>/<trans>`タグ形式。JSON Schema時はJSON)。失敗分のみ単体にフォールバック
   - 大 → チャンク分割して順次翻訳 (`pipeline/LargeFileTranslator`、`.parts_*`作業所)。直前訳文末尾のみ文脈注入
   - 単体 → 1件ずつ翻訳
7. 全経路で品質検証 (`pipeline/TranslationQualityValidator`＋`CompletionMarkerHelper`：単体・大ファイルは`[SRC_END]`、バッチは閉じタグ)。NGは次プロンプト/次モデルへ
8. 成功分のみ`翻訳完了_LLM/<元名>`に保存

## 成功・失敗・中断時の扱い

- 成功：出力フォルダに同名ファイル。次回はスキップ
- 真性失敗（全モデル・全プロンプトNG）：`ファイル名.failed`を保存。次回はスキップ（手動削除で再試行）
- チャンク失敗：親直下に`.failed`は作らず`out/chunk_N.failed`のみ記録。`.parts_*`を残して次回レジューム
- 中断（停止ボタン）：`.failed`を作らず中間状態を保持。次回レジューム可能
- 空ファイル：空出力を作って完了扱い
- 0バイト破損：未完了扱いとして警告ログを出し再処理

## 確定値

- サイズ比・例注入件数等の数値は`LlmTranslationConfig`・`TranslationQualityValidator`の定数が正本
- APIキー: 同梱キー維持（削除しない）
- `.failed`: 自動再試行なし、手動削除運用

## やらないこと

- 自動再試行、`.failed`の自動消去
- 中断時の`.failed`作成
- チャンクへの原文二重注入（訳文末尾に一本化）
