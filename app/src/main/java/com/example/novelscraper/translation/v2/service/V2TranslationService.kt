package com.example.novelscraper.translation.v2.service

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
import com.example.novelscraper.MainActivity

/**
 * LLM翻訳用フォアグラウンドサービス。
 * 画面スリープ時や別アプリ起動中の OS によるタスクキルや通信切断を低減し、
 * 通知バーに進捗表示および停止操作を提供する（30分枠・kill後非復帰のため防止ではない）。
 */
class V2TranslationService : Service() {

    companion object {
        const val CHANNEL_ID_SERVICE = "TranslationChannel_Service"
        const val CHANNEL_ID_COMPLETE = "TranslationChannel_Complete"

        const val ACTION_UPDATE_STATUS = "com.example.novelscraper.translation.UPDATE_STATUS"
        const val ACTION_SHOW_COMPLETE = "com.example.novelscraper.translation.SHOW_COMPLETE"
        const val ACTION_STOP_TRANSLATION = "com.example.novelscraper.translation.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MSG = "extra_msg"
        const val EXTRA_PROGRESS_CURRENT = "extra_progress_current"
        const val EXTRA_PROGRESS_TOTAL = "extra_progress_total"

        const val NOTIFICATION_ID_FOREGROUND = 2
        const val NOTIFICATION_ID_COMPLETE = 1002

        /** 通知の停止ボタン押下時に ViewModel へ通知するコールバック */
        var onStopRequested: (() -> Unit)? = null

        /** チャンネル初期化（Controllerの直接完了通知投稿時にも安全に呼べるよう共有） */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                val serviceChannel = NotificationChannel(
                    CHANNEL_ID_SERVICE, "LLM翻訳 実行中", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "LLM翻訳の進行状況を表示します"
                }
                manager.createNotificationChannel(serviceChannel)

                val completeChannel = NotificationChannel(
                    CHANNEL_ID_COMPLETE, "LLM翻訳 完了通知", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "LLM翻訳の完了を通知します"
                    enableVibration(true)
                }
                manager.createNotificationChannel(completeChannel)
            }
        }

        /** 完了通知の生成・表示（ControllerとService共通の単一実装） */
        fun showCompletionNotification(context: Context, title: String, msg: String) {
            ensureChannels(context)
            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
                .setContentTitle(title)
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID_COMPLETE, notification)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelScraper::TranslationWakelock")
        wakeLock?.acquire(30 * 60 * 1000L) // 30分で自動解放（onStartCommandで再延長）

        // 技術的根拠1行：startForegroundService直後の破棄・停止によるRemoteServiceException即死クラッシュを構造的に防止するため生成直後に昇格する。
        val initNotification = buildForegroundNotification("LLM小説翻訳", "開始準備中...", 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID_FOREGROUND, initNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, initNotification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wakelockを更新・延長（スリープによる通信停止を防止）
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock?.acquire(30 * 60 * 1000L)
        } catch (e: Exception) {
            // 技術的根拠1行：Wakelock失敗を無言化せず記録するが起動処理は継続する（外部振る舞い不変）。
            android.util.Log.w("V2TranslationService", "wakelock acquire failed", e)
        }

        if (intent != null) {
            when (intent.action) {
                ACTION_STOP_TRANSLATION -> {
                    // 技術的根拠1行：陳腐コールバックの残存を防ぐため通知は使い捨てにする。
                    onStopRequested?.invoke()
                    onStopRequested = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ACTION_SHOW_COMPLETE -> {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "LLM翻訳完了"
                    val msg = intent.getStringExtra(EXTRA_MSG) ?: "すべてのファイルの翻訳が完了しました"
                    showCompletionNotification(this, title, msg)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> {
                    // 技術的根拠1行：未知操作を状態更新に流用すると将来の操作追加時に誤処理するため、更新操作だけを受け付ける。
                    if (intent.action == ACTION_UPDATE_STATUS) {
                        val title = intent.getStringExtra(EXTRA_TITLE) ?: "LLM小説翻訳"
                        val msg = intent.getStringExtra(EXTRA_MSG) ?: "翻訳を実行中..."
                        val current = intent.getIntExtra(EXTRA_PROGRESS_CURRENT, 0)
                        val total = intent.getIntExtra(EXTRA_PROGRESS_TOTAL, 0)
                        updateForegroundNotification(title, msg, current, total)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                android.util.Log.w("V2TranslationService", "wakelock release failed", e)
            }
        }
        onStopRequested = null
        super.onDestroy()
    }

    private fun buildForegroundNotification(title: String, msg: String, current: Int, total: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // 通知から直接停止できるアクション
        val stopIntent = Intent(this, V2TranslationService::class.java).apply {
            action = ACTION_STOP_TRANSLATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle(title)
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateForegroundNotification(title: String, msg: String, current: Int, total: Int) {
        val notification = buildForegroundNotification(title, msg, current, total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
