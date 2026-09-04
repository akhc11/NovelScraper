package com.example.novelscraper.translation.llm.pipeline

import java.util.concurrent.ConcurrentHashMap

/**
 * 複数ワーカー (Coroutine Worker) 間で同一ファイルの同時着手を防ぐ排他制御マネージャー。
 * フォルダの一意な識別子 (URI文字列) とファイル名を組み合わせることで、
 * 同名フォルダを跨いだ際のキー衝突や誤スキップを防止する。
 *
 * suspended 関数を持たない設計 (ConcurrentHashMap の原子操作のみ) のため、
 * キャンセル中の `finally` からでも確実に解放できる。
 * インスタンスは翻訳実行ジョブ単位で生成し、フィールド保持しないこと。
 */
class FileClaimManager {
    private val claimedFiles = ConcurrentHashMap<String, Boolean>()

    /**
     * ファイルの排他着手権 (Claim) を試行取得する。
     * 未着手であれば true を返し、クレーム済みに登録する。
     * 既に他ワーカーが着手していれば false を返す。
     * @param folderKey フォルダの一意な識別子 (URI文字列推奨)
     * @param fileName ファイル名
     */
    fun tryClaimFile(folderKey: String, fileName: String): Boolean =
        claimedFiles.putIfAbsent("$folderKey/$fileName", true) == null

    /**
     * 単一ファイルのクレームを解放する。未取得でも何もしない。
     */
    fun releaseFile(folderKey: String, fileName: String) {
        claimedFiles.remove("$folderKey/$fileName")
    }

    /**
     * クレーム取得から解放までを構造的に保証する。
     * 取得失敗時は (false, null) を返し block を実行しない。
     * block の早期リターン・例外・キャンセルのいずれでも解放される。
     */
    inline fun <T> withClaim(folderKey: String, fileName: String, block: () -> T): Pair<Boolean, T?> {
        if (!tryClaimFile(folderKey, fileName)) return false to null
        return try {
            true to block()
        } finally {
            releaseFile(folderKey, fileName)
        }
    }
}
