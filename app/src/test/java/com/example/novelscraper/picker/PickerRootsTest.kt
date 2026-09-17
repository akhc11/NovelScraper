package com.example.novelscraper.picker

import com.example.novelscraper.translation.picker.decodeRootsLenient
import com.example.novelscraper.translation.picker.mergeGrantedRoot
import com.example.novelscraper.translation.picker.sortRootsByRecency
import org.junit.Assert.*
import org.junit.Test

class PickerRootsTest {

    @Test
    fun decode_blankIsEmpty() {
        assertEquals(emptyList<Any>(), decodeRootsLenient(""))
        assertEquals(emptyList<Any>(), decodeRootsLenient("[]"))
    }

    @Test
    fun decode_corruptIsEmpty() {
        // 破損時は空表示に倒し、永続側の上書き中断はリポジトリ層の責務。
        assertTrue(decodeRootsLenient("{broken").isEmpty())
    }

    @Test
    fun decode_roundTrip() {
        val merged = mergeGrantedRoot(emptyList(), "tree://a", "A", 1L)
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                com.example.novelscraper.translation.picker.GrantedRoot.serializer()
            ),
            merged
        )
        val decoded = decodeRootsLenient(json)
        assertEquals(1, decoded.size)
        assertEquals("tree://a", decoded[0].treeUri)
        assertEquals("A", decoded[0].displayName)
    }

    @Test
    fun merge_dedupesToLatest() {
        val first = mergeGrantedRoot(emptyList(), "tree://a", "A", 1L)
        val second = mergeGrantedRoot(first, "tree://b", "B", 2L)
        val third = mergeGrantedRoot(second, "tree://a", "A2", 3L)
        assertEquals(listOf("tree://b", "tree://a"), third.map { it.treeUri })
        assertEquals("A2", third.last().displayName)
        assertEquals(3L, third.last().grantedAt)
    }

    @Test
    fun merge_blankUriIgnored() {
        val base = mergeGrantedRoot(emptyList(), "tree://a", "A", 1L)
        assertEquals(base, mergeGrantedRoot(base, "", "X", 2L))
        assertEquals(base, mergeGrantedRoot(base, "   ".trim(), "X", 2L))
    }

    @Test
    fun merge_blankNameFallback() {
        val merged = mergeGrantedRoot(emptyList(), "tree://a", "", 1L)
        assertEquals("選択フォルダ", merged.single().displayName)
    }

    @Test
    fun sort_newestFirst() {
        val roots = listOf(
            mergeGrantedRoot(emptyList(), "tree://a", "A", 1L),
            mergeGrantedRoot(emptyList(), "tree://b", "B", 3L),
            mergeGrantedRoot(emptyList(), "tree://c", "C", 2L)
        ).flatten()
        assertEquals(listOf("tree://b", "tree://c", "tree://a"), sortRootsByRecency(roots).map { it.treeUri })
    }
}
