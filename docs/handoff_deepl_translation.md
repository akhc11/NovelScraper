# 引き継ぎ状況 - DeepL Web バックグラウンド翻訳機能 実装完了 (handoff_deepl_translation.md)

## 概要
Google翻訳に加え、**DeepLのWebサイト（`www.deepl.com/ja/translator`）を活用した独立バックグラウンド自動翻訳機能**を新規実装しました。
Google翻訳とDeepL翻訳を独立したタスクとして管理し、**2つのエンジンを同時に並行してバックグラウンド実行可能**なアーキテクチャを実現しました。

---

## 主な実装内容と仕様

### 1. DeepL専用タスク（[`DeeplTranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/DeeplTranslationTask.kt)）
- **独立バックグラウンドWebView:** UIライフサイクル（Activity/Compose）から完全に分離した `WebView(context.applicationContext)` で動作。ホーム画面や他アプリを開いても停止しない。
- **PC用UserAgent (`isDesktop = true`):** モバイル用アプリ誘導モーダルを回避し、Web版UIを確実にロード。
- **手動ペースト完全再現シーケンス:**
  - `focus()` → `select()`（全選択）
  - `ClipboardEvent('paste', { clipboardData })` 発火（Ctrl+Vと同一）
  - `InputEvent('input', { inputType: 'insertFromPaste' })` 発火（ユーザー貼り付け認識）
  - `Event('change')` 発火
- **無料Web版の文字数制限対応:**
  - 1チャンク最大 **1,300文字**（Google翻訳は3,500文字）で自動分割。
  - レートリミット対策としてチャンク間ウェイトを **2.0秒** に設定。
- **翻訳完了検知:**
  - エラー監視（文字数制限等）、スピナー/プログレスバー監視、翻訳本文の安定判定（3回連続一致で確定）。

### 2. 出力フォルダ分離（[`TranslationFileStore.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/TranslationFileStore.kt)）
- **Google翻訳出力先:** `翻訳完了_GOOGLE`
- **DeepL翻訳出力先:** `翻訳完了_DEEPL`
- 出力先が完全に分離されているため、同一フォルダを指定してGoogleとDeepLを同時に回してもファイル競合・上書き破損が起きない。

### 3. 並行管理アーキテクチャ（[`ScrapingViewModel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ScrapingViewModel.kt)）
- `googleTranslationTask` と `deeplTranslationTask` の2インスタンスを並行保持。
- `syncServiceStatus()` により、スクレイピングタスク・Google翻訳・DeepL翻訳の3者の状態を合算してフォアグラウンド通知を更新。

### 4. タブ切り替え式UI（[`TranslationPanel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/components/TranslationPanel.kt)）
- パネル上部に `[ Google翻訳 ]` と `[ DeepL翻訳 ]` のタブ切替を配置。
- 実行中のエンジンには「●」の実行中バッジを表示。
- 選択中タブに応じてフォルダ選択、進捗表示、Webページ確認ボタン、開始/停止ボタンが連動。

---

## 変更・作成ファイル一覧
1. `[NEW]` [`DeeplTranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/DeeplTranslationTask.kt) - DeepL Web翻訳専用タスク
2. `[MODIFY]` [`TranslationFileStore.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/TranslationFileStore.kt) - 出力先フォルダパラメータ化（`翻訳完了_GOOGLE` / `翻訳完了_DEEPL`）
3. `[MODIFY]` [`MainUiState.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/MainUiState.kt) - `TranslationEngine` / `EngineTranslationState` 導入
4. `[MODIFY]` [`ScrapingViewModel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ScrapingViewModel.kt) - Google/DeepL 2系統並行管理
5. `[MODIFY]` [`TranslationPanel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/components/TranslationPanel.kt) - タブ切替UI
6. `[MODIFY]` [`MainScreen.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/MainScreen.kt) - パネルコールバックとステータス表示接続
7. `[NEW]` [`docs/handoff_deepl_translation.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/docs/handoff_deepl_translation.md) - 本引き継ぎ書
