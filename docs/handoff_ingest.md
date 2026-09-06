# Ingest v2 — 作り直し記録

## なぜ作り直したか
旧 UniversalCharsetDetector は継ぎ足し構造で、厳格脱落 (poison 1Bで正解脱落)・
単バイト族の盗み (25対4の重みでは区別不能)・境界切断の3系統の事故を起こした。
Tier2/Phase1で塞いだが、構造自体がもろいため全面スクラッチに移行した。
保存点: `ecc5aca` (`git reset --hard ecc5aca` で旧実装に戻れる)。

## 新構成 (`translation/common/ingest/`)
- `TextIngest.kt`: 唯一入口。宣言路→署名路(BOM/ESC)→厳格UTF-8→均一仮説採点→確信度ゲート
- `HypothesisScorer.kt`: 全仮説を寛容復号して同一式で採点。採点と採否を分離
- `LanguageModels.kt`: 韓41音節・中約300漢字・日英機能語＋単バイト族バイト表 (実測導出)
- `IngestResult.kt`: Success/Quarantined/Failed。低確信は成功を返さない (fail-closed)
- `ChunkVerifier.kt`: 分割塊の3層検証 (FFFD・確定・軟)
- `NovelPhysicalSplitter.kt`: 仕様駆動で再実装 (外部契約は同一)

## 設計判断
- 厳格脱落の概念を廃止 (poison脆弱性の構造消滅)。境界切断は全量一括で消滅 (上限64MB)。
- 外部エンジン不採用の理由: ICU/uchardet/Mozilla系は韓国語がEUC-KR止まりで
  CP949/UHC拡張に非対応。小説頻出の…—～等で劣化するため自前が強い。
  android.icu検出器は非公開APIで使用不可。chardet 7はPython専用。
- 盗み防止は言語 gate (採否) が担い、重みは採点のみに使う。
- 指定路 (フォルダ既定encoding) が1000個案件の確定解。UI: LLM設定＋Webパネル。

## 検証
- TextIngestTest 21件 (golden行列)＋Splitter 10件＋フル179件全緑。
- 実ファイル1.2MB: CP949・確信度85・FFFD=2 (ゴミ由来のみ) で正常。
- 較正詳細は docs/ingest_calibration.md。

## 既知の理論限界
純ASCII・超短文の完全自動は情報理論的に不可能。隔離理由表示＋指定路で吸収する。
