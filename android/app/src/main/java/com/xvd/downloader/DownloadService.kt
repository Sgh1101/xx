package com.xvd.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 다운로드가 진행되는 동안 앱이 백그라운드로 가도 죽지 않게 붙잡아 주는 포그라운드 서비스 */
class DownloadService : Service() {

    private val listener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW))
        }
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) Downloads.pauseAll()
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Downloads.removeListener(listener)
        super.onDestroy()
    }

    private fun update() {
        if (Downloads.activeCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build())
    }

    private fun build(): Notification {
        val active = Downloads.snapshot().filter {
            it.state == Downloads.State.QUEUED || it.state == Downloads.State.RUNNING
        }
        val total = active.sumOf { it.total }
        val done = active.sumOf { it.bytes }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("${active.size}개 받는 중")
            .setContentText(if (total > 0) "$pct%" else "준비 중…")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "전체 일시정지", pause)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 1001
        const val ACTION_PAUSE_ALL = "com.xvd.downloader.PAUSE_ALL"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
