# handoff: 辞書の人物メモ欠け（名前だけ確定）修正

## 症状
- `dictionary.json` が `{"characters":{...},"promptsHash":"..."}` の名前だけになり、人物メモ（`profiles`）が付かない。
- 実ログ：素あり96名 → 名寄せ89名 → 命名88名 → `✅ 辞書確定: 88名（スタイル: カタカナ）`。素は集まっているのにメモが消える。

## 根本原因（3点連鎖）
1. **命名プロンプトの例文学習バイアス**：`translate` の例文がカタカナ2件とも `"profiles":{}` だったため、韓国語（カタカナ既定）の命名で軽量モデルが空返却する。
2. **受渡し無検証**：`generateDictionary` の命名ループは `characters` 非空だけ見て確定し、`profiles` ゼロを再試行・警告なしで採用していた。
3. **書出し省略**：`dictJson` が既定値キー（`style`/`profiles`）を省略して書くため、空メモ辞書が旧形式と区別不能＋確定ログにもメモ件数が出ない。

## 修正（core-translation/.../pipeline/DictStage.kt）
- `translate` 文面：素あり名には全件メモを要求（空オブジェクトは素なし時のみ）、カタカナ例文をメモ付き（`사재혁`）に差替え、空例は `no hints given ->` と条件明示。
- 命名ループ：素送信済み＋`profiles` 空なら上限（`reviewRetries`）内で取り直し。最終試行でも空なら訳語温存で確定＋警告ログ（翻訳全体は止めない）。
- `dictJson` に `encodeDefaults = true` を明示し全鍵を常時書出し。
- 確定ログに `・人物メモN件` を追加（旧前缀は維持）。
- テスト：`V2DictInternalTest` に4件追加（全鍵書出し／空メモ再試行で確定／素なし互換1回確定／上限後メモなし確定＋警告）。

## 表記修正（実動作「削除→再実行」に合わせる）
- `V2PromptsTab.kt`：「自動再生成」→「dictionary.json を削除して再実行で作り直し」。
- `dictionary_behavior.md`：文面変更時の作り直し説明・末尾の作り直し手順・命名手順のメモ合成を修正。

## 利用者手順（既存の名前だけ辞書をメモ付きに作り直す）
1. 対象小説フォルダ直下の `dictionary.json` と `.dict_building` を削除。
2. 再実行（辞書モデルは既定のまま・プロンプト空欄でよい）。
3. ログに `✅ 辞書確定: N名（スタイル: カタカナ・人物メモM件）` と出ることを確認。

## 追補（人物メモ1件確定への対応）
- 現象：修正版で再生成 → `辞書確定93名・人物メモ1名`。前回修正の再試行条件が「メモ0件」のみで、部分欠け（1件以上）は即確定していた。
- 追加修正（`DictStage.kt` 命名ループ）：
  - 被覆率基準に変更：素あり名に対するメモ付き数を数え、全件未満なら上限内で取り直し。
  - 最良保持：取り直しで後退（良い回を悪い回で上書き）しないよう、被覆率最大の回を確定。
  - 試行ごとに `人物メモX/Y件のため再試行`、確定時に `人物メモX/Y件で確定` を記録。規格外で落としたメモ件数も記録。
  - プロンプトに `do not stop after a few` を追加（途中で止めるなを明示）。
- テスト2件追加：部分→全件で確定、最良回保持。`:core-translation` 全パス、`:app` focused（V2Pipeline/V2Engine計146件）は既知の `testRunEngine_AutoPromptOrder` 1件のみ失敗（変更前後で同一・無関係）。
- 利用者手順：再度 `dictionary.json`＋`.dict_building` を削除して再実行し、確定行の `人物メモM件` を確認。

## 追補2（命名の分割送信：辞書肥大時の性能非依存化）
- 要望：辞書が大きくてもAI性能に依らず処理できるよう分割すべき。
- 修正（`DictStage.kt`＋`TranslationLimits.kt`＋`DictOptions.kt`相当）：
  - 命名・翻訳を `DICT_TRANSLATE_CHUNK_NAMES = 60` 名ずつに割って送信（`DictOptions.translateChunkNames` で変更可）。
  - 判定・再試行・最良保持はチャンク単位に移動（`translateChunk` に抽出）。表記スタイルは全チャンクの多数決で統一（同数は先勝ち、割れは警告ログ）。
  - チャンク失敗時は従来通り全体保留（次回再挑戦）。訳語・メモは重複なく統合。
