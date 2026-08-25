# 全体レビュー報告 - 堅牢性・パッチワーク・アーキテクチャ (作成日: 2026-08-25)

> 対象: graphify更新後 (558ノード/746エッジ/51コミュニティ) のコードベース実査に基づく。
> **進捗: P1-1〜P1-5 + A-2 実装完了（2026-08-25、コミット 0eabdcd 以降）。** 検証: compileDebugKotlin/testDebugUnitTest SUCCESS。実機確認済み(〜P1-4)。

### 実装詳細（P1-5 + A-2）
| 項目 | 実装内容 |
|---|---|
| P1-5 | buildScrapingScript のデバッグ行セレクタ検出を「TreeWalker 1回の事前収集配列」方式へ変更（旧: 行ごとに再走査 O(lines×nodes)）。意味論は維持（文書順で最初に一致したテキストノードの親） |
| A-2 | `MainScreenCallbacks` data class 新設（11ラムダ集約）。MainScreen 署名を `(viewModel, callbacks)` に縮減、MainActivity 呼び出し側を対応 |

### 実装詳細（P1-1〜P1-4）
| 項目 | 実装内容 |
|---|---|
| P1-1 | `JS_UNIQUE_SELECTOR` / `JS_SHORT_SELECTOR` 定数化し3スクリプト（scraping/popup/probe）で共有。raw文字列の単一バックスラッシュ規約をコメント明記 |
| P1-2 | `setupSystemUI` から非推奨 `statusBarColor` を削除。エッジトゥエッジ+Scaffold黒背景で外見維持。`android.graphics.Color` import削除、警告解消 |
| P1-3 | DialogHelperの静的boolean廃止 → `WeakReference<AlertDialog>` + 同一コンテキスト&isShowing判定へ。Activity再生成後のフラグ固定バグを構造的に排除 |
| P1-4 | 新規 `ExcludeSelectorCodec.kt`（merge/remove純粋関数）+ VM委譲リファクタ + `ExcludeSelectorCodecTest.kt` 9ケース追加（空/重複/trim/空要素/正規化）全パス |

## 1. 総評

- **強み**: 単一Activity+Compose+単一ViewModelのシンプルなMVVM。DataStore永続化のデコード失敗ガード、CopyOnWriteArrayListによるタスク管理、JS側のtry-catch徹底など防御的実装が一貫している。import循環ゼロ。
- **弱点の傾向**: 「動いているからOK」の積み上げで、(a) 巨大化したGod Class、(b) JS文字列内のコピペ重複、(c) 静的状態のライフサイクル無視、の3類型が見られる。いずれも小規模修正で解消可能。

## 2. パッチワーク改善（即効・低リスク）

### P1-1: JSヘルパーの三重複 (ScrapingScriptBuilder.kt / 621行)
`getUniqueSelector` が buildScrapingScript / インスペクターpopup / probe の**3箇所**に重複。今回のエスケープ誤り(`\\s`)も重複編集時に起きた。
**対策**: 共通JSスニペットを `private val JS_UNIQUE_SELECTOR = """..."""` 定数化し `${JS_UNIQUE_SELECTOR}` 展開に。プレビュー/メトリック生成も同様に共有化。将来の仕様変更漏れを構造的に防ぐ。

### P1-2: 非推奨API (MainActivity.kt:94)
`window.statusBarColor = Color.BLACK` は API 35で非推奨（ビルド警告が出続けている）。
**対策**: `enableEdgeToEdge()` + `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars = false` へ置換。見た目維持のまま警告消滅。

### P1-3: DialogHelper の静的フラグ (DialogHelper.kt:54)
`private var isShowingInspectDialog` が object 内静的状態。**Activity再生成（回転・プロセス保持死）中にダイアログ表示中だとフラグがtrueのまま固まり、以後インスペクターダイアログが出なくなる**。
**対策**: フラグ廃止し、ダイアログ表示はComposable側のstateか、最低限 `context` がActivity instanceなら再生成判定して解除。あるいはshow前に古いAlertDialog参照をdismiss。

