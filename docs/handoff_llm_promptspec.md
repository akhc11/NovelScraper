# LLM翻訳 PromptSpec構造化組立 — 改訂プラン（採用可・補強付き）

## 0. 総合判定

* 方向・出典・段階化すべて妥当。引継ぎ書の穴（型なしblocks・欠番沈黙・ログ未添付）を突いている。
* セッション証拠：当該テスト失敗は実実行で確認済み（挿入時失敗→撤回時通過）。
* 結論：本計画で進めて問題なし。ただし下記デメリット5点への1行追記が必須。

## 1. なぜ実装するのか

* 直接原因：指定行方式の破綻。`buildProfilePrompt` の prefix 照合（`Prompts.kt` 内）が基底文改変で外れ、予備路で #1 冒頭2行が欠落。証拠 = 実実行での失敗確認済み。
* 構造原因：番号差し替えが文字列手術のため、付帯物追加のたび同型事故が起きる。
* 研究裏付け：型付きスロット＋決定論組立＋版管理が業界合意（LangChain `ChatPromptTemplate` / DSPy Signatures / llmbestpractices / Respan版管理）。検証鎖・退行路・ハッシュ版（`promptsHash`）は既に合致し、欠けは組立部のみ。

## 2. 目標・非目標

* 目標：組立の文字列推論ゼロ、再試行の構造化、加除の宣言化、出力バイト同一の移行証明。
* 非目標：モデル側問題の解決（指示衝突・注意減衰は残る）、外部レジストリSaaS、A/B基盤。Phase 3 登録制も実需待ちで後回し（YAGNI）。

## 3. 現状把握（着手前に読むこと）

* `translation/v2/pipeline/Prompts.kt`：基底文 #1〜#7・`getV2PromptByNumber`・`buildSystemPrompt`（付加組立）・`buildProfilePrompt`（文字列差替）・`GLOSSARY_SLOT`・各種 block builders。
* `translation/v2/pipeline/Translate.kt`：`TranslateContext`・`buildAttempts`・単品/束ねの文構築点・`RefineConfig`。
* `translation/v2/engine/WorkerRunner.kt`：`bindCall`（派生文生成点）・`profilePromptOrders`。
* `translation/v2/engine/{Rotation,UnmanagedRotation,PromptRouter}.kt`：`execute(prompts,source,profilePrompts)`・巡回（不変）。
* テスト：`V2PromptTest`（照合契約）・`V2PipelineTest`・`V2EngineTest`。
* 作業域注意：着手時 `git status` 必須。WIP 混在時は別ブランチ分離し、混ぜてコミットしないこと。

## 4. 設計（改訂済み）

```kotlin
sealed interface PromptBlock {
  data class ContextTranslated(val tail: String) : PromptBlock
  data class ContextSource(val tail: String) : PromptBlock
  data class TermFix(val annotation: TermAnnotation) : PromptBlock
  data class Glossary(val terms: Map<String, String>) : PromptBlock
  data class Memo(val text: String) : PromptBlock
  data class Batch(val format: String) : PromptBlock
  data object CompletionMarker : PromptBlock
}
data class PromptSpec(val headNum: Int, val headText: String, val blocks: List<PromptBlock>)
fun assemblePrompt(spec: PromptSpec): String // 決定論・純粋
fun buildSpec(headNum: Int, headText: String, prevTranslatedTail: String?, prevSourceTail: String?,
  termAnnotation: TermAnnotation?, glossary: Map<String, String>?, batchFormat: String?,
  profileMemo: String?, enableCompletionMarker: Boolean): PromptSpec
```