- テスト追加：2チャンク統合＋スタイル同数先勝ち。`:core-translation` 全パス、`:app` focused計146件は既知の1件のみ失敗。
- 利用者手順：再度 `dictionary.json`＋`.dict_building` を削除して再実行。命名ログが `N分割で実行中` になる。

## 追補3（無駄コード監査：detekt未使用・Lint・CPD相当を手監査）
- 手法：外部知見（detekt `UnusedPrivateMember`/`UnusedPrivateClass`、Android Lint `UnusedResources`、Sonar CPD相当の目視）に沿い、辞書パイプライン（`DictStage.kt`・`DictionaryBuilder.kt`・`DictionaryStage.kt`）を監査。全リポジトリ横断は対象外（通常修正は対象＋テストのみの原則）。
- 検出1【デッドフォールバック】：`translateChunk` 末尾の `bestDict ?: sanitized`。初回で必ず最良が入る（被覆率0以上＞初期値-1）ため代替側は到達不能と証明 → `bestDict!!`＋根拠注記に修正。
- 検出2【不要な二重処理】：`styleVotes.distinct()` を判定・分岐・記録で3回再計算 → `distinctStyles` に1回化。
- 検出3【コピペ重複】：`V2DictInternalTest` 6件の同一配線（store/文面/記録/呼出し）を `runDictGen` ヘルパーに集約。各テストは応答振る舞いのみ残す。
- 白（問題なし）：private関数7件は全て呼出しあり。`publish` の parse→copy→encode は正規化＋刻印のため正当。チャンク毎の sanitize/parse/素選択は都度新規応答のため正当。
- 保留（公開APIのためAsk-first、未削除）：`DictPrompts.review`（後方互換エイリアス）は読者ゼロ。消す場合は別途確認。
- 検証：`:core-translation` 全33件パス（内 `V2DictInternalTest` 8件）。

## 追補4（推敲の別モデル分離）
- 要望：推敲を翻訳と違うモデルで回したい。
- 仕様：`V2RefineSettings` に `providerId`＋`model` を追加。空＝翻訳継承（従来動作）、model有り＋provider空＝gemini。
- 配線の根拠：既存の思考上書き表は model不変が前提（クォータ scope 混用のため別モデルを載せられない）→ 専用モデル時は単一プロファイルの巡回器を別建て（`createRouter` 再利用・同一資格情報・解放冪等のため二重解放も安全）。継承時は従来路のまま。
- 検証切替：思考可否の判定対象を専用モデル単体に（表示 `推敲(専用モデル)`）。鍵要求に専用先を含める。保存は空欄省略のため旧JSON互換。
- UI：共通タブ推敲欄にプロバイダー選択＋モデル入力＋モデル選択（REFINE）。開始ログに `✨ 推敲専用モデル`。
- テスト：core `V2RefineProfileTest` 3件、app 検証3件＋エンジン通し1件（磨き採用まで確認）。全435件中1件の既知失敗のみ（`Hanja` stale。2026/09/30に修正済み：基底文改訂で消滅した"Hanja"行の指紋を現行プロンプト3の一意識別行に更新）。
- 利用手順：詳細設定→共通タブ→推敲をON→推敲専用モデルに指定（空なら従来通り）。

## 追補5（実装の厳密レビュー反映）
- 観点：公式ベストプラクティス（coroutines内プロセス処理・UDF/state-hoisting・Composition Root集約）に照合。
- 指摘F1【二重処理】：専用判定＋開始ログがワーカー毎に再計算・多重記録 → 束ね前に1回化。
- 指摘F2【無言fallback】：専用routerなし時の継承代行が無記録 → 警告ログを追加。
- 指摘F3【寛容素通しの可視化】：未知providerは送信層でOpenRouter扱いに倒れる（既定仕様・クラッシュなしを確認）→ 検証で警告化（取込時の除外警告と対称）。
- 残留リスク（受容）：主・副routerが同一資格情報を共有し切替え時期がずれる場合あり。各routerが自律再取得し、磨き失敗は未完了保留に倒れるため安全側。共有解放は冪等（Set.remove）のため二重安全。
- 追加テスト：openrouter推敲の鍵選択（`x/y|or`）。全437件中1件の既知失敗のみ。

