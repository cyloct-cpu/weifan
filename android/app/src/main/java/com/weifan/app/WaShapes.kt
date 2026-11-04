package com.weifan.app

/**
 * 参考应用全部圆角形状的实测值（aapt2 dump xmltree 提取）。
 *
 * 为什么需要这个文件：视觉相似度 70% 来自圆角/描边，而之前只抄了 padding 与字号。
 * 全部数值来自 WATransChat 3.55 的 drawable 定义，未经推测。
 *
 * 命名规则：bg_xxx -> XXX_RADIUS_DP
 */
object WaShapes {
    /** bg_account_section */
    const val BG_ACCOUNT_SECTION_RADIUS_DP = 12f
    /** bg_bubble_received */
    const val BG_BUBBLE_RECEIVED_RADIUS_DP = 10f
    /** bg_bubble_sent */
    const val BG_BUBBLE_SENT_RADIUS_DP = 10f
    /** bg_card_simple */
    const val BG_CARD_SIMPLE_RADIUS_DP = 12f
    /** bg_chip_selected */
    const val BG_CHIP_SELECTED_RADIUS_DP = 100f
    /** bg_chip_unselected */
    const val BG_CHIP_UNSELECTED_RADIUS_DP = 100f
    /** bg_code_copied_badge */
    const val BG_CODE_COPIED_BADGE_RADIUS_DP = 20f
    /** bg_contact_info_row */
    const val BG_CONTACT_INFO_ROW_RADIUS_DP = 12f
    /** bg_device_remove_btn */
    const val BG_DEVICE_REMOVE_BTN_RADIUS_DP = 6f
    /** bg_device_row */
    const val BG_DEVICE_ROW_RADIUS_DP = 10f
    /** bg_device_row_current */
    const val BG_DEVICE_ROW_CURRENT_RADIUS_DP = 10f
    /** bg_device_select_toolbar */
    const val BG_DEVICE_SELECT_TOOLBAR_RADIUS_DP = 10f
    /** bg_device_status_active */
    const val BG_DEVICE_STATUS_ACTIVE_RADIUS_DP = 999f
    /** bg_device_status_current */
    const val BG_DEVICE_STATUS_CURRENT_RADIUS_DP = 999f
    /** bg_device_status_expired */
    const val BG_DEVICE_STATUS_EXPIRED_RADIUS_DP = 999f
    /** bg_device_status_offline */
    const val BG_DEVICE_STATUS_OFFLINE_RADIUS_DP = 999f
    /** bg_dialog_card */
    const val BG_DIALOG_CARD_RADIUS_DP = 16f
    /** bg_dialog_input */
    const val BG_DIALOG_INPUT_RADIUS_DP = 12f
    /** bg_event_calendar_icon */
    const val BG_EVENT_CALENDAR_ICON_RADIUS_DP = 8f
    /** bg_filter_chip_selected */
    const val BG_FILTER_CHIP_SELECTED_RADIUS_DP = 999f
    /** bg_followup_chip_selected */
    const val BG_FOLLOWUP_CHIP_SELECTED_RADIUS_DP = 20f
    /** bg_followup_chip_unselected */
    const val BG_FOLLOWUP_CHIP_UNSELECTED_RADIUS_DP = 20f
    /** bg_followup_customer_chip */
    const val BG_FOLLOWUP_CUSTOMER_CHIP_RADIUS_DP = 12f
    /** bg_followup_field */
    const val BG_FOLLOWUP_FIELD_RADIUS_DP = 12f
    /** bg_followup_overdue_badge */
    const val BG_FOLLOWUP_OVERDUE_BADGE_RADIUS_DP = 8f
    /** bg_followup_save_button */
    const val BG_FOLLOWUP_SAVE_BUTTON_RADIUS_DP = 12f
    /** bg_followup_stat_card */
    const val BG_FOLLOWUP_STAT_CARD_RADIUS_DP = 14f
    /** bg_followup_todo_card */
    const val BG_FOLLOWUP_TODO_CARD_RADIUS_DP = 14f
    /** bg_fx_card */
    const val BG_FX_CARD_RADIUS_DP = 12f
    /** bg_fx_card_result */
    const val BG_FX_CARD_RESULT_RADIUS_DP = 12f
    /** bg_fx_currency_chip */
    const val BG_FX_CURRENCY_CHIP_RADIUS_DP = 8f
    /** bg_fx_currency_chip_target */
    const val BG_FX_CURRENCY_CHIP_TARGET_RADIUS_DP = 8f
    /** bg_fx_target_badge */
    const val BG_FX_TARGET_BADGE_RADIUS_DP = 4f
    /** bg_input_capsule */
    const val BG_INPUT_CAPSULE_RADIUS_DP = 26f
    /** bg_language_setup_continue */
    const val BG_LANGUAGE_SETUP_CONTINUE_RADIUS_DP = 12f
    /** bg_language_setup_option */
    const val BG_LANGUAGE_SETUP_OPTION_RADIUS_DP = 10f
    /** bg_language_setup_option_selected */
    const val BG_LANGUAGE_SETUP_OPTION_SELECTED_RADIUS_DP = 10f
    /** bg_link_preview */
    const val BG_LINK_PREVIEW_RADIUS_DP = 8f
    /** bg_login_button */
    const val BG_LOGIN_BUTTON_RADIUS_DP = 28f
    /** bg_login_input */
    const val BG_LOGIN_INPUT_RADIUS_DP = 12f
    /** bg_login_input_error */
    const val BG_LOGIN_INPUT_ERROR_RADIUS_DP = 12f
    /** bg_login_input_round */
    const val BG_LOGIN_INPUT_ROUND_RADIUS_DP = 28f
    /** bg_login_trial_banner */
    const val BG_LOGIN_TRIAL_BANNER_RADIUS_DP = 12f
    /** bg_logout_btn */
    const val BG_LOGOUT_BTN_RADIUS_DP = 8f
    /** bg_logout_reason_banner */
    const val BG_LOGOUT_REASON_BANNER_RADIUS_DP = 12f
    /** bg_media_caption_input */
    const val BG_MEDIA_CAPTION_INPUT_RADIUS_DP = 24f
    /** bg_media_chat_chip */
    const val BG_MEDIA_CHAT_CHIP_RADIUS_DP = 16f
    /** bg_media_document_page */
    const val BG_MEDIA_DOCUMENT_PAGE_RADIUS_DP = 8f
    /** bg_media_info_card */
    const val BG_MEDIA_INFO_CARD_RADIUS_DP = 8f
    /** bg_media_row_icon */
    const val BG_MEDIA_ROW_ICON_RADIUS_DP = 8f
    /** bg_member_form_input */
    const val BG_MEMBER_FORM_INPUT_RADIUS_DP = 8f
    /** bg_member_phrase_item */
    const val BG_MEMBER_PHRASE_ITEM_RADIUS_DP = 10f
    /** bg_membership_badge_expired */
    const val BG_MEMBERSHIP_BADGE_EXPIRED_RADIUS_DP = 999f
    /** bg_membership_dialog */
    const val BG_MEMBERSHIP_DIALOG_RADIUS_DP = 20f
    /** bg_membership_meta */
    const val BG_MEMBERSHIP_META_RADIUS_DP = 12f
    /** bg_membership_seat_panel */
    const val BG_MEMBERSHIP_SEAT_PANEL_RADIUS_DP = 14f
    /** bg_message_action_popup */
    const val BG_MESSAGE_ACTION_POPUP_RADIUS_DP = 12f
    /** bg_message_bubble_highlight_overlay */
    const val BG_MESSAGE_BUBBLE_HIGHLIGHT_OVERLAY_RADIUS_DP = 10f
    /** bg_message_search_highlight */
    const val BG_MESSAGE_SEARCH_HIGHLIGHT_RADIUS_DP = 8f
    /** bg_poll_progress_track */
    const val BG_POLL_PROGRESS_TRACK_RADIUS_DP = 4f
    /** bg_popup_menu */
    const val BG_POPUP_MENU_RADIUS_DP = 8f
    /** bg_qr_code_frame */
    const val BG_QR_CODE_FRAME_RADIUS_DP = 28f
    /** bg_search_bar */
    const val BG_SEARCH_BAR_RADIUS_DP = 16f
    /** bg_skeleton_line */
    const val BG_SKELETON_LINE_RADIUS_DP = 10f
    /** bg_support_bubble_agent */
    const val BG_SUPPORT_BUBBLE_AGENT_RADIUS_DP = 12f
    /** bg_support_bubble_self */
    const val BG_SUPPORT_BUBBLE_SELF_RADIUS_DP = 12f
    /** bg_support_input */
    const val BG_SUPPORT_INPUT_RADIUS_DP = 26f
    /** bg_voice_waveform */
    const val BG_VOICE_WAVEFORM_RADIUS_DP = 1f
}
