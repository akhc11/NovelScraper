package com.example.novelscraper.translation.picker

import kotlinx.serialization.Serializable

/** 自前ブラウザで扱う選択対象の種別。 */
enum class PickerKind { ROOT, FOLDER, FILE }

/**
 * 選択対象の不変モデル。URIは文字列で保持し、android.net.Uriに依存しない。
 * 技術的根拠1行：選択責務をOS許可取りとアプリ内複数選択に分離し、翻訳投入はURI列受け渡しに切断する。
 */
@Serializable
data class PickerTarget(
    val kind: PickerKind,
    /** 文書URI(FOLDER/FILE)またはツリーURI(ROOT)の文字列表現。 */
    val docUri: String,
    /** 所属ツリーURI。ROOTの場合はdocUriと同一。 */
    val treeUri: String,
    /** FILEの場合の親フォルダ文書URI(v2畳み込み用)。不明時は空。 */
    val parentDocUri: String = "",
    /** ツリー起点の相対パス(表示・枝刈り用)。ROOTは空。 */
    val relPath: String = "",
    /** 表示名。 */
    val displayName: String = ""
)
