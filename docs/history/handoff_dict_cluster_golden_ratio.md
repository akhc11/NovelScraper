# 引き継ぎ状況 - 辞書クラスター黄金比サンプリング (handoff_dict_cluster_golden_ratio.md)

## 概要・背景
飛び飛びの間引きサンプリングを完全削除し、
序盤50%・中盤25%・終盤25%の連続クラスターサンプリングに一本化。

1. **`NovelDictionaryGenerator.kt`: selectSampleFiles の黄金比クラスター化**
2. **テスト・ビルド検証**