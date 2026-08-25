# 引き継ぎ状況 - インスペクター取得候補ポップアップ v3 (作成日: 2026-08-25)

> **ステータス: v3/v3.1/v4.1 実装完了・`compileDebugKotlin`/`testDebugUnitTest` SUCCESS（2026-08-25）。** 残タスクは実機確認（実施時点で adb デバイス未接続）。

## V4.3 追記実装（2026-08-25 / 実機フィードバック修正）

1. **上ズレ修正**: テスト結果パネルBoxに `paddingValues` 適用漏れ（旧Dialog名残）を修正 → ステータスバー/ツールバー領域へめり込まない。候補カードも同一コンテナ内のため連動修正
2. **ダークモード統一**: TestResultPanel / ExcludeCandidatesCard を `MaterialTheme.colorScheme`(ライト色) → `AppColors` ダーク配色へ全面置換（他パネルと同系）
3. **候補1件問題**: 先祖にid/class/semanticがないフラット構造向けに「親要素」連鎖フォールバックを popup/probe 両JSへ追加（既存push()経由で重複排除・計算を共用。BODY直下のみのケースは1件が正挙動）

---

## V4.2 追記実装（2026-08-25 / UI統一・排他制御の完成）

1. **インスペクター連動OFF**: `togglePanel`/`closePanels`/`setTestResult(non-null)` が `isInspectMode=false` に連動。注入/破棄は `MainScreen` の `LaunchedEffect(isInspectMode)` に一本化（toolbar/BackHandlerの手動呼出し廃止、onPageFinished再注入と無競合）。パネル越しのタップ貫通問題を解消
2. **チェックボタントグル化**: 結果パネル表示中に押すと閉じる（非表示時のみテスト実行）。お気に入り長押し含むパネル系は既存 `togglePanel` で対応済み
3. **ヘッダー統一**（機能名＋右側「閉じる」Button形式）: HistoryPanel にヘッダー新設(`onCloseClick` 引数追加・MainScreenで `closePanels` 接続)、TestResultPanel を×アイコン→Button化＋下部重複ボタン削除、TranslationPanel も×アイコン→Button化。Settings(説明書維持)/Favorites は現状形式のため変更なし

---

## V4.1 実装結果（2026-08-25）

| 項目 | 実装内容 |
|---|---|
| ①バグ修正 | `buildScrapingScript` 冒頭で既存 `__novel_exclude` クラスを全剥離してから現config適用 → **設定からの除外削除が次回テストに正しく反映**される |
| ②パネル化 | `TestResultDialog.kt` 削除 → `TestResultPanel.kt` 新設（Dialog廃止・Surface非モーダルoverlay・中身は現行流用）。MainScreen前面レイヤーに配置、ツールバー常時操作可 |
| ③排他制御 | `togglePanel`/`closePanels`/`setInspectMode(true)`/`setTestResult(non-null)` が互いに閉じ合う（openedPanel/testResult/excludeCandidates の単一化）。BackHandler は カード→結果→インスペクター→パネル→WebView戻る の順 |
| ④候補カード | `ExcludeCandidatesCard.kt` 新設。行タップ=即適用→`addExcludeSelector`→カード自動クローズ→`onTestRun` 自動再テスト（確認ボタンなし） |
| ⑤probe | `buildCandidateProbeScript(selector)` 新設: 要素自身/ID祖先(`tag#id`)/Class祖先(`tag.class`)/意味コンテナ(article等)の4種をJSON返却（metric=「N件・約M字/K行」）。注入不要・evaluateJavascript戻り値で受領し `excludeCandidates` 状態へ |
| ⑥指標 | popup側も対象別指標に差し替え（本文/タイトル/作品名=N字/M行、次へ=なし(URLプレビュー)、除外のみ一致数） |
| ⑦popup統一 | ページ直接タップの `getCandidates` も同一4種化。v3.1で追加した `requestExclude`/`forceTarget`/`autoTest`/ブリッジ `onRequestTestRun` は本方式への置換により削除（シンプル化） |

