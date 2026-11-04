package com.weifan.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.weifan.app.chat.ChatEngine

/** 开机重排待办提醒（参考有 BOOT_COMPLETED；AlarmManager 重启即清，必须重排） */
class TodoBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val engine = ChatEngine.get(context)
        val future = engine.db.todos(onlyOpen = true).filter { it.dueTs > System.currentTimeMillis() }
        for (t in future) {
            TodoNotifications.schedule(context, t.id, t.content, t.dueTs)
        }
    }
}
