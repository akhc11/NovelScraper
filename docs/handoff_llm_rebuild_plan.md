# LLM翻訳 7機能 作り直しプラン (2026-09-04)

実装はしない。本ファイルはプランのみ。
作業時は `app/src/main/java/com/example/novelscraper/translation/llm/AGENTS.md` を最優先する。

## 0. スコープ

### 維持する外部仕様

- フォルダ構成 (`分割済み/翻訳完了_LLM/.parts_*`)、SAF 経由 I/O
- 並列ワーカー、バッチ/大ファイル/単体の3経路、辞書→翻訳フロー
- 設定の DataStore 永続化

### 直さないもの (ユーザー決定)

- `LlmTranslationConfig` 内ハードコード API キー (利便性のため残す)
- `.failed` 手動削除運用 (自動再試行は作らない。ただし二重 `.failed` でレジュームを塞ぐ構造は直す)

### 中身は刷新可

- 内部テーブル・閾値・分岐・クライアント組立は捨てて書き直してよい。現行コードの無理な再利用禁止。

## 1. 全体原則 (llm/AGENTS.md §3)

- 無言継続の禁止: `?: continue`、`catch → null`、保存戻り値無視は厳禁。境界ガードは「理由付き Failure + ログ + テスト」なら許可。
- `FileClaimManager` は job 単位で新規生成。`try/finally` で100%解放。`finally` から呼べる非 suspend 解放にする。
- 保存は成否必須チェック。完了判定は存在だけでなくサイズも見る。
- 大ファイルは結合完了まで親直下に `.failed` を作らない (可視性のための空マーカーは可。§4 参照)。
- 品質判定は `LanguageDetector` (かな率主軸) に一本化。簡体字 char 配列マッチは廃止方向。
- サニタイズ順序: ```剥離 → 前口上除去 → 末尾後口上除去 → `CompletionMarkerHelper` 検証。
- API パラメータは nullable 透過 (`?: 0.5` 等の固定既定値の埋め込み禁止)。
- KISS: 新ライブラリ禁止。閾値の単一化程度の集約のみ許可。デッドガードは削除。

## 2. 実施順序

`①→⑥→⑤ (純粋・Engine非接触) → ②→③→④ (Engine一括) → ⑦ (UI仕上げ)`

理由: ①⑥⑤は単体テストで完結し merge しない。②③④は `LlmTranslationEngine` の同一関数群を触るため一括で行いコンフリクトを避ける。⑦は最後に文言を合わせる。

## 3. ① 残留言語検出＆品質検証 (`pipeline/TranslationQualityValidator.kt`)

### 現状欠陥 (根拠)

- `SIMPLIFIED_CHINESE_CHARS:13-51` に `万/区/医/油/猪/著/助/祝/追/姿/紫/昨/座/治/致/制/昼` 等の常用漢字が混入。`157-159` の3文字リジェクトは日本語小説で自爆する。
- EN 上限 220 (`230`) は正規値として維持する (旧 docs の 350% 記載は誤り)。
- `stripPreamble:74-93` + `isPreambleLine:56-69` は先頭・英定型のみ。中/韓前口上、末尾後口上、先頭```が素通りする。
- `checkAverageLineLength:192-213` はバイト平均で行数崩壊を推測しており、詩・改行多め原文で誤爆する。

### 作り直し設計

1. 簡体字配列マッチを廃止し、`LanguageDetector.detect` (かな率主軸) に一本化する。高精度に振り切る: `かな==0 && total>80` のときのみ残留疑い、それ以外は成功。弱シグナルはログのみ。
2. サイズ比デフォルトは現行コード値を維持する (`ZH 102-200 / KO 102-150 / EN 105-220 / JA 100-200`)。正規220% (`LlmTranslationConfig.kt:192 sizeRatioEnMax`)。EN は ASCII→UTF-8 の自然膨張により誤リジェクトしやすいが、値を上げすぎるとハルシネーション検知が効かなくなるため、具体値はコードデフォルト + UI 設定での調整に委ねる。AGENTS.md に具体値を固定しない。`LlmTranslationConfig:187-194` との二重定義をやめ、単一 `object` から参照する。
3. `stripPreamble` を `sanitizeLlmOutput` に刷新する。先頭/末尾の```剥離 → 先頭前口上除去 → 末尾後口上除去 → `trim`。本文中の```は残す。`CompletionMarkerHelper.checkAndStripMarker` より前に適用する。
4. 平均行長チェックは廃止方向で行数比に置換する (訳文行数 < 原文行数/3 なら改行消失)。150B バイパス (`225`) は維持。
5. `checkResidualLanguage/checkSizeRatio` 系は純粋関数のままにする。

