package com.example.novelscraper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ScraperService : Service() {
    companion object {
        const val CHANNEL_ID_SERVICE = "ScraperChannel_Service"
        const val CHANNEL_ID_COMPLETE = "ScraperChannel_Complete"

        const val ACTION_UPDATE_STATUS = "com.example.novelscraper.UPDATE_STATUS"
        const val ACTION_SHOW_COMPLETE = "com.example.novelscraper.SHOW_COMPLETE"
        const val EXTRA_MSG = "extra_msg"
        const val EXTRA_TITLE = "extra_title"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelScraper::Wakelock")
        wakeLock?.acquire(30 * 60 * 1000L) // 30分で自動解放（onStartCommandで再延長される）
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_SHOW_COMPLETE -> {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "ダウンロード完了"
                    val msg = intent.getStringExtra(EXTRA_MSG) ?: "すべてのタスクが完了しました"
                    showCompletionNotification(title, msg)
                }
                else -> {
                    val msg = intent.getStringExtra(EXTRA_MSG) ?: "バックグラウンドで実行中..."
                    updateForegroundNotification(msg)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    // ID:1 で通知を更新し続ける（これで件数が変わる）
    private fun updateForegroundNotification(text: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("NovelScraper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(1, notification)
    }

    private fun showCompletionNotification(title: String, msg: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETE)
            .setContentTitle(title)
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE, "Scraper Running", NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(serviceChannel)
            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE, "Download Complete", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
            manager?.createNotificationChannel(completeChannel)
        }
    }
}
