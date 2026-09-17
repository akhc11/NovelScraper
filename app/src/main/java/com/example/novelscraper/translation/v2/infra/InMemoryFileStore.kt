package com.example.novelscraper.translation.v2.infra

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * メモリ実装（結合テスト・将来のパイプライン検証用）。スレッドセーフ。
 * URIは `mem://` 始まりの論理名で扱う。
 */
class InMemoryFileStore : FileStore {
    private data class Node(
        var name: String,
        var isDirectory: Boolean,
        var text: String = "",
        var raw: ByteArray? = null,
        val children: MutableMap<String, Node> = mutableMapOf()
    )

    private val mutex = Mutex()
    private val roots = mutableMapOf<String, Node>()
    private var seq = 0

    /** テスト用のルート作成 */
    suspend fun createRoot(name: String): VDoc = mutex.withLock {
        val node = Node(name, true)
        roots[name] = node
        VDoc(uri = "mem://$name", name = name, isDirectory = true)
    }

    private fun parentSlot(uri: String): Pair<MutableMap<String, Node>, String>? {
        val parts = uri.removePrefix("mem://").split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var map: MutableMap<String, Node> = roots
        for (p in parts.dropLast(1)) {
            map = map[p]?.takeIf { it.isDirectory }?.children ?: return null
        }
        return map to parts.last()
    }

    private fun nodeOf(uri: String): Node? {
        val (parent, name) = parentSlot(uri) ?: return null
        return parent[name]
    }

    private fun docOf(uri: String, node: Node): VDoc {
        return VDoc(
            uri = uri,
            name = node.name,
            isDirectory = node.isDirectory,
            length = if (node.isDirectory) 0L else (node.raw?.size ?: node.text.toByteArray(Charsets.UTF_8).size).toLong()
        )
    }

    /** テスト用：生バイトを直接注入する（SJIS等の取込テスト用） */
    suspend fun writeBytes(fileUri: String, bytes: ByteArray): Boolean = mutex.withLock {
        val node = nodeOf(fileUri) ?: return@withLock false
        if (node.isDirectory) return@withLock false
        node.raw = bytes
        true
    }

    override suspend fun children(dirUri: String): List<VDoc> = mutex.withLock {
        val node = nodeOf(dirUri) ?: return@withLock emptyList()
        if (!node.isDirectory) return@withLock emptyList()
        node.children.values.map { docOf("$dirUri/${it.name}", it) }
    }

    override suspend fun probe(dirUri: String): Boolean = mutex.withLock {
        nodeOf(dirUri)?.isDirectory == true
    }

    override suspend fun openInputStream(fileUri: String): java.io.InputStream? = mutex.withLock {
        val node = nodeOf(fileUri) ?: return@withLock null
        if (node.isDirectory) return@withLock null
        val bytes = node.raw ?: node.text.toByteArray(Charsets.UTF_8)
        java.io.ByteArrayInputStream(bytes)
    }

    override suspend fun readText(fileUri: String): String? = mutex.withLock {
        val node = nodeOf(fileUri) ?: return@withLock null
        if (node.isDirectory) return@withLock null
        node.text
    }

    override suspend fun readBytes(fileUri: String, maxBytes: Int): ByteArray? = mutex.withLock {
        val node = nodeOf(fileUri) ?: return@withLock null
        if (node.isDirectory) return@withLock null
        val bytes = node.raw ?: node.text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes
    }

    override suspend fun writeText(fileUri: String, content: String): Boolean = mutex.withLock {
        val node = nodeOf(fileUri) ?: return@withLock false
        if (node.isDirectory) return@withLock false
        node.text = content
        true
    }

    override suspend fun appendText(fileUri: String, content: String): Boolean = mutex.withLock {
        val node = nodeOf(fileUri) ?: return@withLock false
        if (node.isDirectory) return@withLock false
        node.text += content
        true
    }

    override suspend fun findChild(dirUri: String, name: String): VDoc? = mutex.withLock {
        val node = nodeOf(dirUri) ?: return@withLock null
        if (!node.isDirectory) return@withLock null
        val child = node.children[name] ?: return@withLock null
        docOf("$dirUri/$name", child)
    }

    override suspend fun createDir(parentUri: String, name: String): VDoc? = mutex.withLock {
        val parent = nodeOf(parentUri) ?: return@withLock null
        if (!parent.isDirectory) return@withLock null
        val existing = parent.children[name]
        if (existing != null) {
            return@withLock if (existing.isDirectory) docOf("$parentUri/$name", existing) else null
        }
        seq++
        val node = Node(name, true)
        parent.children[name] = node
        docOf("$parentUri/$name", node)
    }

    override suspend fun createFile(dirUri: String, name: String, mime: String): VDoc? =
        mutex.withLock {
            val parent = nodeOf(dirUri) ?: return@withLock null
            if (!parent.isDirectory) return@withLock null
            val existing = parent.children[name]
            if (existing != null) {
                return@withLock if (!existing.isDirectory) docOf("$dirUri/$name", existing) else null
            }
            val node = Node(name, false)
            parent.children[name] = node
            docOf("$dirUri/$name", node)
        }

    override suspend fun renameFile(dirUri: String, fileUri: String, newName: String): VDoc? =
        mutex.withLock {
            val parent = nodeOf(dirUri)?.takeIf { it.isDirectory } ?: return@withLock null
            val slot = parentSlot(fileUri) ?: return@withLock null
            if (slot.first !== parent.children) return@withLock null
            val node = slot.first[slot.second] ?: return@withLock null
            if (node.isDirectory || parent.children.containsKey(newName)) return@withLock null
            slot.first.remove(slot.second)
            node.name = newName
            parent.children[newName] = node
            docOf("$dirUri/$newName", node)
        }

    override suspend fun deleteRecursively(dirUri: String): Boolean = mutex.withLock {
        val slot = parentSlot(dirUri) ?: return@withLock false
        slot.first.remove(slot.second) != null
    }

    override suspend fun deleteFile(fileUri: String): Boolean = mutex.withLock {
        val slot = parentSlot(fileUri) ?: return@withLock false
        val node = slot.first[slot.second] ?: return@withLock false
        if (node.isDirectory) return@withLock false
        slot.first.remove(slot.second)
        true
    }
}
