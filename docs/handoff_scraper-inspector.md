# handoff: インスペクター＋テキスト検索のレビュー指摘改修 (2026-09-04)

## 質問への回答（根拠付き）
- **開発者ツールで見えるHTMLは検索対象か**: はい。`buildSearchTextScript` は
  `document.documentElement` 全体のテキストノード走査＋`<title>`＋`<meta[content]>`＋セレクタ直接指定の4相探索。
  ただし `SCRIPT/STYLE/NOSCRIPT` の中身と自前ポップアップ類は除外。属性値（alt/placeholder等）は `<meta content>` 以外対象外。
- **最大10個までか**: インスペクター候補ポップアップと除外プローブは10件（`slice(0, 10)`）。
  テキスト検索ダイアログは15件（収集20件で打ち切り→返却15件）。仕様差は意図的（検索は網を広く）。

## 実施チェックリスト（全完了）
- [x] P1: インスペクター起動中の設定staleを `__novelInspector.updateConfig` 差分同期で解消（再注入なし・リスナー張り直しなし）
- [x] P2: `stop()` で `<style>`・マーククラス・除外dim・ハイライト・リスナーを完全除去（`clearInspectorMarks` 共通化）
- [x] P3: 動的スタイル初期化16箇所を `style.cssText` に統一（非標準 `style = '...'` 根絶・テストで回帰防止）
- [x] P4: テキスト検索の適用先を7フィールド化（話数/別URL/除外追加・2段レイアウト）／`pickSelector` 一意性保証／直接指定の正規表現に `,() +` 追加／ERROR・空セレクタ除外
- [x] P5: `applyCurrentConfig` のtry分離／反映時はフィールド色表示（橙残留の修正）／タップ時ハイライト復活（候補なし時は付けない）／`closest` ガード／設定同期の400msデバウンス
- [x] P6: `scraper/AGENTS.md`・ root `AGENTS.md` の stale `InspectElementDialog.kt` 記述を現行構成に修正
- [x] 自己レビュー: 未使用 `buildInspectorUpdateScript` を削除（dead code化していたため）／`clearHighlight` 重複3箇所を共通化
- [x] DoDテスト6クラス全55件パス（`ScrapingScriptBuilderTest` 17件含む）

## 変更ファイル
- `scraper/ScrapingScriptBuilder.kt`（インスペクターJS・検索JS）
- `ui/MainScreen.kt`（注入/破棄＋デバウンス同期・ERROR除外）
- `ui/components/TextQuerySearchDialog.kt`（7フィールド対応）
- `scraper/AGENTS.md`・`AGENTS.md`（ルール同期）
- `ScrapingScriptBuilderTest.kt`（新規5件・既存維持）

## Archify判断
- `scraper.workflow.json` は抽出パイプライン専用でインスペクター節なし。今回は同一 `evaluateJavascript` 経路の再利用のみで新規IPC面・状態遷移・保存処理の変更なしのため、設計図の更新は不要と判断。インスペクターlane追加は別途設計変更時に実施。

## 既知の残件（実害なし・次回以降）
- インスペクターのポップアップを開いたまま外部から設定変更すると、開き済み行の「除外中」バッジが次回タップまで stale（再タップで最新化）。
- 検索対象外: alt/placeholder等の属性テキスト。必要になれば探索相追加。

## 再レビュー追記 (2026-09-04)
- 全文再読で実在の無駄を1件発見・修正: 候補毎に `querySelectorAll` で数えていた `matchCount` が未表示だったため、
  行ラベルに「(N件)」表示（一意なら非表示）として活用。テスト1アサーション追加。
- その他はクリーンと判定: `active` フラグ（多重防御）・`escape()` Base64定石・inspector/probe間push重複（用途別で意図的）は維持が正解。
- `ScrapingScriptBuilderTest` 6秒で全パス確認済み。

## テスト実行記録
- `.\gradlew :app:testDebugUnitTest`（6クラス指定）→ BUILD SUCCESSFUL、55件全パス。
- 途中1件失敗あり（原因: 作業中の一時スクリプトが `\'` を1バイト落としてクォート無効化→単体再実行で特定・修正済み）。最終状態はクリーン。
- `.\gradlew :app:testDebugUnitTest`（6クラス指定）→ BUILD SUCCESSFUL、55件全パス。
- 途中1件失敗あり（原因: 作業中の一時スクリプトが `\'` を1バイト落としてクォート無効化→単体再実行で特定・修正済み）。最終状態はクリーン。
