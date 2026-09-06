# LLM翻訳 引継書（rewrite 遂行中・2026-09-06）

他のAIが本セッションの続きを遂行するための記録。正本はコードと
`app/src/main/java/com/example/novelscraper/translation/llm/LLM_BEHAVIOR_STANDARD.md`、
受入仕様は `docs/spec/llm-behavior-v1.md`（凍結済み）、詳細プランは
`docs/spec/llm-rewrite-plan.md`。本書と矛盾したらコード＋凍結仕様を優先すること。

## 0. Git状態
- 直近コミット `16de8f9`（docs(llm): rewrite受入仕様・プラン・引継書を追加）
- 未コミット（次回コミット対象）：
  - `translation/v2/` 新規33ファイル（工程2〜6＋物理分割・末尾注入。
    新規：`domain/DeclaredEncoding`、`pipeline/Ingest`・`PreSplit`・
    `V2LanguageModels`、`infra/FileStore.readBytes` 拡張）
  - `V2DomainTest.kt`、`V2EngineTest.kt`（事前分割・末尾注入・辞書公開の回帰3件追加）、
    `V2PipelineTest.kt`、`V2SettingsValidationTest.kt`（分割・前文脈の検証2件追加）、
    `V2ParityTest.kt`、`V2IngestTest.kt`（新旧取込等価含む10件）、
    `V2PreSplitTest.kt`（9件）（新規テスト）
  - `LlmPipelineTest.kt`（工程1の不足テスト3件追加）
  - `TranslationPanel.kt`、`MainScreen.kt`（v2入口の追加のみ。旧枝無改変）
  - `RunEngine.kt`（辞書確定物の公開・事前分割・前文脈）、`FileJobs.kt`（言語判定の旧等価修正）、
    `Translate.kt`（原文tail継承）、`V2Settings.kt`（分割・前文脈設定）、
    `SettingsRepository.kt`（旧取込5項目追加）、`V2SettingsDialog.kt`（分割・前文脈UI）、
    `V2SettingsValidation.kt`（検証2項目追加）
  - 本書、`docs/spec/llm-behavior-v1.md`（凍結マーク）
- `LlmTranslationConfig` 内の同梱APIキーはHEAD既存。新規秘密情報を混ぜないこと

## 1. ユーザーの確定制約（覆すな）
1. 辞書なし翻訳は絶対禁止（表記ゆれ、特にカタカナ名の漢字/カタカナ混在を嫌う）
2. Groqは不使用。関連設定・送信路・テストは除去済み（main/testソースに参照ゼロ）
3. 上限オーバーシュート修正は不要（見送り確定）
4. thinkingBudgetは露出しない（Gemini 3と無関係の旧式のため）
5. プロンプト定数分離・動的フォーム完全体・コストガード等は後回し（YAGNI）

## 2. 実装済み変更（コミット68bb410に含む）
- 対応モデル追加：`gemini-3.8-flash`（Stable・MINIMAL未対応）、`gemini-3-flash-preview`
  （安定版 `gemini-3-flash` は存在しない）。新規は temperature 未送信
- thinking一本化：`supportsThinkingLevel`（3.7/3.8のminimal→medium正規化）、
  `thinkingSupported`（Gemma系は思考系を送らずUIにも出さない）。表示と送信は同一関数参照
- 完了済み早期スキップ：出力一覧キャッシュで完了数==総数なら言語・辞書・ワーカーを飛ばす
  （`.failed`＝完了、`.parts_*`残存＝未完了、の意味は全経路同一）
- キー枯渇中止：開始前preflight＋フォルダ先頭＋分割連鎖先頭の3点。
  枯渇管理は（キー×モデル）ペア単位。翻訳モデル全滅（代替なし）または辞書モデル全滅で全体中止。
  RPM一時制限・代替キーありは継続。辞書429もモデル名付きで共有プールへ報告する
- 辞書の失敗振り分け：確定的失敗（ブロック等）は即切り上げて除外し、1件以上成功＋
  一時的失敗なしなら部分マージ。429・通信エラー混じり・成功ゼロは保留→次回再開
- 辞書キャッシュ検証：`.dict_building/manifest.json`（SHA-256）。不一致のみ再取得、
  manifestなし旧版は信頼（移行時の全再生成を回避）
- 取込再利用：`splitSingleTextFile` に `onIngestedText` を追加し、生ファイル二重全読みを排除。
  `common → llm.pipeline` 依存を作らないためString受けコールバック方式。未使用化した
  `detectLanguageOfFile` は削除済み。web経路の呼び出しは無変更で動作する
