# 引き継ぎ状況 - お気に入り星マーク長押し ＆ Erudaフローティングボタン非表示化 ＆ ツールバーUI最適化

最終更新: 2026-08-19

## 1. 概要・背景
ユーザーからの以下の機能改善・UI要望に対応しました：
1. **星マーク長押しでお気に入り一覧オープン & 高速化:**
   - 通常タップ（クリック）: 現在のページのお気に入り追加ダイアログを表示。
   - 長押し（Long Press）: 登録済みのお気に入り一覧パネル（`PanelType.FAVORITES`）を直接開閉。判定時間を **250ms** に短縮し、非常に機敏に反応するように最適化。
2. **不要になったハートボタンの削除:**
   - 星マーク長押しでお気に入り一覧が開けるようになったため、下段ツールバーの「ハートボタン」を削除。
3. **上部ツールボタン（四角ボタン）のサイズ拡大:**
   - ツールボタン（`ToolButton`）のサイズを 32dp → **35dp**、アイコンを 18dp → **20dp**、文字サイズを 12sp → **13sp** に拡大して視認性・タップしやすさを向上。
4. **スパナマーク（Eruda）の右下フローティングボタン非表示化:**
   - 開いた後や閉じた後に画面右下に表示されていた Eruda の歯車/エントリボタン（`.eruda-entry-btn`）を Shadow DOM 含め CSS および API で完全に非表示化。
   - アプリ上部のスパナボタンのみでトグル開閉するクリーンなUIを実現。

---

## 2. 実装内容と変更ファイル

### 1. `HeaderToolbar.kt`
- ハートボタン（`Icons.Filled.Favorite`）を削除。
- 星マークに `pointerInput` + `awaitEachGesture` を用いて、250msの高速長押し判定を実装。
- `ToolButton` のサイズを 35dp、アイコン 20dp、文字 13sp に拡大。

### 2. `MainScreen.kt`
- `HeaderToolbar` に `onStarLongClick = { viewModel.togglePanel(PanelType.FAVORITES) }` を接続。

### 3. `ScrapingScriptBuilder.kt`
- `buildErudaScript()` を改修：
  - `eruda.init({ autoShow: false })`
  - メインDOMおよび `eruda._shadowRoot` の両方に `.eruda-entry-btn { display: none !important; }` スタイルを注入。
  - `eruda.show()` / `eruda.hide()` の実行時に `eruda._entryBtn.hide()` を確実に実行し、画面右下のボタンを物理的に非表示化。

---

## 3. 動作確認・検証
- `.\gradlew assembleDebug` によるAPKビルドが成功（BUILD SUCCESSFUL）。
- `graphify-out/graph.json` およびコードベース全体での整合性を確認済み。

---

## 4. 実機テスト手順
```powershell
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$adb = "C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# インストール & 再起動
.\gradlew installDebug
& $adb shell am force-stop com.example.novelscraper
& $adb shell am start -n com.example.novelscraper/.MainActivity
```
