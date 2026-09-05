package com.example.novelscraper.translation.common

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.llm.pipeline.TextCharsetDetector

/**
 * SAF DocumentFile 経由の読み書き・削除の単一管理点。
 * LargeFileTranslator / NovelDictionaryGenerator / LlmTranslationEngine の
 * 同文コピペをここに一本化し、三重管理を根絶する。
 */
object SafDocIO {

    /**
     * テキスト読み取り。失敗時は null（必要なら onError で通知）。
     */
    fun readDocContent(
        context: Context,
        doc: DocumentFile,
        onError: ((Exception) -> Unit)? = null
    ): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                TextCharsetDetector.readTextAutoDetect(stream)
            }
        } catch (e: Exception) {
            onError?.invoke(e)
            null
        }
    }

    /**
     * テキスト上書き保存。成功時は true。
     */
    fun writeDocContent(context: Context, doc: DocumentFile, content: String): Boolean {
        return try {
            val stream = context.contentResolver.openOutputStream(doc.uri, "wt") ?: return false
            stream.use { s ->
                s.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * ディレクトリ再帰削除。失敗は無視する。
     */
    fun deleteDirectoryRecursively(dir: DocumentFile) {
        try {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    deleteDirectoryRecursively(child)
                } else {
                    child.delete()
                }
            }
            dir.delete()
        } catch (_: Exception) {}
    }
}
