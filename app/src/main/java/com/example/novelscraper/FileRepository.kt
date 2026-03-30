package com.example.novelscraper

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * スクレイプ結果のファイル保存を担当するリポジトリ。
 * MainActivity からファイルI/Oの責務を分離する。
 */
class FileRepository(private val context: Context) {

    private val sanitizeRegex = Regex("[\\\\/:*?\"<>|\\r\\n]")

    /**
     * チャプターをDownloadsフォルダに保存する。
     * Android Q以降は MediaStore、それ以前は直接ファイル書き込み。
     */
    fun saveChapter(folderName: String, title: String, content: String, chapterNum: String) {
        var fileName = title.replace(sanitizeRegex, "").trim()
        if (chapterNum.isNotEmpty()) fileName = "${chapterNum}_${fileName}"
        fileName += ".txt"
        val safeFolderName = folderName.replace(sanitizeRegex, "").trim()
            .ifEmpty { DEFAULT_FOLDER_NAME }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(safeFolderName, fileName, content)
            } else {
                saveWithLegacyFile(safeFolderName, fileName, content)
            }
        } catch (e: Exception) {
            // バックグラウンドタスクのためサイレントに処理
        }
    }

    private fun saveWithMediaStore(folderName: String, fileName: String, content: String) {
        val path = Environment.DIRECTORY_DOWNLOADS + "/$ROOT_FOLDER_NAME/$folderName/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it).use { os ->
                os?.write(content.toByteArray())
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(it, values, null, null)
        }
    }

    private fun saveWithLegacyFile(folderName: String, fileName: String, content: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$ROOT_FOLDER_NAME/$folderName"
        )
        if (!dir.exists()) dir.mkdirs()
        File(dir, fileName).writeText(content)
    }

    companion object {
        private const val ROOT_FOLDER_NAME = "NovelScraper"
        private const val DEFAULT_FOLDER_NAME = "NovelScraper_Others"
    }
}
