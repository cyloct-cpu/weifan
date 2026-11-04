package com.weifan.app

import android.graphics.Color

/**
 * WaSpec —— 参考客户端（WATransChat 3.55）的 1:1 设计规格 token。
 *
 * 全部取值反编译自安装包资源（aapt2 dump resources + dump xmltree），
 * 每条都带对方的原始资源名与昼/夜两档色值，方便日后对照升级。
 * 品牌名、图标、专有文案不照搬，只对齐版式、间距、字号、圆角、配色与交互。
 * 采集日期 2026-11-01。
 */
object WaSpec {

    // ─────────────────────────── 顶栏 / 品牌绿 ───────────────────────────
    val GREEN = Color.parseColor("#128C7E")             // wa_green        顶栏墨绿
    val GREEN_DARK = Color.parseColor("#075E54")        // wa_green_dark
    val GREEN_LIGHT = Color.parseColor("#25D366")       // wa_green_light  发送/未读
    val INPUT_CIRCLE_GREEN = Color.parseColor("#06CE9B")// wa_input_circle_green 发送圆钮

    // ─────────────────────────── 聊天壁纸 ───────────────────────────
    val WALLPAPER_BASE = Color.parseColor("#F5F1EB")    // chat_wallpaper_base
    val WALLPAPER_DOODLE = Color.parseColor("#EAE0D3")  // chat_wallpaper_doodle
    val WALLPAPER_BASE_NIGHT = Color.parseColor("#0B141A")
    val WALLPAPER_DOODLE_NIGHT = 0x0AFFFFFF

    // ─────────────────────────── 气泡 ───────────────────────────
    val BUBBLE_SENT = Color.parseColor("#E0FCD6")       // wa_bubble_sent
    val BUBBLE_SENT_NIGHT = Color.parseColor("#005C4B")
    val BUBBLE_RECEIVED = Color.WHITE                    // wa_bubble_received
    val BUBBLE_RECEIVED_NIGHT = Color.parseColor("#1F2C34")

    val TEXT_PRIMARY = Color.parseColor("#111111")      // wa_text_primary
    val TEXT_PRIMARY_NIGHT = Color.parseColor("#E9EDEF")
    val TEXT_SECONDARY = Color.parseColor("#8E8E93")    // wa_text_secondary
    val TEXT_SECONDARY_NIGHT = Color.parseColor("#8696A0")
    val READ_RECEIPT = Color.parseColor("#34B7F1")      // wa_read_receipt
    val TRANSLATE_TEXT = Color.parseColor("#0A7EA4")    // 译文行

    // ─────────────────────────── 列表 / 搜索 / chips ───────────────────────────
    val SURFACE = Color.WHITE                            // wa_surface
    val SURFACE_NIGHT = Color.parseColor("#1C1C1E")
    val DIVIDER = Color.parseColor("#E5E5EA")           // wa_divider
    val DIVIDER_NIGHT = Color.parseColor("#38383A")
    val GRAY6 = Color.parseColor("#F2F2F7")             // wa_gray6
    val GRAY6_NIGHT = Color.BLACK
    val SEARCH_BG = Color.parseColor("#F2F2F7")         // chat_search_background
    val SEARCH_BG_NIGHT = Color.parseColor("#2C2C2E")
    val SEARCH_ICON = Color.parseColor("#6F6F6F")       // chat_search_icon
    val SEARCH_ICON_NIGHT = Color.parseColor("#8696A0")

    // 会话内搜索条（对齐参考 chatSearchBar：行 44dp / 胶囊 36dp / 输入 15sp）
    const val CHAT_SEARCH_BAR_ROW_HEIGHT_DP = 44
    const val CHAT_SEARCH_CAPSULE_HEIGHT_DP = 36
    const val CHAT_SEARCH_INPUT_SP = 15

