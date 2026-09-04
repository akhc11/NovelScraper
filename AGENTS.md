# NovelScraper2 開発遵守事項（全体共通）

## 0. プロジェクト構造と機能別ルールマップ (パターンA: 階層型)
本プロジェクトは機能ごとに独立した開発ルールを採用している。作業対象の機能に応じて各専用ルールを遵守すること：

```
NovelScraper2/
├── AGENTS.md                                              ← 全体共通規約（本ファイル）
├── graphify-out/                                          ← 構造マップ（コミット時自動更新）
├── docs/
│   ├── handoff_*.md                                       ← 進捗報告・引き継ぎ
│   └── archify/                                           ← 【機能別アーキテクチャ設計図 (Showcase品質)】
│       ├── scraper.workflow.json / .html                  ← スクレイピング設計図
│       ├── web-translation.workflow.json / .html          ← Web翻訳設計図
│       └── llm-translation.workflow.json / .html          ← LLM翻訳設計図
└── app/src/main/java/com/example/novelscraper/
    ├── scraper/AGENTS.md                                  ← 【スクレイピング機能】専用ルール
    └── translation/
        ├── web/AGENTS.md                                  ← 【Web翻訳（Google/DeepL/Papago）】専用ルール
        └── llm/AGENTS.md                                  ← 【LLM翻訳】専用ルール
```

### 機能別ルールの適用先と設計図
| 機能カテゴリ | 対象コンポーネント例 | 参照ルールファイル | 対応 Archify 設計図 (事前必読) |
| :--- | :--- | :--- | :--- |
| **全体共通** | ビルド環境, DB/Prefs/File 基盤, DoD | 本ファイル (`AGENTS.md`) | 各機能のダイアグラム |
| **スクレイピング** | `scraper/` 配下, `InspectElementDialog`, `SettingsPanel` 等 | [`scraper/AGENTS.md`](app/src/main/java/com/example/novelscraper/scraper/AGENTS.md) | [`docs/archify/scraper.workflow.json`](docs/archify/scraper.workflow.json) |
| **Web翻訳** | `translation/web/` 配下, `TranslationPanel` 等 | [`translation/web/AGENTS.md`](app/src/main/java/com/example/novelscraper/translation/web/AGENTS.md) | [`docs/archify/web-translation.workflow.json`](docs/archify/web-translation.workflow.json) |
| **LLM翻訳** | `translation/llm/` 配下, `translation/common/NovelPhysicalSplitter.kt` | [`translation/llm/AGENTS.md`](app/src/main/java/com/example/novelscraper/translation/llm/AGENTS.md) | [`docs/archify/llm-translation.workflow.json`](docs/archify/llm-translation.workflow.json) |

---

## 1. 作業前後の鉄則
- **構造・データフロー把握**:
  - 対象機能のパイプライン・責務境界・メインフローは必ず事前に対象の **Archify 設計図 (`docs/archify/*.workflow.json`)** を読み込んで把握すること。
  - プロジェクト全体のクラス間依存関係の確認が必要な場合は `graphify-out/GRAPH_REPORT.md` を参照すること。
- **進捗管理 (`docs/`)**:
  - 進捗報告やプランは `docs/` 配下の handoff ファイルに記載・更新すること。
  - **別種・別機能の実装時の分離**: 直前の実装と異なる機能（例: スクレイピング改修とLLM改修など）を行う場合、既存の handoff を上書きせず `docs/handoff_<機能名>.md` に分離すること。
  - **完了後の整理**: 完了した handoff ファイルは `docs/history/` フォルダへ移動し、ルートの `docs/` をクリーンに保つこと。

---

## 2. AIによるコーディングの原則（根本治療・推測排除）
- **【最重要】対症療法の絶対禁止**:
  - 表面的な `if` 文追加、フラグの継ぎ接ぎ、タイマーの場当たり的延長、例外の握りつぶし等で「その場しのぎのパッチを当てること」を厳禁とする。
  - 不具合発生時は、必ずデータフロー・責務の不一致を特定し、根本的な設計修正（根本治療）を行うこと。
