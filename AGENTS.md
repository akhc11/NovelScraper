# NovelScraper2 — Repository Instructions

Android小説スクレイパ＋翻訳アプリ。Kotlin / AGP 8.6.1 / Gradle 8.13 / JDK 21。

## Sub-rules（遅延読込。先読み禁止）

- `scraper/`配下を編集する直前に`app/src/main/java/com/example/novelscraper/scraper/AGENTS.md`を読む。他機能のは読まない
- `translation/web/`配下を編集する直前に`app/src/main/java/com/example/novelscraper/translation/web/AGENTS.md`を読む。他機能のは読まない
- `translation/llm/`配下・`translation/common/NovelPhysicalSplitter.kt`を編集する直前に`app/src/main/java/com/example/novelscraper/translation/llm/AGENTS.md`を読む。他機能のは読まない
- 読んだ後はその内容をデフォルトより優先する。複数機能に跨る修正では関係分だけ読む

## Context loading（オンデマンド）

- 通常修正は対象ファイル＋そのテストだけ読めばよい。全ファイル全文Readはしない
- 設計図JSON・GRAPH_REPORTはパイプライン境界・責務分担を変える時だけ読む。1行修正で毎回読まない
- 変更関数の呼び元・呼び先をgrep/graphで2ホップ確認し、技術的根拠を1行で残す

## Commands

```powershell
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
.\gradlew --version  # JDK 21であることを確認してから進む
.\gradlew :app:testDebugUnitTest --tests "com.example.novelscraper.<対象>Test"
.\gradlew :app:testDebugUnitTest  # フル（パイプライン変更時のみ）
.\gradlew installDebug
```

実機再起動が必要な時のみ：`adb logcat -c`→force-stop→`am start -n com.example.novelscraper/.MainActivity`（SDKパスは各自の`ANDROID_HOME`配下を使う）

## Never（6件）

- 秘密情報・APIキー・`.env`をコミットしない
- 生成物（`build/`・`graphify-out/`）を手編集しない
- 失敗テストを無断で削除・スキップしない
- 日本語ファイルのエンコーディングをUTF-8（BOMなし）以外にしない
- 例外を無言で握りつぶさない（`catch → null`で終わらせない）
- 小説プロンプト（翻訳・推敲）に特定ジャンル・作品固有の状況（学園・宿題・特定キャラ設定等）に依存した語句・例文を含めないこと（完全汎用・ジャンルニュートラルを厳守）

## Ask first

- 公開API・外部仕様・フォルダ構成（`分割済み/翻訳完了_LLM/.parts_*`等）の削除・変更
- 新規依存ライブラリの追加
- Archify設計図JSONの更新要否に迷うデータフロー変更

## Always

- 重い処理は`Dispatchers.IO`＋ライフサイクル連動スコープ
- `DataStore`のJSON更新はデコード失敗時に既存データを保護（上書き中断）
- 修正理由を根本（データフロー・責務の不一致）に紐づけて1行で説明する。小さなガード追加自体は禁止しない

## Notes（参考。コードが正本）

- `FileRepository.saveChapter`はsuspend。IOスレッドで呼ぶ
- 履歴上限・サイズ比・バッチ上限等の数値はコードの定数・設定画面が正本。本ファイルに複写しない
- ファイル編集は専用editツールを使う。PowerShell `-replace`での一括置換は日本語破損のため使わない
- 複数ステップの作業記録は`docs/handoff_<機能名>.md`に残す（1行修正では不要）

## Definition of Done

- 常時：focusedテスト（変更モジュール）が全件パス
- パイプライン・IPC・保存処理を変えた時のみ：フルテスト＋実機確認＋該当Archify JSON/HTMLの同期（手順は`.agents/skills/archify/SKILL.md`参照）