## 追補6（推敲不採用時は保存せず保留）
- 要望：推敲ONで磨きができなかった場合は保存しない。
- 仕様変更：`polishTranslation` を `PolishResult`（採用／保留／停止）のsealed化。磨き失敗・検証不合格・停止時は初回訳を返さない。
- 単品路：保留→非確定 `Failed`（`.failed` 化しない）で未完了保留。束ね路：保留品は保存せず単体再送もせず保留（再送しても磨き直すだけの無駄のため）。
- 判定安全性：`shouldPersistFailed` との整合を確認（`polish-rejected` は確定旗に触れない固定文言、呼出失敗は種別そのまま。内容起因の確定失敗のみ `.failed`）。
- 停止時も保存しない（従来は停止中の磨きでも初回訳を保存していた）。
- テスト：既存4件を新仕様に更新（削除なし）＋新規2件（単品保留・束ね保留）。全439件中1件の既知失敗のみ。
- 文言更新：設定画面注記・ヘルプ・手順書を「保存せず次回へ」に統一。

## 追補7（不採用理由の可視化・そのまま返却は採用）
- 質問：不採用の定義は？そのまま返却も不採用か？エラーなく動けば採用にできないか？
- 回答：不採用は3つのみ（送信失敗／物差し不合格／停止）。そのまま返却は採用される（証明テスト追加）。
- 修正：不合格理由を `assessCompletion` の単一判定で取得し保留ログに付記（理由の再計算なし）。保持判定は固定文言 `polish-rejected` のまま確定旗に触れさせない。
- テスト：理由付き保留・そのまま採用の2件追加。全440件中1件の既知失敗のみ。

## 追補8（辞書命名プロンプトの人物メモ空返却防止）
- 要望：辞書生成の最後のプロンプト（`translate`）で登場人物メモ（`profiles`）が空で出力される現象の改善。
- 調査（Web上のプロンプトエンジニアリング・LLM JSON出力の知見）：
  1. **末尾パターンの模倣（Recency bias）の排除**：プロンプト末尾に置かれていた `no hints given -> {"profiles":{}}` の例文が強力なテンプレート引力となり、モデルが条件に関わらず `"profiles":{}` を真似る根本原因になっていたため完全排除。
  2. **入出力スキーマ契約（Contract）の明示**：入力JSON（`names`, `hints`）の構造と役割を明記し、`hints` の情報がメモ生成の根拠であることを明示。
  3. **必須生成（MANDATORY）と空返却禁止の徹底**：「`hints` にキーが存在する全人物について `profiles` 生成は必須（MANDATORY）」「`hints` がある場合に `"profiles":{}` を返してはならない（CRITICAL）」と強力に義務付け。
  4. **Few-Shotの完全入出力マッピング化**：断片的な例ではなく、入力JSONから出力JSONへの完全なペア（漢字／カタカナ）を提示。
  5. **末尾リマインダー（REMINDER）の配置**：プロンプト最末尾に `- REMINDER:` を配置し、出力直前のコンテキストで空返却禁止を念押し。
  6. **完全汎用・ジャンルニュートラルの厳守**：例文には特定ジャンルや作品固有設定を含めず、完全普遍的な語句（「落ち着いた少年、冷静な剣士」「温厚な青年」「聡明な少女」）のみを採用。
- 修正：`DictStage.kt` の `DictPrompts.translate` 文面を刷新。
- 検証：
  - `:core-translation:test` 全件パス。
  - `:app:testDebugUnitTest --tests "com.example.novelscraper.V2PipelineTest"` 全件パス。
  - `:app:testDebugUnitTest --tests "com.example.novelscraper.V2EngineTest"` 既知の無関係な1件（`testRunEngine_AutoPromptOrder`）を除き全件パス。

## 検証
- `:core-translation:test` 全件パス（新規4件含む）。
- `:app:testDebugUnitTest` 全425件中1件失敗のみ：`V2EngineTest.testRunEngine_AutoPromptOrder`（`Hanja` 断定が stale。`Hanja` は現行ソースに存在せず、未コミットの Prompts.kt 改修由来の既存破損。変更前後で同一失敗を確認。本修正とは無関係のため未改修→2026/09/30に修正済み）。

## 別件メモ（本修正外・着手前からの未コミット破損）
- `PickerTargetAdapter.kt:87` の `when` 非網羅で `:app` がコンパイル不可だったため、到達不能な `PickerKind.FILE -> {}` のみ追加してテスト検証を通した（動作不変）。本来の持主の改修と衝突したらそちらを優先すること。
- `translation/llm/AGENTS.md` はリポジトリ指示に記載があるが実在しない（`scraper`/`web` のみ存在）。本件では既定ルールで進行。
