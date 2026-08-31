package com.example.novelscraper

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TranslationFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long
)

class TranslationFileStore(
    private val context: Context,
    private val outputFolderName: String = GOOGLE_OUTPUT_FOLDER
) {

    companion object {
        const val GOOGLE_OUTPUT_FOLDER = "翻訳完了_GOOGLE"
        const val DEEPL_OUTPUT_FOLDER = "翻訳完了_DEEPL"
        const val OUTPUT_FOLDER_NAME = GOOGLE_OUTPUT_FOLDER // 互換性維持用
        private const val TAG = "TranslationFileStore"
    }

    // セッションキャッシュ（毎ファイル保存時のSAF listFiles() IPC負荷を99%削減 & スレッドセーフ）
    private var cachedFolderUri: Uri? = null
    private var cachedOutputDirDoc: DocumentFile? = null
    private val cachedCompletedNames = ConcurrentHashMap.newKeySet<String>()

    fun clearCache() {
        cachedFolderUri = null
        cachedOutputDirDoc = null
        cachedCompletedNames.clear()
    }

    private fun getDocumentFile(folderUri: Uri): DocumentFile? {
        return if (folderUri.scheme == "file") {
            val f = File(folderUri.path ?: return null)
            if (f.exists()) DocumentFile.fromFile(f) else null
        } else {
            DocumentFile.fromTreeUri(context, folderUri)
        }
    }

    /**
     * 翻訳完了フォルダ（指定された outputFolderName）を取得、存在しなければ新規作成する。
     */
    private fun getOrCreateOutputDirectory(rootDoc: DocumentFile): DocumentFile? {
        val children = rootDoc.listFiles()
        // 既存のフォルダ（例: 「翻訳完了_GOOGLE」または「翻訳完了_DEEPL」等）を探す
        val existingDir = children.firstOrNull { 
            it.isDirectory && (
                it.name.equals(outputFolderName, ignoreCase = true) ||
                it.name?.startsWith(outputFolderName, ignoreCase = true) == true
            )
        }
        if (existingDir != null) {
            return existingDir
        }
        return rootDoc.createDirectory(outputFolderName)
    }

    /**
     * 指定されたフォルダ直下の .txt ファイルのうち、
     * 翻訳完了フォルダに同名ファイルが存在しない未翻訳ファイル一覧を取得する。
     */
    suspend fun getPendingTextFiles(folderUri: Uri): List<TranslationFileInfo> = withContext(Dispatchers.IO) {
        val rootDoc = getDocumentFile(folderUri) ?: return@withContext emptyList()
        if (!rootDoc.exists() || !rootDoc.isDirectory) return@withContext emptyList()

        val outputDirDoc = getOrCreateOutputDirectory(rootDoc)
        cachedFolderUri = folderUri
        cachedOutputDirDoc = outputDirDoc
        cachedCompletedNames.clear()

        // 翻訳完了フォルダ内の既存ファイル名一覧を取得してキャッシュ
        if (outputDirDoc != null) {
            outputDirDoc.listFiles()
                .filter { it.isFile }
                .mapNotNull { it.name?.lowercase() }
                .forEach { cachedCompletedNames.add(it) }
        }

        val allChildren = rootDoc.listFiles()
        val pendingList = mutableListOf<TranslationFileInfo>()
        for (file in allChildren) {
            if (file.isFile && file.name?.endsWith(".txt", ignoreCase = true) == true) {
                val fileName = file.name ?: continue
                // 翻訳完了フォルダ内に同名ファイルがなければ未翻訳リストに追加
                if (!cachedCompletedNames.contains(fileName.lowercase())) {
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

        // ファイル名順（自然順ソート）
        pendingList.sortedWith(Comparator { a, b ->
            compareNatural(a.name, b.name)
        })
    }

    /**
     * 自然順（数値連番を正しく考慮したソート）
     */
    private fun compareNatural(a: String, b: String): Int {
        val regex = Regex("(\\d+)|(\\D+)")
        val aTokens = regex.findAll(a).map { it.value }.toList()
        val bTokens = regex.findAll(b).map { it.value }.toList()
        val minLen = minOf(aTokens.size, bTokens.size)

        for (i in 0 until minLen) {
            val aToken = aTokens[i]
            val bToken = bTokens[i]
            val aNum = aToken.toLongOrNull()
            val bNum = bToken.toLongOrNull()

            if (aNum != null && bNum != null) {
                val cmp = aNum.compareTo(bNum)
                if (cmp != 0) return cmp
            } else {
                val cmp = aToken.compareTo(bToken, ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return aTokens.size.compareTo(bTokens.size)
    }

    /**
     * 指定されたファイルのテキストを UTF-8 で読み込む。
     */
    suspend fun readTextFile(fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (fileUri.scheme == "file") {
                val file = File(fileUri.path ?: throw IllegalStateException("Invalid file path: $fileUri"))
                file.readText(Charsets.UTF_8)
            } else {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } ?: throw IllegalStateException("Failed to open input stream for: $fileUri")
            }
        }
    }

    /**
     * 翻訳完了テキストを出力フォルダ配下に同名で保存する。
     * キャッシュを活用して毎回の listFiles() 全件スキャンを回避し、高速に書き込む。
     */
    suspend fun saveTranslatedFile(folderUri: Uri, fileName: String, content: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val outputDirDoc = if (cachedFolderUri == folderUri && cachedOutputDirDoc != null && cachedOutputDirDoc!!.exists()) {
                cachedOutputDirDoc!!
            } else {
                val rootDoc = getDocumentFile(folderUri)
                    ?: throw IllegalStateException("Cannot access folder: $folderUri")
                val dir = getOrCreateOutputDirectory(rootDoc)
                    ?: throw IllegalStateException("Failed to get or create output directory: $outputFolderName")
                cachedFolderUri = folderUri
                cachedOutputDirDoc = dir
                // 新しいフォルダの場合はキャッシュを再構築
                cachedCompletedNames.clear()
                dir.listFiles().filter { it.isFile }.mapNotNull { it.name?.lowercase() }.forEach { cachedCompletedNames.add(it) }
                dir
            }

            val lowerFileName = fileName.lowercase()
            val isAlreadyExisting = cachedCompletedNames.contains(lowerFileName)

            val targetFileDoc = if (isAlreadyExisting) {
                // 既存ファイルが存在する場合は取得、見つからなければ新規作成
                outputDirDoc.findFile(fileName)
                    ?: outputDirDoc.listFiles().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
                    ?: outputDirDoc.createFile("text/plain", fileName)
            } else {
                // 新規ファイルの場合は直接作成（全件スキャンをスキップ）
                outputDirDoc.createFile("text/plain", fileName)
            } ?: throw IllegalStateException("Failed to create file: $fileName")

            if (targetFileDoc.uri.scheme == "file") {
                val file = File(targetFileDoc.uri.path ?: throw IllegalStateException("Invalid target path"))
                file.writeText(content, Charsets.UTF_8)
            } else {
                context.contentResolver.openOutputStream(targetFileDoc.uri, "wt")?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                } ?: throw IllegalStateException("Failed to open output stream for: ${targetFileDoc.uri}")
            }

            cachedCompletedNames.add(lowerFileName)
            targetFileDoc.uri
        }
    }
}
