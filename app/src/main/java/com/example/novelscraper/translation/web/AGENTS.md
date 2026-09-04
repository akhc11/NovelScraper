# Web翻訳機能 専用開発ルール (translation/web 直下)

## 対象スコープ
本ルールは `translation/web/` 配下の全ファイルおよび関連コンポーネントを対象とする：
- タスク・制御: `BaseWebTranslationTask.kt`, `TranslationQueueManager.kt`, `LiveTranslateScriptBuilder.kt`
- ストラテジ・解析: `WebTranslationStrategy.kt`, `TextChunker.kt`, `TranslationFileStore.kt`
- 関連UI: `TranslationPanel.kt`

---

## 0. アーキテクチャ設計図 (作業前必読)
- Web翻訳機能の全体パイプライン、Ctrl+V模倣IPC、およびDOM監視・保存データフローは [`docs/archify/web-translation.workflow.json`](../../../../../../../../../docs/archify/web-translation.workflow.json)（HTML可視化: [`docs/archify/web-translation.workflow.html`](../../../../../../../../../docs/archify/web-translation.workflow.html)）に定義されている。
- Web翻訳改修時は、必ず作業前に上記設計図を確認し、コンポーネント間の責務境界やデータフローを把握すること。

---

## 1. Google翻訳における手動操作再現ロジックの死守（翻訳品質維持）

### 「プログラム入力」判定の回避
- Google翻訳は `textarea.value = text` のような単純な代入を行うとBot判定され、簡易エンジンへフォールバックし翻訳品質が大幅に低下する。
- そのため、**手動 Ctrl+V 貼り付けと同一のブラウザイベントシーケンスを絶対に維持・改変禁止**とする：
  1. `textarea.focus()` → `textarea.select()` で全選択
  2. `ClipboardEvent('paste', { clipboardData })` を発火（Ctrl+Vと同一）
  3. `InputEvent('input', { inputType: 'insertFromPaste' })` を発火（ユーザー貼り付け認識）
  4. `Event('change')` を発火
- このシーケンスを `textarea.value` のみへの代入に簡略化してはならない。

---

## 2. バックグラウンド実行アーキテクチャ

- **独立インスタンスの維持**:
  - 翻訳タスク（`BaseWebTranslationTask` 派生）は、UIのライフサイクル凍結や Activity 破棄による中断を防ぐため、必ず `WebView(context.applicationContext)` による**独立バックグラウンドインスタンス**で動作させること。
  - メインスレッドのUI WebViewを流用してはならない。

---

## 3. Web翻訳ストラテジ保守 (Google / DeepL / Papago)

- 各Webサービス固有のDOM構造、入力・結果取得セレクタは `WebTranslationStrategy.kt` に集約すること。
- テキスト分割（`TextChunker`）の文字数制限（各サービスの許容上限）を遵守すること。

---

## 4. 完了の定義 (DoD)
- 関連ユニットテスト（`TextChunkerTest`, `LiveTranslateScriptBuilderTest` 等）が全て合格すること。
- 実機にて対象サービス（Google / DeepL / Papago）でのバックグラウンド翻訳および結果保存が正常に行われることを確認すること。
- **アーキテクチャ・設計図の同期 (Archify)**:
  - Web翻訳のパイプライン、DOM抽出、チャンク処理、保存処理に変更を加えた場合は、必ず [`docs/archify/web-translation.workflow.json`](../../../../../../../../../docs/archify/web-translation.workflow.json) を同期更新し、以下のコマンドで検証（Showcase合格）・HTML生成を行うこと：
    ```bash
    node .agents/skills/archify/bin/archify.mjs deliver workflow docs/archify/web-translation.workflow.json docs/archify/web-translation.workflow.html --quality showcase
    ```
