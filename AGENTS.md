NovelScraper2/
├── AGENTS.md                    ← 今回見せてもらった不変ルール（そのままでOK）
├── graphify-out/                ← 構造マップ（前回設定済み）
└── docs/
    ├── handoff.md                ← 進捗報告
    └── translation_specification.md ← 翻訳機能・品質維持の仕様書


# NovelScraper2 開発遵守事項（絶対遵守）

## 0. 作業前作業後の鉄則
- **作業開始前**: `graphify-out/GRAPH_REPORT.md` を読んでから作業すること
- **作業中**: 進捗報告やプランを `docs/handoff.md` に記載すること
- **作業終了前**: 必ず `docs/handoff.md` を更新すること


## 1. 修正時の鉄則
- **機能の削除禁止:** 構文エラーの修正や文字化けの修復時に、既存のロジック（特にJS抽出や表示項目）を簡略化したり削除したりしないこと。
- **一括上書きの徹底:** PowerShellの `Replace` は構文を壊すため、常にファイル全体を完全な状態で書き換えること。
- **エンコーディング:** 日本語が含まれるファイルは必ず .NET の UTF8 (BOMなし) を使用するか、Unicodeエスケープを使用すること。

## 2. UI/UX の維持
- **デバッグツール (Eruda):** フローティングボタン（歯車）は常にCSSで非表示にし、アプリのスパナボタンでのみトグルさせること。
- **スクレイピング開始:** FABは廃止。ヘッダーの「再生ボタン」を常に維持し、エンジンに接続すること。

## 3. テスト解析 (Test Run) の表示項目
テスト結果ダイアログには以下の項目を「必ず」含めること。
1. 作品名 (folderName)
2. チャプター番号 (chapter) - URL推測ロジックも含む
3. タイトル (title)
4. 次ページURL (nextUrl)
5. 本文 (content) - 先頭200文字程度を表示

## 4. 自動プリセット (autoUrl)
- `ScrapingViewModel` の `setCurrentUrl` 内での自動チェックロジックを破壊しないこと。
- `SettingsPanel` の一番下の入力欄を常に維持すること。

## 5. AIによるコーディングの原則（シンプル・堅牢・調査）
- **曖昧な場合の事前調査:** 実装方法やAPIの仕様、ライブラリの動作に曖昧な点がある場合は、コードを書き始める前に必ず公式ドキュメントや既存のコードベースを調査、またはWeb検索で確認すること。推測でコードを生成しないこと。
- **シンプルで堅牢なコード設計:** 
  - **エラーハンドリングの徹底:** ネットワークI/O、JSONパース（Kotlinx Serialization）、DataStore読み書き等のエラーが発生しうる箇所には、適切な例外処理（`try-catch`や`runCatching`）を施し、アプリがクラッシュしないようにすること。
  - **非同期処理の安全性:** UIスレッドのブロックを防ぐため、重い処理（ファイル操作、ネットワーク、HTML解析など）は適切なディスパッチャ（`Dispatchers.IO`）を指定したコルーチンで実行すること。また、メモリリークやクラッシュを防ぐため、`viewModelScope` や `lifecycleScope` などの適切なCoroutineScopeを利用し、ライフサイクルに連動させること。
  - **非推奨（Deprecated）APIの排除:** 現在のターゲットSDK（API 35）に適したAPIを使用し、Android 14/15で動作しない古いAPIや非推奨のメソッドは使用しないこと。
  - **不要な複雑化の回避:** KISS原則（Keep It Simple, Stupid）に従い、過剰な共通化や不要なライブラリ導入を避け、既存の標準ライブラリ（Compose, DataStore, Serialization）で完結するシンプルで堅牢なコードを目指すこと。

## 6. パフォーマンス・非同期処理の実装仕様 (最適化済み)
- **ファイル保存 (`FileRepository`):** `saveChapter` は `suspend` 関数であり、必ず `withContext(Dispatchers.IO)` 上で非同期実行される。本文テキストの先頭へのヘッダー（タイトル）付加は行わず、抽出された本文データをそのまま保存すること。
- **設定・履歴管理 (`PreferencesRepository`):**
  - 履歴項目の最大件数は `MAX_HISTORY_SIZE = 100` に制限し、閲覧日時（`timestamp`）順で古いものから自動的に剪定（Prune）される。
  - DataStore の全 JSON 更新（`updateHistory`, `updatePresets`, `updateFavorites`）では、デコード失敗時に既存データを保護（上書き中断）するガードが機能している。
- **WebView 初期化スクリプト (`ScrapingTask` / `CloudflareDetector`):**
  - `onPageFinished` 時の Turnstile 対策クリックと人間的スクロール模倣は、`CloudflareDetector.buildPageLoadInitJs()` を通じて単一の `evaluateJavascript` 呼び出しに集約（IPC通信を1回に維持）すること。個別に `evaluateJavascript` を複数回呼び出さないこと。
- **本文文字数判定 (`ScrapingStateMachine`):** 本文過少（`MIN_CONTENT_LENGTH`）によるリトライおよびエラー停止判定は廃止。本文が空・空白のみの場合は `(本文なし)` を自動補完し、次ページURL（`nextUrl`）が存在する限り止まらずに保存して即時進行すること。
- **構造ナレッジグラフ (`Graphify`):**
  - プロジェクト構造マップは `graphify-out/` 内に最新のデータが保持されている（`GRAPH_REPORT.md`, `graph.json`）。
  - グラフは git commit 時に post-commit フックで自動更新されるため、手動での `graphify .` 実行は不要。
  - フックが機能していない疑いがある場合のみ `graphify hook status` で確認すること。

## 7. Google翻訳における手動操作再現ロジックの死守（翻訳品質維持の絶対条件）
- **「プログラム入力」判定の回避:**
  - Google翻訳は `textarea.value = text` のような単純な値代入を行うと「プログラム入力（Bot/スクリプト）」と判定し、翻訳の質が大幅に低下（簡易エンジンへのフォールバック等）する。
  - そのため、**ユーザーが手動で Ctrl+V 貼り付けした時と100%同一のブラウザイベントシーケンスを絶対に維持・改変禁止**とする：
    1. `textarea.focus()` → `textarea.select()` で全選択
    2. `ClipboardEvent('paste', { clipboardData })` を発火（Ctrl+Vと同一）
    3. `InputEvent('input', { inputType: 'insertFromPaste' })` を発火（ブラウザがユーザー貼り付けと認識）
    4. `Event('change')` を発火
  - このイベントシーケンスを簡略化したり `textarea.value` のみへの変更に戻すことは厳禁。
- **バックグラウンド実行アーキテクチャ:**
  - 翻訳タスク（`TranslationTask`）は、画面UIのライフサイクル凍結を回避するため、`ScrapingTask` と同様に `WebView(context.applicationContext)` による独立バックグラウンドインスタンスで動作させる。

## 8. 完了の定義（Definition of Done）
- 変更したファイルに関連するテストが存在する場合は実行し、パスすることを確認する
- 変更した関数・クラスが他の場所から呼び出されていないか `graphify-out/graph.json` で確認する
- 「完了しました」と報告する前に、上記2点をチェックしたことを明記する