    // ──────────────────输入区圆钮（对齐参考 input_circle_black）──────────────────
    // 参考实值：wa_input_action_btn_bg=@color/wa_surface（白/夜#1C1C1E）
    //           wa_input_border=#14000000（夜#33FFFFFF）1dp
    //           wa_input_action_btn_icon=@color/wa_text_primary（#111111/夜#E9EDEF）
    // 即：白底 + 1dp 淡描边 + 深色图标（新版 WhatsApp 风），非实心绿。
    val INPUT_BTN_STROKE_NIGHT = 0x33FFFFFF.toInt()
    val INPUT_BTN_STROKE = 0x14000000
    fun inputBtnBg(night: Boolean) = if (night) SURFACE_NIGHT else SURFACE
    fun inputBtnStroke(night: Boolean) = if (night) INPUT_BTN_STROKE_NIGHT else INPUT_BTN_STROKE
    fun inputBtnIcon(night: Boolean) = if (night) TEXT_PRIMARY_NIGHT else TEXT_PRIMARY

    val CHIP_UNSELECTED = Color.WHITE                   // chat_filter_chip_unselected
    val CHIP_UNSELECTED_NIGHT = Color.parseColor("#1C1C1E")
    val CHIP_SELECTED = Color.parseColor("#EBEBEF")     // chat_filter_chip_selected
    val CHIP_SELECTED_NIGHT = Color.parseColor("#3A3A3C")
    val CHIP_BORDER = Color.parseColor("#E0E0E4")       // chat_filter_chip_border
    val CHIP_BORDER_NIGHT = Color.parseColor("#48484A")
    val CHIP_SELECTED_BORDER = Color.parseColor("#D1D1D6")
    val CHIP_SELECTED_BORDER_NIGHT = Color.parseColor("#636366")
    val CHIP_TEXT_SELECTED = Color.parseColor("#5E5E5E")

    // ─────────────────────────── 输入区 ───────────────────────────
    val INPUT_BG = Color.parseColor("#F2F2F7")          // chat_search_background 同灰
    val INPUT_BORDER = 0x14000000                        // wa_input_border 8% 黑
    val INPUT_BORDER_NIGHT = 0x33FFFFFF
    val ACTION_BTN_RIPPLE = 0x33000000                   // wa_input_action_btn_ripple
    val ACTION_BTN_RIPPLE_NIGHT = 0x33FFFFFF

    // ─────────────────────────── 登录 / 语言页（auth 体系）───────────────────────────
    val AUTH_PAGE_BG = Color.WHITE                       // auth_page_bg
    val AUTH_PAGE_BG_NIGHT = Color.parseColor("#1C1C1E")
    val AUTH_TEXT_PRIMARY = Color.parseColor("#1A2B4A")  // auth_text_primary
    val AUTH_TEXT_PRIMARY_NIGHT = Color.parseColor("#E8EAED")
    val LOGIN_TEXT_MUTED = Color.parseColor("#6B7280")   // login_text_muted
    val LOGIN_TEXT_MUTED_NIGHT = Color.parseColor("#AEAEB2")
    val LOGIN_TEXT_HINT = Color.parseColor("#9CA3AF")    // login_text_hint
    val LOGIN_TEXT_HINT_NIGHT = Color.parseColor("#8E8E93")
    val PRIMARY_COLOR = GREEN                            // 全局主色：品牌绿（统一绿）
    val PRIMARY_COLOR_NIGHT = Color.parseColor("#2DB875")
    val LOGIN_BANNER_BG = Color.parseColor("#EEF2FF")    // bg_login_trial_banner 浅紫
    val LOGIN_INPUT_BORDER = Color.parseColor("#E5E7EB") // bg_login_input 描边
    val LOGIN_INPUT_BORDER_NIGHT = Color.parseColor("#3A3A3C")
    val LOGIN_BTN_GRADIENT_END = GREEN                   // 渐变终点也用绿（纯色）
    val LOGOUT_BANNER_TEXT = Color.parseColor("#C2410C") // logout_banner_text

