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
    fun web_keepsRootFolderFile() {
        // FILEの親が投入済みフォルダに含まれない場合は維持される。
        val lone = file().copy(parentDocUri = "doc://other")
        val items = PickerTargetAdapter.toWebFolderItems(listOf(root(), folder(), lone))
        assertEquals(3, items.size)
        assertEquals("tree://root", items[0].path)
        assertEquals("小説", items[0].name)
        assertUriEquals("tree://root", items[0].uri)
        assertEquals("doc://a", items[1].path)
        assertEquals("A", items[1].name)
        assertUriEquals("doc://a", items[1].uri)
        // ファイル単体は親ツリーをpathに維持する(既存web分割フローと同形)。
        assertEquals("tree://root", items[2].path)
        assertEquals("a1.txt", items[2].name)
        assertUriEquals("doc://a1", items[2].uri)
    }

    @Test
    fun web_dropsFileCoveredByIncludedFolder() {
        // 投入済みフォルダ直下のファイルは親側の展開で網羅されるため落とす(二重翻訳防止)。
        val items = PickerTargetAdapter.toWebFolderItems(listOf(folder(), file()))
        assertEquals(1, items.size)
        assertEquals("doc://a", items.single().path)
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
        // 親は所属ツリー起点のツリー形(分割出力先の解決用)
        assertEquals(realFolderTreeChild.replace("novel", "other"), items2[0].path)
    }

    @Test
    fun v2_emitsTreeFormUris() {
        val entries = PickerTargetAdapter.toV2FolderEntries(listOf(realFolder(), realFile()))
        // FILEは親に畳まれ重複除去でFOLDER1件
        assertEquals(listOf(realFolderTreeChild), entries.map { it.first })
        assertEquals("novel", entries[0].second)
    }
}
