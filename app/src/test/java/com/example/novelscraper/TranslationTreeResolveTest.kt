package com.example.novelscraper

import com.example.novelscraper.translation.web.percentDecode
import com.example.novelscraper.translation.web.splitTreeChildUri
import org.junit.Assert.*
import org.junit.Test

/**
 * SAF子フォルダ解決の純粋部検証。
 * 技術的根拠1行：子URI直渡しはルート化けの再発源のため、分解の正しさをここで縛る(詳細はresolveTreeFolder契約)。
 */
class TranslationTreeResolveTest {

    private val root = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test
    fun testSplit_RootItselfIsNull() {
        // ルートURI自体は対象外(fromTreeUri直で正しい)。
        assertNull(splitTreeChildUri(root))
        assertNull(splitTreeChildUri("content://x/tree/abc"))
    }

    @Test
    fun testSplit_ChildEncodedForm() {
        // 通常形(%2Fで1セグメント化)。文書ID全体で剥がすこと。
        val child = "$root/document/primary%3ADownload%2Fnovel"
        val split = splitTreeChildUri(child)
        assertNotNull(split)
        assertEquals(root, split!!.first)
        assertEquals(listOf("novel"), split.second)
    }

    @Test
    fun testSplit_ChildNested() {
        val child = "$root/document/primary%3ADownload%2Fnovel%2Fch1"
        val split = splitTreeChildUri(child)
        assertNotNull(split)
        assertEquals(listOf("novel", "ch1"), split!!.second)
    }

    @Test
    fun testSplit_ChildItselfIsRoot() {
        // 子形のルート自体は空相対で返す。
        val child = "$root/document/primary%3ADownload"
        val split = splitTreeChildUri(child)
        assertNotNull(split)
        assertTrue(split!!.second.isEmpty())
    }

    @Test
    fun testSplit_ForeignTreeIsNull() {
        // 別ツリーはfail-closed。
        val child = "$root/document/primary%3AOther%2Fnovel"
        assertNull(splitTreeChildUri(child))
    }

    @Test
    fun testSplit_NonTreeIsNull() {
        assertNull(splitTreeChildUri("content://x/document/abc"))
        assertNull(splitTreeChildUri("file:///sdcard/novel"))
        assertNull(splitTreeChildUri(""))
    }

    @Test
    fun testPercentDecode_Multibyte() {
        assertEquals("a b", percentDecode("a%20b"))
        assertEquals("日本語", percentDecode("%E6%97%A5%E6%9C%AC%E8%AA%9E"))
        assertEquals("plain", percentDecode("plain"))
        // 不正%はそのまま残す(落とさない)。
        assertEquals("100%xy", percentDecode("100%xy"))
    }
}
