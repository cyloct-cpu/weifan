package com.weifan.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.weifan.app.R

/**
 * 跟进待办的本地到期提醒（R2 尾巴，对齐参考 followup_notification）。
 *
 * 设计取舍：
 *  - AlarmManager.setAndAllowWhileIdle：不需要 SCHEDULE_EXACT_ALARM 权限（31+ 的精确闹钟
 *    要用户去设置里授权，得不偿失）；分钟级提醒用这个足够。
 *  - 通知点击 → 跳 FollowupTodosActivity（PendingIntent 简单可靠，不做深链到具体客户）。
 *  - 已过期/已完成/已删除：调用方负责 cancel（见 FollowupTodosActivity 三处调用点）。
 */
object TodoNotifications {
    const val CHANNEL_ID = "todo_reminder"
    const val EXTRA_TODO_ID = "todoId"
    const val EXTRA_CONTENT = "content"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "跟进待办提醒", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "到期的跟进事项提醒" })
        }
    }

    /** 33+ 通知运行时权限；模拟器 appops 默认放行会掩盖缺失（真机必须请求） */
    fun requestPermissionIfNeeded(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4201)
        }
    }

    fun schedule(ctx: Context, todoId: Long, content: String, dueTs: Long) {
        if (dueTs <= System.currentTimeMillis()) return   // 过期的不排
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, todoId, content)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueTs, pi)
    }

    fun cancel(ctx: Context, todoId: Long, content: String = "") {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, todoId, content))
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(todoId.toInt())
    }

    private fun pendingIntent(ctx: Context, todoId: Long, content: String): PendingIntent {
        val intent = Intent(ctx, TodoAlarmReceiver::class.java)
            .putExtra(EXTRA_TODO_ID, todoId)
            .putExtra(EXTRA_CONTENT, content)
        // FLAG_IMMUTABLE 31+ 必须；requestCode 用 todoId 保证同一条不互相覆盖
        return PendingIntent.getBroadcast(
            ctx, todoId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class TodoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TodoNotifications.EXTRA_TODO_ID, -1L)
        val content = intent.getStringExtra(TodoNotifications.EXTRA_CONTENT) ?: "跟进事项到期"
        if (id < 0) return
        TodoNotifications.ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, com.weifan.app.FollowupTodosActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 零依赖原则：不用 androidx NotificationCompat，用框架 Builder（26+ 带 channel 构造器）
        val n = android.app.Notification.Builder(context, TodoNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle("待办到期")
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(id.toInt(), n)
    }
}