## V4.1 プラン（2026-08-25 確定 → 上記のとおり実装済み）

### 背景（ユーザー要望＋発見バグ）
1. 【バグ】設定で除外要素を削除してもテスト実行結果に反映されない
   - **根因**: `buildScrapingScript` が毎回 `__novel_exclude` クラスをDOMへ追加するが、前回付与分を剥離していないため古いマークが残留し除外され続ける
2. 複数除外の効率化: 結果画面を開かずに連続で除外したい。行タップで候補窓を「上に」開く
3. テスト解析ダイアログは現レイアウト維持（見やすい）。ツールバーに重ならないよう下へずらす
4. 候補をもっと多様に（`#id` / `.class` / semantic 等。nth-of-type頼みをやめる）
5. 「一致の数」は無意味 → 文字数/行数などわかりやすい指標へ

### 対応設計
| # | 項目 | 内容 |
|---|---|---|
| 1 | バグ修正 | `buildScrapingScript` 冒頭で `document.querySelectorAll('.__novel_exclude').forEach(el => el.classList.remove('__novel_exclude'))` を exclude 付与前に実行 |
| 2 | パネル化＋排他制御 | テスト解析を SettingsPanel 同機構の非モーダルoverlayへ変更（中身は現行流用・見た目維持）。**オーバーレイ単一化ルール**: 履歴/設定/翻訳/お気に入り/テスト結果は必ず1つのみ開き、どれかを開くと他は自動で閉じる（PanelType 単一値設計へ testResult を組み込む）。戻るジェスチャーで閉じる挙動も共通化。虫眼鏡ON時は全オーバーレイを閉じる（既存踏襲） |
| 3 | 候補カードoverlay | 行タップ → **結果パネルの内側**に Compose 製カード表示（別レイヤーではなく結果の子UI ⇒ 排他ルールと無矛盾）。データは `buildCandidateProbeScript(selector)` の戻りJSON（WebView注入不要）。**行タップ=適用→自動再テスト→カード自動クローズ**（確認ボタンなし・誤タップは再タップ上書き/解除で復旧）。連続除外可。「一度閉じて要素特定→自動再オープン」方式は不採用（状態遷移過多のため） |
| 4 | 指標 | 本文/タイトル/作品名＝「N字/M行」。次へ＝URL。除外のみ一致数 |
| 5 | 候補4種 | ①要素自身 ②ID祖先（tag#id 単体） ③Class祖先（tag.主要class） ④意味コンテナ（closest article/main/[role="main"]）。重複除去・最大4件。**ページ直接タップの popup 側 getCandidates も同一4種に統一** |

### 実装スコープ
- `ScrapingScriptBuilder.kt`: 剥離1行／`buildCandidateProbeScript()` 新設／popup getCandidates 4種化
- `MainActivity.kt`: probe実行＋状態反映へ書換（旧 `handleExcludeRequest` の注入ロジック、JS側 `requestExclude`/`forceTarget`/`autoTest`/ブリッジ `onRequestTestRun` は本方式で不要→削除しシンプル化。適用後再テストは Kotlin から直接 `performTestRun`）
- `TestResultDialog.kt` → パネル化（名称は `TestResultPanel` へ。中身は現行流用）
- 新規 `ui/components/ExcludeCandidatesCard.kt`
- `MainScreen.kt`: パネル組み込み（BackHandler は 候補カード→結果パネル→他パネル の順でポップ）
- `MainUiState.kt` / `ScrapingViewModel.kt`: `excludeCandidates` 子状態の保持/クリア＋**オーバーレイ単一化**を `togglePanel`/`setTestResult`/`setInspectMode` に集約
- ※ `buildHighlightScript()` 引き続き温存

