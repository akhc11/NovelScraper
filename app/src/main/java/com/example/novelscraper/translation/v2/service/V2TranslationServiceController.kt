package com.example.novelscraper.translation.v2.service

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * V2TranslationService のライフサイクル制御およびフォアグラウンド通知管理をカプセル化するコントローラー。
 * ViewModel から Android Framework の低レベル Service 制御を分離する。
 */
class V2TranslationServiceController(private val context: Context) {

    /**
     * フォアグラウンドサービスの通知メッセージを更新（未起動の場合は起動）
     */
    fun updateNotification(title: String, message: String, current: Int = 0, total: Int = 0) {
        try {
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
        } catch (_: Exception) {
            // バックグラウンド制限等による例外を安全に吸収
        }
    }

    /**
     * 完了通知を表示してサービスを終了
     */
    fun showComplete(title: String, message: String) {
        try {
            val intent = Intent(context, V2TranslationService::class.java).apply {
                action = V2TranslationService.ACTION_SHOW_COMPLETE
                putExtra(V2TranslationService.EXTRA_TITLE, title)
                putExtra(V2TranslationService.EXTRA_MSG, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * サービスを停止
     */
    fun stopService() {
        try {
            val stopIntent = Intent(context, V2TranslationService::class.java)
            context.stopService(stopIntent)
        } catch (_: Exception) {
        }
    }
}
