# LLM翻訳 振る舞いの標準（2026-09-04・本フォルダ作業時は毎回必読）

実装ではなく振る舞いの記録。詳細手順は `docs/handoff_llm_rebuild_plan.md`、
作業ルールは同フォルダ `AGENTS.md` を見ること。

## 流れ

1. フォルダ選択 → 開始 (`ui/LlmTranslationPanel`)
2. 物理分割ON時: 親直下の生テキストを `分割済み/<小説名>/part_*.txt` に事前分割 (`common/NovelPhysicalSplitter`)
3. 言語判定 (`pipeline/LanguageDetector`) + `.lang_cache` があれば再利用
4. 辞書ON時のみ人名辞書生成 (`pipeline/NovelDictionaryGenerator`)。失敗時はフォルダ全体スキップ
5. 並列ワーカー起動 (`engine/LlmTranslationEngine`)。1ワーカーがGeminiキー1本を専有
6. ファイルごとに3経路へ分岐:
   - 小 → 後続と束ねてバッチ翻訳 (`pipeline/BatchTranslator`、`[SEG:N]`形式)。失敗時は単体にフォールバック
   - 大 → チャンク分割して順次翻訳 (`pipeline/LargeFileTranslator`、`.parts_*`作業所)。直前訳文末尾20行のみ文脈注入
   - 単体 → 1件ずつ翻訳
7. 全経路で品質検証 (`pipeline/TranslationQualityValidator` + `CompletionMarkerHelper [SRC_END]`)。NGは次プロンプト/次モデルへ
8. 成功分のみ `翻訳完了_LLM/<元名>` に保存

## 成功・失敗・中断時の扱い

- 成功: 出力フォルダに同名ファイル。次回はスキップ
- 真性失敗 (全モデル・全プロンプトNG): `ファイル名.failed` を保存。次回はスキップ（手動削除で再試行）
- チャンク失敗: `out/chunk_N.failed` + 親 `ファイル名.failed` の二重になる（作り直し対象）
- 中断 (停止ボタン): `.failed` を作らず中間状態を保持。次回レジューム可能
- 空ファイル: 空出力を作って完了扱い
- 0バイト破損: 現行は存在のみで完了扱いになる（作り直し対象）

## 確定値

- ENサイズ比上限 220% が正規 (`engine/LlmTranslationConfig.kt:192 sizeRatioEnMax`)
- 辞書不一致時の例注入は5件（10件から削減で確定）
- APIキー: 同梱キー維持（削除しない）
- `.failed`: 自動再試行なし、手動削除運用

## やらないこと

- 自動再試行、`.failed`の自動消去
- 中断時の`.failed`作成
- チャンクへの原文二重注入（訳文末尾20行に一本化）
