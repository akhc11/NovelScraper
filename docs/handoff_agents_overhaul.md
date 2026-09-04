# 引き継ぎ状況 - AGENTS.md 階層型オーバーホール & パッケージ整理 & Archify完全同期運用確立
最終更新: 2026-09-04

## 1. 概要
- 単一のルート `AGENTS.md` に集約・肥大化していたルールセットを、業界標準・ベストプラクティスに基づく「パターンA（階層型・オンデマンド遅延ロード）」へ完全移行。
- クラスファイルの物理配置もパターンAに合わせて整理：
  - スクレイピング（11ファイル）→ `com.example.novelscraper.scraper`
  - Web翻訳（6ファイル）→ `com.example.novelscraper.translation.web`
  - `git mv` を用いた Git 履歴（Rename 検出）の完全保持。
- 敵対的レビュー（Adversarial Review）を実施し、不要・形骸化したプロンプト（巨大ファイル全読指示、ミクロな過去バグ注記、下位互換と矛盾するAPI排除、精神論、二重定義等）を全4ファイルから徹底クリーンアップ。
- **Archify 完全同期運用の確立**:
  - 全3機能（スクレイピング / Web翻訳 / LLM翻訳）について、アーキテクチャ設計図（JSON & HTML）を Showcase 品質プロファイル（`--quality showcase`、エラー0・警告0）で作成・配備。
  - 各 `AGENTS.md` の作業前必読に設計図 JSON を指定。
  - ルートおよび各サブモジュールの DoD（完了の定義）に「データフロー・設計変更時の Archify 設計図（JSON / HTML）の同期更新義務」を明記。

## 2. 構成マップ
```
NovelScraper2/
├── AGENTS.md                                              ← 全体共通（基本哲学、環境、DoD[Archify同期含む]、ルータ）
├── docs/
│   ├── handoff_agents_overhaul.md                         ← 本進捗記録
│   └── archify/
│       ├── llm-translation.workflow.json                  ← LLM翻訳 最新設計仕様（Showcase合格）
│       ├── llm-translation.workflow.html                  ← LLM翻訳 インタラクティブHTML
│       ├── scraper.workflow.json                          ← スクレイピング 最新設計仕様（Showcase合格）
│       ├── scraper.workflow.html                          ← スクレイピング インタラクティブHTML
│       ├── web-translation.workflow.json                  ← Web翻訳 最新設計仕様（Showcase合格）
│       └── web-translation.workflow.html                  ← Web翻訳 インタラクティブHTML
└── app/src/main/java/com/example/novelscraper/
    ├── scraper/
    │   └── AGENTS.md                                      ← スクレイピング専用ルール (Archify必読＆DoD同期)
    └── translation/
        ├── web/
        │   └── AGENTS.md                                  ← Web翻訳専用ルール (Archify必読＆DoD同期)
        └── llm/
            └── AGENTS.md                                  ← LLM翻訳専用ルール (Archify必読＆DoD同期)
```

## 3. 検証結果
- [x] パターンA（階層型ルール配置）の確立
- [x] コードファイルの物理パッケージ移動完了（scraper 11ファイル, translation/web 6ファイル）
- [x] 敵対的レビューによるプロンプト無駄削ぎ落とし完了（全4ファイル）
- [x] Archify 完全同期ルール（作業前必読 ＋ 改修時DoD同期更新義務）の全ファイル明記
- [x] Archify 設計図 3機能すべて Showcase バリデーション合格確認（Error 0, Warning 0）
- [x] 全体ユニットテスト（全件合格確認、0 failures）
- [x] Debug APK アセンブル（`assembleDebug`）合格確認
