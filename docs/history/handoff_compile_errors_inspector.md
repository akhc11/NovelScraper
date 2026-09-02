# 引き継ぎ状況 - コンパイルエラー修正プラン (作成日: 2026-08-25)

> **ステータス: 実装完了・`compileDebugKotlin` SUCCESS（2026-08-25）。** 残タスクは「実機機能確認」のみ（実施時点で adb デバイス未接続のため未実施）。
> **【2026-08-25 追記】** 本docの実機確認 3・4（インスペクター保存/キャンセル）は、インスペクターv3（handoff_inspector_candidates.md）で保存/キャンセルボタン自体が廃止されたため置き換え。確認は v3 の実機確認項目 1〜8 に統合。

## 0. 実装結果（2026-08-25）
- **Step 1（MainScreen.kt import 追加）:** 完施。`import com.example.novelscraper.ScrapingScriptBuilder` を追加。
- **Step 2（MainActivity.kt mainWebView 保持）:** 完施。フィールド `private var mainWebView: WebView? = null` を追加し、`setupWebView()` / `injectInspector()` 内で代入、185/195 行目を `removeInspector(mainWebView)` に変更。
- **Step 3（ScrapingViewModel EXCLUDE 分岐）:** 完施。レビュー通り共通関数 `mergedExclude(current, selector)` を新設し、既存 `addExcludeSelector` を薄いラッパー化（動作完全同等: trim→空除去→重複排除→", "結合）。`applySelectorToConfig` の when に `SelectorField.EXCLUDE -> copy(exclude = mergedExclude(...))` を追加（案3-A）。else 分岐は付けず網羅チェックを維持。
- **AGENTS.md §8 更新:** JDK 21 (`ms-21.0.12.1`) 固定 + `gradlew --stop` 手順を追記（handoff_build_jdk25.md Step 4 と共通）。
- **検証:** `.\gradlew --stop` 後 `compileDebugKotlin` → **BUILD SUCCESSFUL**（エラー4件すべて解消）。初回はデーモンコールド起動＋フル再コンパイルで数分かかるが正常挙動。
- **DoD チェック済み:** graph.json で `applySelectorToConfig` の呼び出し元は `NovelScraperBridge.onInspectResult`（MainActivity.kt:172）のみ、`removeInspector` はシグネチャ変更なしで MainScreen.kt:91/139 に波及なし。変更クラス（ViewModel/Activity/MainScreen）に直接関連するユニットテストは存在しない（UrlExtractorTest 等は非対象）。

## 1. 現象（`compileDebugKotlin` 失敗・4件）

```
e: MainActivity.kt:185:37      Unresolved reference webView
e: MainActivity.kt:195:33      Unresolved reference webView
e: ScrapingViewModel.kt:225:27 when expression must be exhaustive. Add the EXCLUDE branch or an else branch.
e: MainScreen.kt:357:49        Unresolved reference ScrapingScriptBuilder
```

## 2. 原因分析（コード実査済み）

### エラー① MainActivity.kt:185 / 195 — `Unresolved reference webView`
- `NovelScraperBridge`（内部クラス）の `onInspectorSave` / `onInspectorCancel` 内で `removeInspector(webView)` を呼んでいるが、**MainActivity には `webView` というプロパティが存在しない**。
- 実装上、WebView インスタンスは Compose 側（MainScreen の `webViewRef`）からコールバック（`onSetupWebView` 等）で都度渡される方式のため、Activity 側に保持フィールドがない。
- → インスペクター保存/キャンセル時に「ツールバーを閉じる」処理が参照解決できずコンパイル失敗。

### エラー② ScrapingViewModel.kt:225 — `when` 式が網羅されていない
- `SelectorField` enum（ScraperConfig.kt:36-44）に **`EXCLUDE("除外要素")` が追加済み**。
- 一方 `applySelectorToConfig()` の when は BODY/TITLE/NEXT/CHAPTER/FOLDER/FOLDER_LINK の 6 分岐のみで EXCLUDE を扱っていない。
- DialogHelper.showInspectElementDialog（DialogHelper.kt:64）は `SelectorField.entries` を全列挙してダイアログに出すため、EXCLUDE 選択は実際に起こりうる機能パス（単なる警告ではない）。
- 補足: `addExcludeSelector()`（ScrapingViewModel.kt:120-129）は既存の複数除外セレクタ仕様（カンマ結合・重複排除）を実装済み。