    // ─────────────────────────── 尺寸（dp / sp，全部来自资源表）───────────────────────────
    const val TOOLBAR_HEIGHT_DP = 56
    const val SEARCH_CAPSULE_HEIGHT_DP = 36
    const val SEARCH_BAR_ROW_HEIGHT_DP = 44
    const val LIST_AVATAR_SIZE_DP = 46                 // 列表头像（对齐参考 item_chat 46dp）
    const val CHAT_AVATAR_SIZE_DP = 43                 // 会话页头头像 headerAvatarContainer
    const val SENDER_AVATAR_SIZE_DP = 25               // 群内发送者小头像
    // ────── 形状常量：全部来自参考 drawable 的实测值（aapt2 dump xmltree）──────
    // 之前只抄了 padding/字号，没抄背景形状，这是视觉差距的主要来源。
    // 参考：bg_dialog_card = 圆角 16dp、无描边
    const val DIALOG_CARD_RADIUS_DP = 16f
    // bg_dialog_input = 圆角 12dp + 1dp 描边
    const val DIALOG_INPUT_RADIUS_DP = 12f
    const val DIALOG_INPUT_STROKE_DP = 1f
    // bg_bubble_sent / bg_bubble_received = 圆角 10dp（与我们原值一致）
    const val BUBBLE_RADIUS_DP = 10f
    // bg_chip_selected = 圆角 100dp（全圆）+ 1dp 描边
    const val CHIP_SELECTED_RADIUS_DP = 100f
    const val CHIP_SELECTED_STROKE_DP = 1f
    // bg_filter_chip_selected = 圆角 999dp（全圆，无描边）
    const val FILTER_CHIP_RADIUS_DP = 999f
    // bg_followup_stat_card / bg_followup_todo_card = 圆角 14dp
    const val STAT_CARD_RADIUS_DP = 14f
    const val TODO_CARD_RADIUS_DP = 14f
    // bg_fx_card = 圆角 12dp
    const val FX_CARD_RADIUS_DP = 12f
    // bg_card_simple = 圆角 12dp + 1dp 描边
    const val CARD_SIMPLE_RADIUS_DP = 12f
    const val CARD_SIMPLE_STROKE_DP = 1f

    // U2：头像环（参考 drawable/bg_avatar_ring：OVAL + solid wa_gray5 + 1dp wa_avatar_stroke）
    val AVATAR_RING_FILL = Color.parseColor("#E5E5EA")          // wa_gray5
    val AVATAR_RING_FILL_NIGHT = Color.parseColor("#38383A")
    val AVATAR_RING_STROKE = 0x0F000000                          // wa_avatar_stroke
    val AVATAR_RING_STROKE_NIGHT = 0x33FFFFFF.toInt()
    fun avatarRingFill(night: Boolean) = if (night) AVATAR_RING_FILL_NIGHT else AVATAR_RING_FILL
    fun avatarRingStroke(night: Boolean) =
        if (night) AVATAR_RING_STROKE_NIGHT else AVATAR_RING_STROKE
    const val SENDER_AVATAR_GAP_DP = 6                           // chat_sender_avatar_gap
    const val BUBBLE_MAX_WIDTH_DP = 280
    const val BUBBLE_CORNER_DP = 10
    const val BUBBLE_SIDE_INSET_DP = 8
    const val BUBBLE_OPPOSITE_INSET_DP = 30
    const val MESSAGE_LIST_PADDING_H_DP = 8
    const val MESSAGE_LIST_BOTTOM_SPACING_DP = 8
    const val TRANSLATION_TEXT_SP = 13
    const val SCROLL_TO_BOTTOM_SIZE_DP = 42
    const val SCROLL_TO_BOTTOM_CORNER_DP = 21

    /** 会话页顶栏：paddingH 12dp / paddingV 8dp / minHeight 72dp */
    const val CHAT_HEADER_PADDING_H_DP = 12
    const val CHAT_HEADER_PADDING_V_DP = 8
    const val CHAT_HEADER_MIN_HEIGHT_DP = 72
    const val CHAT_HEADER_TITLE_SP = 16
    const val CHAT_HEADER_SUBTITLE_SP = 13
    const val CHAT_BACK_BTN_DP = 40

