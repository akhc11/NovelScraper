package com.example.novelscraper

import com.example.novelscraper.translation.common.compareNatural
import org.junit.Assert.*
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun numericSegmentsCompareNumerically() {
        assertTrue(compareNatural("2.txt", "10.txt") < 0)
        assertTrue(compareNatural("10.txt", "2.txt") > 0)
        assertEquals(0, compareNatural("002.txt", "2.txt"))
    }

    @Test
    fun sortsChapterSequenceNaturally() {
        val names = listOf("10.txt", "2.txt", "1.txt", "20.txt", "3.txt")
        assertEquals(
            listOf("1.txt", "2.txt", "3.txt", "10.txt", "20.txt"),
            names.sortedWith(Comparator(::compareNatural))
        )
    }

    @Test
    fun nonNumericComparesCaseInsensitively() {
        assertTrue(compareNatural("a.txt", "B.txt") < 0)
        assertEquals(0, compareNatural("Note.txt", "note.TXT"))
    }
}
