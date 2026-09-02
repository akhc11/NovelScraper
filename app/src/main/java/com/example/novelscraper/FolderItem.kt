package com.example.novelscraper

import android.net.Uri

/**
 * 複数フォルダ翻訳キュー用のフォルダ情報モデル。
 */
data class FolderItem(
    val path: String,
    val name: String,
    val uri: Uri? = null
)
