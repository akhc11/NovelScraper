package com.example.novelscraper.translation.picker

import android.net.Uri
import android.util.Log
import com.example.novelscraper.FolderItem

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
     * ただし投入済みフォルダ直下のファイルは親側の展開で網羅されるため落とし、二重翻訳を防ぐ。
     * 技術的根拠1行：文書URIのままではTranslationFileStoreが列挙不能で「未完了なし」誤報になるため、許可継承のあるツリー形に境界で寄せる。
     */
    fun toWebFolderItems(targets: List<PickerTarget>): List<FolderItem> {
        val folderUris = targets
            .filter { it.kind == PickerKind.FOLDER }
            .map { it.docUri }
            .toSet()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<FolderItem>(targets.size)
        for (t in targets) {
            if (t.kind == PickerKind.FILE && t.parentDocUri in folderUris) continue
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
                PickerKind.FILE -> {
                    // pathは分割時の出力先解決に使う親フォルダ(ツリー形)。変換不能時は従来通り所属ツリーに退行する。
                    val parentTreeUri = t.parentDocUri.takeIf { it.isNotBlank() }
                        ?.let { toTreeChildUri(t.treeUri, it) } ?: t.treeUri
                    out.add(
                        FolderItem(
                            path = parentTreeUri,
                            name = t.displayName.ifBlank { t.relPath.substringAfterLast('/').ifBlank { t.docUri } },
                            uri = toTreeChildUri(t.treeUri, t.docUri)?.let { safeParse(it) } ?: safeParse(t.docUri)
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * v2向け。v2エンジンは入力URI直下のchildren列挙が前提(RunEngine.processFolder)のため、
     * ファイル単体は親フォルダに畳む。親不明のFILEは投入不能のため落とし、ログに残す。
     * URIはツリー形に寄せる(SafFileStoreはisTreeUri門のため文書URIでは列挙不能)。
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
