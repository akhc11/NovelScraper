# プロジェクト構成とアーキテクチャのレビュー報告書

このレポートは、NovelScraper2 プロジェクトのアーキテクチャおよび現在の組織的状態に関する包括的なレビューを提供します。

## 1. Graphify ナレッジグラフの更新
ナレッジグラフを最新のコードベース（コミット: `7c10c5f`）を反映するように手動で更新しました。
- **グラフの状態**: 最新（Up-to-date）
- **主要なハブ**: `ScrapingViewModel`, `Action`, `ScrapingStateMachine`, `MainActivity`, `ScraperConfig`
- **リレーションの整合性**: エッジ（関係性）の 99% がセマンティック抽出されており、コード間の連携が良好であることが確認されました。

## 2. アーキテクチャの概要 (MVVM)
プロジェクトは、Jetpack Compose の宣言的な性質に最適化されたクリーンな **MVVM (Model-View-ViewModel)** アーキテクチャを採用しています。

### 主要レイヤー:
- **プレゼンテーション (UI)**:
  - `MainScreen` & `HeaderToolbar`: 高レベルの UI 構造。
  - `ui.components`: 機能別パネル (`HistoryPanel`, `SettingsPanel`, `TranslationPanel` など)。
- **ViewModel**:
  - `ScrapingViewModel`: 状態、リポジトリ、バックグラウンドタスクを統合管理。UI に対する唯一の信頼できる情報源（Single Source of Truth）として機能。
- **ビジネスロジック (ユースケース/エンジン)**:
  - **スクレイピングエンジン**: `ScrapingTask` + `ScrapingStateMachine` + `ScrapingScriptBuilder`
  - **翻訳エンジン**: `TranslationTask` + `DeeplTranslationTask`
  - **抽出ロジック**: `UrlExtractor`, `ChapterNumberExtractor`, `TextChunker`
- **データ (永続化)**:
  - `PreferencesRepository`: 設定、履歴、プリセット用の DataStore。
  - `FileRepository`: スクレイピング済みチャプターのローカル保存。
  - `TranslationFileStore`: 翻訳出力ドキュメントの保存。

## 3. 組織・整理状態の評価

### 強み:
- **ガイドライン遵守**: `AGENTS.md` の規定（Google 翻訳の手動操作再現イベント、独立したバックグラウンド WebView など）が厳格に守られています。
- **ドキュメント管理**: `docs/handoff_*.md` ファイルの使用により、機能ごとの履歴が分離されており、「情報の混濁」が防がれています。
- **命名規則**: ファイル名は説明的で、Android の標準的な慣習に従っています（例: `*ViewModel`, `*Repository`, `*Task`）。
- **KISS 原則**: ロジックが直接的で不要な抽象化を避けており、「シンプルかつ堅牢」という目標が達成されています。

### 考察:
- **フラットなパッケージ構造**: 現在、ほとんどのロジックがルートパッケージ (`com.example.novelscraper`) に配置されています。現状ではナビゲーションしやすいですが、ファイル数が 20 を超えてきています。
- **God Node（巨大なノード）の複雑性**: `ScrapingViewModel` (740 行) が最も複雑なコンポーネントです。スクレイピング、翻訳、UI ナビゲーション、永続化といった複数のロジックを管理しています。

## 4. 推奨される改善案 (オプション)

今後さらにプロジェクトが拡大する場合、高い視認性を維持するために以下の微調整を推奨します。

### A. パッケージによるモジュール化（グループ分け）
ルートパッケージのファイルを論理的なサブパッケージに移動する：
- `.model`: `MainUiState`, `ScraperConfig`, `ScrapingResult`, `FolderItem`
- `.repository`: `PreferencesRepository`, `FileRepository`, `TranslationFileStore`
- `.task`: `ScrapingTask`, `TranslationTask`, `DeeplTranslationTask`, `ScraperService`
- `.logic`: `ScrapingStateMachine`, `ScrapingScriptBuilder`, `UrlExtractor`, `ChapterNumberExtractor`, `TextChunker`, `CloudflareDetector`
- `.ui.helper`: `DialogHelper`, `WebViewHelper`

### B. ロジックの委譲
`ScrapingViewModel` 内の「翻訳キューと管理」ロジックを、専用の `TranslationManager` に抽出することを検討してください。これにより ViewModel のサイズが縮小され、テスト可能性が向上します。

## 5. 結論
プロジェクトは **非常によく整理されており**、特定の「スクレイピング/翻訳」要件を満たしつつ、Android のベストプラクティスに従っています。現在の構成は安定しており、メンテナンスも容易です。

**実施済みアクション**: Graphify ナレッジグラフの更新。
**推奨事項**: 現状で「良好」です。ルートディレクトリが乱雑に感じられ始めた場合にのみ、パッケージのモジュール化を検討してください。
