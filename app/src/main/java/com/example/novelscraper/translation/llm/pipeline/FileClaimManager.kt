package com.example.novelscraper.translation.llm.pipeline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 複数ワーカー (Coroutine Worker) 間で同一ファイルの同時着手を防ぐ排他制御マネージャー。
 * bash スクリプト _try_claim_file と同一のアトミック排他仕様。
 */
class FileClaimManager {
    private val mutex = Mutex()
    private val claimedFiles = mutableSetOf<String>()

    /**
     * ファイルの排他着手権 (Claim) を試行取得する。
     * 未着手であれば true を返し、クレーム済みに登録する。
     * 既に他ワーカーが着手していれば false を返す。
     */
    suspend fun tryClaimFile(folderName: String, fileName: String): Boolean = mutex.withLock {
        val key = "$folderName/$fileName"
        if (claimedFiles.contains(key)) {
            false
        } else {
            claimedFiles.add(key)
            true
        }
    }

    /**
     * クレームをクリアする (フォルダ切替時や処理終了時)。
     */
    suspend fun clear() = mutex.withLock {
        claimedFiles.clear()
    }
}