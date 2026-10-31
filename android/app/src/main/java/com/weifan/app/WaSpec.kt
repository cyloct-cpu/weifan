package com.weifan.app

import android.graphics.Color

/**
 * WaSpec —— 同类客户端 设计规格对齐 token。
 * 全部取值反编译自 同类客户端安装包的资源（aapt2 dump resources），
 * 命名保持对方原名，便于后续对照升级。日期 2026-10-31。
 */
object WaSpec {
    // ---- 顶栏 / 品牌绿 ----
    val GREEN = Color.parseColor("#128C7E")        // wa_green 顶栏墨绿
    val GREEN_DARK = Color.parseColor("#075E54")   // wa_green_dark
    val GREEN_LIGHT = Color.parseColor("#25D366")  // wa_green_light 发送按钮/未读
    val INPUT_CIRCLE_GREEN = Color.parseColor("#06CE9B") // wa_input_circle_green 现版发送圆钮

    // ---- 聊天壁纸 ----
    val WALLPAPER_BASE = Color.parseColor("#F5F1EB")   // chat_wallpaper_base
    val WALLPAPER_DOODLE = Color.parseColor("#EAE0D3") // chat_wallpaper_doodle

    // ---- 气泡 ----
    val BUBBLE_SENT = Color.parseColor("#E0FCD6")      // wa_bubble_sent（比 WhatsApp 原版更浅）
    val BUBBLE_RECEIVED = Color.WHITE                  // wa_bubble_received
    val TEXT_PRIMARY = Color.parseColor("#111111")     // wa_text_primary
    val TEXT_SECONDARY = Color.parseColor("#8E8E93")   // wa_text_secondary 时间戳
    val READ_RECEIPT = Color.parseColor("#34B7F1")     // wa_read_receipt 已读✓✓蓝
    val TRANSLATE_TEXT = Color.parseColor("#0A7EA4")   // 译文行（沿用微翻既定蓝）

    // ---- 列表 ----
    val DIVIDER = Color.parseColor("#E5E5EA")          // wa_divider
    val GRAY6 = Color.parseColor("#F2F2F7")            // wa_gray6 搜索框底
    val CHIP_UNSELECTED = Color.WHITE                  // chat_filter_chip_unselected
    val CHIP_SELECTED = Color.parseColor("#EBEBEF")    // chat_filter_chip_selected
    val CHIP_BORDER = Color.parseColor("#E0E0E4")      // chat_filter_chip_border
    val CHIP_SELECTED_BORDER = Color.parseColor("#D1D1D6")
    val CHIP_TEXT_GREEN = GREEN                        // chip 文字用墨绿
    val CHIP_TEXT_SELECTED = Color.parseColor("#5E5E5E")

    // ---- 输入区 ----
    val INPUT_BG = Color.parseColor("#F2F2F7")         // chat_search_background 同灰
    val INPUT_BORDER = 0x14000000                      // wa_input_border 8% 黑

    // ---- 尺寸（dp/sp）----
    const val TOOLBAR_HEIGHT_DP = 56
    const val SEARCH_CAPSULE_HEIGHT_DP = 36
    const val SEARCH_BAR_ROW_HEIGHT_DP = 44
    const val LIST_AVATAR_SIZE_DP = 50                 // 参考截图实测 ~50dp
    const val CHAT_AVATAR_SIZE_DP = 40                 // 会话页头头像
    const val SENDER_AVATAR_SIZE_DP = 25               // 群内发送者小头像
    const val BUBBLE_MAX_WIDTH_DP = 280
    const val BUBBLE_CORNER_DP = 10
    const val BUBBLE_SIDE_INSET_DP = 8
    const val BUBBLE_OPPOSITE_INSET_DP = 30
    const val MESSAGE_LIST_PADDING_H_DP = 8
    const val MESSAGE_LIST_BOTTOM_SPACING_DP = 8
    const val TRANSLATION_TEXT_SP = 13                 // chat_translation_text_size
    const val SCROLL_TO_BOTTOM_SIZE_DP = 42
    const val SCROLL_TO_BOTTOM_CORNER_DP = 21
}
