package com.example.novelscraper.translation.picker

import android.util.Log
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自前ブラウザの走査・選択状態。Android枠組みに依存しない純粋な保持者とし、
 * FileStore差し替えでJVM単体テスト可能にする。
 * 技術的根拠1行：列挙コストは再帰クエリ発数に比例するため、可視1階層の遅延読み＋確定時の選択分のみ走査に切断する。
 */
class SafBrowserState(
    private val store: FileStore,
    private val scope: CoroutineScope,
    val treeUri: String,
    val rootName: String
) {
    companion object {
        /** 確定走査の上限(コード正本。表示設定画面には複写しない)。 */
        const val MAX_DEPTH = 8
        const val MAX_FILES = 5000
        const val MAX_DIRS = 1000
        const val MAX_PER_DIR = 2000

        /**
         * 走査から除外する出力・中間物ディレクトリの一致則。
         * v2出力先は設定で変更可能なため完全一致ではなく接頭辞で判定する。
         */
        private val EXCLUDED_DIR_PREFIXES = listOf("翻訳完了_", ".parts_")
        private val EXCLUDED_DIR_EXACT = setOf("分割済み")
        private const val TAG = "SafBrowserState"

        internal fun isExcludedDir(name: String): Boolean {
            return name in EXCLUDED_DIR_EXACT || EXCLUDED_DIR_PREFIXES.any { name.startsWith(it) }
        }

        internal fun isExcludedFile(name: String): Boolean {
            return name.startsWith(".tmp_") || name == "dictionary.json"
        }
    }

    data class Crumb(val uri: String, val name: String, val rel: String)
    data class Row(val doc: VDoc, val relPath: String)
    data class CollectResult(
        val targets: List<PickerTarget>,
        val fileCount: Int,
        val dirCount: Int,
        val truncated: Boolean,
        val skipped: List<String>
    )

    private val _crumbs = MutableStateFlow(
        listOf(Crumb(treeUri, rootName.ifBlank { FALLBACK_FOLDER_NAME }, ""))
    )
    val crumbs: StateFlow<List<Crumb>> = _crumbs.asStateFlow()

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _checked = MutableStateFlow<Set<String>>(emptySet())
    val checked: StateFlow<Set<String>> = _checked.asStateFlow()

    /** チェック時点のスナップショット(FILE→親畳み込み・表示外チェックの保持用)。 */
    private data class CheckedSnap(val doc: VDoc, val relPath: String, val parentUri: String)
    private val checkedSnaps = mutableMapOf<String, CheckedSnap>()

    private var listGen = 0L

    private val _txtOnly = MutableStateFlow(true)
    val txtOnly: StateFlow<Boolean> = _txtOnly.asStateFlow()

    fun setTxtOnly(enabled: Boolean) {
        _txtOnly.value = enabled
        refresh()
    }

    fun refresh() {
        val crumb = _crumbs.value.lastOrNull() ?: return
        val gen = ++listGen
        _loading.value = true
        _error.value = null
        scope.launch {
            try {
                val docs = withContext(Dispatchers.IO) { store.children(crumb.uri) }
                if (gen != listGen) return@launch
                _rows.value = docs
                    .filter { d -> if (_txtOnly.value) d.isDirectory || isTxt(d.name) else true }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { Row(it, if (crumb.rel.isEmpty()) it.name else "${crumb.rel}/${it.name}") }
            } catch (e: Exception) {
                if (gen != listGen) return@launch
                Log.w(TAG, "list failed: ${crumb.uri}", e)
                _error.value = e.message ?: "一覧の取得に失敗しました"
                _rows.value = emptyList()
            } finally {
                if (gen == listGen) _loading.value = false
            }
        }
    }

    fun enter(row: Row) {
        if (!row.doc.isDirectory) return
        if (_crumbs.value.isEmpty()) return
        _crumbs.value = _crumbs.value + Crumb(row.doc.uri, row.doc.name, row.relPath)
        refresh()
    }

    /** @return falseのとき既にルートにいる。 */
    fun goUp(): Boolean {
        if (_crumbs.value.size <= 1) return false
        _crumbs.value = _crumbs.value.dropLast(1)
        refresh()
        return true
    }

    fun goTo(index: Int) {
        if (index !in _crumbs.value.indices) return
        _crumbs.value = _crumbs.value.take(index + 1)
        refresh()
    }

    fun toggle(row: Row) {
        val uri = row.doc.uri
        val cur = _checked.value.toMutableSet()
        if (cur.add(uri)) {
            checkedSnaps[uri] = CheckedSnap(
                doc = row.doc,
                relPath = row.relPath,
                parentUri = _crumbs.value.lastOrNull()?.uri ?: treeUri
            )
        } else {
            cur.remove(uri)
            checkedSnaps.remove(uri)
        }
        _checked.value = cur
    }

    fun clearChecked() {
        _checked.value = emptySet()
        checkedSnaps.clear()
    }

    /**
     * チェック済みを確定列へ変換する。フォルダは上限付きBFSで.txtを回収し、
     * 除外物・上限超過は落として結果に記録する(無言で捨てない)。
     */
    suspend fun collectTargets(): CollectResult = withContext(Dispatchers.IO) {
        val targets = ArrayList<PickerTarget>()
        val skipped = ArrayList<String>()
        var truncated = false
        var fileCount = 0
        var dirCount = 0

        // 技術的根拠1行：選択集合は順序を持たないため、投入順の正本として相対パス順に確定し再現性を保つ。
        val ordered = _checked.value.mapNotNull { uri ->
            val snap = checkedSnaps[uri]
            if (snap == null) {
                Log.w(TAG, "checked snapshot lost: $uri")
                skipped.add("$uri: 選択情報の消失のため除外")
                null
            } else snap
        }.sortedBy { it.relPath }

        for (snap in ordered) {
            val row = Row(snap.doc, snap.relPath)
            val parentUri = snap.parentUri
            if (row.doc.isDirectory) {
                if (isExcludedDir(row.doc.name)) {
                    skipped.add("${row.relPath}: 出力・中間物のため除外")
                    continue
                }
                if (dirCount >= MAX_DIRS) {
                    truncated = true
                    skipped.add("${row.relPath}: フォルダ上限のため除外")
                    continue
                }
                dirCount++
                targets.add(
                    PickerTarget(
                        kind = PickerKind.FOLDER,
                        docUri = row.doc.uri,
                        treeUri = treeUri,
                        relPath = row.relPath,
                        displayName = row.doc.name
                    )
                )
                val walk = walkTxtFiles(row, skipped)
                truncated = truncated || walk.truncated
                for (f in walk.files) {
                    if (fileCount >= MAX_FILES) {
                        truncated = true
                        skipped.add("${row.relPath}: ファイル上限のため残りを除外")
                        break
                    }
                    fileCount++
                    targets.add(
                        PickerTarget(
                            kind = PickerKind.FILE,
                            docUri = f.doc.uri,
                            treeUri = treeUri,
                            parentDocUri = f.parentUri,
                            relPath = f.relPath,
                            displayName = f.doc.name
                        )
                    )
                }
            } else {
                if (isExcludedFile(row.doc.name)) {
                    skipped.add("${row.relPath}: 中間物のため除外")
                    continue
                }
                if (_txtOnly.value && !isTxt(row.doc.name)) {
                    skipped.add("${row.relPath}: .txtのみ対象のため除外")
                    continue
                }
                if (fileCount >= MAX_FILES) {
                    truncated = true
                    skipped.add("${row.relPath}: ファイル上限のため除外")
                    continue
                }
                fileCount++
                targets.add(
                    PickerTarget(
                        kind = PickerKind.FILE,
                        docUri = row.doc.uri,
                        treeUri = treeUri,
                        parentDocUri = parentUri,
                        relPath = row.relPath,
                        displayName = row.doc.name
                    )
                )
            }
        }
        CollectResult(targets, fileCount, dirCount, truncated, skipped)
    }

    private data class FoundFile(val doc: VDoc, val relPath: String, val parentUri: String)
    private data class Walk(val files: List<FoundFile>, val truncated: Boolean)

    private suspend fun walkTxtFiles(root: Row, skipped: MutableList<String>): Walk {
        val files = ArrayList<FoundFile>()
        var truncated = false
        var dirs = 0
        val queue = ArrayDeque<Triple<VDoc, String, Int>>()
        queue.add(Triple(root.doc, root.relPath, 0))
        while (queue.isNotEmpty()) {
            val (dir, rel, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) {
                truncated = true
                skipped.add("$rel: 階層上限のため除外")
                continue
            }
            val children = try {
                store.children(dir.uri)
            } catch (e: Exception) {
                Log.w(TAG, "walk failed: ${dir.uri}", e)
                skipped.add("$rel: 読取失敗のため除外 (${e.message})")
                continue
            }
            if (children.size > MAX_PER_DIR) {
                truncated = true
                skipped.add("$rel: 件数上限のため先頭${MAX_PER_DIR}件のみ走査")
            }
            // プロバイダの返却順は無保証のため名寄せソートし、打切り範囲も再現させる。
            val ordered = children
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .take(MAX_PER_DIR)
            for (child in ordered) {
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (child.isDirectory) {
                    if (isExcludedDir(child.name)) {
                        skipped.add("$childRel: 出力・中間物のため除外")
                        continue
                    }
                    if (dirs >= MAX_DIRS) {
                        truncated = true
                        skipped.add("$childRel: フォルダ上限のため除外")
                        continue
                    }
                    dirs++
                    queue.add(Triple(child, childRel, depth + 1))
                } else {
                    if (isExcludedFile(child.name)) continue
                    if (!isTxt(child.name)) continue
                    files.add(FoundFile(child, childRel, dir.uri))
                    if (files.size >= MAX_FILES) {
                        truncated = true
                        skipped.add("$rel: ファイル上限のため残りを除外")
                        return Walk(files, true)
                    }
                }
            }
        }
        return Walk(files, truncated)
    }

    private fun isTxt(name: String): Boolean = name.endsWith(".txt", ignoreCase = true)
}
