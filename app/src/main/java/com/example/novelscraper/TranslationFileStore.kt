package com.example.novelscraper

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TranslationFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long
)

class TranslationFileStore(private val context: Context) {

    companion object {
        const val OUTPUT_FOLDER_NAME = "翻訳完了_GOOGLE"
        private const val TAG = "TranslationFileStore"
    }

    /**
     * 翻訳完了フォルダ（翻訳完了_GOOGLE）を取得、存在しなければ新規作成する。
     * DocumentFile.findFile は環境により失敗して連番フォルダ (1), (2) が作られる原因となるため、
     * 必ず listFiles() の大文字小文字無視スキャンで既存フォルダを再利用する。
     */
    private fun getOrCreateOutputDirectory(rootDoc: DocumentFile): DocumentFile? {
        val children = rootDoc.listFiles()
        // 既存の「翻訳完了_GOOGLE」または「翻訳完了_google」等のフォルダを探す
        val existingDir = children.firstOrNull { 
            it.isDirectory && (
                it.name.equals(OUTPUT_FOLDER_NAME, ignoreCase = true) ||
                it.name?.startsWith("翻訳完了_GOOGLE", ignoreCase = true) == true ||
                it.name?.startsWith("翻訳完了_google", ignoreCase = true) == true
            )
        }
        if (existingDir != null) {
            return existingDir
        }
        return rootDoc.createDirectory(OUTPUT_FOLDER_NAME)
    }

    /**
     * 指定されたフォルダ直下の .txt ファイルのうち、
     * 翻訳完了フォルダ（翻訳完了_GOOGLE）に同名ファイルが存在しない未翻訳ファイル一覧を取得する。
     */
    suspend fun getPendingTextFiles(folderUri: Uri): List<TranslationFileInfo> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
        if (!rootDoc.exists() || !rootDoc.isDirectory) return@withContext emptyList()

        val outputDirDoc = getOrCreateOutputDirectory(rootDoc)

        // 翻訳完了フォルダ内の既存ファイル名一覧を取得
        val existingCompletedNames = outputDirDoc?.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { it.name?.lowercase() }
            ?.toSet() ?: emptySet()

        val allChildren = rootDoc.listFiles()
        val pendingList = mutableListOf<TranslationFileInfo>()
        for (file in allChildren) {
            if (file.isFile && file.name?.endsWith(".txt", ignoreCase = true) == true) {
                val fileName = file.name ?: continue
                // 翻訳完了フォルダ内に同名ファイルがなければ未翻訳リストに追加
                if (!existingCompletedNames.contains(fileName.lowercase())) {
                    pendingList.add(
                        TranslationFileInfo(
                            uri = file.uri,
                            name = fileName,
                            size = file.length()
                        )
                    )
                }
            }
        }

        // ファイル名順（自然順または辞書順）でソート
        pendingList.sortedBy { it.name }
    }

    /**
     * 指定されたファイルのテキストを UTF-8 で読み込む。
     */
    suspend fun readTextFile(fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: throw IllegalStateException("Failed to open input stream for: $fileUri")
        }
    }

    /**
     * 翻訳完了テキストを「翻訳完了_GOOGLE」フォルダ配下に同名で保存する。
     * 連番フォルダや連番ファイルが作成されないよう、確実に同一フォルダ内に上書きまたは作成する。
     */
    suspend fun saveTranslatedFile(folderUri: Uri, fileName: String, content: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                ?: throw IllegalStateException("Cannot access folder: $folderUri")

            val outputDirDoc = getOrCreateOutputDirectory(rootDoc)
                ?: throw IllegalStateException("Failed to get or create output directory: $OUTPUT_FOLDER_NAME")

            // 既存ファイルがあれば取得（大文字小文字無視で検索）、なければ新規作成
            val targetFileDoc = outputDirDoc.listFiles().firstOrNull { 
                it.isFile && it.name.equals(fileName, ignoreCase = true) 
            } ?: outputDirDoc.createFile("text/plain", fileName)
              ?: throw IllegalStateException("Failed to create file: $fileName")

            context.contentResolver.openOutputStream(targetFileDoc.uri, "wt")?.use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } ?: throw IllegalStateException("Failed to open output stream for: ${targetFileDoc.uri}")

            targetFileDoc.uri
        }
    }
}
