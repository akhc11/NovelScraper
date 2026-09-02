# 引き継ぎ状況 - 通信スループット拡張・短文バリデーション・SAF複製防止 (handoff_adversarial_hardening.md)

## 概要・背景
敵対的レビューによって特定された並列実行時およびエッジケースにおける堅牢性向上を実施。

1. **OkHttpClient Dispatcher 拡張 (`LlmApiClient.kt`)**
   - `maxRequests = 128`, `maxRequestsPerHost = 64`
2. **短文バリデーションガード (`TranslationQualityValidator.kt`)**
   - `srcBytes < 150` のサイズ比バイパス
3. **SAF 複製防止 (`LlmTranslationEngine.kt`, `LargeFileTranslator.kt`)**
   - `findFile ?: createFile`