### DoD

- 「昨日の座席で治療」「病院の医師」が Success。
- EN 210% 良訳が Success、EN 250% 水増しが Failure (220% 境界の確認)。
- ```包み・中/日/英前口上・末尾後口上が除去後に保存される。
- `LlmPipelineTest` の旧期待値 (簡体字3文字検出等) を更新し、全件合格。

## 4. ② 排他制御 (`pipeline/FileClaimManager.kt` + Engine 走査部)

### 現状欠陥 (根拠)

- `FileClaimManager:22-45` は `Mutex + suspend` のため `finally` から呼べない。全漏れの根源。
- Engine `417` 読み失敗 `continue`、`515` 同型、`527-530` バッチ上限 `break` で claim だけ残留し、他ワーカーは永久スキップする。
- `fileClaimManager` が Engine フィールド (`45`) で常駐し、停止→即再開で旧 `finally{clear()} (132)` が新 job の claim を消す。
- `readFileContent:941-949` は `catch → null` の無言継続。

### 作り直し設計

1. `Mutex + suspend` をやめ、`ConcurrentHashMap<String, Boolean>` の `putIfAbsent/remove` による非 suspend の原子操作に刷新する。`withClaim(folderKey, fileName) { block }` インライン関数で `try/finally` 解放を構造的に保証する。suspend 不要なのでキャンセル中の `finally` でも確実に解放される。
2. `FileClaimManager` は `startTranslation` 毎に新規生成し、フィールド保持を廃止する。
3. claim-first を維持する (read-first に反転しない。二重読みと TOCTOU を避けるため)。
4. バッチは採用確定分のみ一括 claim する。1件でも失敗したら当該バッチを組まない。
5. `readFileContent` は `Result` 化してログ付き返却し、無言 `continue` を廃止する。

### DoD

- 読み失敗・バッチ break 後も次ワーカーが処理可能。
- 停止→即再開で二重翻訳なし。
- `clear()` 全体消去の呼び出し箇所ゼロ。

## 5. ③ 保存＆完了整合性 (Engine I/O)

### 現状欠陥 (根拠)

- `saveFileContent` 戻り値無視が5箇所以上 (`424,483,519` 等)。失敗でも完了扱いになる。
- 完了判定が存在のみ (`408`)。0バイト破損が永久完了扱いになる。

### 作り直し設計

1. `save` 成否を必須チェックする。`false` の場合は `existingOutputNames` にも追加しない（= 未翻訳扱いのまま残す）。次のワーカー周回または次回起動時に自然と再処理される。
2. 起動時キャッシュを `Map<name, length>` 化し、`findFile + length()` の往復を1回走査に集約する。`length == 0` のファイルはキャッシュに入れない（未翻訳扱い）。注意: `DocumentFile.length()` は不明時も 0 を返すため、0バイト再訳は1フォルダにつき1回の警告ログを出す（無限ログ防止）。
3. 空原文 (`421-435`) の空出力作成は維持するが、戻り値チェック + 明示的分岐に分離し、破損0バイトと意図的空を混同しない。意図的空は `existingOutputNames` に追加してよい（save 成功を確認した上で）。
4. `allFiles.indexOf (442,502)` の O(N) 走査を `Map<name,doc>` 化する。

### DoD

- 書き込み失敗がログ + 未完了になる。
- 0バイト放置ファイルが警告付きで再訳される。
- 意図的空ファイルで無限ループしない。

## 6. ④ 大ファイル (`pipeline/LargeFileTranslator.kt` + Engine `456-495`)

### 現状欠陥 (根拠)

- チャンク `.failed` (`280-285`) + 親 `.failed` (`480-486`) の二重作成で、親スキップ (`408`) がレジューム (`89-114`) を塞ぐ。
- `chunkProgress` 未更新で UI `(0,0)` 固定。
- `122 ?: continue` 無ログ、`328 delete()` 無視。

### 作り直し設計

1. チャンク失敗は `out/chunk_N.failed` のみ残し `return false` する。
2. **親 `.failed` は一切作らない。** `.parts_xxx` ディレクトリの存在自体が「大ファイル翻訳が中断中」のシグナルとして十分。親エンジンの `runWorker` ループ先頭で、`existingOutputNames` チェック (`408`) よりも前に `.parts_xxx` の存在を確認し、存在すれば `LargeFileTranslator` に委譲する。
3. `onChunkProgress(index,total)` を追加し `_engineState.chunkProgress` をチャンク単位で更新する (リトライ毎は更新しない)。
4. `in/chunk` 生成失敗は集計化し、全滅時は `false` 即返却する。
5. `allDone:299-303` の逐次 `findFile` をキャッシュ方式に寄せ、`delete()` はログ化する。
6. **`writeDocContent` の戻り値チェックを全箇所で必須化する。** 特に結合結果書き込み (`326`) が失敗した場合は `workDir.delete()` を実行せず、チャンクデータを保全してエラー終了する（データロス防止）。

### DoD

- チャンク1失敗 → 再開で当該チャンクから再開し、成功済みスキップ (`107-114`) に到達する。
- UI に `3/10` 等が表示される。
- 分割 ON 時に 28MB 級で完走する。

## 7. ⑤ APIパラメータ (`api/OpenAiCompatibleClient.kt` + Engine `callApiForProfile`)

### 現状欠陥 (根拠)

- Engine `919,935` の `?: 0.5` (および `topP ?: 0.9`、`repetitionPenalty ?: 1.05`) が null 省略設計を潰し、推論モデルで 400 になる。
- `qwen/分岐:40-47` がベンダー結合。
- `response.body?.string() (66,78)` の close 漏れ。

### 作り直し設計

1. `OpenAiChatRequest` は `encodeDefaults=false` 維持のまま (`LlmApiClient:17-22`)、`temperature/topP/topK/repetitionPenalty` を nullable 透過する。Engine 側の `?:` を削除する。Json 設定変更は不要 (`OpenAiModels:11-21` は全 nullable + default null のため null 省略が既に効く)。
2. `qwen/` 特例は削除せず `buildReasoningPayload(model,...)` に隔離 + テスト化する。
3. `response` は `use` 化する。`Retry-After` 無視・402/413 一律 `FatalError` は仕様として残す。
4. **`LargeFileTranslator:165-208` の API 呼び出しコードは `callApiForProfile` と同一ロジックのコピペ再実装。** 共通関数に抽出し、Engine と LargeFileTranslator の両方から呼ぶ。temperature 修正が片方だけに適用される事故を防ぐ。

### DoD

- `temperature=null` 時に JSON に `temperature` キーなし (シリアライズテスト)。
- o1/Groq 推論モデルで 400 が出ない。

## 8. ⑥ プロンプト (`prompt/TranslationPrompts.kt` + `prompt/PromptBuilder.kt`)

### 現状欠陥 (根拠)

- `PROMPT_1_ZH:6-16` のみ OUTPUT ONLY 定型句なし。他はあり。前口上誘発。
- `PromptBuilder:76-78` の除去が `[1]` に作用しない。
- 辞書不一致時に無関係例10件注入 (`162-168`) でトークン浪費。

### 作り直し設計

1. `PROMPT_1_ZH` 末尾に他と同一の `- OUTPUT ONLY: ...` を追加し、併せて `Do not wrap in code fences. Start directly with the translation.` の2行も追加する。
2. `buildDictionarySection` の不一致時の例注入は 10件 → 5件に削減する（完全廃止はしない。表記形式の見本として残すため）。
3. `getPromptByNumber else→PROMPT_1 (108)` の無言フォールバックは残し、責務を⑦の UI 側バリデーションに寄せる。

### DoD

- ZH 出力の前口上率低下 (目視 + テスト)。
- 辞書不一致時にプロンプト肥大なし。

## 9. ⑦ UI・永続化 (`ui/LlmSettingsDialog.kt` + `ui/LlmTranslationPanel.kt`)

### 現状欠陥 (根拠)

- `keys.ifEmpty{currentConfig} (896)` でキー削除不能。
- モデル0件保存可 → 開始可 → `.failed` 量産。
- `dictBatchMaxBytes` UI100万 (`930`) vs エンジン10万 (`NovelDictionaryGenerator:374`) の無言切捨て。
- プリセット範囲チェックなし (`988`)。

### 作り直し設計

1. 空保存を正規受入する (旧値復元を削除)。空時は明示確認ダイアログのみ出す。
2. `modelProfiles.isEmpty() || (geminiKeys.isEmpty() && openRouter.isBlank() && groq.isBlank())` 時は開始ボタン無効 + 理由表示 (`LlmTranslationPanel:275` の `enabled` 条件に統合)。
3. クランプを `4000-100000` に統一し、UI 表示も修正する。
4. プリセットは「1-7は内蔵、8以上はカスタム本文必須、なければ保存時拒否」とする (一律禁止にしない)。`onDelete` は1件残しガード。
5. 全消し後の復元手段として「同梱値にリセット」ボタンを同画面に置く。

### DoD

- キー全消し → 保存 → 再開で空のまま (復元されない)。
- モデル0件/キー空で開始不可。
- 範囲外プリセット登録不可。

## 10. 見落とし是正 (レビュー指摘による追加項目)

### ⑧ `prevSourceTail` の毎回全文再読み込み (`LlmTranslationEngine.kt:440-449`)

- `readFileContent(prevFile)` で前のファイルを毎回フルリードし、末尾数行だけ使って捨てている。1000ファイルのフォルダで999回の無駄なSAF I/O。
- **修正**: 直前1件分の原文末尾 N 行のみをスライディング保持し、次のファイル処理時にキャッシュから取得する (1000件分のMapを作らない)。再読み込みは不要。
- `allFiles.indexOf(fileDoc)` の O(N) 走査も §5.4 の `Map<name,doc>` 化で解消する。

### ⑨ `LargeFileTranslator.writeDocContent` の戻り値全箇所無視 (`LargeFileTranslator.kt:74,270,282,326`)

- `writeDocContent` は `Boolean` を返すが、全4箇所で戻り値を無視。§5.3 (Engine の `saveFileContent`) と同じ問題。
- **特に致命的**: 結合結果書き込み (`326`) が失敗した場合、直後の `workDir.delete()` (`328`) で全チャンクの翻訳済みデータが消失する。復元不能なデータロス。
- **修正**: ④ の作り直し設計 項目6 に統合済み。

## 11. 検証計画 (共通)

- `LlmPipelineTest` は旧挙動を合格条件にしているため、追加ではなく更新が必要 (簡体字3文字検出等の期待値書換え)。
- 追加ケース: ①常用漢字・EN境界・```剥離、②claim 解放・バッチ break、③0バイト再訳、⑤temperature 省略、⑧prevSourceTail キャッシュ、⑨writeDocContent 戻り値。
- 既存テスト回帰 + `graph.json` で `ScrapingViewModel/TranslationQueueManager` 呼び出し確認。
- 実機4手順: 小説10件×並列2 / 中断→再開 / EN長編 / ZH人名回で欠落なし。

## 12. EN 上限に関する確定事項 (2026-09-04)

- EN サイズ比の具体値は AGENTS.md で固定しない。コードのデフォルト値 (`sizeRatioEnMax = 220`) を基本とし、ユーザーが UI で調整する。
- EN は ASCII→UTF-8 の自然膨張で他言語より誤リジェクトしやすいが、値を上げすぎるとハルシネーション検知が効かなくなるため、バランスが必要。
- 旧 docs の 350% 記載は誤りのため `docs/history/` ごと削除済み (現在0件)。historyを探さないこと。
