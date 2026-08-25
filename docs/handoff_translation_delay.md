# 引き継ぎ状況 - 翻訳待機時間（範囲ランダム指定・例: 30-80秒）＆ 機械判定回避ロジック 実装完了 (handoff_translation_delay.md)

## 概要
Google翻訳およびDeepL翻訳において、ユーザーが**チャンク間・ファイル間の待機時間を「最小秒数 - 最大秒数（例: `30-80` や `1-3`）」の範囲指定で設定・保存**できるようにし、各処理完了時に指定範囲から毎回異なるランダム秒数を抽選して待機する仕組みを実装しました。

---

## 主な実装内容と仕様

### 1. 範囲指定（Min - Max）ランダム待機時間計算ロジック
- **[`TranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/TranslationTask.kt) & [`DeeplTranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/DeeplTranslationTask.kt)**
  - `calculateDelayMs(delayStr: String, defaultSec: Double): Long` を実装。
  - **フォーマット対応:**
    - `"30-80"` → 30.0秒〜80.0秒の間から毎回ランダムな浮動小数点秒数を抽選（例: 43.2秒、71.8秒...）。
    - `"1.5-3.5"` → 小数点付きの範囲指定にも対応。
    - `"2.0"` または `"5"` → 単一数値指定の場合は `±0.3〜0.4秒` のJitterを付加。
  - 各チャンク完了時およびファイル完了時に、毎回新しく抽選された待機時間で `delay()` を実行。

### 2. 待機時間設定の永続化 & 状態管理
- **[`PreferencesRepository.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/PreferencesRepository.kt)**
  - DataStoreキー: `google_chunk_delay`, `google_file_delay`, `deepl_chunk_delay`, `deepl_file_delay`
  - 文字列型（`String`）で範囲文字列（`"30-80"` 等）を保存・復元。
- **[`MainUiState.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/MainUiState.kt)**
  - `EngineTranslationState` に `chunkDelay: String`, `fileDelay: String` を保持。
  - デフォルト値:
    - Google翻訳: チャンク間 `"1-3"` / ファイル間 `"1-2"`
    - DeepL翻訳: チャンク間 `"3-8"` / ファイル間 `"2-5"`
- **[`ScrapingViewModel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ScrapingViewModel.kt)**
  - `updateTranslationDelays(engine, chunkDelay: String, fileDelay: String)` を実装。
  - タスク生成時に設定文字列を渡す。

### 3. UI（[`TranslationPanel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/components/TranslationPanel.kt)）
- よく使う推奨設定（Google用: `[1-3秒]` `[2-5秒]` `[5-10秒]` `[30-80秒]` / DeepL用: `[3-8秒]` `[5-15秒]` `[10-30秒]` `[30-80秒]`）のワンタップ入力チップスを追加（タップでチャンク間・ファイル間の両方に同時適用）。
- 各入力欄での個別カスタマイズも可能。
- 翻訳中は誤操作防止のため入力不可に制御。

---

## 変更・作成ファイル一覧
1. `[MODIFY]` [`PreferencesRepository.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/PreferencesRepository.kt)
2. `[MODIFY]` [`MainUiState.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/MainUiState.kt)
3. `[MODIFY]` [`ScrapingViewModel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ScrapingViewModel.kt)
4. `[MODIFY]` [`TranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/TranslationTask.kt)
5. `[MODIFY]` [`DeeplTranslationTask.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/DeeplTranslationTask.kt)
6. `[MODIFY]` [`TranslationPanel.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/components/TranslationPanel.kt)
7. `[MODIFY]` [`MainScreen.kt`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/app/src/main/java/com/example/novelscraper/ui/MainScreen.kt)
8. `[MODIFY]` [`docs/handoff_translation_delay.md`](file:///c:/Users/asan6/OneDrive/ドキュメント/android%20studio/NovelScraper2/docs/handoff_translation_delay.md)
