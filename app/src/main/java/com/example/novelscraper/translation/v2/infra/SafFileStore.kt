package com.example.novelscraper.translation.v2.infra

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF実装。ContentResolver直結の最小操作のみを持ち、判定・解釈は上位層に置かない。
 * 旧実装の参照・流用なし（振る舞い仕様のみ持ち込み）。
 */
class SafFileStore(private val context: Context) : FileStore {

    private fun docOf(uri: String): DocumentFile? {
        return try {
            val parsed = Uri.parse(uri) ?: return null
            if (isTreeUri(parsed)) DocumentFile.fromTreeUri(context, parsed)
            else DocumentFile.fromSingleUri(context, parsed)
        } catch (_: Exception) {
            null
        }
    }

    private fun isTreeUri(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return path.contains("/tree/")
    }

    private fun VDocOf(doc: DocumentFile): VDoc {
        return VDoc(
            uri = doc.uri.toString(),
            name = doc.name ?: "",
            isDirectory = doc.isDirectory,
            length = try {
                doc.length()
            } catch (_: Exception) {
                0L
            }
        )
    }

    override suspend fun children(dirUri: String): List<VDoc> = withContext(Dispatchers.IO) {
        try {
            docOf(dirUri)?.listFiles()?.map { VDocOf(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun readText(fileUri: String): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(Uri.parse(fileUri))?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun readBytes(fileUri: String, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(fileUri))?.use { stream ->
                    val out = java.io.ByteArrayOutputStream(8192)
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = stream.read(buf, 0, buf.size.coerceAtMost((maxBytes - total).coerceAtLeast(0)))
                        if (read == -1) break
                        out.write(buf, 0, read)
                        total += read
                        if (total >= maxBytes) break
                    }
                    out.toByteArray()
                }
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun writeText(fileUri: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(Uri.parse(fileUri), "wt")?.use { out ->
                    out.writer(Charsets.UTF_8).use { writer ->
                        writer.write(content)
                        writer.flush()
                    }
                } != null
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun appendText(fileUri: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(Uri.parse(fileUri), "wa")?.use { out ->
                    out.writer(Charsets.UTF_8).use { writer ->
                        writer.write(content)
                        writer.flush()
                    }
                } != null
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun findChild(dirUri: String, name: String): VDoc? =
        withContext(Dispatchers.IO) {
            try {
                docOf(dirUri)?.findFile(name)?.let { VDocOf(it) }
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun createDir(parentUri: String, name: String): VDoc? =
        withContext(Dispatchers.IO) {
            try {
                docOf(parentUri)?.createDirectory(name)?.let { VDocOf(it) }
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? =
        withContext(Dispatchers.IO) {
            try {
                docOf(dirUri)?.createFile(mime, name)?.let { VDocOf(it) }
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun deleteRecursively(dirUri: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = docOf(dirUri) ?: return@withContext false
                deleteDeep(doc)
                true
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun deleteFile(fileUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            docOf(fileUri)?.delete() == true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteDeep(doc: DocumentFile): Boolean {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) {
                deleteDeep(child)
            }
        }
        return doc.delete()
    }
}