    /** 输入胶囊：外框 paddingH 8dp，胶囊内 paddingH 6dp，minHeight 48dp */
    const val INPUT_AREA_PADDING_DP = 8
    const val INPUT_CAPSULE_PADDING_DP = 6
    const val INPUT_CAPSULE_MIN_HEIGHT_DP = 48
    const val ATTACH_BUTTON_SIZE_DP = 28
    const val ATTACH_BUTTON_PADDING_DP = 3
    const val MESSAGE_INPUT_SP = 16
    const val MESSAGE_INPUT_MIN_HEIGHT_DP = 36
    const val MESSAGE_INPUT_MAX_LINES = 4
    const val TRANSLATE_BUTTON_HEIGHT_DP = 36
    const val TRANSLATE_BUTTON_PADDING_H_DP = 6
    const val TRANSLATE_BUTTON_TEXT_SP = 12
    const val PTT_SEND_BUTTON_SIZE_DP = 48
    const val PTT_SEND_MARGIN_DP = 4
    const val SEND_BUTTON_PADDING_DP = 12
    const val COMPOSER_SCRIM_HEIGHT_DP = 72

    /** 列表页：顶栏 paddingTop 10 / paddingBottom 4，标题 20sp medium，图标按钮 42dp */
    const val LIST_HEADER_PADDING_TOP_DP = 10
    const val LIST_HEADER_PADDING_BOTTOM_DP = 4
    const val LIST_HEADER_PADDING_START_DP = 16
    const val LIST_HEADER_PADDING_END_DP = 8
    const val LIST_TITLE_SP = 20
    const val LIST_ICON_BUTTON_DP = 42

    /** 搜索框：高 40dp，左右外边距 12dp，内边距 14dp，图标 18dp */
    const val SEARCH_BAR_HEIGHT_DP = 40
    const val SEARCH_BAR_MARGIN_H_DP = 12
    const val SEARCH_BAR_MARGIN_TOP_DP = 10
    const val SEARCH_BAR_PADDING_H_DP = 14
    const val SEARCH_ICON_SIZE_DP = 18
    const val SEARCH_INPUT_SP = 15
    const val SEARCH_INPUT_GAP_DP = 8

    /** chips 行：上下外边距 8/10dp，chip 间距 8dp，加号按钮 32dp */
    const val CHIP_ROW_MARGIN_TOP_DP = 8
    const val CHIP_ROW_MARGIN_BOTTOM_DP = 10
    const val CHIP_ROW_PADDING_H_DP = 12
    const val CHIP_GAP_DP = 8
    const val CHIP_ADD_BUTTON_DP = 32
    const val CHIP_ADD_PADDING_DP = 6

    /** 连接状态条：paddingH 16 / paddingV 10，文字 13sp，进度与文字间距 10dp */
    const val CONNECTING_BAR_PADDING_H_DP = 16
    const val CONNECTING_BAR_PADDING_V_DP = 10
    const val CONNECTING_TEXT_SP = 13
    const val CONNECTING_GAP_DP = 10

    /** 空态：文字 15sp 标题 / 14sp 统计 / 13sp 提示，间距 12/8/8dp，左右 24dp */
    const val EMPTY_PADDING_H_DP = 24
    const val EMPTY_TITLE_SP = 15
    const val EMPTY_STATS_SP = 14
    const val EMPTY_HINT_SP = 13
    const val EMPTY_GAP_1_DP = 12
    const val EMPTY_GAP_2_DP = 8

