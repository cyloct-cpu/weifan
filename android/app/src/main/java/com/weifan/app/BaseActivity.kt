package com.weifan.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.app.Activity

/**
 * 所有页面的基类：按用户的主题设置（昼 / 夜 / 跟随系统 + 自定义时段）决定配色。
 *
 * 为什么不用 AppCompatDelegate.setDefaultNightMode：
 * 我们刻意零依赖（不引 AndroidX），所以用框架自带的 applyOverrideConfiguration 改 uiMode。
 * 这样连系统对话框、选择器这些原生控件也会跟着变暗。
 *
 * 夜间时段跨界时自己重算并重建界面（每分钟检查一次）。
 */
abstract class BaseActivity : Activity() {

    protected lateinit var prefs: Prefs

    /** 当前是不是夜间 */
    protected var night: Boolean = false
        private set

    /** 跨时段重建用的定时器 */
    private var watcher: java.util.Timer? = null

    override fun attachBaseContext(newBase: Context) {
        val stored = Prefs(newBase)
        val isNight = Brand.isNight(newBase, stored)
        val config = Configuration(newBase.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (isNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        night = Brand.isNight(this, prefs)
        applyStatusBar()
        startWatcher()
    }

    /**
     * 状态栏跟着页面底色走（对齐参考：登录/配对页白底时状态栏也白）。
     * 之前只有 3 个页面单独设，其余页面留着默认灰带，并排一看就突兀。
     * 夜间用深色底 + 亮图标，白天用浅色底 + 暗图标（LIGHT_STATUS_BAR）。
     */
    protected fun applyStatusBar(bg: Int? = null) {
        try {
            val fill = bg ?: Brand.palette(night).bg
            window.statusBarColor = fill
            val v = window.decorView
            var flags = v.systemUiVisibility
            flags = if (night) flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    else flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            v.systemUiVisibility = flags
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页切了主题返回时，立即重建（不等 30 秒的时段巡查器）
        applyThemeChange()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    /** 当前配色 */
    protected val palette: Palette get() = Brand.palette(night)

    /**
     * 夜间时段跨界时自动重建界面。
     * 每分钟看一眼就够了 —— 时段精度就是分钟。
     */
    private fun startWatcher() {
        watcher = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    val now = Brand.isNight(this@BaseActivity, prefs)
                    if (now != night) {
                        runOnUiThread { recreate() }
                    }
                }
            }, 30_000L, 30_000L)
        }
    }

    /**
     * 设置页里改了主题后，立刻重建。
     *
     * 只有「手动指定昼 / 夜」才需要重建（配色变了）；
     * 「跟随系统」时配色由系统决定，改的是不是配色而是生效时间，不必重建。
     */
    protected fun applyThemeChange() {
        val wasNight = night
        night = Brand.isNight(this, prefs)
        if (wasNight != night) recreate()
    }
}
