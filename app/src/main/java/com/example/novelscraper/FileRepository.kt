package com.example.novelscraper

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * スクレイプ結果のファイル保存を担当するリポジトリ。
 * MainActivity からファイルI/Oの責務を分離する。
 */
class FileRepository(private val context: Context) {

    private val sanitizeRegex = Regex("[\\\\/:*?\"<>|\\r\\n]")

    /**
     * チャプターをDownloadsフォルダに保存する。
     * 保存先は Downloads/<saveDir>/<作品名>/（saveDir空欄時は従来のNovelScraper）。
     * Android Q以降は MediaStore、それ以前は直接ファイル書き込み。
     */
    suspend fun saveChapter(folderName: String, title: String, content: String, chapterNum: String, saveDir: String = ""): Boolean = withContext(Dispatchers.IO) {
        var fileName = title.replace(sanitizeRegex, "").trim()
        if (chapterNum.isNotEmpty()) fileName = "${chapterNum}_${fileName}"
        fileName += ".txt"
        val safeFolderName = folderName.replace(sanitizeRegex, "").trim()
            .ifEmpty { DEFAULT_FOLDER_NAME }
        val safeRootName = saveDir.replace(sanitizeRegex, "").trim()
            .ifEmpty { ROOT_FOLDER_NAME }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(safeRootName, safeFolderName, fileName, content)
            } else {
                saveWithLegacyFile(safeRootName, safeFolderName, fileName, content)
            }
            true
        } catch (e: Exception) {
            Log.e("FileRepository", "Failed to save chapter file [fileName=$fileName, rootName=$safeRootName, folderName=$safeFolderName]", e)
            false
        }
    }

    private fun saveWithMediaStore(rootName: String, folderName: String, fileName: String, content: String) {
        val path = Environment.DIRECTORY_DOWNLOADS + "/$rootName/$folderName/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null for $fileName")

        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: throw IOException("Failed to open output stream for MediaStore URI: $uri")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // 書き込み失敗時は pending の孤立エントリを削除
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    private fun saveWithLegacyFile(rootName: String, folderName: String, fileName: String, content: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$rootName/$folderName"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed to create directory: ${dir.absolutePath}")
        }
        File(dir, fileName).writeText(content, Charsets.UTF_8)
    }

    companion object {
        private const val ROOT_FOLDER_NAME = "NovelScraper"
        private const val DEFAULT_FOLDER_NAME = "NovelScraper_Others"
    }
}
