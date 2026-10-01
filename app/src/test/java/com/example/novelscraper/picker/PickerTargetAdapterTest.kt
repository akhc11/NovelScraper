package com.example.novelscraper.picker

import com.example.novelscraper.translation.picker.PickerKind
import com.example.novelscraper.translation.picker.PickerTarget
import com.example.novelscraper.translation.picker.PickerTargetAdapter
import org.junit.Assert.*
import org.junit.Test

class PickerTargetAdapterTest {

    private fun root() = PickerTarget(
        kind = PickerKind.ROOT,
        docUri = "tree://root",
        treeUri = "tree://root",
        displayName = "小説"
    )

    private fun folder() = PickerTarget(
        kind = PickerKind.FOLDER,
        docUri = "doc://a",
        treeUri = "tree://root",
        relPath = "A",
        displayName = "A"
    )

    private fun file() = PickerTarget(
        kind = PickerKind.FILE,
        docUri = "doc://a1",
        treeUri = "tree://root",
        parentDocUri = "doc://a",
        relPath = "A/a1.txt",
        displayName = "a1.txt"
    )

    /** JVM単体テストではUri.parseが既定値(null)を返すため、URI一致は寛容に判定する。 */
    private fun assertUriEquals(expected: String, actual: android.net.Uri?) {
        assertTrue(actual == null || actual.toString() == expected)
    }

    @Test
    fun web_foldsLoneFileToParentFolder() {
        // WEB実行系はフォルダ列挙前提のため、ファイル単体は親フォルダに畳み、ファイルURIは残さない。
        val lone = file().copy(parentDocUri = "doc://other", relPath = "Other/a1.txt")
        val items = PickerTargetAdapter.toWebFolderItems(listOf(root(), folder(), lone))
        assertEquals(3, items.size)
        assertEquals("tree://root", items[0].path)
        assertEquals("小説", items[0].name)
        assertUriEquals("tree://root", items[0].uri)
        assertEquals("doc://a", items[1].path)
        assertEquals("A", items[1].name)
        assertUriEquals("doc://a", items[1].uri)
        // 親フォルダに畳まれる(FOLDER品目と同形)。
        assertEquals("doc://other", items[2].path)
        assertEquals("Other", items[2].name)
        assertUriEquals("doc://other", items[2].uri)
    }

    @Test
    fun web_dropsFileCoveredByIncludedFolder() {
        // 投入済みフォルダ直下のファイルは親側の展開で網羅されるため落とす(二重翻訳防止)。
        val items = PickerTargetAdapter.toWebFolderItems(listOf(folder(), file()))
        assertEquals(1, items.size)
        assertEquals("doc://a", items.single().path)
    }

    @Test
    fun web_dropsOrphanFile() {
        // 親不明FILEは投入不能のため落とす。
        val orphan = file().copy(parentDocUri = "")
        assertTrue(PickerTargetAdapter.toWebFolderItems(listOf(orphan)).isEmpty())
    }

    @Test
    fun web_foldsNestedFileToSubfolder() {
        // チェック済みフォルダF + 孫FILE(親=sub): subフォルダ品目になり、ファイルURIは残らない。
        val nested = file().copy(
            docUri = "doc://s1",
            parentDocUri = "doc://sub",
            relPath = "A/Sub/s1.txt",
            displayName = "s1.txt"
        )
        val items = PickerTargetAdapter.toWebFolderItems(listOf(folder(), nested))
        assertEquals(2, items.size)
        assertEquals("doc://a", items[0].path)
        assertEquals("doc://sub", items[1].path)
        assertEquals("Sub", items[1].name)
    }

    @Test
    fun web_dedupesSameUri() {
        val lone = file().copy(parentDocUri = "doc://other")
        val items = PickerTargetAdapter.toWebFolderItems(listOf(folder(), folder(), lone, lone))
        assertEquals(2, items.size)
    }

    @Test
    fun web_blankNamesFallback() {
        val items = PickerTargetAdapter.toWebFolderItems(
            listOf(root().copy(displayName = ""), folder().copy(displayName = "", relPath = ""))
        )
        assertEquals("選択フォルダ", items[0].name)
        assertEquals("選択フォルダ", items[1].name)
    }

    @Test
    fun v2_foldsFileToParent() {
        val entries = PickerTargetAdapter.toV2FolderEntries(listOf(root(), folder(), file()))
        // ROOT(tree)+FOLDER(a)+FILE→親(aに畳まれ重複除去)。
        assertEquals(listOf("tree://root", "doc://a"), entries.map { it.first })
        assertEquals("A", entries[1].second)
    }

