# 引き継ぎ状況 - 文字コード自動判別・辞書動的ルーティング・バッチ柔軟パース (handoff_charset_dict_batch_robustness.md)

## 概要・背景
第2回敵対的レビューで特定された以下の欠陥および未実装機能を根本治療する。

1. **文字コード自動判別ヘルパー（`TextCharsetDetector.kt`）の新設**
   - BOM 判定および UTF-8 / GB18030 / EUC-KR / Shift_JIS 自動判別デコード。
2. **原文テキストクレンジング（`TextCleanser.kt`）の新設**
   - 有害な制御文字・NULL バイトのサニタイズ。
3. **人名辞書生成のプロバイダー動的ルーティング**
   - `dictProvider` に応じた Gemini / OpenRouter / Groq の適切な API クライアント・認証キー・エンドポイントの呼び出し。
4. **バッチ翻訳セグメントマーカーの柔軟な正規表現パース（`BatchTranslator.kt`）**
   - `[SEG: 1]`, `【SEG:1】` などの表記ゆれ吸収。
5. **1フォルダあたりの処理上限件数（`filesPerFolder`）の実装**
6. **フォルダ内言語キャッシュ（`.lang_cache`）の実装**