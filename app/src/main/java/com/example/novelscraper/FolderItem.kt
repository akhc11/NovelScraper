package com.example.novelscraper

import android.net.Uri

/**
 * アプリ内フォルダピッカーおよび複数フォルダ翻訳キュー用のフォルダ情報モデル。
 */
data class FolderItem(
    val path: String,
    val name: String,
    val totalTextFiles: Int = 0,
    val untranslatedGoogleCount: Int = 0,
    val untranslatedDeeplCount: Int = 0,
    val uri: Uri? = null
)
