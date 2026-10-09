package com.example.novelscraper.translation.picker

import android.util.Log
import com.example.novelscraper.translation.common.compareNatural
import com.example.novelscraper.translation.v2.infra.FileStore
import com.example.novelscraper.translation.v2.infra.VDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自前ブラウザの走査・選択状態。Android枠組みに依存しない純粋な保持者とし、
 * FileStore差し替えでJVM単体テスト可能にする。
 * 技術的根拠1行：実行系(v2 processFolder・web getPendingTextFiles)は直下一階層のみ列挙するため、確定側の再帰展開を持たず明示集合に一本化し契約不一致をなくす。
 */
class SafBrowserState(
    private val store: FileStore,
    private val scope: CoroutineScope,
    val treeUri: String,
    val rootName: String,
    /**
     * 動的除外する出力dir名（v2設定のoutputSubDir等）。空白は無視される。
     * 技術的根拠1行：出力先は設定で変更可能なため静的則に載らず、確定側が設定値を注入して再翻訳ループを塞ぐ。
     */
    private val extraExcludedDirs: Set<String> = emptySet()
) {
    companion object {
        /**
         * 確定上限(コード正本。表示設定画面には複写しない)。
         * フォルダは直下全件意図として数え、孫以下の展開はしない（実行系が直下のみのため）。
         */
        const val MAX_FILES = 5000
        const val MAX_DIRS = 1000

        /**
         * 走査から除外する出力・中間物ディレクトリの一致則。
         * v2出力先は設定で変更可能なため完全一致ではなく接頭辞で判定する。
         * `.dict_building` は辞書生成の作業dir（入力直下に生成・txtなし）で選択対象外。
         */
        private val EXCLUDED_DIR_PREFIXES = listOf("翻訳完了_", ".parts_")
        private val EXCLUDED_DIR_EXACT = setOf("分割済み", ".dict_building")
        private const val TAG = "SafBrowserState"

        internal fun isExcludedDir(name: String, extraExact: Set<String> = emptySet()): Boolean {
            if (name in extraExact) return true
            return name in EXCLUDED_DIR_EXACT || EXCLUDED_DIR_PREFIXES.any { name.startsWith(it) }
        }

        /**
         * 相対パス配下ガード。いずれかの区間が出力・中間物なら真（深い許可木でもすり抜けない）。
         * 技術的根拠1行：出力dirは選択フォルダ直下とは限らないため、表示名単体ではなく経路で判定する。
         */
        internal fun isExcludedPath(relPath: String, extraExact: Set<String> = emptySet()): Boolean {
            return relPath.split('/').any { isExcludedDir(it, extraExact) }
        }

        internal fun isExcludedFile(name: String): Boolean {
            // 技術的根拠1行：言語キャッシュ旧式(.lang_cache.txt)はtxt終端のため候補化する。v2実行は弾くがweb実行は拾うため確定側で落とす。
            return name.startsWith(".tmp_") || name == "dictionary.json" || name.startsWith(".lang_cache")
        }

        /**
         * 範囲選択の純粋区間計算(JVMテスト可)。表示順URI列・起点・終点から包含区間を返す。
         * 起点不明・表示外時は空（呼出側は起点再設定に倒す）。
         * 技術的根拠1行：順序は表示順を正本にし、確定時のrelPath再ソートと二重管理しない。
         * 自然順の実体は translation/common の単一正本を参照する。
         */
        internal fun rangeSlice(
            visibleUris: List<String>,
            anchorUri: String?,
            targetUri: String
        ): List<String> {
            if (anchorUri == null) return emptyList()
            val from = visibleUris.indexOf(anchorUri)
            val to = visibleUris.indexOf(targetUri)
            if (from < 0 || to < 0) return emptyList()
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            return visibleUris.subList(lo, hi + 1)
        }

        /**
         * 絞り込みの純粋適用(JVMテスト可)。読込済み行の部分一致のみで追加IPCは作らない。
         */
        internal fun applyQuery(rows: List<Row>, query: String): List<Row> {
            val q = query.trim()
            if (q.isEmpty()) return rows
            return rows.filter { it.doc.name.contains(q, ignoreCase = true) }
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

    /** 一覧の並べ替え。フォルダ優先は不変（Files by Google/iOS方式）。 */
    enum class SortMode(val label: String) {
        NAME_ASC("名前↑"),
        NAME_DESC("名前↓"),
        SIZE_DESC("サイズ大→小"),
        SIZE_ASC("サイズ小→大")
    }

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

    /** プレビュー表示用の選択明細（uri・相対パス・種別）。チェック集合と同値に保つ。 */
    data class CheckedEntry(val uri: String, val relPath: String, val isDirectory: Boolean)
    private val _checkedEntries = MutableStateFlow<List<CheckedEntry>>(emptyList())
    val checkedEntries: StateFlow<List<CheckedEntry>> = _checkedEntries.asStateFlow()

    /** チェック時点のスナップショット(FILE→親畳み込み・表示外チェックの保持用)。 */
    private data class CheckedSnap(val doc: VDoc, val relPath: String, val parentUri: String)
    private val checkedSnaps = mutableMapOf<String, CheckedSnap>()

    private var listGen = 0L

    private val _txtOnly = MutableStateFlow(true)
    val txtOnly: StateFlow<Boolean> = _txtOnly.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /**
     * 範囲選択の起点（長押しで設定）。表示順URI列上の位置を正本にする。
     * 技術的根拠1行：起点をStateに寄せ、短タップ単発と長押し範囲の二系統を共存させる（Fossify/Total Commander式）。
     */
    private val _anchorUri = MutableStateFlow<String?>(null)
    val anchorUri: StateFlow<String?> = _anchorUri.asStateFlow()

    /**
     * 名前絞り込みの正本。表示順・範囲選択・全選択のすべてがこの下のvisibleRowsを参照する。
     * 技術的根拠1行：絞り込みを読込済み行の部分一致に寄せ、Dialog側との二重管理を作らない（追加IPCなし）。
     */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 表示順の唯一正本（ソート済み行＋絞り込み）。選択系はこの順序を正本にする。 */
    // 技術的根拠1行：stateIn共有はテストのrunBlocking常駐で閉塞するため、収集側起点のコールドFlowに寄せる。
    val visibleRows: Flow<List<Row>> =
        combine(_rows, _query) { rows, q -> applyQuery(rows, q) }

    fun setTxtOnly(enabled: Boolean) {
        _txtOnly.value = enabled
        refresh()
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setSortMode(mode: SortMode) {
        if (_sortMode.value == mode) return
        _sortMode.value = mode
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
                // 技術的根拠1行：フォルダ優先は不変とし群内のみ切替える（業界標準の grouping folders 方式）。
                // 技術的根拠1行：名前順は自然順を正本にし、表示区間＝実行順の乖離（10.txtが2.txtより前等）をなくす。
                val mode = _sortMode.value
                val ascNatural: Comparator<VDoc> = Comparator { a, b -> compareNatural(a.name, b.name) }
                val nameCmp: Comparator<VDoc> = if (mode == SortMode.NAME_DESC) ascNatural.reversed() else ascNatural
                val fileCmp: Comparator<VDoc> = when (mode) {
                    SortMode.SIZE_DESC -> compareByDescending<VDoc> { it.length }.then(nameCmp)
                    SortMode.SIZE_ASC -> compareBy<VDoc> { it.length }.then(nameCmp)
                    else -> nameCmp
                }
                _rows.value = docs
                    .filter { d -> if (_txtOnly.value) d.isDirectory || isTxt(d.name) else true }
                    .sortedWith { a, b ->
                        if (a.isDirectory != b.isDirectory) {
                            if (a.isDirectory) -1 else 1
                        } else if (a.isDirectory) {
                            nameCmp.compare(a, b)
                        } else {
                            fileCmp.compare(a, b)
                        }
                    }
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
        // 技術的根拠1行：起点は表示順上の位置のため、階層を跨いだら失効させ次回長押しから再開させる。
        _anchorUri.value = null
        refresh()
    }

    /** @return falseのとき既にルートにいる。 */
    fun goUp(): Boolean {
        if (_crumbs.value.size <= 1) return false
        _crumbs.value = _crumbs.value.dropLast(1)
        _anchorUri.value = null
        refresh()
        return true
    }

    /** @return falseのとき範囲外のため移動しない。 */
    fun goTo(index: Int): Boolean {
        if (index !in _crumbs.value.indices) return false
        _crumbs.value = _crumbs.value.take(index + 1)
        _anchorUri.value = null
        refresh()
        return true
    }

    fun toggle(row: Row) {
        val uri = row.doc.uri
        if (!_checked.value.contains(uri)) {
            accumulate(listOf(row))
            return
        }
        _checked.value = _checked.value - uri
        checkedSnaps.remove(uri)
        // 技術的根拠1行：起点自体を外したら範囲の基準が壊れるため起点も消し、次回長押しから再開させる。
        if (_anchorUri.value == uri) _anchorUri.value = null
        publishCheckedEntries()
    }

    /** 長押し1回目：範囲の起点を設定する（単発選択は変えない）。 */
    fun setAnchor(row: Row) {
        _anchorUri.value = row.doc.uri
    }

    /**
     * 長押し2回目：起点〜終点の表示区間を加算のみで選択し、起点は消費する（複数範囲は起点→終点の繰返し）。
     * @return 新規追加件数。起点不明・表示外時は0で起点を終点に置き換える（次回に繋げる）。
     * 技術的根拠1行：toggle反転では区間選択にならずdragはスクロールと競合するため、長押し2点式に寄せる。
     */
    fun selectRange(visibleRows: List<Row>, target: Row): Int {
        val anchor = _anchorUri.value
        val visibleUris = visibleRows.map { it.doc.uri }
        val slice = rangeSlice(visibleUris, anchor, target.doc.uri)
        if (slice.isEmpty()) {
            _anchorUri.value = target.doc.uri
            return 0
        }
        val byUri = visibleRows.associateBy { it.doc.uri }
        val added = accumulate(slice.mapNotNull { byUri[it] })
        _anchorUri.value = null
        return added
    }

    /**
     * 長押しの振分け（UIはこの1口だけ呼ぶ）。起点なし→起点設定、起点あり→区間確定。
     * @return 0=起点設定、1以上=区間追加件数。
     */
    fun onLongPressRow(visibleRows: List<Row>, row: Row): Int {
        if (_anchorUri.value == null || _anchorUri.value == row.doc.uri) {
            setAnchor(row)
            return 0
        }
        return selectRange(visibleRows, row)
    }

    /** 選択明細の個別解除（プレビューからの取り消し用）。 */
    fun uncheck(uri: String) {
        if (!_checked.value.contains(uri)) return
        _checked.value = _checked.value - uri
        checkedSnaps.remove(uri)
        if (_anchorUri.value == uri) _anchorUri.value = null
        publishCheckedEntries()
    }

    private fun publishCheckedEntries() {
        _checkedEntries.value = checkedSnaps.values
            // 技術的根拠1行：確定順と同一の自然順に寄せ、表示＝投入の乖離（10話が2話より前等）をなくす。
            .sortedWith { a, b -> compareNatural(a.relPath, b.relPath) }
            .map { CheckedEntry(it.doc.uri, it.relPath, it.doc.isDirectory) }
    }

    fun clearChecked() {
        _checked.value = emptySet()
        checkedSnaps.clear()
        _anchorUri.value = null
        publishCheckedEntries()
    }

    /**
     * 加算の唯一入口（toggle追加・範囲・全選択の重複排除）。新規追加件数を返す。
     * 技術的根拠1行：スナップショット付与を1箇所に寄せ、将来の保持項目追加時の乖離を作らない。
     */
    private fun accumulate(rows: List<Row>): Int {
        if (rows.isEmpty()) return 0
        val parent = _crumbs.value.lastOrNull()?.uri ?: treeUri
        val cur = _checked.value.toMutableSet()
        var added = 0
        for (row in rows) {
            if (cur.add(row.doc.uri)) {
                checkedSnaps[row.doc.uri] = CheckedSnap(row.doc, row.relPath, parent)
                added++
            }
        }
        if (added == 0) return 0
        _checked.value = cur
        publishCheckedEntries()
        return added
    }

    /**
     * 表示中の行を一括選択する（累積・冪等）。実体は加算の唯一入口に寄せる。
     */
    fun selectAllVisible(rows: List<Row>) {
        accumulate(rows)
    }

    /**
     * チェック済みを確定列へ変換する同期的確定（IOなし・保持済み状態のみ参照）。
     * 展開はしない（明示集合が正本）。フォルダ=直下全件意図（実行系が直下のみ列挙）、ファイル=単体。孫以下は含めない。
     * 除外物・上限超過は落として結果に記録する(無言で捨てない)。
     * 技術的根拠1行：確定は保持済みスナップショットの畳み込みのみで解決するため、スレッド移動を作らない。
     */
    fun collectTargets(): CollectResult {
        val targets = ArrayList<PickerTarget>()
        val skipped = ArrayList<String>()
        var truncated = false
        var fileCount = 0
        var dirCount = 0

        // 技術的根拠1行：選択集合は順序を持たないため、投入順の正本として自然順の相対パス順に確定し再現性を保つ。
        val ordered = _checked.value.mapNotNull { uri ->
            val snap = checkedSnaps[uri]
            if (snap == null) {
                Log.w(TAG, "checked snapshot lost: $uri")
                skipped.add("$uri: 選択情報の消失のため除外")
                null
            } else snap
        }.sortedWith { a, b -> compareNatural(a.relPath, b.relPath) }

        for (snap in ordered) {
            val row = Row(snap.doc, snap.relPath)
            val parentUri = snap.parentUri
            // 技術的根拠1行：成果物配下のdir直指定・file直指定の双方を経路1則で落とし、再翻訳ループを作らない。
            if (isExcludedPath(row.relPath, extraExcludedDirs)) {
                skipped.add("${row.relPath}: 出力・中間物のため除外")
                continue
            }
            if (row.doc.isDirectory) {
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
        return CollectResult(targets, fileCount, dirCount, truncated, skipped)
    }

    private fun isTxt(name: String): Boolean = name.endsWith(".txt", ignoreCase = true)
}
