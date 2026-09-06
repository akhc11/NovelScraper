# LLM翻訳 引継書（rewrite 準備・2026-09-06）

他のAIが本セッションの続きを遂行するための記録。正本はコードと
`app/src/main/java/com/example/novelscraper/translation/llm/LLM_BEHAVIOR_STANDARD.md`。
本書と矛盾したらコード＋BEHAVIORメモを優先すること。

## 0. Git状態
- 直近コミット `68bb410`（checkpoint。LLM改善群＋ingest移行WIPを一括保存）
- コミット後に `LLM_BEHAVIOR_STANDARD.md` のみ追記（全体フロー図・詳細振る舞い・決定事項更新）。本書作成時点で未コミット
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
- 次の一手：工程1（仕様凍結＋不足テスト先行）の着手指示待ち

## 6. 作業ルール（必読）
- `translation/llm/` 配下の編集前に `translation/llm/AGENTS.md` を読むこと（他機能のは読まない）
- 日本語ファイルはUTF-8（BOMなし）。PowerShell `-replace` 一括置換禁止（日本語破損）
- 例外の無言握りつぶし禁止。失敗テストの無断削除・スキップ禁止
- コミット・PRは明示指示時のみ。DataStoreのJSON更新はデコード失敗時に既存保護
- 重い処理は `Dispatchers.IO`＋ライフサイクル連動スコープ
- Ask first：公開API・外部仕様・フォルダ構成の削除変更、新規依存、設計図JSON要否不明の変更

## 7. 動作環境・コマンド（引継ぎ先の初期設定用）
- Android / Kotlin / AGP 8.6.1 / Gradle 8.13 / JDK 21。作業ディレクトリ直下で実行
- 事前確認：`$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"` 後に `.\gradlew --version`
- focused：`.\gradlew :app:testDebugUnitTest --tests "com.example.novelscraper.LlmPipelineTest"`
- フル：`.\gradlew :app:testDebugUnitTest`（パイプライン変更時はフル＋実機確認）
- ブランチは `master` 直。graphifyの再構築フックがコミット時に走る（`graphify-out/` は手編集禁止）
- PowerShell条件実行は `cmd1; if ($?) { cmd2 }` 形式。ファイル操作は専用ツール使用
