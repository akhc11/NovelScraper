package com.example.novelscraper.translation.v2.service

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * V2TranslationService のライフサイクル制御およびフォアグラウンド通知管理をカプセル化するコントローラー。
 * ViewModel から Android Framework の低レベル Service 制御を分離する。
 */
class V2TranslationServiceController(private val context: Context) {

    private var lastPostMs: Long = 0L

    /**
     * フォアグラウンドサービスの通知メッセージを更新（未起動の場合は起動）。
     * 技術的根拠1行：進捗のたびに起動要求を出すとOS側のスロットル対象になるため、1秒未満の連投は最終到達以外まとめ、成否を返す。
     */
    fun updateNotification(title: String, message: String, current: Int = 0, total: Int = 0): Boolean {
        val now = System.currentTimeMillis()
        val isFinal = total > 0 && current >= total
        if (!isFinal && now - lastPostMs < 1000L) return true
        lastPostMs = now
        return try {
            val intent = Intent(context, V2TranslationService::class.java).apply {
                action = V2TranslationService.ACTION_UPDATE_STATUS
                putExtra(V2TranslationService.EXTRA_TITLE, title)
                putExtra(V2TranslationService.EXTRA_MSG, message)
                putExtra(V2TranslationService.EXTRA_PROGRESS_CURRENT, current)
                putExtra(V2TranslationService.EXTRA_PROGRESS_TOTAL, total)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            // 技術的根拠1行：バックグラウンド制限等は戻り値falseで吸収しつつ原因をLogcatに残す（外部振る舞い不変）。
            android.util.Log.w("V2ServiceController", "updateNotification failed", e)
            false
        }
    }

    /**
     * 完了通知を表示してサービスを終了。
     * 技術的根拠1行：完了時にstartForegroundServiceを呼ぶとstopSelfによるOS即死クラッシュを招くため、サービス停止後にNotificationManagerから直接投稿する。
     */
    fun showComplete(title: String, message: String): Boolean {
        stopService()
        return try {
            V2TranslationService.showCompletionNotification(context, title, message)
            true
        } catch (e: Exception) {
            android.util.Log.w("V2ServiceController", "showComplete failed", e)
            false
        }
    }

    /**
     * サービスを停止
     */
    fun stopService(): Boolean {
        return try {
            val stopIntent = Intent(context, V2TranslationService::class.java)
            context.stopService(stopIntent)
            true
        } catch (e: Exception) {
            android.util.Log.w("V2ServiceController", "stopService failed", e)
            false
        }
    }
}
