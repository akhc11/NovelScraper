# 敵対的アーキテクチャレビュー & 全面的疎結合化 完了報告書

## 1. 実施概要
ユーザーからの「敵対的レビューの実施、不必要な機能間干渉の排除、適度な疎結合化」の要請に基づき、コードベース全体を敵対的に精査し、段階的リファクタリング（Phase 1〜5）を完了しました。

---

## 2. 達成された改善点と処方箋結果

### ① 翻訳タスクの基底クラス共通化・Strategyパターン化（DRY達成 & ルール7死守）
- **変更内容**:
  - `TranslationTask` (434行) と `DeeplTranslationTask` (417行) の約 90% の重複コードを `BaseWebTranslationTask` に完全集約。
  - エンジン固有の処理は `WebTranslationStrategy`（`GoogleTranslationStrategy`, `DeeplTranslationStrategy`）として分離。
  - **【重要・ルール7死守】**: Google翻訳の手動貼り付け再現イベントシーケンス（`focus` → `select` → `ClipboardEvent('paste')` → `InputEvent('input', {inputType: 'insertFromPaste'})` → `Event('change')`）を100%維持。

### ② ファイル保存エラーのハンドリング & 堅牢化
- **変更内容**:
  - `FileRepository.saveChapter` で MediaStore への書き込み例外発生時に孤立 pending エントリをクリーンアップし、例外を適切に捕捉。
  - 保存失敗時に ViewModel へエラーを伝播し、`UiEvent.ShowToast` でユーザーに通知する安全装置を追加。

### ③ UIState の完全一元化 & ViewModel の純粋化 (Single Source of Truth)
- **変更内容**:
  - `presets`, `favorites`, `history`, `activeTasks`, `currentStatusText` を `MainUiState` に統合。
  - 6 重に乱立していた StateFlow 購読を単一の `StateFlow<MainUiState>` 購読に一本化し、不要なリコンポジションを撲滅。
  - `ScrapingViewModel` および `TranslationQueueManager` から `Toast.makeText` などの Android Context/View 依存を全廃し、One-shot イベントチャネル `UiEvent`（`ShowToast`, `NavigateToUrl`）経由で View 層（`MainActivity`）に通知するクリーンアーキテクチャを確立。

### ④ UI レイヤーの健全化 & SettingsPanel ハックの解消
- **変更内容**:
  - `MainScreen` の `SettingsPanel` で行われていた `translationY = 9999f` の画面外退避ハックを廃止し、Compose の標準的な Overlay パネル描画モデルに統合。非表示時の不要なツリー常駐とメモリ負荷を解消。

---

## 3. 検証結果 (Definition of Done)
1. **単体テスト (`testDebugUnitTest`)**:
   - `ScrapingStateMachineTest` を含む全テストが 100% パス。
2. **コンパイル & フルビルド (`assembleDebug`)**:
   - JDK 21 環境下で `BUILD SUCCESSFUL` を確認。
3. **デッドコード・参照整合性**:
   - 削除・改変された旧タスク参照や不要な import、未ハンドリングのメソッドがないことを確認済み。
