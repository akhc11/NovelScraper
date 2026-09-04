package com.example.novelscraper.translation.llm.pipeline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 複数ワーカー (Coroutine Worker) 間で同一ファイルの同時着手を防ぐ排他制御マネージャー。
 * フォルダの一意な識別子 (URI文字列) とファイル名を組み合わせることで、
 * 同名フォルダを跨いだ際のキー衝突や誤スキップを完全に防止する。
 */
class FileClaimManager {
    private val mutex = Mutex()
    private val claimedFiles = mutableSetOf<String>()

    /**
     * ファイルの排他着手権 (Claim) を試行取得する。
     * 未着手であれば true を返し、クレーム済みに登録する。
     * 既に他ワーカーが着手していれば false を返す。
     * @param folderKey フォルダの一意な識別子 (URI文字列推奨)
     * @param fileName ファイル名
     */
    suspend fun tryClaimFile(folderKey: String, fileName: String): Boolean = mutex.withLock {
        val key = "$folderKey/$fileName"
        if (claimedFiles.contains(key)) {
            false
        } else {
            claimedFiles.add(key)
            true
        }
    }

    /**
     * 特定フォルダのクレームを解放する (フォルダ処理完了時)。
     */
    suspend fun releaseFolder(folderKey: String) = mutex.withLock {
        val prefix = "$folderKey/"
        claimedFiles.removeAll { it.startsWith(prefix) }
    }

    /**
     * 全クレームをクリアする (セッション終了時)。
     */
    suspend fun clear() = mutex.withLock {
        claimedFiles.clear()
    }
}