- **【最重要】推測・安易な思い込みによるコード生成の絶対禁止**:
  - 「たぶんこう動くだろう」という推測でコードを生成することを厳禁とする。必ず関連コード全体を読み込み、技術的根拠を明確にしてから設計・実装すること。
- **シンプルで堅牢なコード設計 (KISS原則)**:
  - **エラーハンドリング**: ネットワークI/O、JSONパース、DataStore読み書き等のエラー発生箇所には適切な例外処理を施しクラッシュを防ぐ。
  - **非同期処理の安全性**: 重い処理は必ず `Dispatchers.IO` で実行し、ライフサイクルに連動したコルーチンスコープを利用する。
  - **不要な複雑化の回避**: 過剰な共通化や不要なライブラリ導入を避け、標準ライブラリでシンプルに完結させる。

---

## 3. 編集・エンコーディングの鉄則
- **PowerShell `-replace` の使用禁止**: 日本語やマルチバイト文字を含む構文を壊すため、ファイル編集ツール（`replace_file_content` / `write_to_file` / エディタ等）を使用すること。
- **エンコーディング**: 日本語が含まれるファイルは必ず UTF-8 (BOMなし) を使用すること。
- **既存機能・外部仕様の無断削除禁止**: 既存の機能仕様・外部インターフェースを勝手に削らないこと（内部の安全なリファクタリング・不要コード除去は除く）。

---

## 4. プロジェクト基盤仕様
- **ファイル保存 (`FileRepository`)**: `saveChapter` は `suspend` 関数であり、必ず `withContext(Dispatchers.IO)` 上で非同期実行される。
- **設定・履歴管理 (`PreferencesRepository`)**:
  - 履歴項目の最大件数は `MAX_HISTORY_SIZE = 100` に制限し、閲覧日時順で古いものから自動剪定される。
  - DataStore の全 JSON 更新では、デコード失敗時に既存データを保護（上書き中断）するガードを維持すること。
- **構造ナレッジグラフ (`Graphify`)**:
  - プロジェクト構造マップは `graphify-out/` 内に保持されている。git commit 時に post-commit フックで自動更新される。

---

## 5. 実機テスト＆ビルド環境の標準手順
- **環境設定（PowerShell必須）**:
  - **JDK 21 固定（必須）**: Gradle 8.13 / AGP 8.6.1 / Kotlin 2.0.21 のため必ず JDK 21 を使用すること：
    ```powershell
    $env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
    $env:JAVA_HOME = "C:\Users\asan6\.jdks\ms-21.0.12.1"
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    ```
  - **デーモン停止**: JDK 切替時やエラー時はビルド前に `.\gradlew --stop` を実行。
  - ADB パス: `C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- **実機インストール＆再起動コマンド**:
  ```powershell
  .\gradlew installDebug
  $adb = "C:\Users\asan6\AppData\Local\Android\Sdk\platform-tools\adb.exe"
  & $adb logcat -c
  & $adb shell am force-stop com.example.novelscraper
  & $adb shell am start -n com.example.novelscraper/.MainActivity
  ```

---

## 6. 完了の定義 (Definition of Done)
- 変更したファイルに関連するテストが存在する場合は実行し、全件パスすることを確認する。
- 変更した関数・クラスの影響範囲を確認する。
- **アーキテクチャ・設計図の同期 (Archify)**:
  - システム構成、IPC、パイプライン、データフローの変更を伴う改修を行った場合は、必ず `docs/archify/` 配下の該当設計図（JSON）を同期更新し、以下のコマンドで Showcase 品質検証および HTML 再生成を行うこと：
    ```bash
    node .agents/skills/archify/bin/archify.mjs deliver workflow docs/archify/<対象機能>.workflow.json docs/archify/<対象機能>.workflow.html --quality showcase
    ```
- 報告前に上記をチェックしたことを明記する。