# スクレイピング機能 専用開発ルール (scraper 直下)

## 対象スコープ
本ルールは `scraper/` 配下の全ファイルおよび関連コンポーネントを対象とする：
- タスク・制御: `ScrapingTask.kt`, `ScrapingScriptBuilder.kt`, `ScrapingStateMachine.kt`, `CloudflareDetector.kt`, `ScraperService.kt`, `ScraperServiceController.kt`
- 設定・パーサ: `ScraperConfig.kt`, `ChapterNumberExtractor.kt`, `UrlExtractor.kt`, `ExcludeSelectorCodec.kt`, `ScrapingResult.kt`
- 関連UI: `InspectElementDialog.kt`, `SettingsPanel.kt`, `TestResultPanel.kt`, `HeaderToolbar.kt`

---

## 0. アーキテクチャ設計図 (作業前必読)
- スクレイピング機能の全体パイプライン、IPC、および状態遷移データフローは [`docs/archify/scraper.workflow.json`](../../../../../../../../docs/archify/scraper.workflow.json)（HTML可視化: [`docs/archify/scraper.workflow.html`](../../../../../../../../docs/archify/scraper.workflow.html)）に定義されている。
- スクレイピング改修時は、必ず作業前に上記設計図を確認し、コンポーネント間の責務境界やデータフローを把握すること。

---

## 1. UI/UX・インスペクター仕様

### デバッグツール (Eruda)
- フローティングボタン（歯車アイコン）は常にCSSおよびAPIで非表示を維持すること。
- アプリのスパナボタン（デバッグ切り替え）でのみトグルさせること。

### スクレイピング開始
- スクレイピングの開始・停止は、ヘッダーツールバーの「再生/停止ボタン」を通じてエンジン（`ScrapingViewModel` / `ScraperServiceController`）と連動させること。

### インスペクター (虫眼鏡) & JavaScriptInterface
- **`typeof` 判定の禁止**: Android WebView の `@JavascriptInterface` メソッドは、JavaScript 側で `typeof === 'function'` 判定を行ってはならない（Android の仕様上 false になるため、必ず直接呼び出すこと）。
- **リスナー破棄**: インスペクター OFF 時には必ず `removeEventListener` でイベントリスナーを完全破棄し、ゾンビ化（重複実行・メモリリーク）を防ぐこと。

### お気に入り (星マーク)
- **タップ**: 現在のページ・プリセットをお気に入りに追加。
- **長押し (250ms)**: お気に入り一覧ダイアログを開閉する。

---

## 2. テスト解析 (Test Run) 表示仕様

テスト結果ダイアログ（`TestResultPanel`）には以下の5項目を必ず含めること：
1. **作品名** (`folderName`)
2. **チャプター番号** (`chapter`) - URL推測ロジックも含む
3. **タイトル** (`title`)
4. **次ページURL** (`nextUrl`)
5. **本文** (`content`) - 先頭200文字程度を表示

---

## 3. 自動プリセット (autoUrl)

- URLセット時に、プリセット一覧から `autoUrl`（ドメイン/前方一致）が合致する設定を自動ロードする挙動を維持すること。
- `SettingsPanel` の最下部にある autoUrl 入力欄を維持すること。

---

## 4. WebView 制御 & スクラップ進行仕様

### 初期化スクリプトの IPC 単一化
- `onPageFinished` 時の Turnstile 対策クリックおよび人間的スクロール模倣は、`CloudflareDetector.buildPageLoadInitJs()` を通じて**単一の `evaluateJavascript` 呼び出しに集約（IPC通信を1回に維持）**すること。

### 本文空時のノンストップ進行
- 本文テキストが空または空白のみの場合は `(本文なし)` を自動補完し、次ページURL（`nextUrl`）が存在する限りエラー停止せず保存して即時進行すること。

---

## 5. 完了の定義 (DoD)
- スクレイピング関連のユニットテストが全て合格すること：
  - `ScrapingScriptBuilderTest`
  - `ChapterNumberExtractorTest`
  - `ScrapingStateMachineTest`
  - `StateLogicTest`
  - `UrlExtractorTest`
  - `PresetJsonTest`
- **アーキテクチャ・設計図の同期 (Archify)**:
  - スクレイピングのデータフロー、IPC、状態遷移、保存処理に変更を加えた場合は、必ず [`docs/archify/scraper.workflow.json`](../../../../../../../../docs/archify/scraper.workflow.json) を同期更新し、以下のコマンドで検証（Showcase合格）・HTML生成を行うこと：
    ```bash
    node .agents/skills/archify/bin/archify.mjs deliver workflow docs/archify/scraper.workflow.json docs/archify/scraper.workflow.html --quality showcase
    ```
