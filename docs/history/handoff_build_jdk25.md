# 引き継ぎ状況 - ビルド失敗 原因分析 & 修正プラン (作成日: 2026-08-25)

> **ステータス: 案A 実施完了（2026-08-25）。** JAVA_HOME の JDK 21 固定 + `gradlew --stop` 手順を AGENTS.md §8 に反映済み（Step 1〜4 完了）。`compileDebugKotlin` SUCCESS 確認済み。残タスクは Step 5 の `installDebug` による実機確認のみ（実施時点で adb デバイス未接続）。

## 1. 現象

`.\gradlew compileDebugKotlin`（他のタスクも同様）が 1 秒で即死する:

```
* What went wrong:
25.0.2

FAILURE: Build failed with an exception.
BUILD FAILED in 1s
```

エラーメッセージが「25.0.2」というバージョン文字列だけで、一見すると原因不明に見えるのが特徴。

## 2. 原因（実機で実証済み）

### 直接原因
`--stacktrace` を付けたところ、真の例外は以下:

```
java.lang.IllegalArgumentException: 25.0.2
    at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:307)
    at org.jetbrains.kotlin.cli.jvm.modules.JavaVersionUtilsKt.isAtLeastJava9(javaVersionUtils.kt:11)
    ...（build.gradle.kts の Kotlin DSL スクリプトコンパイル中に発生）
```

つまり **Gradle デーモンが JDK 25（バージョン文字列 "25.0.2"）で起動しており、プロジェクト側の古い Kotlin コンパイラ内蔵の `JavaVersion.parse` が Java 25 のバージョン文字列をパースできずにクラッシュ**している。ソースコードには一切問題がない。

### 環境の実測値
| 項目 | 実測値 | 評価 |
|---|---|---|
| Android Studio 同梱 JBR (`C:\Program Files\Android\Android Studio\jbr`) | **OpenJDK 25.0.2** | ← 原因。Android Studio 更新に伴い JBR が 25 に跳ね上がった |
| Gradle Wrapper | **8.13** | Java 25 でのデーモン実行を非サポート（Java 24 まで / Java 25 は Gradle 9.x〜） |
| AGP | 8.6.1 | JDK 17〜21 前提。JDK 25 非対応 |
| Kotlin | 2.0.21 | 同上 |
| 利用可能な代替 JDK | `C:\Users\asan6\.jdks\ms-21.0.12.1`（Microsoft OpenJDK 21.0.12 LTS、動作確認済み） | JDK 21 は Gradle 8.13 / AGP 8.6.1 / Kotlin 2.0.21 の全てが正式サポート |

### なぜ今まで動いていたのに壊れたか
`AGENTS.md` §8 の標準手順が `JAVA_HOME = C:\Program Files\Android\Android Studio\jbr` を指定しているため、Android Studio を更新した瞬間から Gradle が知らぬ間に JDK 25 で動くようになった。手順書どおりに実行しても失敗する状態になっている。

## 3. 修正プラン（案 A: 推奨・最小リスク）

**方針: プロジェクトのビルドファイルは一切触らず、Gradle を実行する JDK を既存の JDK 21 に固定する。**

1. **JAVA_HOME の切替**
   - ビルド用 PowerShell セットアップを下記に変更する:
     ```powershell
     $env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
     $env:JAVA_HOME = "C:\Users\asan6\.jdks\ms-21.0.12.1"
     $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
     ```
2. **古いデーモンの停止**（JDK 25 で起きているデーモンが残っているため必須）
   - `.\gradlew --stop`
3. **検証ビルド**
   - `.\gradlew compileDebugKotlin --console=plain` → SUCCESS を確認
4. **AGENTS.md §8 の更新**
   - JAVA_HOME 指示先を `.jdks\ms-21.0.12.1` に書き換え、再発防止（将来また JBR バージョンが変わっても影響を受けない）
5. **完了の定義チェック**
   - `.\gradlew installDebug` で実機インストールまで確認
   - 変更は環境変数と AGENTS.md のみのため、Kotlin コード呼び出し関係（graph.json）への影響なし

## 4. 代替プラン（参考・非推奨）

- **案 B: Gradle/AGP/Kotlin を Java 25 対応版へ全面アップグレード**
  - Gradle wrapper → 9.x、AGP → 対応バージョン、Kotlin/KSP → 新版へ一斉更新。
  - 影響範囲が大きく（Compose BOM・Room KSP 等）、今回の「ビルドできない」を直す目的に対して過剰。別タスクとして実施すべき。

### 案B の詳細（2026-08-25 調査済み・公式互換表ベース / 未実施）

| 項目 | 現在 | 変更後 | 根拠 |
|---|---|---|---|
| Gradle Wrapper | 8.13 | **9.1.0 以降** | Java 25 デーモン対応は Gradle 9.1.0〜 |
| AGP | 8.6.1 | **9.0.x** | AGP 9.0 の最低 Gradle = 9.1.0。JDK 17+、Build Tools 36.0.0 必須 |
| Kotlin (`kotlin-android`) | 2.0.21 | **適用削除（Built-in Kotlin 化）** | AGP 9.0 が内蔵 Kotlin をデフォルト有効化。KGP 2.2.10 を自動適用 |
| KSP | 2.0.21-1.0.25 | **2.2.10-2.0.2 以上へ自動引上げ** | AGP 9.0 が KGP に合わせて自動アップグレード |
| serialization / compose プラグイン | 2.0.21 系 | **KGP 2.2.10 系へ揃え直し** | バージョン一致が必要 |

**連鎖して必要になる可能性が高い追加対応:**
- Room 2.6.1 → 新しい KSP/Kotlin メタデータで動かすため Room 2.7.x への更新が必要な可能性大
- Compose BOM 2024.10.00 → 動く可能性はあるが更新推奨
- `gradle.properties` の多数のデフォルト値が AGP 9 で変更されるため見直し必要
- 移行を避けたい場合の一時的なオプトアウト: `android.builtInKotlin=false` + `android.newDsl=false`（警告表示対象。AGP 10 では廃止予定）
- Gradle 9 で Kotlin DSL が言語バージョン 2.2 になるため `build.gradle.kts` 側も要確認

**メリット:** JAVA_HOME を Android Studio JBR (JDK 25) のままビルド可能になり、今後の JBR 更新で再発しない。
**デメリット/リスク:** 影響ファイルが wrapper・libs.versions.toml・app/build.gradle.kts・gradle.properties と広範。Kotlin 2.0→2.2 の言語仕様変化、Room/KSP/Compose の互換性検証、実機での全機能回帰テスト（スクレイピング・翻訳・お気に入り等）が必須。所要は案Aの数分に対して数時間〜規模。
- **案 C: `org.gradle.java.home` を gradle.properties に固定**
  - マシン固有パスがリポジトリに入るため非推奨。どうしても固定したい場合はユーザーの `GRADLE_USER_HOME/gradle.properties` 側へ。

## 5. リスクと注意事項

- 案 A は設定変更のみで Kotlin ソース・依存関係に無影響。JDK 21 は AGP 8.6.1 の推奨範囲内。
- `--stop` を忘れると旧デーモン（JDK 25）が再利用されて同じエラーが出続けるので必ず実行すること。
- Android Studio IDE 内ビルドを使う場合も、Settings → Build Tools → Gradle JVM を ms-21.0.12.1 に合わせる必要がある（CLI と IDE で食い違うとまた別のエラーになる）。
