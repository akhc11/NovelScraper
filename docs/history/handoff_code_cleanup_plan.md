# 機能別 厳格な敵対的レビュー & 不要コード削除プラン

本報告書は、`AGENTS.md` の遵守事項に基づき、`NovelScraper2` の全機能・全コードを厳しく精査し、不要なコード・デッドコード・不自然な挙動・過剰な処理を洗い出して策定した削除・改善プランです。

---

## 1. アプリ機能ごとの厳格な敵対的レビュー結果

### 機能 1: 即時翻訳 (LiveTranslate / DOMインプレース翻訳)
- **現状の評価**: 
  - `element.js` を用いた翻訳自体は高速で動作している。
- **発見された不自然な挙動 / 不要な動作**:
  - **【ユーザー指摘の根本原因】翻訳後のマウスカーソル重なり（ホバー）によるテキスト勝手強調・ツールチップ浮遊**:
    - Google翻訳ランタイムがテキストを `<font class="goog-text-highlight">` で囲み、マウスカーソルが重なるとホバーハイライト（背景色変更）や、原文表示のバルーンツールチップ（`#goog-gt-tt`, `.goog-te-balloon-frame`, `.goog-tooltip`）を動的に表示する。
    - 現在の CSS（`LiveTranslateScriptBuilder.kt`）では `#goog-gt-tt` や `.goog-te-balloon-frame`、`font.goog-text-highlight` の完全無効化が不足している。
- **対策**:
  - CSS にホバー強調の完全無力化（`background: transparent !important; box-shadow: none !important;`）と、ツールチップの完全非表示（`#goog-gt-tt, .goog-te-balloon-frame { display: none !important; visibility: hidden !important; }`）を追加。

---

### 機能 2: インスペクター (虫眼鏡) & セレクタ取得
- **現状の評価**:
  - v3（候補ポップアップ方式）への移行により、WebView内で直接候補を選択・適用できるようになった。
- **発見された不要コード (デッドコード)**:
  - **`InspectElementDialog`（旧 Compose ダイアログ）の完全な残骸化**:
    - `AppDialogs.kt` の `InspectElementDialog`（104行）は、v3化によって一切呼び出されなくなっている。
    - それに付随する `ActiveDialog.InspectElement`（`MainUiState.kt`）、`viewModel.showInspectElementDialog`（`ScrapingViewModel.kt`）、`NovelScraperBridge.onInspectResult`（`MainActivity.kt`）もすべてデッドコード。
- **対策**:
  - `InspectElementDialog` および関連するブリッジメソッド・UiState・ViewModel関数を完全削除し、コードベースをスリム化。

---

### 機能 3: Webバックグラウンド翻訳 (Google / DeepL)
- **現状の評価**:
  - `BaseWebTranslationTask` と `WebTranslationStrategy`（Strategy パターン）への統合、`TranslationQueueManager` への分離により、手動操作再現（ルール7）を死守しつつ安定動作している。
- **発見された不要コード (デッドコード)**:
  - **`TranslationTask.kt` と `DeeplTranslationTask.kt` の完全な未使用化**:
    - `TranslationQueueManager.kt` が `BaseWebTranslationTask(strategy = GoogleTranslationStrategy())` などを直接生成しているため、継承クラスであった `TranslationTask.kt`（28行）と `DeeplTranslationTask.kt`（28行）はプロジェクト内のどこからも呼ばれていない。
  - **`FolderItem.kt` の未使用フィールド**:
    - `totalTextFiles`, `untranslatedGoogleCount`, `untranslatedDeeplCount` は旧自作ピッカー時代の残骸であり、現在は一切参照・代入されていない。
  - **`TranslationFileStore.kt` の未使用定数**:
    - `OUTPUT_FOLDER_NAME`（旧互換用）が参照されていない。
- **対策**:
  - `TranslationTask.kt` と `DeeplTranslationTask.kt` をファイルごと削除。
  - `FolderItem.kt` の不要フィールドを削除。
  - `TranslationFileStore.kt` の未使用定数を削除。

---

### 機能 4: スクレイピングエンジン (`ScrapingTask` / `ScrapingStateMachine`)
- **現状の評価**:
  - 状態遷移とWebView操作が綺麗に分離されており、Cloudflare対策や自動巡回も堅牢。
- **発見された不要コード (デッドコード)**:
  - **`ScrapingStateMachine.Action.Retry` と `Action.Error`**:
    - 定義されているが、StateMachine内部ではリトライ時に `Action.WaitAndLoad`、エラー停止時に `Action.Finish("エラー停止: ...")` を生成して返しているため、これら2つのサブクラスは決して生成されず、`ScrapingTask.kt` 側の分岐も到達不能なデッドコードになっている。
- **対策**:
  - `Action.Retry` と `Action.Error` の定義および `ScrapingTask.kt` 内の到達不能分岐を削除。

---

### 機能 5: プリセット・履歴・お気に入り・UI
- **現状の評価**:
  - Single Source of Truth（`MainUiState`）に基づき、オーバーレイやダイアログの排他制御が整理されている。
  - 不足・余剰なUIはなく、シンプルで堅牢。

---

## 2. 削除・修正対象ファイル一覧

| ファイル | 変更内容 | 削減行数 (見込み) |
|---|---|---|
| `LiveTranslateScriptBuilder.kt` | マウスホバー時の強調（ハイライト）およびバルーンツールチップを完全抹殺するCSS修正 | 修正 |
| `AppDialogs.kt` | 未使用の `InspectElementDialog` コンポーザブルを削除 | 約 -104行 |
| `MainUiState.kt` | 未使用の `ActiveDialog.InspectElement` を削除 | 約 -2行 |
| `ScrapingViewModel.kt` | 未使用の `showInspectElementDialog` を削除 | 約 -4行 |
| `MainActivity.kt` | 未使用の `onInspectResult` ブリッジ定義および MainScreen 内のハンドラを削除 | 約 -15行 |
| `MainScreen.kt` | 未使用の `ActiveDialog.InspectElement` 表示ブロックを削除 | 約 -20行 |
| `TranslationTask.kt` | **ファイル削除**（未使用ラッパークラス） | -28行 |
| `DeeplTranslationTask.kt` | **ファイル削除**（未使用ラッパークラス） | -28行 |
| `FolderItem.kt` | 未使用のカウント用プロパティ（3個）を削除 | 約 -4行 |
| `TranslationFileStore.kt` | 未使用の定数 `OUTPUT_FOLDER_NAME` を削除 | 約 -2行 |
| `ScrapingStateMachine.kt` | 未使用の `Action.Retry`, `Action.Error` サブクラスを削除 | 約 -3行 |
| `ScrapingTask.kt` | 到達不能な `Action.Retry`, `Action.Error` 分岐を削除 | 約 -8行 |