### 検証ポイント（実機）
1. 除外追加→自動再テストで反映
2. **設定で除外削除→チェック→本文復活（バグ修正の決定打）**
3. 4種候補表示・文字数/行数指標・プレビュー展開
4. 行タップ→カード→選択→カード閉じ→連続で次の行、のループが閉じ操作ゼロで回る
5. v3実機確認 1〜8 の回帰

---

## 0.1 v3.1 追記実装（2026-08-25 / ユーザー要望2件）

> ※ 本節のうち「テスト解析→ページpopup誘導」フローは v4.1 で Compose カード方式に置き換え予定。プレビュー拡大（110px スクロール）は継続有効。

| 要望 | 対応 |
|---|---|
| プレビューが小さすぎて確認できない | 候補ポップアップのプレビュー欄を拡大（max-height 110px・**スクロール可**・pre-wrap）。ポップ自体も max-height 70vh / 幅 360px に拡大 |
| テスト解析の🗑廃止・複数候補選択・削除結果の確認 | `TestResultDialog` の行タップ → ダイアログ経由でページ側の**除外候補ポップアップ**を表示（`__novelInspector.requestExclude(selector)`）。対象要素へ自動スクロール＋ハイライト、候補3階層に「消えるもの」プレビュー付き。適用後は **`onRequestTestRun` ブリッジで自動再テスト**し結果ダイアログが最新状態で再表示される |

実装詳細:
- JS: `showCandidatePopup(cands, x, y, forceTarget, autoTest)` に拡張（テスト結果経由は exclude チップ強制＋autoTest有効）。`window.__novelInspector.requestExclude()` を公開
- Kotlin: ブリッジ `onRequestTestRun()` 追加（mainWebView で performTestRun 再実行）。`handleExcludeRequest()`: インスペクター未起動なら `setInspectMode(true)` + 自動注入後に requestExclude（evaluateJavascript は投入順実行を利用）
- UI: `TestResultDialog` の 🗑IconButton 削除→行全体タップで `onRequestExclude`。案内文更新。`MainScreen` の `onExclude/onHighlight` ラムダを廃止し `onRequestExclude` パラメータに集約
- ※ `buildHighlightScript()` は未使用だが温存（将来のハイライト用途・機能保護）

## 0. 実装結果（2026-08-25）

| ファイル | 変更 |
|---|---|
| `MainActivity.kt` | `NovelScraperBridge` に `onApplyCandidate(target, selector)`（target→SelectorField変換→`applySelectorToConfig`＋トースト）と `onRemoveExclude(selector)` を追加。旧 `onInspectorSave`/`onInspectorCancel` は削除（参照はJSのみと実査済み） |
| `ScrapingViewModel.kt` | `removeExcludeSelector()` 新設（該当1件除去→再結合。空安全） |
| `ScrapingScriptBuilder.kt` | `buildInspectorScript` 全面書換: ツールバー廃止、候補生成 `getCandidates`、ポップアップ `showCandidatePopup`（チップ=直近使用デフォルト・行タップ即反映・除外解除行・位置クランプ）、注入時ヒント表示、`stop()` で popup/hint/リスナー完全破棄＋`__novelInspectActive` フラグ管理 |

- 検証: `compileDebugKotlin` **BUILD SUCCESSFUL**（警告は既存の statusBarColor 非推奨のみで範囲外）
- JS側の除外フォーマットは既存仕様厳守（カンマ+スペース結合・重複排除）。Kotlin側 `mergedExclude` / `removeExcludeSelector` と整合

## 1. 背景・現状の問題

### 問題A: 本文が一部しか取れない
インスペクター（`ScrapingScriptBuilder.buildInspectorScript` / ScrapingScriptBuilder.kt:248）は、タップされた**最深部の要素そのもの**を `getUniqueSelector()`（同:311）で即設定する。本文モードで段落 `<p>` をタップすると:

```
config.body = "div#novel_honbun > p:nth-of-type(3)"   ← 1段落分だけ
```

