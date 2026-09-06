package com.example.novelscraper.translation.v2.infra

/**
 * 文書ハンドル。実体（SAF DocumentFile等）に依存しない不変の参照情報。
 * 一覧取得時点のスナップショットであり、存在保証はしない（I/O時に再確認する）。
 */
data class VDoc(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val length: Long = 0L
)

/**
 * ファイル倉庫の最小契約。SAF・テスト用メモリ実装が満たす。
 * 失敗は例外ではなく null/false で返す（理由はログ層の責務）。
 * すべての操作は呼出側スレッドで実行される前提（IO切替は呼出側）。
 */
interface FileStore {
    suspend fun children(dirUri: String): List<VDoc>
    suspend fun readText(fileUri: String): String?
    /**
     * 生バイト読込（取込の文字コード判定用）。巨大ファイル対策に上限付き。
     * 技術的根拠1行：文字コード判定は復号前のバイト列で行う必要があり、
     * 復号済みreadTextでは文字化けの有無を判定できない。
     */
    suspend fun readBytes(fileUri: String, maxBytes: Int = 64 * 1024 * 1024 + 1): ByteArray?
    suspend fun writeText(fileUri: String, content: String): Boolean
    /** 追記（結合のストリーミング用）。未対応時は上書き＋再読込で代替すること */
    suspend fun appendText(fileUri: String, content: String): Boolean
    suspend fun findChild(dirUri: String, name: String): VDoc?
    suspend fun createDir(parentUri: String, name: String): VDoc?
    suspend fun createFile(dirUri: String, name: String, mime: String): VDoc?
    suspend fun deleteRecursively(dirUri: String): Boolean
    suspend fun deleteFile(fileUri: String): Boolean
}
