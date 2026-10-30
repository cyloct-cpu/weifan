package com.weifan.app

import android.app.Application
import mobile.Mobile
import mobile.WeifanClient

/**
 * 全局单例容器：协议客户端必须整个 App 生命周期只有一份
 * （whatsmeow 一个实例 = 一个关联设备连接，不能重复建）。
 */
class WeiFanApplication : Application() {

    private var client: WeifanClient? = null

    @Synchronized
    fun protocol(): WeifanClient {
        val existing = client
        if (existing != null) return existing
        // 代理在设置里可配（Prefs.waProxy），空串 = 直连；国内网络必须走代理
        val prefs = Prefs(this)
        val c = Mobile.newClient(filesDir.absolutePath, prefs.waProxy)
        client = c
        return c
    }
}
