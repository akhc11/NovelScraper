package com.example.novelscraper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * ScraperService のライフサイクル制御およびフォアグラウンド通知管理をカプセル化するコントローラー。
 * ViewModel から Android Framework の低レベル Service 制御を分離する。
 */
class ScraperServiceController(private val context: Context) {

    /**
     * フォアグラウンドサービスの通知メッセージを更新（未起動の場合は起動）
     */
    fun updateNotification(message: String) {
        try {
            val intent = Intent(context, ScraperService::class.java).apply {
                action = ScraperService.ACTION_UPDATE_STATUS
                putExtra(ScraperService.EXTRA_MSG, message)
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
     * サービスを停止
     */
    fun stopService() {
        try {
            val stopIntent = Intent(context, ScraperService::class.java)
            context.stopService(stopIntent)
        } catch (_: Exception) {
            // 停止時の例外を安全に吸収
        }
    }
}
