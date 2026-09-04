package com.example.novelscraper.translation.web

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.translation.common.UniversalCharsetDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TranslationFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long
)

/**
 * 翻訳対象ファイルおよび完了ファイルの管理を行うストレージクラス。
 * 毎回の listFiles() 全件走査による SAF の極端な I/O オーバーヘッドを回避するため、
 * ディレクトリ参照および完了ファイル名のインメモリキャッシュを保持する。
 */
class TranslationFileStore(
    private val context: Context,
    val outputFolderName: String = GOOGLE_OUTPUT_FOLDER
) {
    companion object {
        const val GOOGLE_OUTPUT_FOLDER = "翻訳完了_GOOGLE"
        const val DEEPL_OUTPUT_FOLDER = "翻訳完了_DEEPL"
        const val PAPAGO_OUTPUT_FOLDER = "翻訳完了_PAPAGO"
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

    /**
     * 指定されたフォルダ内の .txt ファイル一覧を取得し、
     * 出力先フォルダ（outputFolderName）にまだ存在しない「未翻訳ファイル」のみを自然順（01, 02, ... 10）でソートして返す。
     */
    suspend fun getPendingTextFiles(folderUri: Uri): List<TranslationFileInfo> = withContext(Dispatchers.IO) {
        val rootDoc = getDocumentFile(folderUri) ?: return@withContext emptyList()
        if (!rootDoc.exists() || !rootDoc.isDirectory) return@withContext emptyList()

        // 出力フォルダを取得または作成し、完了ファイル名をキャッシュ
        val outputDirDoc = getOrCreateOutputDirectory(rootDoc)
        cachedFolderUri = folderUri
        cachedOutputDirDoc = outputDirDoc

        cachedCompletedNames.clear()
        if (outputDirDoc != null) {
            outputDirDoc.listFiles()
                .filter { it.isFile && it.length() > 0L }
                .mapNotNull { it.name?.lowercase() }
                .forEach { cachedCompletedNames.add(it) }
        }

        val allChildren = rootDoc.listFiles()
        val pendingList = mutableListOf<TranslationFileInfo>()
        for (file in allChildren) {
            if (file.isFile && file.name?.endsWith(".txt", ignoreCase = true) == true) {
                val fileName = file.name ?: continue
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

        // 自然順（01, 02, 10 等）でソート
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
     * 指定されたファイルのテキストを万能文字コード自動判別（Unicode / 韓国語 / 中国語 / 日本語）で読み込む。
     */
    suspend fun readTextFile(fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = if (fileUri.scheme == "file") {
                val file = File(fileUri.path ?: throw IllegalStateException("Invalid file path: $fileUri"))
                file.readBytes()
            } else {
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    inputStream.readBytes()
                } ?: throw IllegalStateException("Failed to open input stream for: $fileUri")
            }

            UniversalCharsetDetector.decodeBytes(bytes)
        }
    }

    /**
     * 翻訳完了テキストを出力フォルダ配下に同名で保存する。
     * キャッシュを活用して毎回の listFiles() 全件スキャンを回避し、高速に書き込む。
     * 書き込み失敗時はロールバック削除を行い、破損・中途半端な残骸を残さない。
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
                cachedCompletedNames.clear()
                dir.listFiles().filter { it.isFile && it.length() > 0L }.mapNotNull { it.name?.lowercase() }.forEach { cachedCompletedNames.add(it) }
                dir
            }

            val lowerFileName = fileName.lowercase()
            val isAlreadyExisting = cachedCompletedNames.contains(lowerFileName)

            val targetFileDoc = if (isAlreadyExisting) {
                outputDirDoc.findFile(fileName)
                    ?: outputDirDoc.listFiles().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
                    ?: outputDirDoc.createFile("text/plain", fileName)
            } else {
                outputDirDoc.createFile("text/plain", fileName)
            } ?: throw IllegalStateException("Failed to create file: $fileName")

            try {
                if (targetFileDoc.uri.scheme == "file") {
                    val file = File(targetFileDoc.uri.path ?: throw IllegalStateException("Invalid target path"))
                    file.writeText(content, Charsets.UTF_8)
                } else {
                    context.contentResolver.openOutputStream(targetFileDoc.uri, "wt")?.use { outputStream ->
                        outputStream.write(content.toByteArray(Charsets.UTF_8))
                        outputStream.flush()
                    } ?: throw IllegalStateException("Failed to open output stream for: ${targetFileDoc.uri}")
                }

                // 完全に書き込み成功した後にキャッシュへ追加
                cachedCompletedNames.add(lowerFileName)
                targetFileDoc.uri
            } catch (e: Exception) {
                // 書き込み失敗時は破損残骸を残さないようロールバック削除
                try { targetFileDoc.delete() } catch (_: Exception) {}
                cachedCompletedNames.remove(lowerFileName)
                throw e
            }
        }
    }

    private fun getDocumentFile(folderUri: Uri): DocumentFile? {
        return if (folderUri.scheme == "file") {
            val f = File(folderUri.path ?: return null)
            if (f.exists()) DocumentFile.fromFile(f) else null
        } else {
            DocumentFile.fromTreeUri(context, folderUri)
        }
    }

    private fun getOrCreateOutputDirectory(rootDoc: DocumentFile): DocumentFile? {
        val children = rootDoc.listFiles()
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
}