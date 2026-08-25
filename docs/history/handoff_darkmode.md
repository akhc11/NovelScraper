# WebView ダークモード 実装・仕様・実機テスト手順書 (handoff_darkmode.md)

## 1. 機能概要
Android WebView に対する完全自動のダークモード機能。
Google などのネイティブ対応サイトだけでなく、カクヨム・小説家になろう・Wikipedia などのダーク未対応サイトも、画像・動画の視認性を損なわずに背景 `#121212` / 文字 `#e0e0e0` へスマートに自動暗転する。

---

## 2. 実装仕様と動作原理

### 2.1 採用アーキテクチャ（Chromium ネイティブ Algorithmic Darkening）
- **`themes.xml`**: `<item name="android:isLightTheme">false</item>` を設定し、WebView にアプリがダークテーマであることを伝達。
- **`WebViewHelper.applyDarkMode`**:
  ```kotlin
  if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
      WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, enabled)
  }
  webView.setBackgroundColor(if (enabled) Color.parseColor("#121212") else Color.WHITE)
  ```
- **重要（競合防止）**:
  JavaScript によるカスタム CSS 注入（`injectDarkModeCss` 等）は、Chromium の自動暗転シェーダーと衝突して「一度暗転した後に白へ戻る」バグを引き起こすため、**完全排除**してネイティブ設定のみに一本化している。

### 2.2 状態管理と UI
- **トグルボタン**: ヘッダー右端の「🌙 / ☀️」ボタン
- **設定パネル**: 「表示・動作設定」内の「WebView ダークモード」Switch
- **永続化**: `PreferencesRepository`（DataStore）で `WEBVIEW_DARK_MODE` キーとして保存（デフォルト: `true`）
- **切替時動作**: `MainScreen.kt` の `AndroidView.update` で差分を検出し、`view.reload()` でページを再レンダリング

---

## 3. 実機テスト＆AI自動検証手順（ADB / Logcat）

### 3.1 前提環境
- 実機が PC に USB 接続されており、USBデバッグが有効になっていること。
- ADB パス: `C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- JDK パス: `C:\Program Files\Android\Android Studio\jbr`

### 3.2 ビルド＆インストール手順
```powershell
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# ビルド＆実機インストール
.\gradlew installDebug
```

### 3.3 アプリ起動・再起動コマンド
```powershell
$adb = "C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# ログクリア ＆ アプリ完全再起動
& $adb logcat -c
& $adb shell am force-stop com.example.novelscraper
& $adb shell am start -n com.example.novelscraper/.MainActivity
```

### 3.4 ダークモード動作検証手順
1. **アプリ起動**: アプリを開いた時点で、画面全体のテーマおよび WebView 背景が暗色になっていることを確認。
2. **ネイティブ対応サイト確認**: Google 検索を開き、公式ダークモードで表示されることを確認。
3. **一般・小説サイト確認**:
   - カクヨム (`kakuyomu.jp`)、小説家になろう (`syosetu.com`)、Wikipedia (`ja.wikipedia.org`) を開く。
   - ページ読み込み完了後も白に戻らず、ずっとダークモードが維持されていることを確認。
4. **トグル切り替え確認**:
   - ヘッダーの「🌙」ボタンをタップ → アイコンが「☀️」に変わり、即座に通常（ライト）表示にリロードされることを確認。
   - 再度「☀️」をタップ → 「🌙」に変わり、ダークモードでリロードされることを確認。
5. **アプリ再起動テスト**:
   - ダークモード ON / OFF それぞれの状態でアプリを終了・再起動し、設定が維持されていることを確認。

---

## 4. 変更ファイル一覧
- `gradle/libs.versions.toml`: `androidxWebkit = "1.12.1"` 追加
- `app/build.gradle.kts`: `implementation(libs.androidx.webkit)` 追加
- `app/src/main/res/values/themes.xml`: `android:isLightTheme = false` 追加
- `WebViewHelper.kt`: `applyDarkMode` 実装
- `MainUiState.kt`: `isWebViewDarkMode: Boolean = true` 追加
- `PreferencesRepository.kt`: DataStore 永続化キーと Flow 追加
- `ScrapingViewModel.kt`: Flow collect と `toggleWebViewDarkMode()` 実装
- `HeaderToolbar.kt`: 🌙 / ☀️ トグルボタン追加
- `SettingsPanel.kt`: Switch UI 追加
- `MainScreen.kt`: WebView 適用と動的リロード連携

---

## 5. ロールバック手順（万が一元に戻す場合）
```powershell
# 実装直前のクリーン状態タグへ復元
git reset --hard backup-before-darkmode
```
