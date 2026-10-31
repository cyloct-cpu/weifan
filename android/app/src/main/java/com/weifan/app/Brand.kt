package com.weifan.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.weifan.app.core.Theme

/**
 * 品牌色板。浅色为默认，深色是夜间那一套。
 * 取值对齐 WhatsApp 官方规格（WaSpec.kt）：主操作 = 顶栏绿 #128C7E，
 * 收件方向 = 已读蓝 #34B7F1，成功/未读 = 亮绿 #25D366。
 * warm / cool 是旧字段名，语义未变：warm = 发件（主操作），cool = 收件。
 *
 * 深浅由 Theme.isNight() 判定（不是直接读系统 uiMode）——
 * 用户可以手动定昼/夜，也可以设自定义夜间时段，所以色板得由上层决定传哪一套。
 */
class Palette(
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val line: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val warm: Int,
    val cool: Int,
    val teal: Int,
    val ink: Int,
    val warn: Int,
    val err: Int
) {
    val warmGrad: IntArray get() = intArrayOf(Color.parseColor("#0E7C6F"), Color.parseColor("#128C7E"))
    val coolGrad: IntArray get() = intArrayOf(Color.parseColor("#2FA9E8"), Color.parseColor("#34B7F1"))
    val mixGrad: IntArray get() = intArrayOf(Color.parseColor("#128C7E"), Color.parseColor("#25D366"))
}

object Brand {

    /** 系统当前是不是暗色 */
    fun systemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 浅色（昼） */
    val LIGHT = Palette(
        bg = Color.parseColor("#EFF2F5"),
        surface = Color.WHITE,
        surface2 = Color.parseColor("#F5F6F6"),
        line = Color.parseColor("#E4E7EA"),
        text = Color.parseColor("#111B21"),
        text2 = Color.parseColor("#667781"),
        text3 = Color.parseColor("#8696A0"),
        warm = Color.parseColor("#128C7E"),
        cool = Color.parseColor("#34B7F1"),
        teal = Color.parseColor("#25D366"),
        ink = Color.WHITE,
        warn = Color.parseColor("#B06A1F"),
        err = Color.parseColor("#D63C41")
    )

    /** 深色（夜） */
    val DARK = Palette(
        bg = Color.parseColor("#0B141A"),
        surface = Color.parseColor("#111B21"),
        surface2 = Color.parseColor("#182229"),
        line = Color.parseColor("#22303A"),
        text = Color.parseColor("#E9EDEF"),
        text2 = Color.parseColor("#A3B1BB"),
        text3 = Color.parseColor("#8696A0"),
        warm = Color.parseColor("#0E7C6F"),
        cool = Color.parseColor("#34B7F1"),
        teal = Color.parseColor("#25D366"),
        ink = Color.WHITE,
        warn = Color.parseColor("#F0A85C"),
        err = Color.parseColor("#F15C6D")
    )

    /** 按 night 标志取色板 */
    fun palette(night: Boolean): Palette = if (night) DARK else LIGHT

    /** 旧接口：直接跟系统（保留给还没接主题设置的地方） */
    fun palette(context: Context): Palette = palette(systemDark(context))

    /**
     * 按用户设置算出当前是不是夜间。
     * 与扩展侧 src/shared/theme.js 同算法。
     */
    fun isNight(context: Context, prefs: Prefs): Boolean =
        Theme.isNight(
            mode = prefs.themeMode,
            scheduleEnabled = prefs.themeScheduleEnabled,
            scheduleStart = prefs.themeScheduleStart,
            scheduleEnd = prefs.themeScheduleEnd,
            systemDark = systemDark(context)
        )
}
