# web翻訳 開始時クラッシュ — 修正プラン（実装前レビュー確定版）

最終更新: 2026-09-11 / 対象: `translation/web/` + `TranslationPanel` / 設計図 (`docs/archify/web-translation.workflow.json`) は境界変更時のみ同期
状態: **実装済み（2026-09-11）・実機確認待ち**（Ask first: 公開API・外部仕様・フォルダ構成・新規依存の変更なし）

## 1. 結論（3行）

- 主原因は `TranslationQueueManager.start() → Dispatchers.IO → new BaseWebTranslationTask() → new WebView()` のスレッド違反であり、方向性は**根本修正**（パッチワークではない）。
- 現時点の証拠ではlogcat未取得のため「100%確定」ではなく**有力仮説（高確度）**に留める。確定には実機logcatの裏付けが必要。
- 修正は「Mainでオーケストレーション＋WebView生成、SAF/分割/保存のみIO」に切断する最小・疎結合パッチとする。他機能（LLM/V2・Scraper）に触れない。

技術的根拠1行: ファイルIOは既に`TranslationFileStore`内で`withContext(IO)`済みなのに外側でIOに寄せた結果View生成だけがスレッド違反になった、責務配置の不一致。

## 2. 根拠チェーン（ファイル:行）

- `translation/web/TranslationQueueManager.kt:197` — `scope.launch(Dispatchers.IO)` でキュー展開を開始。
- `translation/web/TranslationQueueManager.kt:275` — `startNextFolderInQueue()` 内で再度 `scope.launch(Dispatchers.IO)`（二重ホップ）。
- `translation/web/TranslationQueueManager.kt:414` — そのIO文脈で `BaseWebTranslationTask(...)` を構築。
- `translation/web/BaseWebTranslationTask.kt:59` — `private val webView = WebView(context.applicationContext)` はフィールド初期化子＝呼出スレッド（＝IO）で実行。`init:68-69` の `applyStandardSettings/layout` も同スレッド。
- 対照: `ScrapingViewModel.kt:495` → `scraper/ScrapingTask.kt:36` はUI起点・Main生成のため落ちない。web翻訳だけ落ちる事実と一致する。
- `BaseWebTranslationTask.kt:62,88` — 本体ループは `Dispatchers.Main + SupervisorJob` 上で回り、`TextChunker`等のCPU処理までMainに載っている（Main/IO逆転）。
- `BaseWebTranslationTask.kt:72-82` → `112-124` — 安全ハンドラをページロード用`webViewClient`で上書きし、以後復元なし。
- `BaseWebTranslationTask.kt:130-132` → `310-311` — `withTimeout`のタイムアウトが`catch(CancellationException)`に吸われ「中断しました」に誤分類される。
- `BaseWebTranslationTask.kt:419-426` — `evalJs`は`mainHandler.post + suspendCancellableCoroutine`でタイムアウト/解除時の後始末なし。
- `BaseWebTranslationTask.kt:428-442` — `stop()`の`destroy()`は`mainHandler.post`で正しい。以降も維持する。

## 3. 一次情報（思い込み禁止の裏付け）

- Chromium `android_webview/docs/threading.md`: Viewは単一view threadに束縛。WebViewはJB MR2以降`WebView.java`で強制チェック。最初に触ったスレッドをUIスレッドと見なす。
  - https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/threading.md
- AOSP `WebView.java`: `checkThread()` で同一スレッド強制。`A WebView method was called on thread ... All WebView methods must be called on the same thread`。
  - https://android.googlesource.com/platform/frameworks/base/+/a5408e6/core/java/android/webkit/WebView.java
- Android Developers `Optimize WebView startup`: バックグラウンド化が許されるのは`WebViewCompat.startUpWebView()`の初期化作業のみ。View生成自体のオフスレッド化は正当化されない。
  - https://developer.android.com/develop/ui/views/layout/webapps/optimize-webview-startup
