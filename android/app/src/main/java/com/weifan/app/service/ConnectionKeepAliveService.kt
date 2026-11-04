package com.weifan.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.weifan.app.R
import com.weifan.app.chat.ChatEngine

/**
 * 连接保活前台服务（对齐参考 ConnectionKeepAliveService；类型 dataSync）。
 * 保活 = 进程常驻 + 长连接不因后台被杀；消息到达走既有的 onMessage 链路。
 */
class ConnectionKeepAliveService : Service() {
    private val channelId = "keepalive"

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "连接保活", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle("微翻保持在线")
            .setContentText("正在保持 WhatsApp 连接")
            .setOngoing(true)
            .build()
        startForeground(2001, n)
        ChatEngine.get(this).connectAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ConnectionKeepAliveService::class.java))
        }
    }
}
