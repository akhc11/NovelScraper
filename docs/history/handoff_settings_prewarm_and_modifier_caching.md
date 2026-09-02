# 引き継ぎ状況 - 設定画面の事前ウォームアップ（Pre-warmed）＆ Modifier 静的キャッシュによるオープン遅延ゼロ化

## 1. 概要・現象
- 設定画面を開く（歯車ボタンをタップする）際、13個の ConfigInputField を含む巨大なコンポジションツリーが一括でインスタンス化されるため、初期オープン時に数百ミリ秒の遅延・引っかかりが発生していた。

## 2. 修正方針
1. **アプローチ 1（事前ウォームアップ / Pre-warmed Component）**:
   - MainScreen.kt 内で SettingsPanel をバックグラウンドで常時ウォームアップ（事前コンポジション）しておき、タップ時は graphicsLayer { alpha = ... } と zIndex のみで即座（0ms）に表示。
2. **アプローチ 3（Modifier 静的キャッシュ / Allocation Zero）**:
   - SettingsPanel.kt 内の全入力欄・カードの Modifier チェーン、TextStyle、Shape を Top-Level 定数としてキャッシュし、オブジェクト生成コストをゼロ化。

## 3. 検証・ビルド手順
- 	estDebugUnitTest で単体テスト実行
- ssembleDebug でビルド確認