# 引き継ぎ状況 - 最終更新: 2026-08-19 (Google検索＆全Webサイト対応ダークモード完全修正完了)

## 現在の状態
- **Google検索および全Webサイトのダークモード完全対応:**
  - Android公式仕様に基づき、`res/values/themes.xml`（`isLightTheme=true`）および `res/values-night/themes.xml`（`isLightTheme=false`）を正式設定。
  - `WebViewHelper.applyDarkMode()` 内で `AppCompatDelegate.setDefaultNightMode()` を連動させ、Google.comなどのモダンサイトに `prefers-color-scheme: dark` を正常伝播。
  - Google検索結果の特殊なDOM構造（`:root color-scheme: dark`, `#main`, `#cnt`, `#search`, CSS変数）をカバーするスマートダークCSSを強化。
- **検証:** `assembleDebug` および単体テスト全件（10/10）がすべて正常にパスすることを確認済み。

## 今回の修正内容
1. `res/values/themes.xml` & `res/values-night/themes.xml`: `android:isLightTheme` を明示。
2. `WebViewHelper.kt`: `AppCompatDelegate.setDefaultNightMode()` の同期呼び出しと、Google検索DOMセレクタの網羅。
