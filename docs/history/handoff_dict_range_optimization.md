# 引き継ぎ状況 - 辞書抽出範囲最適化 (セーフティガード・0全件・均等サンプリング) (handoff_dict_range_optimization.md)

## 概要・背景
1. **合計容量セーフティガード (2MB)**: 巨大ファイル時の API 爆発防止
2. **0 = 全件対象**: フォルダ内の全ファイルを対象に辞書作成
3. **均等サンプリング (UNIFORM)**: 長編小説で序盤・中盤・終盤から均等に抽出

1. **`LlmTranslationConfig.kt`: DictSampleMode, dictMaxTotalScanBytes 追加**
2. **`NovelDictionaryGenerator.kt`: selectSampleFiles, buildSmartBatches リミッター実装**
3. **`LlmSettingsDialog.kt`: モード切替 UI 実装**