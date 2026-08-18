# 引き継ぎ状況 - 最終更新: 2026-08-19 (ダークモード旧コード完全クリーン化＆純粋一本化完了)

## 現在の状態
- **旧コード・不要ライブラリの完全削除とクリーン化を完了:**
  - 不要になった `androidx.webkit` 依存関係（`gradle/libs.versions.toml`, `app/build.gradle.kts`）を完全削除。
  - `themes.xml` および `themes-night.xml` から複雑なWebView用の不要フラグを全廃し、初期のクリーンな状態に復元。
  - `WebViewHelper.kt` を、ハードウェア背景色切り替え（Zero-FOUC）と洗練された Dynamic Semantic Dark Theme スクリプトのみの純粋で美しい構造に一本化。
  - `MainScreen.kt` の `onPageStarted`（0ms先行注入）により、白チラつきを根絶したシームレスなダークモードを実現。
- **検証:** `assembleDebug` および単体テスト全件（10/10）がすべて正常にパスすることを確認済み。

## 今回のクリーン化内容
1. `gradle/libs.versions.toml` & `app/build.gradle.kts`: `androidx.webkit` の依存関係を削除。
2. `res/values/themes.xml` & `res/values-night/themes.xml`: 不要なプロパティを削除しクリーン化。
3. `WebViewHelper.kt`: 不要なAPI呼び出しを全撤廃し、純粋なDynamic Semantic Dark Paletteに集約。
