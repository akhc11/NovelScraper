# 引き継ぎ状況 - 即時翻訳・インスペクター・テスト実行の完全相互排他ガード適用

## 1. 概要・現象
- 敵対的レビュー（Adversarial Review）により、以下の操作交差時の潜在的リスクを特定：
  1. インスペクターON中に「即時翻訳（🌐）」を押すと、インスペクターのイベントリスナーが残ったまま翻訳が走る。
  2. 即時翻訳ON中に「テスト実行（✔）」を押すと、Google翻訳で書き換えられた日本語テキストおよび <font> タグ構造に対してスクレイピング解析が走ってしまう。
  3. ページ遷移時に googtrans Cookie が次ページに漏洩する可能性。

## 2. 修正内容
1. MainActivity.kt:
   - 	oggleLiveTranslation 実行時、もし isInspectMode なら iewModel.setInspectMode(false) を呼び出してインスペクターを自動解除。
   - performTestRun 実行時、もし isLiveTranslated または isLiveTranslating なら uildRestoreScript を実行して原文に復元してから解析を実行。
2. MainScreen.kt:
   - WebViewClient.onPageFinished でページ遷移時に WebViewHelper.clearGoogleTranslateCookies(url) を呼び出し、Cookie の別ページ残存を遮断。

## 3. 検証・ビルド手順
- 	estDebugUnitTest で単体テスト実行
- ssembleDebug でビルド確認