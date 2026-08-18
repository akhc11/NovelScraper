# 引き継ぎ状況 - 最終更新: 2026-08-19 (Android公式WebViewダークモード機能実装完了)

## 現在の状態
- **Android公式ダークモード機能の実装完了:**
  - `androidx.webkit:webkit:1.12.1` を導入。
  - `WebViewHelper.applyDarkMode()` により、Android公式の `Algorithmic Darkening`（API 33+）および `Force Dark`（API 29〜32）を適用。
  - ヘッダーツールバーに「🌙（ダーク）/ ☀️（ライト）」トグルボタンを追加。
  - ダークモードのON/OFF状態を DataStore（`PreferencesRepository`）に自動永続化。
- **検証:** `assembleDebug` および単体テスト全件（10/10）がすべて正常にパスすることを確認済み。

## 今回の追加・変更内容

### 1. ライブラリ追加 (`gradle/libs.versions.toml`, `app/build.gradle.kts`)
- `androidx.webkit:webkit:1.12.1` を追加。

### 2. WebViewダークモード適用ロジック (`WebViewHelper.kt`)
- `WebViewFeature.ALGORITHMIC_DARKENING` および `WebViewFeature.FORCE_DARK` をチェックし、Chromiumエンジンネイティブで最速・最高画質でWebページを黒化。

### 3. UI・ViewModel・DataStoreの連携
- `MainUiState.kt`: `isDarkMode: Boolean` を追加。
- `PreferencesRepository.kt`: `darkModeFlow` と `saveDarkMode` を追加。
- `ScrapingViewModel.kt`: `toggleDarkMode()` を追加し、起動時に設定値を自動復元。
- `HeaderToolbar.kt`: ツールボタン行に「🌙 / ☀️」切り替えボタンを配置。
- `MainScreen.kt`: WebView初期化時およびState変更時に即座にダークモードを反映。

## 検証結果
- **`assembleDebug`**: BUILD SUCCESSFUL (36 actionable tasks)
- **単体テスト**: 10 tests - 全件 PASS
- **既存機能の完全維持**: 自動プリセット、スクレイピング、手動再現翻訳ロジックを100%継承