- Android Developers `Best practices for coroutines`: `Suspend functions should be main-safe ... using withContext`、`Dispatchers.Main=UIのみ`、`Dispatchers.IO=blocking I/O`、`Don't hardcode Dispatchers`、`Prefer Main for root`。
  - https://developer.android.com/kotlin/coroutines/coroutines-best-practices
  - https://developer.android.com/kotlin/coroutines/coroutines-adv
- Kotlin公式: `class TimeoutCancellationException : CancellationException ... thrown by withTimeout`。
  - https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-timeout-cancellation-exception
- WebView終了処理公式: `Don't reuse the WebView instance ... remove, destroy, clear references ... Create a new instance ... Return true`。
  - https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
  - https://developer.android.com/develop/ui/views/layout/webapps/handle-termination
  - https://developer.android.com/develop/ui/views/layout/webapps/manage-webview-memory

## 4. 根本 vs 対処の評価表

| 主張 | 判定 | 理由1行 |
|---|---|---|
| WebView生成スレッド違反が主原因 | 根本・正 | 生成/使用の単一スレッド束縛という不変条件違反のため |
| Main生成＋SAF/分割/保存のみIOに残す方針 | 根本・正 | 責務配置の不一致を直すもので公式main-safe分離に合致するため |
| 二重`launch(IO)`解消 | 根本だが説明不足→§6で補正 | 二重ホップ自体は無駄だが単純削除はANR回帰のため分割線が必要 |
| `onRenderProcessGone`上書き指摘 | 根本・正（過小評価→§5で補強） | ループ中の死亡通知が欠落する実バグのため |
| `withTimeout`誤分類指摘 | 正・軽微 | 継承関係は公式確認済みのため |
| `scope.cancel()`整理 | 方向は正・手段要精査 | 独立`SupervisorJob`放置は構造化並行性違反だが冪等性に注意が必要なため |
| 「100%一致」断定 | 要修正→有力仮説に格下げ | logcat裏付けなしに確定はできないため |

## 5. 構造レビュー [A][B][C]

動作フローは単一パイプラインではない: `IO(197) → IO(275) → Main(62,88) → IO(FileStore)` の4ホップで追跡困難。推奨は「Mainオーケストレーション＋`withContext(IO)`のmain-safe関数」への一本化。

- [A] 無駄なコード（デッドフォールバック）:
  - `72-82`の安全ハンドラは`112-124`で上書きされ以後復元なし。第二クライアントの`119-123`は`pageLoaded`（完了済み）だけ見て`return true`し、`listener通知/stop()`を呼ばない。ループ中のレンダラー死亡は無音ハング→各待機タイムアウトまで停滞する。
  - `130-132`の`pageLoaded=false`を無視して続行し、`waitForDomReady:321-327`に雪崩れる失敗値の握りつぶし連鎖がある。
- [B] 不要な二重処理:
  - `197`→`275`の二重`launch(IO)`は無駄。ただし外側IOを丸ごとMain化すると`210,212,301`の`resolveDocument/listFiles`（ブロッキングSAF IPC）がMainに載りANR化する。`TranslationFileStore`系は`withContext(IO)`済みでmain-safeだが、Manager自身のSAF列挙は未分離のため、切断線の定義が必要。
  - 翻訳ループ（Main上）で`TextChunker`等のCPU処理をしている。`Default=CPU集約`分離から外れる。
- [C] 危険なコピペ重複:
  - `ScrapingTask.kt:36`と`BaseWebTranslationTask.kt:59`は同型のフィールド初期化パターン。現在は前者がMain生成で偶然安全なだけ。Main生成を強制する共通ヘルパーなしは再発源。
  - `evalJs:419-426`にタイムアウト/解除時の後始末がなく、両タスク横断の定型処理として切り出し対象。

## 6. 推奨方針（最小・疎結合の追加4点。実装は別途承認後）