### P1-4: 除外セレクタロジックのテスト不在
`mergedExclude` / `removeExcludeSelector` は文字列処理の純粋ロジックなのに未テスト（既存テストはUrlExtractor等のutilのみ）。
**対策**: object `ExcludeSelectorCodec { merge(), remove() }` に切り出し、境界ケース（空・カンマ連打・前後空白・完全一致重複）の単体テスト追加。ViewModelからは委譲呼び出し。

### P1-5: テスト実行の隠れ計算コスト (MainActivity.performTestRun)
`buildScrapingScript(config, !blockImages, true)` — isDebug=true ハードコードで、**毎回全行に対するTreeWalker走査**が走る。長文ページで顕著。
**対策**: デバッグ行情報は除外編集に必要なので維持しつつ、TreeWalker初回ヒットで親要素をキャッシュし同一親の再走査をスキップ（行は順序実行なので大半がキャッシュヒットになる）。

## 3. アーキテクチャ整理（中期）

### A-1: ScrapingViewModel God Class (714行・公開関数約45個)
責務が6系統混在: ①UI overlay状態 ②config/presets ③history/favorites ④スクレイピングタスク ⑤翻訳キュー/サービス ⑥インポート/エクスポート。graphifyでも最大ハブ(50 edges)。
**段階的分割案**（一気にやらず、触った領域から）:
```
ScrapingViewModel (facade: 既存public API維持)
 ├─ UiOverlayReducer      ... togglePanel/setTestResult/excludeCandidates/exclusivity
 ├─ ConfigRepositoryBridge ... presets/favorites/history (DataStore委譲の薄いラッパ)
 ├─ ScrapingTaskController ... taskList/refreshStatus/startScraping
 └─ TranslationQueueManager... エンジン別キュー/サービス同期 (339+331行タスクと対になる)
```
既存呼び出し元(MainScreen/MainActivity)を壊さないfacade維持が鉄則。

### A-2: MainScreen の引数爆発 (362行・lambda 15本)
**対策**: `MainScreenCallbacks` data class へ集約（onXxxグループ化）。Panel系コンポーネントも同様。破壊的変更だが機械的。

### A-3: オーバーレイ状態の型統一
`openedPanel/testResult/excludeCandidates/isInspectMode` の排他が4フィールド×関数群に分散。V4.1で挙動は統一されたが、**構造はまだ分散**。
**対策**: `sealed interface Overlay { None, Panel(x), TestResult(card?), Inspect }` へ統一すると「1つしか開けない」がコンパイラ保証になる。A-1のUiOverlayReducerとセットで実施推奨。

### A-4: WebViewの二重所有
Compose `webViewRef` と Activity `mainWebView` が同一インスタンスを指す。現状はfactory経由で同期されるが暗黙知。
**対策**: Activity単一所有＋Composeはcallback受領のみ、を長期方針に（ドキュメント化だけでも可）。

## 4. 実装済みで問題なしと確認した点

- オーバーレイ排他（V4.1/V4.2）: 挙動統一済み。残るは上記A-3の構造化のみ
- probe/カード適用フロー: 例外ガード・null安全・自動再テスト経路とも堅牢
- DataStore更新ガード、タスクのライフサイクル連動、JS注入の一元化(LaunchedEffect): 問題なし

## 5. 推奨実施順

| 優先 | 項目 | 工数目安 |
|---|---|---|
| 今週 | P1-1 JS共通化 / P1-3 フラグ修正 / P1-2 非推奨解消 / P1-4 テスト追加 | 各30分〜 |
| 次 | P1-5 TreeWalkerキャッシュ / A-2 Callbacks集約 | 半日〜 |
| 計画的 | A-1 ViewModel分割（翻訳キューから着手） / A-3 Overlay sealed化 | 数日・要回帰テスト |

---

（この文書は対応完了後 docs/history/ へ移動すること）
