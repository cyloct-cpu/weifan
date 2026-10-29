package com.weifan.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * 品牌色板。浅色为默认，深色跟随系统（uiMode）。
 * 界面里唯一的彩色就是「方向」：暖 = 发件，冷 = 收件，混 = 双向通用。
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
    val warmGrad: IntArray get() = intArrayOf(Color.parseColor("#FF4353"), Color.parseColor("#FF9064"))
    val coolGrad: IntArray get() = intArrayOf(Color.parseColor("#4C9BF7"), Color.parseColor("#00E9DD"))
    val mixGrad: IntArray get() = intArrayOf(Color.parseColor("#FD6C4E"), Color.parseColor("#2FB6FF"))
}

object Brand {
    fun palette(context: Context): Palette {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Palette(
                bg = Color.parseColor("#0F1720"),
                surface = Color.parseColor("#161C24"),
                surface2 = Color.parseColor("#1D242E"),
                line = Color.parseColor("#2A333F"),
                text = Color.parseColor("#E6EAF0"),
                text2 = Color.parseColor("#A9B4C2"),
                text3 = Color.parseColor("#8B96A6"),
                warm = Color.parseColor("#FD6C4E"),
                cool = Color.parseColor("#2FB6FF"),
                teal = Color.parseColor("#00E9DD"),
                ink = Color.parseColor("#1A0E0A"),
                warn = Color.parseColor("#F1A45C"),
                err = Color.parseColor("#F15C6D")
            )
        } else {
            Palette(
                bg = Color.parseColor("#F4F6F8"),
                surface = Color.WHITE,
                surface2 = Color.parseColor("#F5F7F9"),
                line = Color.parseColor("#E7EAEE"),
                text = Color.parseColor("#161B22"),
                text2 = Color.parseColor("#55606E"),
                text3 = Color.parseColor("#8B97A5"),
                warm = Color.parseColor("#FD6C4E"),
                cool = Color.parseColor("#2FB6FF"),
                teal = Color.parseColor("#00E9DD"),
                ink = Color.parseColor("#1A0E0A"),
                warn = Color.parseColor("#B06A1F"),
                err = Color.parseColor("#D63C41")
            )
        }
    }
}
