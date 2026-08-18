# 引き継ぎ状況 - 最終更新: 2026-08-19 (ダークモードのコード全面整理 & Zero-FOUC一本化完了)

## 現在の状態
- **無駄のないクリーンなダークモードアーキテクチャに一本化:**
  - 複雑で挙動の不安定な `WebSettingsCompat.setForceDark` 等の重複レイヤーを完全撤廃。
  - ハードウェア初期背景色（`#121212`）と、ページ開始直後（`onPageStarted`）の先行スタイル注入により、**白フラッシュ（FOUC）を0ミリ秒で根絶**。
  - 洗練された単一の Dynamic Semantic Dark Theme により、カクヨム等のバー文字透け防止、Google検索タブ白線除去、コントラスト保証を完全に両立。
- **検証:** `assembleDebug` および単体テスト全件（10/10）がすべて正常にパスすることを確認済み。

## 今回の修正内容
- `WebViewHelper.kt`: 不要なAPI依存を削除し、`applyDarkMode` と `buildDarkModeJs` をスリム化。
- `MainScreen.kt`: `onPageStarted` での先行スタイル注入を追加し、白チラつきを根絶。
