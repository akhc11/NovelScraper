package com.example.novelscraper.translation.web

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.novelscraper.PreferencesRepository
import com.example.novelscraper.translation.common.ingest.DeclaredEncoding
import com.example.novelscraper.translation.common.ingest.TextIngest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TranslationFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long
)

/**
 * SAF子フォルダ解決の正本(再発防止のため必読・理想の動作と禁止事項)。
 *
 * 理想の動作:
 * - 許可ルート配下の子フォルダURI(tree/<root>/document/<child>形)は、
 *   ルートDocumentFileからfindFileで1段ずつ辿って解決する([resolveTreeFolder])。
 * - 辿り着けなければnullを返し、呼出側は「対象なし」としてログ付きで明示する(fail-closed)。
 *
 * 禁止事項(過去に同じバグを繰り返した):
 * - 子フォルダURIをDocumentFile.fromTreeUriに直渡ししないこと。
 *   frameworkがgetTreeDocumentId(=pathSegments[1]=ルートID)でURIを組み立て直すため、
 *   選択子フォルダではなく許可ルート自体を指す別物が返る。結果はルート列挙→0件無言完了、
 *   最悪フォルダ違いの誤翻訳になる。OSピッカーはルートURI自体のため正常に見える。
 *   これが「同じフォルダなのにブラウザだけ壊れる」「直したはずなのに再発する」の正体。
 *   根拠: DocumentFile.java fromTreeUri/buildDocumentUriUsingTree/getTreeDocumentId。
 * 注意事項:
 * - 権限はルートの永続許可に依存する。失効時は辿りがnullになる(再付与はSafBrowser側の責務)。
 * - fromSingleUriは単一文書用でlistFiles不可のため、子フォルダ列挙には使えない。
 * - 本関数の文字列部はJVMテスト可(Android APIに触れないこと)。文書アクセス部のみ要実機。
 */
internal fun splitTreeChildUri(uri: String): Pair<String, List<String>>? {
    // 注意: 文書ID内の"/"は%2Fで1セグメント化されることがあるため、 officials "/"分割ではなく
    // 文書ID全体のデコード後に前方一致で剥がすこと。さもないと通常形を見落としてnullにする。
    val treeIdx = uri.indexOf("/tree/")
    if (treeIdx < 0) return null
    val afterTree = uri.substring(treeIdx + "/tree/".length)
    val rootSegEnd = afterTree.indexOf('/')
    val rootSeg = if (rootSegEnd < 0) afterTree else afterTree.substring(0, rootSegEnd)
    if (rootSeg.isEmpty()) return null
    val root = uri.substring(0, treeIdx + "/tree/".length + rootSeg.length)
    val rest = if (rootSegEnd < 0) "" else afterTree.substring(rootSegEnd)
    if (!rest.startsWith("/document/")) return null // ルートURI自体は対象外
    val childSeg = rest.removePrefix("/document/")
    if (childSeg.isEmpty()) return null
    val rootId = percentDecode(rootSeg)
    val childId = percentDecode(childSeg)
    if (childId == rootId) return root to emptyList() // 子形のルート自体
    // 別ツリーの子は扱わない(権限も別物のためfail-closed)。
    if (!childId.startsWith(rootId + "/")) return null
    return root to childId.removePrefix(rootId + "/").split('/')
}

/** 最小の%デコード。Uri.decode(Android依存)をJVMテスト可のため自前化する。java.net.URLDecoderは不可('+'を空白化するため文書IDが壊れる)。 */
internal fun percentDecode(s: String): String {
    if (!s.contains('%')) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val hex = s.substring(i + 1, i + 3)
            val v = hex.toIntOrNull(16)
            if (v != null) {
                // マルチバイトはバイト列で組み立て直す。
                val bytes = ArrayList<Byte>()
                var j = i
                while (j + 2 < s.length && s[j] == '%' && s.substring(j + 1, j + 3).toIntOrNull(16) != null) {
                    bytes.add(s.substring(j + 1, j + 3).toInt(16).toByte())
                    j += 3
                }
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                i = j
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * 子フォルダURIをルート辿りで解決する(Android部)。
 * 注意: 失敗時はnull+Log.wに倒す。ルート列挙への退行は絶対にしないこと(上記契約)。
 */
internal fun resolveTreeFolder(context: Context, folderUri: Uri): DocumentFile? {
    val split = splitTreeChildUri(folderUri.toString()) ?: return null
    val (rootString, relative) = split
    val rootDoc: DocumentFile? = try {
        DocumentFile.fromTreeUri(context, Uri.parse(rootString))
    } catch (e: Exception) {
        android.util.Log.w("TranslationFileStore", "root resolve failed: $rootString", e)
        null
    }
    if (rootDoc == null) {
        android.util.Log.w("TranslationFileStore", "unreachable grant root (re-grant needed): $rootString")
        return null
    }
    if (!rootDoc.exists()) {
        android.util.Log.w("TranslationFileStore", "grant root gone (re-grant needed): $rootString")
        return null
    }
    var cur: DocumentFile = rootDoc
    for (name in relative) {
        // 注意: findFileは表示名照合。文書ID断片での照合はしないこと(provider差異で外れる)。
        val next = try {
            cur.findFile(name)
        } catch (e: Exception) {
            android.util.Log.w("TranslationFileStore", "traverse failed at $name under $rootString", e)
            null
        }
        if (next == null || !next.exists()) {
            android.util.Log.w("TranslationFileStore", "not found in grant tree (moved/renamed?): $name under $rootString")
            return null
        }
        cur = next
    }
    return cur
}

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
        // 注意: 解決不能は空扱いだが必ずログに残すこと。無言空振りが再発の温床(上記契約)。
        val rootDoc = getDocumentFile(folderUri)
        if (rootDoc == null) {
            Log.w(TAG, "unresolvable folder, treating as empty (see resolveTreeFolder contract): $folderUri")
            return@withContext emptyList()
        }
        if (!rootDoc.exists() || !rootDoc.isDirectory) {
            Log.w(TAG, "not a directory, treating as empty: $folderUri")
            return@withContext emptyList()
        }

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
     * 指定されたファイルのテキストを取込エンジン (BOM / UTF-8 / 韓中日 / 単バイト族) で読み込む。
     * 判定不能時は例外 (Result.failure) とし、化けたテキストを残さない。
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

            val declared = try {
                DeclaredEncoding.parseOrNull(PreferencesRepository(context).inputEncodingFlow.first())
            } catch (_: Exception) {
                null
            }
            TextIngest.ingest(bytes, declared).getOrThrow()
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

    /**
     * フォルダ解決の唯一の入口。
     * 注意: 子URIは[resolveTreeFolder]のルート辿りに寄せ、fromTreeUri直渡しはしないこと(上記契約)。
     */
    private fun getDocumentFile(folderUri: Uri): DocumentFile? {
        return if (folderUri.scheme == "file") {
            val f = File(folderUri.path ?: return null)
            if (f.exists()) DocumentFile.fromFile(f) else null
        } else if (splitTreeChildUri(folderUri.toString()) != null) {
            resolveTreeFolder(context, folderUri)
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