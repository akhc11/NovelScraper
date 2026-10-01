package com.example.novelscraper.translation.picker

import android.net.Uri
import android.util.Log
import com.example.novelscraper.FolderItem

/**
 * v2向けフォルダ確定単位。空のfileUrisは絞り込みなし(全件・従来動作)。
 * 非空時は選択時点のファイルURIのみ翻訳し、指定外の親同胞を巻き込まない。
 */
data class V2FolderTarget(
    val folderUri: String,
    val displayName: String,
    val fileUris: Set<String> = emptySet()
)

/**
 * 自前ブラウザの確定結果を既存エンジン入力へ変換する境界。
 * 既存の入力契約(FolderItem列・v2の(uri,name)列)は変えず、ここで吸収する。
 * 技術的根拠1行：複数選択の可否はピッカー契約が決めるため、単発制限の差分はアプリ側変換で吸収し既存実行系に触れない。
 */
object PickerTargetAdapter {

    private const val TAG = "PickerTargetAdapter"

    /**
     * 文書URIを所属ツリー起点のツリー形URIへ変換する純粋関数(JVMテスト可)。
     * 技術的根拠1行：翻訳実行系はツリーURI契約(getDocumentFileはfromTreeUriのみ・SafFileStoreはisTreeUri門)かつ永続許可はツリー単位のため、境界のここで一本化し下流分岐を作らない。
     * 変換不能時はnull(呼出側は従来docUriに退行し、既存動作を変えない)。
     */
    fun toTreeChildUri(treeUri: String, docUri: String): String? {
        if (treeUri.isBlank() || docUri.isBlank()) return null
        if (docUri.contains("/tree/")) return docUri
        if (docUri.startsWith("file:")) return docUri
        if (!treeUri.contains("/tree/") || !docUri.contains("/document/")) return null
        val treeAuthority = treeUri.substringAfter("://").substringBefore('/').ifBlank { return null }
        val docAuthority = docUri.substringAfter("://").substringBefore('/').ifBlank { return null }
        if (!treeAuthority.equals(docAuthority, ignoreCase = true)) return null
        val docId = docUri.substringAfter("/document/", "").ifBlank { return null }
        return "$treeUri/document/$docId"
    }

    private fun safeParse(uriString: String): Uri? {
        return try {
            if (uriString.isBlank()) null else Uri.parse(uriString)
        } catch (e: Exception) {
            Log.w(TAG, "uri parse failed", e)
            null
        }
    }

    /**
     * web翻訳向け。実行系のツリーURI契約に合わせ、FOLDER/FILEはツリー形URIで渡す。
     * 投入済みフォルダ直下のファイルは親側の展開で網羅されるため落とし、二重翻訳を防ぐ。
     * ファイル単体・孫以下は親フォルダに畳む(V2 entriesと同責務)。
     * 技術的根拠1行：ファイルURIのまま投入するとTranslationFileStoreが非ディレクトリで列挙不能となり空振り成功扱い完了になるため、境界で親フォルダに寄せる。
     * 注意: FOLDERの子URI(tree/<root>/document/<child>形)は旧エンジン側でルート辿り解決すること。
     * fromTreeUri直渡しはルート化けする既知バグのため禁止(詳細はTranslationFileStore.resolveTreeFolder契約)。
     */
    fun toWebFolderItems(targets: List<PickerTarget>): List<FolderItem> {
        val folderUris = targets
            .filter { it.kind == PickerKind.FOLDER }
            .map { it.docUri }
            .toSet()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<FolderItem>(targets.size)
        for (t in targets) {
            if (t.kind == PickerKind.FILE) {
                // WEB実行系はフォルダ列挙前提のため親フォルダに畳む。親不明は投入不能のため落とす。
                if (t.parentDocUri.isBlank()) {
                    Log.w(TAG, "dropping FILE without parent: ${t.docUri}")
                    continue
                }
                if (t.parentDocUri in folderUris) continue
                val parentName = t.relPath.substringBeforeLast('/', "").substringAfterLast('/')
                if (!seen.add("FOLDER:${t.parentDocUri}")) continue
                out.add(
                    FolderItem(
                        path = t.parentDocUri,
                        name = parentName.ifBlank { FALLBACK_FOLDER_NAME },
                        uri = toTreeChildUri(t.treeUri, t.parentDocUri)?.let { safeParse(it) }
                            ?: safeParse(t.parentDocUri)
                    )
                )
                continue
            }
            val key = "${t.kind}:${t.docUri}"
            if (!seen.add(key)) continue
            when (t.kind) {
                PickerKind.ROOT -> out.add(
                    FolderItem(
                        path = t.treeUri,
                        name = t.displayName.ifBlank { FALLBACK_FOLDER_NAME },
                        uri = safeParse(t.treeUri)
                    )
                )
                PickerKind.FOLDER -> out.add(
                    FolderItem(
                        path = t.docUri,
                        name = t.displayName.ifBlank { t.relPath.substringAfterLast('/').ifBlank { FALLBACK_FOLDER_NAME } },
                        uri = toTreeChildUri(t.treeUri, t.docUri)?.let { safeParse(it) } ?: safeParse(t.docUri)
                    )
                )
                // 技術的根拠1行：FILEは上記で親畳み込み後にcontinue済みのため到達不能。網羅性のみ担保し動作は変えない。
                PickerKind.FILE -> {}
            }
        }
        return out
    }