    /** 登录 / 语言页（auth 体系）*/
    const val AUTH_PADDING_H_DP = 24
    const val AUTH_TITLE_SP = 45
    const val AUTH_SUBTITLE_SP = 22
    const val AUTH_DESC_SP = 14
    const val AUTH_HINT_SP = 13
    const val AUTH_INPUT_HEIGHT_DP = 56
    const val AUTH_INPUT_PADDING_H_DP = 14
    const val AUTH_INPUT_TEXT_SP = 15
    const val AUTH_INPUT_ICON_DP = 22
    const val AUTH_INPUT_GAP_DP = 10
    const val AUTH_INPUT_RADIUS_DP = 14
    const val AUTH_BUTTON_TEXT_SP = 16
    const val AUTH_BUTTON_PADDING_V_DP = 16
    const val AUTH_BUTTON_PADDING_H_DP = 24
    const val AUTH_BUTTON_RADIUS_DP = 28
    const val AUTH_BACK_BTN_DP = 32
    const val AUTH_BANNER_PADDING_DP = 14
    const val AUTH_BANNER_TEXT_SP = 14
    const val AUTH_BANNER_ICON_DP = 20
    const val AUTH_FOOTER_SP = 14
    const val AUTH_TOGGLE_ICON_DP = 22

    /** 语言页：标题区 paddingTop 76dp，搜索框高 48dp，语言行 48dp */
    const val LANG_HEADER_PADDING_TOP_DP = 76
    const val LANG_SEARCH_HEIGHT_DP = 48
    const val LANG_ROW_HEIGHT_DP = 48
    const val LANG_LIST_PADDING_H_DP = 16
    const val LANG_BOTTOM_BAR_PADDING_H_DP = 24
    const val LANG_BOTTOM_BAR_PADDING_TOP_DP = 14
    const val LANG_BOTTOM_BAR_PADDING_BOTTOM_DP = 24
    const val LANG_BOTTOM_BUTTON_HEIGHT_DP = 52
    const val LANG_BOTTOM_HINT_SP = 12
    const val LANG_BOTTOM_HINT_SP2 = 13

    // ─────────────────────────── 昼夜取值助手 ───────────────────────────
    fun surface(night: Boolean) = if (night) SURFACE_NIGHT else SURFACE
    fun divider(night: Boolean) = if (night) DIVIDER_NIGHT else DIVIDER
    fun textPrimary(night: Boolean) = if (night) TEXT_PRIMARY_NIGHT else TEXT_PRIMARY
    fun textSecondary(night: Boolean) = if (night) TEXT_SECONDARY_NIGHT else TEXT_SECONDARY
    fun searchBg(night: Boolean) = if (night) SEARCH_BG_NIGHT else SEARCH_BG
    fun searchIcon(night: Boolean) = if (night) SEARCH_ICON_NIGHT else SEARCH_ICON
    fun authBg(night: Boolean) = if (night) AUTH_PAGE_BG_NIGHT else AUTH_PAGE_BG
    fun authText(night: Boolean) = if (night) AUTH_TEXT_PRIMARY_NIGHT else AUTH_TEXT_PRIMARY
    fun authMuted(night: Boolean) = if (night) LOGIN_TEXT_MUTED_NIGHT else LOGIN_TEXT_MUTED
    fun authHint(night: Boolean) = if (night) LOGIN_TEXT_HINT_NIGHT else LOGIN_TEXT_HINT
    fun primary(night: Boolean) = if (night) PRIMARY_COLOR_NIGHT else PRIMARY_COLOR
    fun chipFill(selected: Boolean, night: Boolean) =
        if (selected) (if (night) CHIP_SELECTED_NIGHT else CHIP_SELECTED)
        else (if (night) CHIP_UNSELECTED_NIGHT else CHIP_UNSELECTED)
    fun chipStroke(selected: Boolean, night: Boolean) =
        if (selected) (if (night) CHIP_SELECTED_BORDER_NIGHT else CHIP_SELECTED_BORDER)
        else (if (night) CHIP_BORDER_NIGHT else CHIP_BORDER)
    fun bubbleSent(night: Boolean) = if (night) BUBBLE_SENT_NIGHT else BUBBLE_SENT
    fun bubbleReceived(night: Boolean) = if (night) BUBBLE_RECEIVED_NIGHT else BUBBLE_RECEIVED
    fun wallpaperBase(night: Boolean) = if (night) WALLPAPER_BASE_NIGHT else WALLPAPER_BASE
    fun wallpaperDoodle(night: Boolean) = if (night) WALLPAPER_DOODLE_NIGHT else WALLPAPER_DOODLE
}
