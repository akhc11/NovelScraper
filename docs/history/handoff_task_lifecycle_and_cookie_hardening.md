# 引き継ぎ状況 - タスクライフサイクル完全破棄・Cookie干渉防止・ナビゲーション整合化

## 1. 概要・現象
- **スクレイピング完了時のWebView未破棄**:
  - ScrapingTask が正常終了（全話保存完了）またはエラー停止した際、	ask.stop() が呼ばれずにバックグラウンド WebView がメモリ上に残留するリスク。
- **スクレイピング開始時のCookie汚染**:
  - 画面側で即時翻訳を使った直後にスクレイピングを開始すると、裏 WebView に googtrans Cookie が送信され、HTML 抽出が狂うリスク。
- **履歴・お気に入りタップ時の二重ナビゲーション判定**:
  - パネルコールバック側での直接 onNavigate 呼び出しと、LaunchedEffect(currentUrl) での二重評価。

## 2. 修正方針
1. ScrapingViewModel.kt:
   - onTaskFinished で 	ask.stop() を呼び出し、裏 WebView と CoroutineScope を完全破棄。
   - startScraping 呼び出し時に WebViewHelper.clearGoogleTranslateCookies(targetUrl) を実行。
2. MainScreen.kt:
   - 履歴・お気に入りのアイテムタップ処理を setCurrentUrl に統一し、ナビゲーションフローを一本化。

## 3. 検証・ビルド手順
- 	estDebugUnitTest で単体テスト実行
- ssembleDebug でビルド確認