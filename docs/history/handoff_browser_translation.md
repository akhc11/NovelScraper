# ブラウザ・ワンタッチ全ページ翻訳機能 実装・仕様・進捗書 (handoff_browser_translation.md)

## 1. 機能概要
海外小説サイトや外国語Webページを、Google ChromeやKiwi Browserのように**ワンタップでページ全体を日本語化**して読めるようにする機能。

---

## 2. アーキテクチャ刷新と深層堅牢化仕様

### 2.1 Cookie完全排除・純粋JavaScript完結型翻訳 ＆ SPA無限スクロール自動追従
- `document.cookie` への書き込みを100%全廃。
- `select.goog-te-combo` に対する `Event('change', { bubbles: true })` 発火により、現在のページ限りでインプレース日本語化。
- **MutationObserver 最速検知 ＆ 8.0秒タイムアウト**: 低速回線でもウィジェット生成をミリ秒単位で検知・発火。
- **SPA無限スクロール自動追従**: スクロールで後からDOMに追加された次話チャプターを検知し、300msデバウンスで自動的に日本語化。

### 2.2 ツールバーの主要ボタン ＋ [︙] メニュー化（幅320dp完全収容）
- **Row 2 (常時表示 6ボタン):**
  - 左側 (パネル): `[履歴]`, `[翻 (キュー)]`
  - 右側 (主要操作): `[設定]`, `[テスト]`, `[虫眼鏡]`, `[▶再生]`, `[🌐翻訳]`, `[︙]`
- **`[︙]` DropdownMenu (Material 3):**
  - 🖼️ 画像読み込み（ON/OFF）
  - 💻 PC版サイト表示（ON/OFF）
  - 🌙 ダークモード（ON/OFF）
  - 🛠️ 開発者ツール (Eruda起動)

### 2.3 テスト/インスペクター二度手間の完全解消（保留キュー自動実行）
- 翻訳ONの状態で「テスト実行」「インスペクター」がタップされた場合、`PendingPostReloadAction` に保留をセットし、自動で原文復帰（リロード）を発火。
- `onPageFinished`（リロード完了時）に、**保留されていたテスト解析ダイアログまたはインスペクターが100%自動で立ち上がる**（2回タップする二度手間を完全根絶）。
- 原文復帰時は `sessionStorage` ＋ 2.0秒待機猶予により、長文・重いサイトでも直前の読書位置へ確実に自動復元。

---

## 3. 実装・変更ファイル
1. `app/src/main/java/com/example/novelscraper/WebTranslateHelper.kt` [NEW]
2. `app/src/main/java/com/example/novelscraper/MainUiState.kt` [MODIFY]
3. `app/src/main/java/com/example/novelscraper/ScrapingViewModel.kt` [MODIFY]
4. `app/src/main/java/com/example/novelscraper/ui/components/HeaderToolbar.kt` [MODIFY]
5. `app/src/main/java/com/example/novelscraper/ui/MainScreen.kt` [MODIFY]
6. `app/src/main/java/com/example/novelscraper/MainActivity.kt` [MODIFY]

---

## 4. 進捗状況
- [x] 設計・仕様策定（敵対的レビューの課題全解消）
- [x] `WebTranslateHelper.kt`（Cookie全廃・MutationObserver最速検知・8秒待機・SPA追従・2秒スクロール復元）
- [x] `HeaderToolbar.kt`（主要6ボタン ＋ [︙] メニュー化でUIハミ出し完全解消）
- [x] `MainScreen.kt`（保留キューによるテスト/インスペクター自動実行・二度手間解消）
- [x] ビルド・テスト検証（`gradlew testDebugUnitTest` 全件パス確認完了）