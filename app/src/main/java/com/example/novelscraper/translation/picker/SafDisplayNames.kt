package com.example.novelscraper.translation.picker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 許可フォルダ表示名が何からも取れない場合の単一フォールバック。 */
const val FALLBACK_FOLDER_NAME = "選択フォルダ"

/**
 * ツリーURIの表示名解決の単一正本。
 * 取得失敗時はURI末尾、さらに無ければフォールバックに退行する(正常系の退行であり無言握り潰しではない)。
 * 技術的根拠1行：許可取り3箇所の表示名解決を一本化し、フォールバック乖離を物理的に防ぐ。
 */
fun treeDisplayName(context: Context, treeUri: Uri): String {
    val resolved = try {
        DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.lastPathSegment
    } catch (_: Exception) {
        treeUri.lastPathSegment
    }
    return resolved?.takeIf { it.isNotBlank() } ?: FALLBACK_FOLDER_NAME
}