### エラー③ MainScreen.kt:357 — `Unresolved reference ScrapingScriptBuilder`
- `ScrapingScriptBuilder.buildHighlightScript(selector)` 自体は存在する（ScrapingScriptBuilder.kt:414）。
- MainScreen.kt（package `com.example.novelscraper.ui`）の import ブロックに **`import com.example.novelscraper.ScrapingScriptBuilder` が抜けているだけ**。

## 3. 修正プラン

### Step 1: MainScreen.kt — import 追加（1行）
- import ブロックへ `import com.example.novelscraper.ScrapingScriptBuilder` を追加。

### Step 2: MainActivity.kt — WebView 参照の保持
- Activity フィールドに `private var mainWebView: WebView? = null` を追加。
- `setupWebView(view)`（および保険として `injectInspector(view)`）内で `mainWebView = view` を代入し、常に最新のメイン WebView を保持。
- 185 行目・195 行目を `removeInspector(mainWebView)` に変更（`removeInspector(view: WebView?)` は null 安全なのでシグネチャ変更不要）。
- メモリ配慮: Activity 自身のビュー参照の上書き保持のみで WeakReference までは不要（KISS。リーク源は Compose 側再生成時に旧参照を上書きするだけで解消）。

### Step 3: ScrapingViewModel.kt — EXCLUDE 分岐の追加
- when に `SelectorField.EXCLUDE -> ...` を追加。
- **設計判断（要確認ポイント）**: `copy(exclude = selector)` の素の置換は既存の除外セレクタを上書き消去してしまうため、インスペクター v2 の「複数除外セレクタ」仕様を守るには `addExcludeSelector()` と同一のマージロジック（split→重複チェック→join）を使う分岐を実装することを推奨。
  - 案3-A（推奨）: EXCLUDE 分岐のみマージ処理を呼ぶ（`addExcludeSelector` のロジック共通化）
  - 案3-B: 素の置換（簡易。ただし仕様違反のおそれ）

## 4. 検証手順

```powershell
# JDK 21 固定（前回修正済みの設定）
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$env:JAVA_HOME = "C:\Users\asan6\.jdks\ms-21.0.12.1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

.\gradlew compileDebugKotlin --console=plain   # SUCCESS 確認
.\gradlew installDebug                          # 実機インストール
$adb = "C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am force-stop com.example.novelscraper
& $adb shell am start -n com.example.novelscraper/.MainActivity
```

**実機機能確認（インスペクター系の回帰）:**
1. テスト解析 → 結果ダイアログの「ハイライト」ボタン → 対象要素がハイライトされる（エラー③の機能確認）
2. 結果ダイアログの「除外」→ 設定の exclude に追記され自動再テストが走る（エラー②のマージ確認）
3. インスペクター起動 → 保存 → トースト表示＋ツールバーが閉じる（エラー①の確認）
4. インスペクター起動 → キャンセル → ツールバーが閉じリスナーが破棄される（AGENTS.md §2 ゾンビ化防止の維持確認）

## 5. 完了の定義 (DoD)
- [ ] `compileDebugKotlin` SUCCESS
- [ ] 上記実機機能確認 1〜4 パス
- [ ] 変更関数の呼び出し関係を `graphify-out/graph.json` で確認（`applySelectorToConfig` / `NovelScraperBridge` の呼び出し元に影響波及なしことを確認）
- [ ] 既存テスト（UrlExtractorTest 等が対象外のため影響なし想定）のパス確認

## 6. リスクと注意事項
- エラー②で案3-B（置換）を採ると「複数除外」機能が静かに壊れるため、推奨は案3-A。
- MainActivity へのフィールド追加は最小限にとどめ、既存のコールバック駆動アーキテクチャ（AGENTS.md §2 の JavascriptInterface 仕様）は変更しないこと。
- `when` に `else` を付けて黙らせる方法は、将来 enum 追加時の取りこぼしを隠すため非推奨（明示 EXCLUDE 分岐とする）。