1. `TranslationQueueManager.start/next`はMainでオーケストレーションし、SAF列挙・分割準備のみ`withContext(IO)`に閉じ込める。`BaseWebTranslationTask`の`new`と`start()`はMainで呼ぶ。他機能（LLM/V2・ScrapingTask）に触れない。
2. `BaseWebTranslationTask`内の`FileStore/TextChunker`呼び出しは既にmain-safe／`Default`逃がしにし、`WebView`系（`settings/url/loadUrl/evaluateJavascript/destroy`）のみMain/`mainHandler`に統一。`webViewClient`は単一インスタンスに統合し、`onRenderProcessGone`で必ず`destroy＋新規生成方針＋listener通知`に一本化（公式の再利用禁止を遵守）。
3. `withTimeout`→`withTimeoutOrNull`化または`TimeoutCancellationException`先行catchで誤分類解消。
4. 検証: `TextChunkerTest/LiveTranslateScriptBuilderTest` focused → フル`testDebugUnitTest`（パイプライン変更のため）→ 実機で`adb logcat -c`→開始→`AndroidRuntime/checkThread`消滅と、フォルダ1件/Googleのみの疎通確認。Archify JSONは境界変更時のみ。

補足（本プランでの事前合意事項）:

- `web/`専用ルール遵守: Google貼付けのCtrl+V再現シーケンス（`focus→select→paste→input:insertFromPaste→change`）は崩さない（`translation/web/AGENTS.md` Trap 1）。
- バックグラウンド用はUIのWebView流用ではなくアプリコンテキストの独立WebViewを維持する（Trap 2）。変えるのは生成スレッドのみ。
- DOM・セレクタは`WebTranslationStrategy`に集約のまま。新規サービス対応はAsk first。
- 例外の無言握りつぶし禁止・`DataStore` JSON保護・`Dispatchers.IO`＋ライフサイクル連動・UTF-8(BOMなし)を維持。新規依存なし。

## 7. 不明点と追加で必要な情報（確定に必要）

- 実機logcat（`AndroidRuntime/checkThread/Looper.mQueue/WebViewChromiumFactoryProvider`の有無）。
- 再現条件: エンジン種別（Google/DeepL/Papago）・分割ON/OFF・フォルダ種別（生txt/分割済み）・Android/WebViewバージョン（DeepL/Papagoは`isDesktop=true`で負荷が異なりOOM系との切り分けに必要）。
- `TranslationQueueManager`に渡る`scope`の実体（`viewModelScope`かServiceスコープか。ライフサイクル連動の判定に必要）。

## 9. 実装記録（2026-09-11）

- `TranslationQueueManager.kt`: `start/next`の`launch(Dispatchers.IO)`をMain化し、阻塞SAF解決（`resolveDocument/listFiles/parentFile/分割済みfind-create`）のみ`withContext(IO)`に分離。`splitSingleTextFile`はmain-safeのためMainから呼ぶ。`BaseWebTranslationTask`構築はMain文脈のまま。
- `BaseWebTranslationTask.kt`: 生成時Mainチェック（fail-fast）、`WebViewClient`単一化（`pageLoadSignal`方式・上書き廃止）、`TextChunker`を`Dispatchers.Default`へ、`TimeoutCancellationException`先行catchで誤分類解消、`stop()`で`scope.cancel()`しリーク防止。Google貼付けJS・Strategy・保存仕様は不変。
- 検証: focused（`TextChunkerTest`・`LiveTranslateScriptBuilderTest`）パス → フル`testDebugUnitTest`パス（21ファイル・failures/errorsゼロ、BUILD SUCCESSFUL）。Archify JSONは責務境界不変のため未更新。
- 残: 実機確認（`adb logcat -c`→Google翻訳開始→`AndroidRuntime/checkThread`消滅と1フォルダ疎通）。

## 8. Definition of Done

- 常時: focusedテスト（`TextChunkerTest`・`LiveTranslateScriptBuilderTest`）全件パス。
- パイプライン・IPC・保存処理を変えるため: フルテスト＋実機（変更したサービスのみ）＋該当Archify JSON/HTML同期の要否判断。
- 本ファイルは計画書であり、コード未変更。実装時は本プランの§6のみを対象とし、他機能の差分が出たら即時中断して再レビューする。