- 辞書thinkingLevel設定：設定UI（Gemini時のみ）＋抽出・マージ・レビュー全工程へ透過。既定null＝現状維持
- Groq除去：enum・設定・送信路（`ReasoningStyle.TOP_LEVEL_NONE` 機構ごと）・UI・テスト。
  OpenRouterの送信内容は除去前後で同一

## 3. テスト状態
- `.\gradlew :app:testDebugUnitTest` フル実行で全183件パス（`LlmPipelineTest` 76件含む）。
  ストレステストは `@Ignore` のためスキップ2件。AGENTS.mdのDoD上、残りは実機確認のみ
- 既知の無害な警告：`MainScreen.kt:120` のFlowPreview警告（本件と無関係）

## 4. 既知の残件・見送り（着手順）
1. `.dict_building` 旧キャッシュ信頼の切替時期（現状は移行配慮で信頼）
2. `saveFinalDictionary` の結果無視、`レビュー失敗の無言継続（仕様内として維持中）
3. マージ段階の確定的失敗は3回リトライ後に保留（稀のため許容中）
4. ローテーションOFF時は翻訳側枯渇がプールに伝わらない（既定ONのため軽微）
5. 非Gemini辞書のキー枯渇は共有プール不可視
6. topK送信残骸（未使用）、辞書の温度等未露出、safety設定なし、maxOutputTokens固定65536

## 5. rewrite方針（ユーザー確定済み）
- **純粋 greenfield** を選択（strangler・外部委託は不採用）。移行リスクは考慮外とする
- 設計核：能力記述子＋エラー共通分類＋汎用プール＋再試行／退避の層分離。
  単一プロファイル・ファイルシステム基準レジューム・2経路構成は merit により維持する
- 新規会社は原則OpenRouter経由で受容（コード追加ゼロ）。ネイティブ対応は明確な得がある社のみ
- UIは能力表からの動的生成を初日から。設定は「プリセット既定＜ユーザー上書き」、空＝未送信
- 同一リポの新パッケージ（例：`translation/v2/`）に構築し旧実装と共存、旧参照禁止。
  同一フォルダの新旧並行比較→差分ゼロで切替→旧削除
- 詳細プラン：`docs/spec/llm-rewrite-plan.md`（実装用全文）。受入仕様の草案：
  `docs/spec/llm-behavior-v1.md`（工程1で凍結する。それまでは改訂可）
- 工程：仕様凍結＋不足テスト → domain/SPI/能力表 → infra → pipeline → UI → 並行比較・実機・切替
- 進捗：工程1完了（仕様凍結・旧コード不足テスト3件追加・フルパス）。
  工程2のdomain層まで実装済み（未コミット）：`translation/v2/domain/` に
  分類・能力表・値解決・汎用プール・コスト計・SPI、`V2DomainTest` 6件パス。フル193件パス
- 進捗：工程3のinfra層まで実装済み（未コミット）：`translation/v2/infra/` に
  FileStore契約＋SAF実装＋メモリfake、`v2/settings/` に設定モデル＋DataStore＋旧取込、
  Gemini/OpenRouterハンドラー（純粋な送受信組立・解析＋fixtureテスト）。
  `V2DomainTest` 10件、フル197件パス
- 進捗：工程4のpipeline＋engine層まで実装済み（未コミット）：
  `translation/v2/pipeline/`（状態・言語・品質・分割結合・バッチ入出力・プロンプト組立・
  辞書stage・翻訳フロー）、`translation/v2/engine/`（巡回・実行エンジン）、
  `translation/v2/ui/`（能力駆動フォーム部品）。
  `V2PipelineTest` 13件・`V2EngineTest` 4件、フル214件パス
- 進捗：工程5のUI・設定動的化まで実装済み（未コミット）：
  `translation/v2/ui/` に検証（`V2SettingsValidation`）・設定ダイアログ
  （`V2SettingsDialog`：モデル一覧・辞書・キー・制限・コスト上限＋保存前範囲検査＋
  疎通テスト＋旧取込）・実行VM（`V2TranslationViewModel`）・実行パネル
  （`V2TranslationPanel`：`RunEngine` 状態表示結線）、旧 `TranslationPanel` の
  LLM_API枝に並ぶv2入口を追加（旧削除は工程6）。`V2SettingsValidationTest` 7件、
  フル221件パス（v2からの旧参照ゼロ確認済み）
- 進捗：工程6の自動化部分まで実装済み（未コミット）：
  (a)辞書永続化バグ修正＝`RunEngine.buildDictionary` が確定物をフォルダ直下
  `dictionary.json` へ公開＋`.dict_building` 掃除（読込点と保存点の不一致で毎回
  再生成になっていた。凍結仕様§8）。回帰テスト `testRunEngine_DictPublishedAndReused`。
  (b)言語判定修正＝`FileJobs.detectLanguage` のハングル混入則を10%→漢字多数決に
  変更（ハングル引用含む中国語の旧回帰と等価に）。(c)自動並行比較ハーネス
   `V2ParityTest` 6件（言語5＋引用混入・品質ゲート英韓・完了マーカー・バッチ枠・
   チャンク行保全・辞書サンプリング）。フル219件パス（失敗0・旧参照ゼロ維持）。
- 進捗：物理分割＋末尾注入を実装（未コミット。ユーザー要求）：
  (a)取込 `pipeline/Ingest`＝宣言/BOM/ESC/厳格UTF-8/均一仮説採点のfail-closed取込。
  較正値・頻度表は凍結仕様として再定義（`V2LanguageModels`。新旧クロステストで等価担保）。
  (b)事前分割 `pipeline/PreSplit`＝行束ね・超長行分割・既存尊重・rollback・空1パート・
  チャンク文字化け検証付き。3MB級の途中ブロック時はpart単位で `.failed` 化し、
  他パートは継続・結合不要のため停止しない。(c)末尾注入＝単体・バッチ・チャンク1・
  フォールバック継承へ直前ファイル原文末尾（既定20行・既定OFF）を透過。
  `V2IngestTest` 10件・`V2PreSplitTest` 9件・engine回帰2件・検証2件・旧取込1件。
  フル248件パス（失敗0・v2の旧参照ゼロ維持）。
  残りは実機チェックリスト（§7）→切替判断→旧削除（Ask first・§8参照）のみ
- 次は実機検証（§7をユーザー実施）→切替＋旧削除の可否判断（§8）を行うこと

## 5b. なぜrewriteしているか（背景の要約）
- 旧実装は1300行級エンジン・2000行級ダイアログ等へ肥大し、社内モデル差
  （Gemma思考400等）の判定が散在していた。分岐を増やしても消えない複雑さを
  「単一判定点・能力表・汎用プール」に集約する設計へ作り替える
- ユーザー確定方針は純粋 greenfield（strangler・外部委託は不採用）。
  ただし単一プロファイル・FS基準レジューム・2経路構成は単純さのmeritで維持する
- 旧コードの参照・流用は禁止。仕様書とテストのみ持ち込み可。
  `translation/v2/` と旧実装の共存＋パッケージ境界で担保する

## 5c. v2ファイル一覧（責務）
- domain/：`FailureKind`（6分類）、`ErrorMapper`（共通＋Gemini日次分離）、
  `Capabilities`（能力表・Gemini/OpenRouter既知収録）、`Params`（値解決単一則）、
  `QuotaPool`（(資格情報×スコープ)汎用）、`CostMeter`（上限ガード）、
  `ProviderHandler`（送受信の最小契約＋要求型）、`DeclaredEncoding`（入力charset宣言）
- pipeline/`Prompts`：7定数（旧転記・一致テスト付き）＋解決順pure関数・
  自動選択表・バッチ形式指示。`Quality` に行数比を追加
- infra/：`FileStore`（契約＋`readBytes`）、`SafFileStore`（実機用）、
  `InMemoryFileStore`（テスト用fake＋`writeBytes`）、
  `GeminiHandler`・`OpenRouterHandler`（送受信組立・解析。純粋関数＋fixtureテスト付き）
- settings/：`V2Settings`（設定モデル＋分割・前文脈）、
  `SettingsRepository`（DataStore＋旧取込検証付き）
- pipeline/：`FileJobs`（状態・言語検出）、`Quality`（剥離・マーカー・サイズ比・かな率）、
  `Chunking`（分割＋追記結合）、`BatchIO`（束ね・分離）、`Prompts`（基底文注入式の組立）、
  `DictStage`（抽出・マージ・レビュー＋manifest＋失敗振分）、`Translate`
  （単体・バッチ救援・大ファイル。attemptDriversは汎用再試行層として残す）、
  `Ingest`（fail-closed取込＋仮説採点＋塊検証）、`PreSplit`（事前物理分割）、
  `V2LanguageModels`（取込頻度表。バイト等価検証済み）
- engine/：`Rotation`（モデル×プロンプト巡回・同一スコープ再送・枯渇skip・キー交代）、
  `RunEngine`（preflight・事前分割・早期スキップ・言語キャッシュ・辞書・
  ワーカー分配・前文脈・中止判定・進捗）
- ui/：`CapabilityForm`（能力駆動の動的フォーム部品）、`V2SettingsValidation`
  （保存前検査＋丸め・JVMテスト付き）、`V2SettingsDialog`（本組立）、
  `V2TranslationViewModel`（設定購読・実行結線）、`V2TranslationPanel`（v2入口）
- 教訓：単体テストのJVM上で `org.json` は使えない（Androidスタブ）。JSON操作は
  kotlinx.serialization に統一すること
- 教訓：本環境のwrite/edit経路では非ASCII・`\u` シーケンスが破損しうる
  （ハングル1文字の実破損を確認。他はコンソール表示化けのみ）。
  新規CJKリテラルは避け、頻度表等の固定データはバイト列で新旧等価検証する。
  確定破損時はPowerShellのバイト置換で修復し、引継書に記録する。
  日本語コメント・常用漢字かなカナは保存実績あり（編集後に spot check する）

## 6. 作業ルール（必読）
- `translation/llm/` 配下の編集前に `translation/llm/AGENTS.md` を読むこと（他機能のは読まない）
- 日本語ファイルはUTF-8（BOMなし）。PowerShell `-replace` 一括置換禁止（日本語破損）
- 例外の無言握りつぶし禁止。失敗テストの無断削除・スキップ禁止
- コミット・PRは明示指示時のみ。DataStoreのJSON更新はデコード失敗時に既存保護
- 重い処理は `Dispatchers.IO`＋ライフサイクル連動スコープ
- Ask first：公開API・外部仕様・フォルダ構成の削除変更、新規依存、設計図JSON要否不明の変更

## 7. 実機チェックリスト（工程6残件・ユーザー実施用。API費用に注意し対象を絞ること）
- [ ] 新旧サンプリング比較：同一小規模フォルダ群を旧パネル→出力退避→`.failed`除去→
  v2パネルで実行し、訳文出力の差分を確認（物理分割OFF・辞書ON/OFF両条件）
- [ ] 長時間・並列：3並列で10ファイル超の実行が完走すること
- [ ] 中断再開：実行中停止→`.failed`なし→再実行で `.parts_*` レジューム＋完了すること
- [ ] 長編：大ファイル1件でチャンク結合が全文揃いで完了し、作業所が掃除されること
- [ ] 枯渇中止：無効キーで全体中止し、進行中ファイルに `.failed` を作らないこと
- [ ] コスト上限：上限極小で新規送信が止まり中断扱いになること
- [ ] 辞書再利用：2回目実行で辞書再生成ログが出ないこと（自動テスト済みの実機裏付け）

## 8. 切替＋旧削除の判断材料（Ask first・未実施）
- 自動比較の到達点：`V2ParityTest` 18件・`V2IngestTest` 10件（新旧取込等価7系列含む）・
  `V2PreSplitTest` 9件・`V2PipelineTest` 17件・`V2ResidualTest` 10件で検証済み、
  フル268件パス。バッチ形式指示をv2再設計で移植（下記）。
- 進捗：7プロンプト＋行数比チェックを移植（未コミット。ユーザー要求）：
  7定数は旧転記（新旧文面一致テストで担保）、解決順はpure関数に再設計
  （Rotation共有前提のため primary 決定。旧既定と同値）。行数比は引数化して移植。
  `V2PromptTest` 3件・engine自動選択1件・検証1件。フル273件パス。
  残る乖離は送信ゲート等のみ。プリセット・カスタム文面編集は次段階として見送り。残留検出は文節単位・書記素優先で実装（下記）。検証済み等価：言語検出6文・
  品質ゲート英韓・残留簡体字・前口上耐性・完了マーカー素形・バッチ整形枠・
  チャンク行保全・辞書公開/再利用・物理分割（取込判定・束ね・既存尊重・rollback・
  空1パート・文字化けabort・SJIS実バイト）・末尾注入（単体・バッチ・チャンク1・
  フォールバック継承）・空ファイル・`.failed`/`.parts_`/`.lang_cache`/早期スキップ・
  chunk停止・結合掃除・停止時無墓標・設定エラー無墓標・クレーム解放・
  manifest旧版信頼・辞書必須skip・マージ3/レビュー2/1断片直行・reasoning none・
  temperature省略・並列3/遅延10/出力dir・providerOrder透過。
  乖離は下記（JVM検証＝★、コード対照＝☆。実機比較の確認対象。仕様改訂v2要否は
  切替判断時に決める）：
  1. ★最低送信間隔ゲートなし（旧10秒全共有。`requestDelaySec` 未参照。429率に影響）
  2. ★ワーカー開始ずらし既定0（旧は(wId-1)*10秒。機構はあり）
  3. ★`promptOrder` 固定 `[1,1]`＋基底1種（旧は7種＋言語別自動KO[3,7]/ZH[1,1]/EN[2,7]。
     出力内容に直結する最大の残乖離。実機のみ検証可）
  4. ★サイズ比既定：ユーザー指示に基づき旧版と完全等価（ZH102/200・KO90/150・EN105/220・JA100/200）に修正済み（V2ParityTest等価確認済み。sizeRatioOkのtrim正規化も適用）。
  5. ★行数脱落不検出（旧は訳行×3＜原文行でFailure）
  6. ★マーカー表記揺れ・後口上救済：アイデアA（末尾300字限定・装飾/表記揺れ/全角括弧/後口上救済）を実装済み（V2ParityTest等価確認済み）。バッチ完走判定は厳密XML維持。
  7. ★バッチ救済なし（旧の全角・引用符省略・旧式SEG・JSON→v2は厳密XMLのみ。
       ただし失敗時は単体fallbackで吸収する設計。形式指示は移植済み：
       `buildBatchFormat` が件数・形状・例・末尾注意を1ブロックで指示。
       旧の行削除方式ではなく明示上書き方式（v2基底が1行のため削除不可）。
       例は1件に圧縮）
   8. ★バッチ入力無洗浄（旧は制御除去＋構造タグ全角化。原文内偽タグの誤分割余地）
   9. ☆thinking minimal正規化なし（旧は3.7/3.8をmedium送信、v2は未送信）
   10. ☆lite系temperature=1.0未送信（旧送信、v2能力表にsamplingなし）
   11. ☆maxOutputTokens未送信（旧はGemini 65536固定送信）
   12. ☆言語別splitしきい値なし（旧は目標文字数からの言語別逆算・下限4000、v2固定30000B）
   13. ☆辞書抽出リトライ4固定・並列既定8（旧は(キー数×2,2-6)・既定4）
   14. ☆辞書モデル既定空→開始不可（旧既定3.1-flash-lite。要設定化の運用差）
   15. ☆疎通テストはゲート不消費・内容とタイムアウトが旧と微差
   16. ★前文脈エコーの除去なし（新旧とも防止は指示文のみ。別話原文tail・訳文tailの
       反復は両側とも受理し保存物に残る。唯一の差は当該原文の先頭反復で旧のみ検出。
       バッチは注入到達・チャンク1のみ注入を動作確認済み）
- 実装済みのため候補から除外：物理事前分割・原文tail注入・文字コード判定・
  辞書確定物公開・残留検出（文節単位。短漢字ブロックは周囲継承・ラテンのみ不検出・
  ZH/KOのみ対象。敵対的レビューは引継ぎ応答2026-09-07を参照）・
  サイズ上限200MB/コスト上限（v2新設の拡張）
   - 実装済みのため候補から除外：物理事前分割・原文tail注入・文字コード判定・
     辞書確定物公開・サイズ上限200MB/コスト上限（v2新設の拡張）
- 削除範囲（2ホップ確認済み）：`translation/llm/` 全33ファイル＋3md、
  `TranslationQueueManager.llmEngine` 系、`ScrapingViewModel` のLLM系4関数、
  `PreferencesRepository.llmConfigFlow` 系、`MainUiState` のLLM系2状態、
  `MainScreen` の `LlmSettingsDialog` 分岐、`TranslationPanel` の旧枝、
  `ActiveDialog.LlmSettings`、`LlmPipelineTest` 79件・`GemmaStressTest` 2件
  （失敗テストの扱いは明示承認が必要）、`V2ParityTest` の旧参照部のv2単独化、
  Archify `llm-translation.workflow.json`/HTMLの同期要否
- 前提：§7実機チェックの完了＋上記テスト扱いの明示承認。両方が揃うまで旧削除しないこと

## 9. 動作環境・コマンド（引継ぎ先の初期設定用）
- Android / Kotlin / AGP 8.6.1 / Gradle 8.13 / JDK 21。作業ディレクトリ直下で実行
- 事前確認：`$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"` 後に `.\gradlew --version`
- focused：`.\gradlew :app:testDebugUnitTest --tests "com.example.novelscraper.LlmPipelineTest"`
- フル：`.\gradlew :app:testDebugUnitTest`（パイプライン変更時はフル＋実機確認）
- ブランチは `master` 直。graphifyの再構築フックがコミット時に走る（`graphify-out/` は手編集禁止）
- PowerShell条件実行は `cmd1; if ($?) { cmd2 }` 形式。ファイル操作は専用ツール使用
