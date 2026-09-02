package com.example.novelscraper.translation.llm.pipeline

import android.content.Context
import androidx.documentfile.provider.DocumentFile

object TextFilePhysicalSplitter {

    /**
     * ルートフォルダ直下の生テキストファイルを指定バイト数 (splitSizeBytes) で物理分割し、
     * 「分割済み/<小説名>/part_XXXX.txt」に出力する (bash v29.0.5.4.0 準拠)。
     */
    fun splitRawNovelFiles(
        context: Context,
        inputFolderDoc: DocumentFile,
        splitSizeBytes: Int = 8000,
        onLog: (String) -> Unit = {}
    ): Int {
        val rootFiles = inputFolderDoc.listFiles().filter {
            it.isFile && it.name?.endsWith(".txt") == true && !it.name!!.startsWith("part_")
        }

        if (rootFiles.isEmpty()) return 0

        val splitRootDir = inputFolderDoc.findFile("分割済み") ?: inputFolderDoc.createDirectory("分割済み")
        if (splitRootDir == null) {
            onLog("❌ 物理分割: 出力先「分割済み」フォルダ作成失敗")
            return 0
        }

        var splitFolderCount = 0

        for (fileDoc in rootFiles) {
            val fileName = fileDoc.name ?: continue
            val novelBaseName = fileName.removeSuffix(".txt")

            // 既に分割済みフォルダがあればスキップ
            if (splitRootDir.findFile(novelBaseName) != null) {
                continue
            }

            val rawContent = readDocContent(context, fileDoc) ?: continue
            if (rawContent.isBlank()) continue

            val cleansedContent = TextCleanser.cleanse(rawContent)
            val parts = NovelTextSplitter.splitIntoChunks(
                text = cleansedContent,
                limitBytes = splitSizeBytes,
                prefix = "part",
                suffix = ".txt"
            )

            if (parts.isEmpty()) continue

            val novelDir = splitRootDir.createDirectory(novelBaseName) ?: continue
            onLog("✂️ 物理分割: $fileName → 分割済み/$novelBaseName/ (${parts.size}パート / 枠:${splitSizeBytes}B)")

            for (part in parts) {
                val partDoc = novelDir.createFile("text/plain", part.first) ?: continue
                writeDocContent(context, partDoc, part.second)
            }
            splitFolderCount++
        }

        return splitFolderCount
    }

    private fun readDocContent(context: Context, doc: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                TextCharsetDetector.readTextAutoDetect(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDocContent(context: Context, doc: DocumentFile, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}