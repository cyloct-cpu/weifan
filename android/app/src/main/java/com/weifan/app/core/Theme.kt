package com.weifan.app.core

import java.util.Calendar

/**
 * 主题判定：三档 —— 昼 / 夜 / 跟随系统（跟随系统时可另设夜间时段）。
 *
 * 与扩展侧 src/shared/theme.js **同规格**（同算法、同边界）。
 * 改一边另一边也要改。
 *
 * 为什么不用纯 CSS 的 prefers-color-scheme：手动指定夜间时，它会跟系统打架。
 * 所以由 JS/Kotlin 算出最终是昼是夜，再决定用哪套色板。
 */
object Theme {

    /** 三档模式 */
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_AUTO = "auto"

    /**
     * 循环到下一档：昼 → 夜 → 跟随系统 → 昼。
     * 与扩展侧 src/shared/theme.js 的 nextThemeMode 同规格（未知值当作跟随系统的下一档）。
     */
    fun nextMode(mode: String): String = when (mode) {
        MODE_LIGHT -> MODE_DARK
        MODE_DARK -> MODE_AUTO
        MODE_AUTO -> MODE_LIGHT
        else -> MODE_DARK
    }

    /** 默认夜间时段：22:00 – 07:00（跨零点） */
    val DEFAULT_SCHEDULE = mapOf(
        "enabled" to false,
        "start" to "22:00",
        "end" to "07:00"
    )

    /**
     * "22:30" → 分钟数；不合法返回 null
     */
    fun toMinutes(hhmm: String?): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm?.trim() ?: "") ?: return null
        val hours = m.groupValues[1].toIntOrNull() ?: return null
        val minutes = m.groupValues[2].toIntOrNull() ?: return null
        if (hours > 23 || minutes > 59) return null
        return hours * 60 + minutes
    }

    /**
     * 最终是昼还是夜。
     *
     * @param mode light / dark / auto
     * @param scheduleEnabled 自定义夜间时段是否启用
     * @param scheduleStart "22:00"
     * @param scheduleEnd "07:00"
     * @param systemDark 系统是不是暗色
     * @param now 当前时刻
     * @return true 表示夜间
     */
    fun isNight(
        mode: String,
        scheduleEnabled: Boolean,
        scheduleStart: String,
        scheduleEnd: String,
        systemDark: Boolean,
        now: Calendar = Calendar.getInstance()
    ): Boolean {
        if (mode == MODE_LIGHT) return false
        if (mode == MODE_DARK) return true

        // 跟随系统：设了自定义时段就按时段来（跨零点也认），否则跟系统
        if (scheduleEnabled) {
            val start = toMinutes(scheduleStart)
            val end = toMinutes(scheduleEnd)
            if (start != null && end != null) {
                val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val inWindow = if (start <= end) {
                    current >= start && current < end
                } else {
                    current >= start || current < end
                }
                return inWindow
            }
        }
        return systemDark
    }

    /** 夜间时段的说明文字（给设置页显示） */
    fun scheduleNote(start: String, end: String): String {
        val valid = toMinutes(start) != null && toMinutes(end) != null
        return if (valid) {
            "$start – $end 为夜间，跨过午夜也算。"
        } else {
            "时间填得不合法，按 24 小时制填「时:分」。"
        }
    }
}