    @Test
    fun v2_dropsOrphanFile() {
        val orphan = file().copy(parentDocUri = "")
        val entries = PickerTargetAdapter.toV2FolderEntries(listOf(orphan))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun v2_dedupesAndSkipsBlank() {
        val entries = PickerTargetAdapter.toV2FolderEntries(
            listOf(folder(), folder(), PickerTarget(PickerKind.FOLDER, "", "tree://root"))
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun v2Targets_singleFileKeepsAllowList() {
        // ファイル1件選択→親に畳むがfileUrisに1件保持し、同胞巻き込みを防ぐ。
        val targets = PickerTargetAdapter.toV2FolderTargets(listOf(file()))
        assertEquals(1, targets.size)
        assertEquals("doc://a", targets[0].folderUri)
        assertEquals(setOf("doc://a1"), targets[0].fileUris)
    }

    @Test
    fun v2Targets_folderAloneMeansAll() {
        // FOLDER単体(子FILEなし)は空集合=全件扱い(従来動作)。
        val targets = PickerTargetAdapter.toV2FolderTargets(listOf(folder()))
        assertEquals(1, targets.size)
        assertTrue(targets[0].fileUris.isEmpty())
    }

    @Test
    fun v2Targets_groupsByParent() {
        // A直下2件+B配下1件→親ごとにグルーピングし、親フォルダ自体は増やさない。
        val bFile = file().copy(
            docUri = "doc://b1",
            parentDocUri = "doc://b",
            relPath = "A/B/b1.txt",
            displayName = "b1.txt"
        )
        val a2 = file().copy(docUri = "doc://a2", relPath = "A/a2.txt", displayName = "a2.txt")
        val targets = PickerTargetAdapter.toV2FolderTargets(listOf(file(), a2, bFile))
        assertEquals(2, targets.size)
        val byUri = targets.associateBy { it.folderUri }
        assertEquals(setOf("doc://a1", "doc://a2"), byUri["doc://a"]!!.fileUris)
        assertEquals(setOf("doc://b1"), byUri["doc://b"]!!.fileUris)
    }

    // ---- 実URI形でのツリー契約検証 ----

    private val realTree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private val realFolderDoc =
        "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnovel"
    private val realFileDoc =
        "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnovel%2F001.txt"
    private val realFolderTreeChild =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fnovel"
    private val realFileTreeChild =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fnovel%2F001.txt"

    private fun realFolder() = PickerTarget(
        kind = PickerKind.FOLDER,
        docUri = realFolderDoc,
        treeUri = realTree,
        relPath = "novel",
        displayName = "novel"
    )

    private fun realFile() = PickerTarget(
        kind = PickerKind.FILE,
        docUri = realFileDoc,
        treeUri = realTree,
        parentDocUri = realFolderDoc,
        relPath = "novel/001.txt",
        displayName = "001.txt"
    )

    @Test
    fun treeChild_convertsDocumentToTreeForm() {
        assertEquals(
            realFolderTreeChild,
            PickerTargetAdapter.toTreeChildUri(realTree, realFolderDoc)
        )
        assertEquals(
            realFileTreeChild,
            PickerTargetAdapter.toTreeChildUri(realTree, realFileDoc)
        )
    }

    @Test
    fun treeChild_passthroughAndRejects() {
        // ツリー形・fileスキームはそのまま
        assertEquals(realTree, PickerTargetAdapter.toTreeChildUri(realTree, realTree))
        assertEquals(
            "file:///sdcard/novel",
            PickerTargetAdapter.toTreeChildUri(realTree, "file:///sdcard/novel")
        )
        // 変換不能はnull(呼出側は従来値に退行)
        assertNull(PickerTargetAdapter.toTreeChildUri("", realFolderDoc))
        assertNull(PickerTargetAdapter.toTreeChildUri(realTree, ""))
        assertNull(PickerTargetAdapter.toTreeChildUri(realTree, "doc://a"))
        assertNull(
            PickerTargetAdapter.toTreeChildUri(
                realTree,
                "content://com.other.provider/document/abc"
            )
        )
    }

    @Test
    fun web_emitsTreeFormUris() {
        val items = PickerTargetAdapter.toWebFolderItems(listOf(realFolder(), realFile()))
        // FILEは投入済みフォルダ直下のため畳まれ、FOLDER1件のみ
        assertEquals(1, items.size)
        assertEquals(realFolderDoc, items[0].path)
        // uriはJVM上null許容だが、pathと対でツリー契約の文字列検証はv2側で行う
        val lone = realFile().copy(parentDocUri = realFolderDoc.replace("novel", "other"))
        val items2 = PickerTargetAdapter.toWebFolderItems(listOf(lone))
        assertEquals(1, items2.size)
        // 親フォルダに畳む(FOLDER品目と同形: pathは文書URI)。
        assertEquals(realFolderDoc.replace("novel", "other"), items2[0].path)
    }

    @Test
    fun v2_emitsTreeFormUris() {
        val entries = PickerTargetAdapter.toV2FolderEntries(listOf(realFolder(), realFile()))
        // FILEは親に畳まれ重複除去でFOLDER1件
        assertEquals(listOf(realFolderTreeChild), entries.map { it.first })
        assertEquals("novel", entries[0].second)
    }
}