取得エンジン（`buildScrapingScript` / 同:110）は一致範囲＝1要素のみ保存する。祖先コンテナ（例: `#novel_honbun`）を選ぶ手段がない。

### 問題B: テスト解析の🗑除外で必要な要素まで消える
各行のセレクタは「そのテキストを含む最初のテキストノードの parentElement」（同:148-155）。行テキストがコンテナ直書きだと `#novel_honbun` まるごと除外され本文全滅する。粒度を選ぶ手段がない（ページ直接タップでは指が当たる最深レンダリング要素より細かくは不可）。

### 問題C: モード事前選択の強制とUI重複
上部モードボタン（タイトル/本文/次へ/除外）を先に選ばされ誤操作が多い。作品名(folder)が指定できない。また保存/×ボタンは虫眼鏡トグル・設定パネル・テスト解析と機能が重複しており、保存忘れによる「古いconfigでのテスト解析」事故を誘発していた。

## 2. 確定仕様（v3）

### 統一フロー「どこでもタップ→候補→即反映」

```
虫眼鏡ON → 注入時に一時ヒント「要素をタップして候補から設定」（数秒で消える）
ページ上のマークは従来通り（青=タイトル/緑=本文/橙=次へ/半透明=除外）
任意の要素タップ（モード事前選択不要）
  └─ ミニポップアップ:
       ①要素自身  p:nth-of-type(3)   一致1   "　　「おかえり…"
       ②近い祖先  div#novel_honbun  一致1   "　　「おかえり…(全文)"
       ③上位祖先  div.main_txt      一致1   "(全文+余計なもの)"
       割当先: [本文*] [タイトル] [次へ] [作品名] [除外]   (*=直近使用をデフォルト)
  └─ 行タップ = 即反映（Bridge → ViewModel 直接）＆ポップアップ閉じ
  └─ ×または範囲外タップ → 設定変更なしで閉じる
確認・終了 → 既存資産を使用（チェックボタン=テスト解析／設定パネル／虫眼鏡OFF・戻りGestureで終了）
```

- **WebView内ツールバー（保存/×含む）は完全廃止。** 終了は虫眼鏡トグル/BackHandler（MainScreen.kt:89、リスナー完全破棄済み経路）
- **保存概念の廃止:** 適用のたびにViewModelへ直接反映（単一情報源）。テスト解析は常に最新configで走る

### 候補生成ルール

| 候補 | ルール |
|---|---|
| ① 要素自身 | タップ要素 |
| ② 近い祖先 | id/class を持つ直近の祖先 |
| ③ 上位祖先 | ②のさらに上の id/class 持ち祖先 |

- `<body>`/`<html>` は含めない。重複セレクタはマージ。最大3件（不足時は減らす）
- 各候補データ: `{ selector, label, matchCount, preview }`

### プレビュー意味論（割当先ごと）

| 割当先 | プレビュー内容 |
|---|---|
| 本文 / タイトル / 作品名 | `innerText` 先頭100文字（空白正規化） |
| 次へ | 解決済みリンク先URL（`href`） |
| 除外 | 非表示化される内容サンプル＋一致数 |

### 除外の新動作

- 対象タップ → 除外チップ選択中に候補行タップで適用（粒度とプレビュー確認済み）
- **解除:** タップ要素のセレクタが既に除外済みなら、ポップアップ先頭に「この除外を解除」行を出す
- 既存の除外済み半透明表示（opacity 0.3 / `applyCurrentConfig` 相当）は維持
- 除外フォーマット厳守: カンマ+スペース結合・重複排除（既存 `mergedExclude` を使用）

## 3. 実装ステップ

### Kotlin側（小規模）