    /**
     * v2向け(絞り込み付き)。v2エンジンは入力URI直下のchildren列挙が前提のため、
     * 従来はファイル単体を親フォルダに畳んで同胞ごと翻訳していた。
     * 本関数は親ごとに選択ファイルURI集合を保持し、RunEngine側でURI一致フィルタする。
     * FOLDER単体(子FILEなし)の空集合は全件扱い(従来動作)。親不明FILEは落とす。
     * 技術的根拠1行：畳み込み責務をURI変換の境界に残し、絞り込み判定は実行時列挙側に寄せて二重管理しない。
     */
    fun toV2FolderTargets(targets: List<PickerTarget>): List<V2FolderTarget> {
        data class Acc(var name: String, val files: LinkedHashSet<String> = LinkedHashSet())
        val acc = LinkedHashMap<String, Acc>()
        fun accFor(uri: String, name: String): Acc {
            return acc.getOrPut(uri) { Acc(name.ifBlank { FALLBACK_FOLDER_NAME }) }.also {
                if (name.isNotBlank() && it.name == FALLBACK_FOLDER_NAME) it.name = name
            }
        }
        for (t in targets) {
            when (t.kind) {
                PickerKind.ROOT -> {
                    if (t.treeUri.isBlank()) continue
                    accFor(t.treeUri, t.displayName)
                }
                PickerKind.FOLDER -> {
                    val uri = toTreeChildUri(t.treeUri, t.docUri) ?: t.docUri
                    if (uri.isBlank()) continue
                    accFor(uri, t.displayName.ifBlank { t.relPath.substringAfterLast('/') })
                }
                PickerKind.FILE -> {
                    if (t.parentDocUri.isBlank()) {
                        Log.w(TAG, "dropping FILE without parent: ${t.docUri}")
                        continue
                    }
                    val parentUri = toTreeChildUri(t.treeUri, t.parentDocUri) ?: t.parentDocUri
                    if (parentUri.isBlank()) continue
                    val fileUri = toTreeChildUri(t.treeUri, t.docUri) ?: t.docUri
                    if (fileUri.isBlank()) continue
                    val parentName = t.relPath.substringBeforeLast('/', "").substringAfterLast('/')
                    accFor(parentUri, parentName).files.add(fileUri)
                }
            }
        }
        return acc.map { (uri, a) -> V2FolderTarget(uri, a.name.ifBlank { FALLBACK_FOLDER_NAME }, a.files.toSet()) }
    }

    /**
     * v2向け。v2エンジンは入力URI直下のchildren列挙が前提(RunEngine.processFolder)のため、
     * ファイル単体は親フォルダに畳む。親不明のFILEは投入不能のため落とし、ログに残す。
     * URIはツリー形に寄せる(SafFileStoreはisTreeUri門のため文書URIでは列挙不能)。
     * 絞り込み付きが必要な場合は[toV2FolderTargets]を使うこと(本関数は後方互換のフォルダ列のみ)。
     */
    fun toV2FolderEntries(targets: List<PickerTarget>): List<Pair<String, String>> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<String, String>>(targets.size)
        fun add(uri: String, name: String) {
            if (uri.isBlank()) return
            if (seen.add(uri)) out.add(uri to name.ifBlank { FALLBACK_FOLDER_NAME })
        }
        for (t in targets) {
            when (t.kind) {
                PickerKind.ROOT -> add(t.treeUri, t.displayName)
                PickerKind.FOLDER -> add(
                    toTreeChildUri(t.treeUri, t.docUri) ?: t.docUri,
                    t.displayName.ifBlank { t.relPath.substringAfterLast('/') }
                )
                PickerKind.FILE -> {
                    if (t.parentDocUri.isBlank()) {
                        Log.w(TAG, "dropping FILE without parent: ${t.docUri}")
                    } else {
                        val parentName = t.relPath.substringBeforeLast('/', "").substringAfterLast('/')
                        add(toTreeChildUri(t.treeUri, t.parentDocUri) ?: t.parentDocUri, parentName)
                    }
                }
            }
        }
        return out
    }
}
