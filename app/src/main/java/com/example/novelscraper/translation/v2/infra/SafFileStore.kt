package com.example.novelscraper.translation.v2.infra

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF（Storage Access Framework）実装。
 * AndroidX の DocumentFile（TreeDocumentFile）を完全に排除し、
 * DocumentsContract および ContentResolver に直結してすべての操作を実行する。
 * これにより、階層ディレクトリや特殊文字を含む環境でもルートへの巻き戻りや親直下への誤保存を物理的に防止する。
 */
class SafFileStore(private val context: Context) : FileStore {

    /**
     * URI文字列の安全パース。
     * 技術的根拠1行：ファイル名に「#」が含まれるとUri.parseがフラグメント（アンカー）と誤認してパスが途切れるため正規化する。
     */
    private fun safeParseUri(uriString: String): Uri? {
        return try {
            val normalized = if (uriString.contains('#')) uriString.replace("#", "%23") else uriString
            Uri.parse(normalized)
        } catch (_: Exception) {
            null
        }
    }

    private fun isTreeUri(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return path.contains("/tree/")
    }

    private fun extractDocumentId(uri: Uri): String? {
        return try {
            if (uri.path?.contains("/document/") == true) {
                DocumentsContract.getDocumentId(uri)
            } else if (uri.path?.contains("/tree/") == true) {
                DocumentsContract.getTreeDocumentId(uri)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun toDocumentUri(uri: Uri): Uri {
        return try {
            if (uri.path?.contains("/document/") == true) uri
            else if (uri.path?.contains("/tree/") == true) {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            } else uri
        } catch (_: Exception) {
            uri
        }
    }
    /**
     * 実表示名の取得。同名衝突によるプロバイダの自動リネーム（" (1)" 等）を検知するために使用。
     * 技術的根拠1行：ContentResolverから直接COLUMN_DISPLAY_NAMEを取得し、誤認や親ルートへの巻き戻りを排除する。
     */
    private fun actualName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 作成結果の検証。自動リネームされていたら重複 "(1)" を消して既存を探し直す。
     * 技術的根拠1行：同名存在時の createDocument の挙動はプロバイダ定義で、重複 "(1)" を残すと無限増殖するため。
     */
    private suspend fun resolveCreated(
        parentUri: String,
        requestedName: String,
        createdUri: Uri?,
        isDirectory: Boolean
    ): VDoc? {
        if (createdUri == null) return null
        val actual = actualName(createdUri)
        val isCollisionRename = actual != null && Regex(""".*\s\(\d+\).*""").matches(actual)
        if (actual == null || actual.equals(requestedName, ignoreCase = true) || !isCollisionRename) {
            return VDoc(
                uri = createdUri.toString(),
                name = actual ?: requestedName,
                isDirectory = isDirectory,
                length = 0L
            )
        }
        try {
            DocumentsContract.deleteDocument(context.contentResolver, createdUri)
        } catch (_: Exception) {
        }
        return findChild(parentUri, requestedName)
    }

    override suspend fun children(dirUri: String): List<VDoc> = withContext(Dispatchers.IO) {
        try {
            val parsed = safeParseUri(dirUri) ?: return@withContext emptyList()
            if (!isTreeUri(parsed)) return@withContext emptyList()
            val docId = extractDocumentId(parsed) ?: return@withContext emptyList()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parsed, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val result = mutableListOf<VDoc>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val childDocId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: ""
                    val mime = cursor.getString(mimeIdx) ?: ""
                    val size = if (cursor.isNull(sizeIdx)) 0L else cursor.getLong(sizeIdx)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(parsed, childDocId)
                    val isDir = (mime == DocumentsContract.Document.MIME_TYPE_DIR)
                    result.add(
                        VDoc(
                            uri = childUri.toString(),
                            name = name,
                            isDirectory = isDir,
                            length = size
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            // 技術的根拠1行：I/O障害を空フォルダと区別するため戻り値は維持しつつLogcatに残す（外部振る舞い不変）。
            android.util.Log.w("SafFileStore", "children failed: $dirUri", e)
            emptyList()
        }
    }

    override suspend fun openInputStream(fileUri: String): java.io.InputStream? = withContext(Dispatchers.IO) {
        try {
            val parsed = safeParseUri(fileUri) ?: return@withContext null
            context.contentResolver.openInputStream(parsed)
        } catch (e: Exception) {
            android.util.Log.w("SafFileStore", "openInputStream failed", e)
            null
        }
    }

    override suspend fun readText(fileUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val parsed = safeParseUri(fileUri) ?: return@withContext null
            context.contentResolver.openInputStream(parsed)?.use { stream ->
                // 技術的根拠1行：UTF-8固定を排しTextIngestでGBK等の文字コードを自動判別・安全にデコードする（物理分割OFF時の文字化け・OOM防止）。
                when (val res = com.example.novelscraper.translation.common.ingest.TextIngest.ingest(stream)) {
                    is com.example.novelscraper.translation.common.ingest.IngestResult.Success -> res.text
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun readBytes(fileUri: String, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val parsed = safeParseUri(fileUri) ?: return@withContext null
                context.contentResolver.openInputStream(parsed)?.use { stream ->
                    val out = java.io.ByteArrayOutputStream(8192)
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val toRead = buf.size.coerceAtMost((maxBytes - total).coerceAtLeast(0))
                        if (toRead <= 0) break
                        val read = stream.read(buf, 0, toRead)
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
                val parsed = safeParseUri(fileUri) ?: return@withContext false
                context.contentResolver.openOutputStream(parsed, "wt")?.use { out ->
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
                val parsed = safeParseUri(fileUri) ?: return@withContext false
                context.contentResolver.openOutputStream(parsed, "wa")?.use { out ->
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
                children(dirUri).firstOrNull { it.name.equals(name, ignoreCase = true) }
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun createDir(parentUri: String, name: String): VDoc? =
        withContext(Dispatchers.IO) {
            try {
                val parsed = safeParseUri(parentUri) ?: return@withContext null
                val targetDocUri = toDocumentUri(parsed)
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    targetDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    name
                )
                resolveCreated(parentUri, name, createdUri, isDirectory = true)
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? =
        withContext(Dispatchers.IO) {
            try {
                val parsed = safeParseUri(dirUri) ?: return@withContext null
                val targetDocUri = toDocumentUri(parsed)
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    targetDocUri,
                    mime,
                    name
                )
                resolveCreated(dirUri, name, createdUri, isDirectory = false)
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 同一フォルダ内での置換（確定操作用）。非対応プロバイダでは例外またはnullになる。
     * 技術的根拠1行：置換後の実名が要求と一致しない場合（衝突時の自動改名等）は残骸を消してnullを返し、呼出側の退行経路に委ねる。
     */
    override suspend fun renameFile(dirUri: String, fileUri: String, newName: String): VDoc? =
        withContext(Dispatchers.IO) {
            try {
                val parsed = safeParseUri(fileUri) ?: return@withContext null
                val renamedUri = DocumentsContract.renameDocument(
                    context.contentResolver,
                    parsed,
                    newName
                ) ?: return@withContext null
                val actual = actualName(renamedUri)
                if (actual != null && !actual.equals(newName, ignoreCase = true)) {
                    try {
                        DocumentsContract.deleteDocument(context.contentResolver, renamedUri)
                    } catch (_: Exception) {
                    }
                    return@withContext null
                }
                // 技術的根拠1行：寸法は呼出側が読み返し照合で確かめるため、ここでは問い合わせず0で返す。
                VDoc(
                    uri = renamedUri.toString(),
                    name = actual ?: newName,
                    isDirectory = false,
                    length = 0L
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun deleteRecursively(dirUri: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val parsed = safeParseUri(dirUri) ?: return@withContext false
                for (child in children(dirUri)) {
                    // 技術的根拠1行：部分削除残骸の原因追跡のため子失敗を記録するが戻り値契約は維持する（外部振る舞い不変）。
                    val childOk = if (child.isDirectory) {
                        deleteRecursively(child.uri)
                    } else {
                        deleteFile(child.uri)
                    }
                    if (!childOk) {
                        android.util.Log.w("SafFileStore", "deleteRecursively child failed: ${child.uri}")
                    }
                }
                val targetDocUri = toDocumentUri(parsed)
                DocumentsContract.deleteDocument(context.contentResolver, targetDocUri)
            } catch (e: Exception) {
                android.util.Log.w("SafFileStore", "deleteRecursively failed: $dirUri", e)
                false
            }
        }

    override suspend fun deleteFile(fileUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed = safeParseUri(fileUri) ?: return@withContext false
            val targetDocUri = toDocumentUri(parsed)
            DocumentsContract.deleteDocument(context.contentResolver, targetDocUri)
        } catch (_: Exception) {
            false
        }
    }
}