* `buildSystemPrompt` は Spec 組立の薄い wrapper にし、出力バイト同一を snapshot で証明する。
* 再試行は `spec.copy(headText = targetHead, headNum = target)` ＋再描画。`buildProfilePrompt` は削除する。
* 派生文生成は `TranslateContext` の factory（`(promptNum) -> PromptSpec`、部品を capture）に寄せ、`bindCall` は描画だけする。`PromptRouter.execute` の型は不変（Rotation 系・テスト無改修）。
* 部品は {文脈, 対応表, メモ, 書式, 栞} の順序固定。カスタム文は頭差し替えとして流す（現行通り）。
* 版管理は `promptsHash` を Spec 全体（順序含む）に拡張し、ログ span に短縮 hash を添付する。
* 技術的根拠1行：順序をリスト型で固定し、`indexOf/replace` の位置推論を構造から消す。
* 技術的根拠1行：欠番は fail-fast（例外 or 検査失敗）にし、`values.firstOrNull() ?: ""` の沈黙フォールバックを温存しない。

## 5. デメリット5点と対策（本改訂の差分）

1. snapshot 組合せ爆発 → 代表組合せ＋全番号×最小付帯に絞る。7基底×付帯の全組合せ（千件級）をやめ、Phase 1 テストは「代表10件前後＋全7番号×付帯なし/最小1件」に限定する。将来の文面微修正時の再生成 toil を抑える。
2. 暫定二重路の恒久化リスク → 削除期限を明記。`specFactory = null` 従来路との併存は Phase 2 完了時に削除する。二重路を残したまま Phase 3 に進まない。
3. prompts 引数の扱い未指定 → vestigial 引数を残さない。factory 化後、`buildSystemPrompt` の旧既定引数は wrapper 互換のためだけに残し、新規呼出しは `buildSpec` 単一入口に寄せる。旧引数への新規追加を禁止する。
4. 凍結旧実装コピーの腐敗 → 条件付き TODO 化。テスト内に保存する旧実装コピーは `// TODO(Phase2完了時に削除): 移行証明用凍結コピー` と明記し、Phase 2 で必ず削除する。
5. YAGNI 境界 → Phase 3（部品の登録宣言化）は実需待ち。計画書通り後回しにし、本件 DoD に含めない。

## 6. 段階（各段 green・各段単独 revert 可）

1. Spec/組立の導入＋snapshot 固定（既存テスト全通過が証明）。`buildProfilePrompt` 温存。テストは絞り込み snapshot（§5-1）のみ。
2. factory 経由描画へ切替＋`buildProfilePrompt` 削除＋二重路削除（§5-2）＋凍結コピー削除（§5-4）。照合テストは「全番号×付帯有無」の描画一致に書換え。
3. 部品の登録宣言化（任意・後回し可。実需が出るまで着手しない）。

## 7. テスト計画

* snapshot：絞り込み描画バイト一致（移行前後）。全組合せはやらない。
* 契約：描画文は基底頭で始まること（全番号）。旧照合テストの上位互換。
* 回帰：focused → フル green。`buildProfilePrompt` 参照残りゼロを grep 確認。

## 8. DoD・工数・リスク

* DoD：上記 green ＋参照残りゼロ＋二重路残存ゼロ＋凍結コピー削除済み＋ `git diff` に基底文の意味変更なし＋ WIP 混入なし。
* 工数：日級。リスクは指示衝突の可視化不足のみで、検証鎖・階層マーカーで受ける（従来通り）。
* 三性評価：堅牢性○（文字列推論の消滅＋早期失敗＋版ログで無言劣化クラスが消える）、保守性○（条件付き。snapshot toil と二重路を §5 で縛れば現行より明確に良い）、拡張性○（加除は部品単位になり番号差し替え路の再学習が不要）。
* 将来リスクは snapshot toil と二重路の2点に集約され、本改訂の1行追記で塞ぐ。残るはモデル側（指示衝突）のみで非目標化が正しい。
* リポジトリ規約遵守：`AGENTS.md` 必読（編集前に関連 sub-rule も確認）、日本語 UTF-8（BOM なし）、技術的根拠1行コメント、focused → フル、失敗テストの無断削除禁止。
