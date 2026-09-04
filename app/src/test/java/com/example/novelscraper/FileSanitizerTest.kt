package com.example.novelscraper

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSanitizerTest {

    private val sanitizeRegex = Regex("[\\\\/:*?\"<>|\\r\\n]")
    private val DEFAULT_FOLDER_NAME = "ダウンロード小説"

    private fun generateFileName(title: String, chapterNum: String): String {
        var fileName = title.replace(sanitizeRegex, "").trim()
        if (chapterNum.isNotEmpty()) fileName = "${chapterNum}_${fileName}"
        return "$fileName.txt"
    }

    private fun sanitizeFolderName(folderName: String): String {
        return folderName.replace(sanitizeRegex, "").trim().ifEmpty { DEFAULT_FOLDER_NAME }
    }

    @Test
    fun testSanitizeInvalidFileNameCharacters() {
        // Windows/Android 禁則文字: \ / : * ? " < > | および 改行
        val dangerousTitle = "第1話: 始まりの朝 / 旅立ち? *謎の男* <警告> \"危険\" | 改行\r\nあり"
        val fileName = generateFileName(dangerousTitle, "0001")

        assertEquals("0001_第1話 始まりの朝  旅立ち 謎の男 警告 危険  改行あり.txt", fileName)
    }

    @Test
    fun testGenerateFileName_withChapterAndWithoutChapter() {
        // 連番あり
        val withChapter = generateFileName("プロローグ", "0001")
        assertEquals("0001_プロローグ.txt", withChapter)

        // 連番なし（短編など）
        val withoutChapter = generateFileName("短編小説", "")
        assertEquals("短編小説.txt", withoutChapter)
    }

    @Test
    fun testSanitizeFolderName_fallbackOnEmptyOrForbiddenCharsOnly() {
        // 禁則文字のみのフォルダ名
        val forbiddenOnly = "////:::***???"
        val safeFolder1 = sanitizeFolderName(forbiddenOnly)
        assertEquals(DEFAULT_FOLDER_NAME, safeFolder1)

        // 空文字
        val empty = ""
        val safeFolder2 = sanitizeFolderName(empty)
        assertEquals(DEFAULT_FOLDER_NAME, safeFolder2)

        // 正常なタイトル
        val normal = "無職転生 〜異世界行ったら本気だす〜"
        val safeFolder3 = sanitizeFolderName(normal)
        assertEquals("無職転生 〜異世界行ったら本気だす〜", safeFolder3)
    }
}