1. **MainActivity** — `NovelScraperBridge` に新メソッド追加:
   - `@JavascriptInterface fun onApplyCandidate(target: String, selector: String)` → target文字列を `SelectorField` に変換して `viewModel.applySelectorToConfig(field, selector)` を呼ぶ（mainHandler.post 内。例外ガード付き）
   - `@JavascriptInterface fun onRemoveExclude(selector: String)` → `viewModel.removeExcludeSelector(selector)`
   - 旧 `onInspectorSave` / `onInspectorCancel` は参照消失を確認のうえ削除（※handoff_compile_errors_inspector.md の確認項目3/4は本プランで置き換わる旨を同docへ追記）
2. **ScrapingViewModel** — `removeExcludeSelector(selector: String)` を新設（`mergedExclude` の逆: 該当1件を除去して再結合。空でも安全）
3. **ScrapingScriptBuilder.buildInspectorScript** — JS書き換え（下記）

### JS側（buildInspectorScript 内）

4. `getCandidates(el, target)` 新設 — 祖先辿り・最大3件・target別プレビュー生成
5. `showCandidatePopup(candidates, x, y)` 新設 — 小窓描画・位置クランプ・割当先チップ（直近使用記憶）・行タップ=`AndroidBridge.onApplyCandidate(...)` 呼び出し＆閉じ・除外解除行・範囲外/×で中止
6. `handleClick` 書き換え — `#__novel_popup` 判定スキップ後、popup 表示に統一
7. ツールバー生成コード削除・注入時の一時ヒント表示追加
8. `stop()` 整理 — popup・リスナー完全破棄（§2 ゾンビ化防止。toolbar参照は削除）
9. **改変しないもの:** `applyCurrentConfig` のマーク/透明化ロジック、`getUniqueSelector` アルゴリズム、Eruda・ハイライト系スクリプト、`buildInspectorStopScript` の入口

## 4. 検証手順

```powershell
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$env:JAVA_HOME = "C:\Users\asan6\.jdks\ms-21.0.12.1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew --stop            # デーモン競合回避
# バックグラウンド実行推奨（フォアグラウンドはタイムアウトkillされうる）
.\gradlew compileDebugKotlin --console=plain
.\gradlew installDebug
$adb = "C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am force-stop com.example.novelscraper
& $adb shell am start -n com.example.novelscraper/.MainActivity
```

**実機確認項目:**
1. 段落タップ → 候補②で全文プレビュー確認 → タップ即反映 → チェックボタンで本文全体が取れる（保存操作なしで最新反映されていること）
2. タイトル/作品名も同様
3. 次へ: URLプレビュー確認 → 適用
4. 除外: プレビュー確認 → 適用。消しすぎたら再タップ→深い候補 or 「解除」行で修正
5. 直近使用の割当先が次回デフォルト
6. ×/範囲外で不変
7. 虫眼鏡OFF/戻るでpopup・リスナー残留なし（ゾンビ化防止）
8. 共有URL・プリセット自動適用(autoUrl)など既存フローの回帰
9. ※旧確認項目（保存トースト/キャンセル）は本v3で廃止済み機能のため対象外

## 5. 完了の定義 (DoD)

- [ ] `compileDebugKotlin` SUCCESS
- [ ] 上記実機確認 1〜8 パス
- [ ] graph.json で影響確認（`applySelectorToConfig` 新規呼び出し元＝NovelScraperBridge.onApplyCandidate のみ追加。既存呼び出し元 DialogHelper 経路に影響なし）
- [ ] 単体テストパス

## 6. リスク・注意事項

- `nth-of-type` 付きセレクタは構造変化に弱い（既存の性質。触らない）
- 大きなコンテナの `innerText` は先頭100文字のみ抽出
- ポップアップ内クリックの capture 再入防止（`#__novel_popup` 判定）
- ブリッジ呼び出しは必ず mainHandler.post 上でViewModel操作（既存パターン踏襲）
- 即反映のため「取り消し」は再タップ上書き/解除行/設定パネルが担う（プレビュー確認済みの明示的操作のみが反映 trigger なことで成立）
- 旧 `onInspectResult` 経路（DialogHelper 全フィールド適用ダイアログ）は温存。インスペクターv3とは独立に動く
