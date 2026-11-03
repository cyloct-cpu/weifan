package com.weifan.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.json.JSONObject
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.chat.ChatEngine
import com.weifan.app.core.Lang
import com.weifan.app.data.MessageRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话页 —— 按参考客户端 activity_chat 的规格 1:1 重排：
 *
 *   顶栏：paddingH 12dp / paddingV 8dp / minHeight 72dp；返回键 40dp；头像 43dp；
 *        标题 16sp medium（左对齐）、副标题 13sp（"最近上线"）；底部分隔线
 *   消息区：涂鸦壁纸、气泡 10dp 圆角、左右 inset 8/30dp、上下间距 3dp
 *   输入区：外层 padding 8dp；胶囊 minHeight 48dp / paddingH 6dp；
 *        ＋(28dp)、😊(28dp)、输入框(16sp / minHeight 36dp / maxLines 4)、译按钮(36dp)；
 *        发送圆钮 48dp、左间距 4dp
 */
class ChatActivity : BaseActivity() {

    private lateinit var engine: ChatEngine
    private val main = Handler(Looper.getMainLooper())

    // 位图缓存：key = mediaPath|thumbHash → 降采样后位图；重渲染不重复解码。
    // LRU 上限 24 张（每张约 0.5–2MB），超出自动淘汰最久未用项。
    private val bmpCache = object : java.util.LinkedHashMap<String, android.graphics.Bitmap>(
        24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>): Boolean {
            return size > 24
        }
    }
    private var chatJid: String = ""
    private var chatTitle: String = ""
    private var chatLangLabel: String = ""
    /** B1：resolvePn 解析出的对方真实号码（LID 会话靠它推货币） */
    private var resolvedPhone: String = ""
    /** 下载失败过的消息 id：避免每轮重渲染都重试，形成死循环重绘 */
    private val mediaDownloadFailed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: View
    private lateinit var subtitle: TextView
    private lateinit var translateBtn: TextView
    // 会话内搜索（批1-b）
    private lateinit var chatSearchBar: LinearLayout
    private lateinit var chatSearchInput: EditText
    // A5/A6：建议面板（@ 提及 / 斜杠命令）
    private lateinit var suggestionPanel: LinearLayout
    private var groupMembers: List<Pair<String, String>> = emptyList()   // phone → 名字
    // 头部标签徽章（批1-c）
    private lateinit var labelBadges: HorizontalScrollView
    private lateinit var labelBadgeRow: LinearLayout
    private var searchQuery = ""

    companion object {
        private const val REQ_PICK_IMAGE = 11
        private const val REQ_PICK_DOC = 12
    }

    /** 语音播放器（单例：同一时间只播一条）*/
    private var player: android.media.MediaPlayer? = null
    private var playingRow: LinearLayout? = null
    private var playingRowViewRef: Pair<LinearLayout, TextView>? = null

    /** 引用回复状态（id/participant/摘要） */
    private var pendingQuote: Triple<String, String, String>? = null
    private var loadingOlder = false
    private lateinit var quoteBar: LinearLayout

    private fun stopPlayback() {
        try { player?.release() } catch (_: Throwable) { }
        player = null
        playingRowViewRef?.second?.text = "▶"
        playingRow = null
        playingRowViewRef = null
    }

    override fun onDestroy() {
        stopPlayback()
        try { attachDialog?.dismiss(); recordDialog?.dismiss() } catch (_: Throwable) { }
        attachDialog = null; recordDialog = null
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        super.onDestroy()
    }

    private var translateOn = true

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatJid = intent.getStringExtra("jid") ?: ""
        chatTitle = intent.getStringExtra("title") ?: ""
        engine = ChatEngine.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }

        // ── 顶栏：minHeight 72dp，paddingH 12 / paddingV 8
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(WaSpec.CHAT_HEADER_PADDING_H_DP), dp(WaSpec.CHAT_HEADER_PADDING_V_DP),
                dp(WaSpec.CHAT_HEADER_PADDING_H_DP), dp(WaSpec.CHAT_HEADER_PADDING_V_DP))
            minimumHeight = dp(WaSpec.CHAT_HEADER_MIN_HEIGHT_DP)
        }

        // 返回键 40dp
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            contentDescription = "返回"
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(WaSpec.CHAT_BACK_BTN_DP), dp(WaSpec.CHAT_BACK_BTN_DP)))

        // 头像 43dp，左间距 4dp
        top.addView(avatarWithRing(), LinearLayout.LayoutParams(
            dp(WaSpec.CHAT_AVATAR_SIZE_DP), dp(WaSpec.CHAT_AVATAR_SIZE_DP)).apply {
            marginStart = dp(4)
        })

        // 标题列：16sp medium + 13sp 副标题（"最近上线"），左间距 8dp
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = chatTitle.ifBlank { chatJid.substringBefore("@") }
            textSize = WaSpec.CHAT_HEADER_TITLE_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START
        })
        // 国家检测：优先真实手机号（Go 层已把 LID 解析回 PN），再看会话语言记忆
        val pn = engine.lastPnFor(chatJid)
        val detected = com.weifan.app.core.CountryDetect.label(pn.ifBlank { chatJid })
        chatLangLabel = when {
            detected.isNotBlank() -> detected
            else -> engine.chatLangMemory[chatJid]?.let { com.weifan.app.core.CountryDetect.langName(it) }
                ?: "自动检测"
        }
        val seedLang = com.weifan.app.core.CountryDetect.detect(pn.ifBlank { chatJid })?.lang
        if (seedLang != null) engine.chatLangMemory.putIfAbsent(chatJid, seedLang)
        // 主动解析 LID→手机号（异步），拿到后刷新副标题与翻译目标语言
        engine.resolvePnAsync(chatJid) { pn ->
            if (isDestroyed || isFinishing) return@resolvePnAsync
            // B1：LID 会话推不出国家码，汇率识别靠这里解析出的真实号码
            if (pn.isNotBlank()) resolvedPhone = pn
            val lb = com.weifan.app.core.CountryDetect.label(pn.ifBlank { chatJid })
            if (lb.isNotBlank()) {
                chatLangLabel = lb
                if (::subtitle.isInitialized) subtitle.text = "最近上线 · $chatLangLabel"
            }
        }
        subtitle = TextView(this).apply {
            text = "最近上线 · $chatLangLabel"
            textSize = WaSpec.CHAT_HEADER_SUBTITLE_SP.toFloat()
            setTextColor(WaSpec.textSecondary(night))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(subtitle)

        // 头部标签徽章行（对齐参考 headerLabelRow → headerLabelBadges，marginTop 4dp）
        // 参考把客户标签直接摊在会话页顶部，是 CRM 可见性的关键入口。
        labelBadges = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        labelBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelBadges.addView(labelBadgeRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titleCol.addView(labelBadges, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        top.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })

        // 客户语言（对齐参考 customerLanguageButton 40dp —— 会话级指定客户语言与引擎）
        top.addView(TextView(this).apply {
            text = "文"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            contentDescription = "客户语言"
            setOnClickListener { showCustomerLanguageDialog() }
        }, LinearLayout.LayoutParams(dp(WaSpec.CHAT_BACK_BTN_DP), dp(WaSpec.CHAT_BACK_BTN_DP)))
        // 会话内搜索（对齐参考 searchButton 40dp ic_search_messages_header）
        top.addView(TextView(this).apply {
            text = "🔍"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            setOnClickListener { toggleChatSearch() }
        }, LinearLayout.LayoutParams(dp(WaSpec.CHAT_BACK_BTN_DP), dp(WaSpec.CHAT_BACK_BTN_DP)))

        top.addView(TextView(this).apply {
            text = "⋮"
            text = "⋮"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@ChatActivity, ChatInfoActivity::class.java)
                    .putExtra("jid", chatJid).putExtra("title", chatTitle))
            }
        }, LinearLayout.LayoutParams(dp(WaSpec.CHAT_BACK_BTN_DP), dp(WaSpec.CHAT_BACK_BTN_DP)))

        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(WaSpec.divider(night)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // ── 会话内搜索条（默认 gone，点 🔍 展开；对齐参考 chatSearchBar 44dp/胶囊 36dp）
        chatSearchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
        }
        chatSearchInput = EditText(this).apply {
            hint = "搜索消息"
            textSize = WaSpec.CHAT_SEARCH_INPUT_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            setBackground(null)
            maxLines = 1
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    renderMessages(false)
                }
            })
        }
        chatSearchBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@ChatActivity, WaSpec.searchBg(night), 18)
            setPadding(dp(12), 0, dp(12), 0)
            addView(TextView(this@ChatActivity).apply {
                text = "🔍"; textSize = 14f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(chatSearchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(this@ChatActivity).apply {
                text = "✕"; textSize = 16f
                setTextColor(WaSpec.textSecondary(night))
                isClickable = true
                setOnClickListener {
                    chatSearchInput.setText(""); searchQuery = ""
                    chatSearchBar.visibility = View.GONE; hideKeyboard(); renderMessages(false)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(0, dp(WaSpec.CHAT_SEARCH_CAPSULE_HEIGHT_DP), 1f))
        root.addView(chatSearchBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(WaSpec.CHAT_SEARCH_BAR_ROW_HEIGHT_DP)))

        // ── 消息流（涂鸦壁纸）
        scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留白 76dp：滚到底时最后一条消息不会被输入胶囊盖住（实机发现的遮挡）
            setPadding(dp(WaSpec.MESSAGE_LIST_PADDING_H_DP), dp(8),
                dp(WaSpec.MESSAGE_LIST_PADDING_H_DP), dp(76))
        }
        scroll.addView(listBox)
        // 向上翻页：滚到顶自动拉更早历史（P0-5）
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (scroll.scrollY == 0 && !loadingOlder && listBox.childCount > 0) {
                loadingOlder = true
                engine.loadOlderMessagesAsync(chatJid) {
                    loadingOlder = false
                    if (it) main.post { renderMessages(stickToBottom = false) }
                }
            }
        }
        scroll.post {
            val w = maxOf(scroll.width, 1)
            val h = maxOf(scroll.height, 1)
            val res = if (night) R.drawable.wa_wallpaper_doodle_dark else R.drawable.wa_wallpaper_doodle
            val bmp = android.graphics.BitmapFactory.decodeResource(resources, res)
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bmp, w, maxOf(h, w * bmp.height / bmp.width), true)
            scroll.background = android.graphics.drawable.BitmapDrawable(resources, scaled)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 输入区：外层 padding 8dp，胶囊 + 48dp 发送圆钮
        val bottomWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(WaSpec.INPUT_AREA_PADDING_DP), dp(WaSpec.INPUT_AREA_PADDING_DP),
                dp(WaSpec.INPUT_AREA_PADDING_DP), dp(WaSpec.INPUT_AREA_PADDING_DP))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        val capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@ChatActivity, WaSpec.surface(night),
                WaSpec.INPUT_CAPSULE_MIN_HEIGHT_DP / 2, 1,
                if (night) WaSpec.INPUT_BORDER_NIGHT else WaSpec.INPUT_BORDER)
            setPadding(dp(WaSpec.INPUT_CAPSULE_PADDING_DP), 0, dp(WaSpec.INPUT_CAPSULE_PADDING_DP), 0)
            minimumHeight = dp(WaSpec.INPUT_CAPSULE_MIN_HEIGHT_DP)
        }
        // ＋：附件面板（文档/照片视频等，对齐 WATransChat）
        capsule.addView(TextView(this).apply {
            text = "＋"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            setOnClickListener { showAttachmentPanel() }
        }, LinearLayout.LayoutParams(
            dp(WaSpec.ATTACH_BUTTON_SIZE_DP), dp(WaSpec.ATTACH_BUTTON_SIZE_DP)))
        capsule.addView(TextView(this).apply {
            text = "😊"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
        }, LinearLayout.LayoutParams(
            dp(WaSpec.ATTACH_BUTTON_SIZE_DP), dp(WaSpec.ATTACH_BUTTON_SIZE_DP)))
        input = EditText(this).apply {
            hint = "输入消息"
            textSize = WaSpec.MESSAGE_INPUT_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            setBackgroundResource(0)
            maxLines = WaSpec.MESSAGE_INPUT_MAX_LINES
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(WaSpec.MESSAGE_INPUT_MIN_HEIGHT_DP)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        capsule.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 正在输入…通知（有输入就 composing，3 秒无输入自动 paused —— 引擎内防抖）
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.isNotBlank() == true) engine.notifyComposing(chatJid)
                // A5/A6：@ 提及（群聊）与 / 斜杠命令建议面板
                maybeShowSuggestions(s?.toString() ?: "")
            }
        })

        // 译按钮 36dp：开/关切换（关闭时发原文）
        translateBtn = TextView(this).apply {
            text = "开"   // 对齐参考 translateButton：开/关 两态（原为「译」）
            textSize = WaSpec.TRANSLATE_BUTTON_TEXT_SP.toFloat()
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatActivity, WaSpec.GREEN_LIGHT, 10)
            setPadding(dp(WaSpec.TRANSLATE_BUTTON_PADDING_H_DP), 0,
                dp(WaSpec.TRANSLATE_BUTTON_PADDING_H_DP), 0)
            isClickable = true
            setOnClickListener {
                translateOn = !translateOn
                text = if (translateOn) "开" else "关"
                background = Ui.roundBg(this@ChatActivity,
                    if (translateOn) WaSpec.GREEN_LIGHT else WaSpec.GRAY6, 10)
                setTextColor(if (translateOn) Color.WHITE else WaSpec.textSecondary(night))
            }
        }
        capsule.addView(translateBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(WaSpec.TRANSLATE_BUTTON_HEIGHT_DP)).apply {
            marginStart = dp(6)
            })

        // 引用回复预览条（pendingQuote 非空时显示在输入胶囊上方）
        quoteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@ChatActivity, WaSpec.GRAY6, 10)
            // 对齐参考 layout_reply_bar：padding 12/8/12/8
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            addView(View(this@ChatActivity).apply {
                setBackgroundColor(WaSpec.GREEN_LIGHT)
            }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(this@ChatActivity).apply {
                id = 1001
                textSize = 13f
                setTextColor(WaSpec.textSecondary(night))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
            addView(TextView(this@ChatActivity).apply {
                text = "✕"
                textSize = 14f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(dp(10), dp(4), dp(4), dp(4))
                isClickable = true
                setOnClickListener { clearQuote() }
            })
        }
        bottomWrap.addView(quoteBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        // ── A5/A6 建议面板（@ 提及 / 斜杠命令），默认隐藏，在输入胶囊上方
        suggestionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SURFACE_NIGHT else Color.WHITE, 12, 1, WaSpec.divider(night))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
        }
        bottomWrap.addView(suggestionPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        row.addView(capsule, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 发送圆钮 48dp，左间距 4dp
        sendBtn = TextView(this).apply {
            text = "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.inputBtnIcon(night))
            // 对齐参考 input_circle_black：白底 + 1dp 淡描边 + 深色图标（夜间反转）
            background = Ui.roundBg(this@ChatActivity, WaSpec.inputBtnBg(night),
                WaSpec.PTT_SEND_BUTTON_SIZE_DP / 2, 1, WaSpec.inputBtnStroke(night))
            isClickable = true
            setOnClickListener { onSend() }
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(
            dp(WaSpec.PTT_SEND_BUTTON_SIZE_DP), dp(WaSpec.PTT_SEND_BUTTON_SIZE_DP)).apply {
            marginStart = dp(WaSpec.PTT_SEND_MARGIN_DP)
            bottomMargin = dp(2)
        })

        bottomWrap.addView(row)
        root.addView(bottomWrap)

        setContentView(root)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun avatarView(): View {
        val size = dp(WaSpec.CHAT_AVATAR_SIZE_DP)
        val cjk = chatTitle.firstOrNull { ch -> ch.toInt() > 0x2E80 }
        val letter = cjk?.toString() ?: chatTitle.firstOrNull()?.toString() ?: chatJid.take(2)
        val colors = arrayOf("#25D366", "#34B7F1", "#FFB74D", "#9575CD", "#4DB6AC", "#F06292")
        return TextView(this).apply {
            text = letter
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = Ui.roundBg(this@ChatActivity,
                Color.parseColor(colors[(chatJid.hashCode() and 0xff) % colors.size]), size / 2)
            val view = this
            engine.fetchAvatarAsync(chatJid) { f ->
                if (isDestroyed || isFinishing || f == null) return@fetchAvatarAsync
                // ANR 修复：头像解码+圆裁也移到后台线程（回调在主线程）
                Thread {
                    try {
                        // 头像显示 ≤52dp：降采样后解码，避免大图全量入内存
                        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opt)
                        var s = 1
                        while (opt.outWidth / (s * 2) >= dp(104) || opt.outHeight / (s * 2) >= dp(104)) s *= 2
                        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
                            ?: return@Thread
                        val side = minOf(bmp.width, bmp.height)
                        val out = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
                        val cv = android.graphics.Canvas(out)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        paint.shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP,
                            android.graphics.Shader.TileMode.CLAMP)
                        cv.drawCircle(side / 2f, side / 2f, side / 2f, paint)
                        main.post {
                            if (isDestroyed || isFinishing) return@post
                            view.text = ""
                            view.background = android.graphics.drawable.BitmapDrawable(resources, out)
                        }
                    } catch (_: Throwable) { }
                }.start()
            }
        }
    }

    /**
     * U2：带环的头像容器（对齐参考 headerAvatarContainer 三层：头像图 + bg_avatar_ring + 占位）。
     * 环 = 灰底圆 + 1dp 描边；头像内缩 1dp 让描边露出来（同尺寸铺满会被盖住）。
     */
    private fun avatarWithRing(): View {
        val size = dp(WaSpec.CHAT_AVATAR_SIZE_DP)
        val ring = Ui.roundBg(this@ChatActivity, WaSpec.avatarRingFill(night),
            size / 2, 1, WaSpec.avatarRingStroke(night))
        return android.widget.FrameLayout(this).apply {
            addView(android.view.View(this@ChatActivity).apply { background = ring },
                android.widget.FrameLayout.LayoutParams(size, size))
            addView(avatarView(), android.widget.FrameLayout.LayoutParams(size - dp(2), size - dp(2)).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    override fun onResume() {
        super.onResume()
        engine.onMessageTranslated = { jid, _, _ ->
            main.post { if (jid == chatJid) renderMessages() }
        }
        engine.onConnectionChanged = { c ->
            main.post {
                if (c) engine.announceOnlineAsync()
                if (::subtitle.isInitialized) subtitle.text = subtitleText(c)
            }
        }
        engine.onReceiptChanged = { jid, _, _ ->
            main.post { if (jid == chatJid) renderMessages() }
        }
        engine.onTypingChanged = { jid, typing ->
            main.post {
                if (jid == chatJid) {
                    if (::subtitle.isInitialized) subtitle.text = if (typing) "正在输入…"
                        else subtitleText(engine.isConnected())
                }
            }
        }
        engine.onPollVotesChanged = { jid, pollMsgId ->
            main.post { if (jid == chatJid) renderMessagesSoon() }
        }
        engine.db.markChatRead(chatJid)
        renderMessages()
        renderHeaderLabels()
        engine.connectAsync()
        engine.markReadOnServerAsync(chatJid)
        engine.subscribePresenceAsync(chatJid)
    }

    private fun subtitleText(connected: Boolean): String {
        val country = com.weifan.app.core.CountryDetect.label(pnCache())
        val base = (if (connected) "在线" else "离线") +
            if (country.isNotBlank()) " · $country" else " · $chatLangLabel"
        return base
    }

    private fun pnCache(): String = engine.lastPnFor(chatJid)

    override fun onPause() {
        super.onPause()
        engine.onMessageTranslated = null
        engine.onConnectionChanged = null
        engine.onReceiptChanged = null
        engine.onTypingChanged = null
    }

    // 渲染去抖：下载/回执等回调密集触发时 300ms 内只重渲染一次；
    // 另用 pendingRender 标记避免清空后重入（防 7 张图 × 7 轮重复解码）
    private val renderPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val renderDebounce = Runnable {
        renderPending.set(false)
        try { renderMessages() } catch (_: Throwable) { }
    }
    private fun renderMessagesSoon() {
        if (renderPending.compareAndSet(false, true)) {
            main.postDelayed(renderDebounce, 300)
        }
    }

    /** 头部标签徽章：读 chat_labels 渲染小胶囊；点行尾「＋标签」弹复选框 */
    private fun renderHeaderLabels() {
        if (!::labelBadgeRow.isInitialized) return
        labelBadgeRow.removeAllViews()
        val names = engine.db.chatLabelNames(chatJid)
        labelBadges.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        for ((i, n) in names.withIndex()) {
            labelBadgeRow.addView(TextView(this).apply {
                text = n
                textSize = 11f
                setTextColor(WaSpec.textSecondary(night))
                background = Ui.roundBg(this@ChatActivity, WaSpec.chipFill(false, night), 10, 1,
                    WaSpec.chipStroke(false, night))
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        // 行尾添标签入口
        labelBadgeRow.addView(TextView(this).apply {
            text = "＋标签"
            textSize = 11f
            setTextColor(WaSpec.primary(night))
            setPadding(dp(8), dp(2), dp(8), dp(2))
            isClickable = true
            setOnClickListener { showHeaderLabelPicker() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
    }

    /** 会话页直接改标签（复用列表页同一套 labels 表） */
    private fun showHeaderLabelPicker() {
        val all = engine.db.labels()
        if (all.isEmpty()) {
            Toast.makeText(this, "还没有标签，先在会话列表顶部「＋」建一个", Toast.LENGTH_LONG).show()
            return
        }
        val names = all.map { it.second }.toTypedArray()
        val checked = engine.db.chatLabelNames(chatJid).toSet()
        val states = names.map { it in checked }.toBooleanArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("设置标签")
            .setMultiChoiceItems(names, states) { _, which, isChecked -> states[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                engine.db.setChatLabels(chatJid,
                    all.filterIndexed { i, _ -> states[i] }.map { it.first })
                renderHeaderLabels()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 切换会话内搜索条（对齐参考 searchButton 行为） */
    private fun toggleChatSearch() {
        if (chatSearchBar.visibility == View.VISIBLE) {
            chatSearchBar.visibility = View.GONE
            chatSearchInput.setText(""); searchQuery = ""
            hideKeyboard(); renderMessages(false)
        } else {
            chatSearchBar.visibility = View.VISIBLE
            chatSearchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(chatSearchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken ?: window.decorView.windowToken, 0)
    }

    private fun renderMessages(stickToBottom: Boolean = true) {
        val prevHeight = listBox.height
        // 只在用户本来就贴近底部时才自动粘底；在上方阅读旧消息时不打扰
        val wasAtBottom = scroll.scrollY >= (listBox.height - scroll.height - dp(60))
        listBox.removeAllViews()
        val all = engine.db.messages(chatJid)
        // 会话内搜索过滤：原文或译文包含关键词（不区分大小写）
        val msgs = if (searchQuery.isBlank()) all else {
            val q = searchQuery.lowercase()
            all.filter { it.text.lowercase().contains(q) || it.translated.lowercase().contains(q) }
        }
        if (msgs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = if (searchQuery.isBlank()) "暂无消息\n给对方发一条中文，发出的自动是对方的语言"
                       else "没有找到包含「$searchQuery」的消息"
                textSize = WaSpec.EMPTY_HINT_SP.toFloat()
                setTextColor(WaSpec.textSecondary(night))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        for (m in msgs) listBox.addView(bubble(m))
        // 图片/贴纸/语音/文档自动下载（视频仍手动点）；下载完重渲染一次。
        // 关键：只对真正带媒体文件的消息下载。poll/location/contact/event 没有文件可下，
        // 之前一并发请求 → 每轮都失败回调 → 每 300ms 重建整个列表，
        // 导致气泡点击/长按事件全部丢失（实测发现）。失败过的也不再重试。
        val downloadable = setOf("image", "sticker", "audio", "document")
        for (m in msgs.filter {
            it.mediaKind in downloadable && it.mediaPath.isBlank() && it.id !in mediaDownloadFailed
        }) {
            engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                if (f == null) mediaDownloadFailed.add(m.id)
                main.post { renderMessagesSoon() }
            }
        }
        for (m in msgs.filter { !it.fromMe && it.translated.isBlank() && it.text.isNotBlank() }) {
            engine.translateIncomingAsync(m.chatJid, m.id, m.text)
        }
        if (stickToBottom && wasAtBottom) {
            // 双重 post：等 ScrollView 完成一轮布局再滚到底，否则消息多时会停在顶部
            main.post { scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        } else if (!stickToBottom) {
            // 历史翻页：内容头部变长，把视口停在原来那条消息上
            scroll.post {
                val delta = listBox.height - prevHeight
                if (delta > 0) scroll.scrollTo(0, delta)
            }
        }
    }

    /**
     * 媒体视图：
     * image/sticker → 缩略图立即显示，点按下载原图替换；
     * video → 缩略图 + ▶ 遮罩，点按下载；
     * audio/document → 文件行（图标+名称/大小），点按下载（audio 下载完自动播放）。
     */
    private fun mediaView(m: MessageRow): View {
        val ctx = this
        fun decodeThumb(b64: String): android.graphics.Bitmap? = try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) { null }

        fun roundBmp(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
            // 输入已经 inSampleSize 降采样（≤约480dp），拷贝一份做圆角遮罩，内存可控
            val out = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(out)
            val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP)
            }
            val r = dp(8).toFloat()
            cv.drawRoundRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), r, r, pt)
            return out
        }

        fun fileSizeLabel(): String {
            val s = m.mediaSize
            return when {
                s >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", s / 1048576.0)
                s >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", s / 1024.0)
                s > 0 -> "${s}B"
                else -> ""
            }
        }

        // 已下载的文件（命中即返回）
        fun localFile(): java.io.File? {
            if (m.mediaPath.isNotBlank()) {
                val f = java.io.File(m.mediaPath)
                if (f.exists() && f.length() > 0) return f
            }
            return null
        }

        // 图类容器：最多 240dp 高，等比缩放
        val imageView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxWidth = dp(240)
        }
        fun showImage(f: java.io.File?) {
            // ANR 修复：解码（含圆角处理）移到后台线程，主线程只 setImageBitmap。
            // 大图（600KB+ JPEG）主线程同步 decodeFile 曾卡 UI 77 秒并触发 ANR。
            fun apply(bmp: android.graphics.Bitmap?) {
                if (bmp != null) {
                    imageView.setImageBitmap(roundBmp(bmp))
                    imageView.background = null
                    imageView.minimumHeight = 0
                    imageView.minimumWidth = 0
                } else {
                    // 无缩略图且未下载：可见占位框
                    imageView.setImageDrawable(null)
                    imageView.background = Ui.roundBg(this@ChatActivity,
                        if (night) WaSpec.SURFACE_NIGHT else WaSpec.GRAY6, 8, 1, WaSpec.divider(night))
                    imageView.minimumHeight = dp(120)
                    imageView.minimumWidth = dp(180)
                }
            }
            // 先查缓存（主线程读取，LinkedHashMap 非线程安全 → 只在此处访问）
            val cacheKey = (f?.absolutePath ?: "thumb:") + "|" + (m.id)
            val cached = synchronized(bmpCache) { bmpCache[cacheKey] }
            if (cached != null) { apply(cached); return }
            if (f == null) {
                val tb = decodeThumb(m.mediaThumb)
                if (tb != null) synchronized(bmpCache) { bmpCache[cacheKey] = tb }
                apply(tb); return
            }
            Thread {
                // 内存修复：气泡显示宽 ≤240dp，inSampleSize 降采样，禁止全尺寸解码
                // （曾把 RSS 从 148MB 推到 1.4GB 被 lmkd 杀）
                fun sampleFile(): android.graphics.Bitmap? = try {
                    val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opt)
                    var s = 1
                    while (opt.outWidth / (s * 2) >= dp(240) * 2 || opt.outHeight / (s * 2) >= dp(240) * 2) s *= 2
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
                } catch (_: Throwable) { null }
                val bmp = sampleFile()
                if (bmp != null) synchronized(bmpCache) { bmpCache[cacheKey] = bmp }
                main.post {
                    if (isDestroyed || isFinishing) return@post
                    val again = synchronized(bmpCache) { bmpCache[cacheKey] }
                    apply(again ?: bmp ?: decodeThumb(m.mediaThumb))
                }
            }.start()
        }


        return when (m.mediaKind) {
            "poll" -> {
                // 真投票渲染：📊 问题 + 选项圆点行 + "投票 · 点按查看" 提示（对齐 WhatsApp）
                val meta = try { JSONObject(m.mediaMeta) } catch (_: Throwable) { JSONObject() }
                val opts = meta.optString("waveform").split("\n").filter { it.isNotBlank() }
                // v2.9.0：落库票数（poll_votes）→ 选项行尾显示票数徽章；点击选项直接投票
                val counts = engine.db.pollCounts(m.id)
                val voters = engine.db.pollVoterCount(m.id)
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = "📊  " + m.mediaName.ifBlank { "投票" }
                    textSize = 14.5f
                    setTextColor(WaSpec.textPrimary(night))
                })
                val hasVotes = counts.isNotEmpty()
                for (opt in opts.take(8)) {
                    val n = counts[opt] ?: 0
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, 0)
                    }
                    row.addView(View(this).apply {
                        background = Ui.roundBg(this@ChatActivity,
                            if (night) 0xFF8696A0.toInt() else 0xFF8696A0.toInt(), dp(11))
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    row.addView(TextView(this).apply {
                        text = opt
                        textSize = 13.5f
                        setTextColor(WaSpec.textPrimary(night))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(9); weight = 1f })
                    if (hasVotes) {
                        row.addView(TextView(this).apply {
                            text = "$n"
                            textSize = 13f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(if (n > 0) WaSpec.primary(night) else WaSpec.textSecondary(night))
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                    }
                    // 点选项即投票（改票=重投）；只对我收到的投票开放（我发的由对方投）
                    row.isClickable = true
                    row.isFocusable = true
                    android.util.Log.i("WeFlipPoll", "bind row opt=$opt msg=${m.id}")
                    row.setOnClickListener {
                        android.util.Log.i("WeFlipPoll", "CLICKED opt=$opt msg=${m.id}")
                        voteOnPoll(m, opts, opt)
                    }
                    col.addView(row)
                }
                // 点这行 = 看结果（对齐参考 dialog_poll_results）；选项行仍单独投票
                col.addView(TextView(this).apply {
                    text = if (hasVotes) "$voters 人已投 · 点选项投票/改票 · 点这里看结果"
                           else "点选项可投票 · 点这里看结果"
                    textSize = 11.5f
                    setTextColor(WaSpec.textSecondary(night))
                    setPadding(0, dp(7), 0, 0)
                    isClickable = true
                    setOnClickListener { showPollResults(m) }
                })
                col
            }
            "location" -> {
                // 位置卡片：🌍 地图占位图 + 地名/经纬度
                val meta = try { JSONObject(m.mediaMeta) } catch (_: Throwable) { JSONObject() }
                val latlng = meta.optString("waveform") // "lat|lng|address"
                val parts = latlng.split("|")
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val map = TextView(this).apply {
                    text = "🌍"
                    textSize = 44f
                    gravity = Gravity.CENTER
                    minHeight = dp(110)
                    background = Ui.roundBg(this@ChatActivity,
                        if (night) 0xFF2A3942.toInt() else 0xFFE7EDEA.toInt(), dp(10))
                }
                col.addView(map, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
                col.addView(TextView(this).apply {
                    text = m.mediaName.ifBlank { "位置" }
                    textSize = 14f
                    setTextColor(WaSpec.textPrimary(night))
                    setPadding(0, dp(6), 0, 0)
                    maxLines = 1
                })
                if (parts.size >= 2) col.addView(TextView(this).apply {
                    text = parts[0] + ", " + parts[1]
                    textSize = 11.5f
                    setTextColor(WaSpec.textSecondary(night))
                })
                col
            }
            "contact" -> {
                // 联系人卡片：👤 + 姓名 + vCard 摘要（点击可看完整 vCard）
                val meta = try { JSONObject(m.mediaMeta) } catch (_: Throwable) { JSONObject() }
                val vcard = meta.optString("thumbnail")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = "👤"
                    textSize = 26f
                    gravity = Gravity.CENTER
                    background = Ui.roundBg(this@ChatActivity,
                        if (night) 0xFF2A3942.toInt() else 0xFFDFE9E4.toInt(), dp(24))
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = m.mediaName.ifBlank { "联系人" }
                    textSize = 14.5f
                    setTextColor(WaSpec.textPrimary(night))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val tel = vcard.lineSequence().firstOrNull { it.startsWith("TEL") }
                    ?.substringAfter(':')?.trim().orEmpty()
                if (tel.isNotBlank()) col.addView(TextView(this).apply {
                    text = tel
                    textSize = 11.5f
                    setTextColor(WaSpec.textSecondary(night))
                    maxLines = 1
                })
                row.addView(col, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                row.isClickable = true
                row.setOnClickListener {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(m.mediaName.ifBlank { "联系人" })
                        .setMessage(vcard.ifBlank { "（无 vCard 内容）" })
                        .setPositiveButton("关闭", null)
                        .show()
                }
                row
            }
            "image", "sticker", "video" -> {
                showImage(localFile())
                // imageView / play / wrap 三层都挂监听：只挂 wrap 时实机点不到
                // （点击被上层子 View 吃掉的典型情况），三层都设等于无论点哪个像素都能开
                if (m.mediaKind == "video") {
                    // ▶ 遮罩叠层
                    val wrap = android.widget.FrameLayout(this)
                    wrap.addView(imageView, android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // ▶ 只做视觉叠层：不可点击，事件留给下层 imageView（图片分支已验证有效）
                    val play = TextView(this).apply {
                        text = "▶"
                        textSize = 22f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        isClickable = false
                        isFocusable = false
                        background = Ui.roundBg(this@ChatActivity, 0x66000000, 24)
                    }
                    wrap.addView(play, android.widget.FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                        gravity = Gravity.CENTER
                    })
                    wrap.isClickable = false
                    wrap.isFocusable = false
                    // 监听直接挂在 imageView（与图片分支同构，那条实测能进）
                    val openIt = View.OnClickListener { openVideo(m) }
                    imageView.isClickable = true
                    imageView.setOnClickListener(openIt)
                    wrap
                } else {
                    imageView.isClickable = true
                    // 图片/贴图：点开全屏查看器，可左右滑看同一会话的全部媒体
                    imageView.setOnClickListener { openMediaViewer(m) }
                    imageView
                }
            }
            else -> {
                if (m.mediaKind == "audio") {
                    // 1:1 语音条：绿色播放圆钮 + 波形 + 时长（对齐 WATransChat）
                    val meta = try { JSONObject(m.mediaMeta) } catch (_: Throwable) { JSONObject() }
                    val seconds = meta.optInt("seconds")
                    val duration = if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else ""
                    val waveData = try {
                        val w = meta.optString("waveform")
                        if (w.isNotBlank()) android.util.Base64.decode(w, android.util.Base64.DEFAULT) else null
                    } catch (_: Throwable) { null }

                    val playBtn = TextView(this).apply {
                        text = "▶"
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background = Ui.roundBg(this@ChatActivity, WaSpec.GREEN_LIGHT,
                            WaSpec.PTT_SEND_BUTTON_SIZE_DP / 2)
                    }
                    val wave = WaveformView(this, night).apply {
                        setData(waveData, m.id)
                    }
                    val dur = TextView(this).apply {
                        text = duration
                        textSize = 12f
                        setTextColor(WaSpec.textSecondary(night))
                    }
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    row.addView(playBtn, LinearLayout.LayoutParams(
                        dp(44), dp(44)))
                    row.addView(wave, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                        marginStart = dp(8); marginEnd = dp(8)
                    })
                    if (duration.isNotBlank()) row.addView(dur)

                    fun setPlayingUI(playing: Boolean) {
                        playBtn.text = if (playing) "⏸" else "▶"
                        wave.animateProgress(playing)
                    }
                    row.setOnClickListener {
                        if (playingRow === row) { stopPlayback(); setPlayingUI(false); return@setOnClickListener }
                        engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                            if (f == null) {
                                Toast.makeText(this@ChatActivity, "下载失败，请点重试",
                                    Toast.LENGTH_SHORT).show()
                                return@fetchMediaAsync
                            }
                            stopPlayback()
                            try {
                                val mp = android.media.MediaPlayer()
                                mp.setDataSource(f.absolutePath)
                                mp.setOnCompletionListener {
                                    setPlayingUI(false)
                                    playingRow = null
                                    it.release()
                                    if (player === it) player = null
                                }
                                mp.setOnErrorListener { p, _, _ ->
                                    setPlayingUI(false)
                                    playingRow = null
                                    p.release(); if (player === p) player = null
                                    Toast.makeText(this@ChatActivity, "该语音格式不支持播放",
                                        Toast.LENGTH_SHORT).show()
                                    true
                                }
                                mp.prepare()
                                mp.start()
                                player = mp
                                playingRow = row
                                setPlayingUI(true)
                            } catch (e: Throwable) {
                                Toast.makeText(this@ChatActivity,
                                    "播放失败：${e.message ?: "未知"}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    playingRowViewRef = row to playBtn
                    row
                } else {
                    // document 文件行
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                    }
                    row.addView(TextView(this).apply {
                        text = "📄"
                        textSize = 18f
                    })
                    val colX = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    colX.addView(TextView(this).apply {
                        text = m.mediaName.ifBlank { "文件" }
                        textSize = 14f
                        setTextColor(WaSpec.textPrimary(night))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                    colX.addView(TextView(this).apply {
                        text = fileSizeLabel()
                        textSize = 11.5f
                        setTextColor(WaSpec.textSecondary(night))
                    })
                    row.addView(colX, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
                    row.setOnClickListener {
                        // 本地已有就直接开，否则先下再开（对标参考 PdfViewerActivity）
                        val local = localFile()
                        if (local != null && local.exists()) openDocument(local, m)
                        else {
                            Toast.makeText(this@ChatActivity, "正在下载…", Toast.LENGTH_SHORT).show()
                            engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                                if (f != null && f.exists()) openDocument(f, m)
                                else Toast.makeText(this@ChatActivity, "下载失败",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    row
                }
            }
        }
    }

    // MARK: - 媒体打开（A1/A2：独立 viewer）

    private fun existingFile(m: MessageRow): java.io.File? {
        if (m.mediaPath.isBlank()) return null
        val f = java.io.File(m.mediaPath)
        return if (f.exists()) f else null
    }

    /** 图片/贴图：全屏查看器（可左右滑看本会话全部媒体） */
    private fun openMediaViewer(m: MessageRow) {
        android.util.Log.i("WeFlipOpen", "openMediaViewer id=${m.id} kind=${m.mediaKind} path=${m.mediaPath}")
        if (existingFile(m) == null) {
            engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                if (f != null && f.exists()) startActivity(Intent(this, MediaViewerActivity::class.java)
                    .putExtra("jid", chatJid).putExtra("msgId", m.id))
                else Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show()
            }
            return
        }
        startActivity(Intent(this, MediaViewerActivity::class.java)
            .putExtra("jid", chatJid).putExtra("msgId", m.id))
    }

    /** 视频：确保已下载后开独立播放器 */
    private fun openVideo(m: MessageRow) {
        android.util.Log.i("WeFlipOpen", "openVideo id=${m.id} path=${m.mediaPath}")
        val open = { f: java.io.File? ->
            if (f != null && f.exists()) startActivity(Intent(this, VideoViewerActivity::class.java)
                .putExtra("path", f.absolutePath)
                .putExtra("name", m.mediaName.ifBlank { f.name }))
            else Toast.makeText(this, "视频下载失败", Toast.LENGTH_SHORT).show()
            Unit
        }
        val local = existingFile(m)
        if (local != null) open(local)
        else {
            Toast.makeText(this, "正在下载视频…", Toast.LENGTH_SHORT).show()
            engine.fetchMediaAsync(m.chatJid, m.id) { f -> open(f) }
        }
    }

    /** 文档：PDF 进专用查看器，其它交给系统「打开方式」 */
    private fun openDocument(f: java.io.File, m: MessageRow) {
        val name = m.mediaName.ifBlank { f.name }
        val isPdf = name.endsWith(".pdf", true) || m.mediaMime.contains("pdf", true) ||
            f.name.endsWith(".pdf", true)
        if (isPdf) {
            startActivity(Intent(this, PdfViewerActivity::class.java)
                .putExtra("path", f.absolutePath).putExtra("name", name))
            return
        }
        // 非 PDF：文本类用内置预览；其它交系统「打开方式」（零依赖，不经 FileProvider）
        val isText = listOf(".txt", ".md", ".csv", ".json", ".xml", ".log")
            .any { name.endsWith(it, true) } || m.mediaMime.startsWith("text/")
        if (isText) {
            openTextPreview(f, name)
            return
        }
        try {
            val uri = android.net.Uri.fromFile(f)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, m.mediaMime.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Throwable) {
            // file:// 在 targetSdk≥24 可能被拦，所以给个可执行的下一步而不是死路
            Toast.makeText(this,
                "没有能打开这种文件的应用。长按气泡可另存到手机。", Toast.LENGTH_LONG).show()
        }
    }

    /** 文本类文件的内置预览（等宽字体 + 选框，大文件只读前 200KB） */
    private fun openTextPreview(f: java.io.File, name: String) {
        val content = try {
            val max = 200 * 1024
            f.inputStream().use { ins ->
                val buf = ByteArray(max)
                val n = ins.read(buf)
                if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (e: Throwable) { "读取失败：${e.message ?: e}" }
        val tv = TextView(this).apply {
            text = content
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(WaSpec.textPrimary(night))
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val sv = android.widget.ScrollView(this).apply {
            addView(tv)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }


    // MARK: - A5/A6 输入建议（@ 提及 / 斜杠命令）

    /** 斜杠命令表：跨境场景最常用的几句模板，选中即插入 */
    private val slashCommands = listOf(
        "/quote" to "报价模板",
        "/lead" to "交期说明",
        "/ship" to "发货地址",
        "/pay" to "付款方式",
        "/thanks" to "感谢跟进"
    )

    private fun slashBody(cmd: String): String = when (cmd) {
        "/quote" -> "Price: USD ___ /pc, FOB Shenzhen, MOQ ___. Quantity and delivery to be confirmed."
        "/lead" -> "Lead time: ___ working days after deposit received."
        "/ship" -> "Shipping address: ___, contact: ___, phone: ___."
        "/pay" -> "Payment: 30% deposit, 70% before shipment. T/T or Alibaba Trade Assurance."
        "/thanks" -> "Thanks for your inquiry. Please let me know if you need any further details."
        else -> ""
    }

    /** 根据当前输入决定是否弹面板：行首『/』→ 命令；群聊里『@』→ 成员 */
    private fun maybeShowSuggestions(text: String) {
        if (!::suggestionPanel.isInitialized) return
        if (text.startsWith("/") && !text.contains(' ')) {
            val kw = text.drop(1)
            val hits = slashCommands.filter { kw.isBlank() || it.first.drop(1).startsWith(kw, true) }
            showSuggestionItems(hits.map { it.first to it.second }) { cmd, _ ->
                input.setText(slashBody(cmd))
                input.setSelection(input.text.length)
                hideSuggestions()
            }
            return
        }
        if (chatJid.contains("@g.us")) {
            val cur = input.selectionStart.coerceAtLeast(0)
            val before = if (cur <= text.length) text.substring(0, cur) else text
            val at = before.lastIndexOf('@')
            if (at >= 0 && !before.substring(at).contains(' ')) {
                val kw = before.substring(at + 1)
                ensureGroupMembers()
                val hits = groupMembers.filter { kw.isBlank() || it.second.contains(kw, true) }
                if (hits.isNotEmpty()) {
                    showSuggestionItems(hits.map { it.second to "@${it.second}" }) { _, label ->
                        val start = at
                        val end = input.selectionStart.coerceAtLeast(start)
                        if (end <= input.text.length) input.text.replace(start, end, "$label ")
                        hideSuggestions()
                    }
                    return
                }
            }
        }
        hideSuggestions()
    }

    /** 群成员：首次用时从协议层拉，失败不阻塞输入 */
    private fun ensureGroupMembers() {
        if (groupMembers.isNotEmpty()) return
        Thread {
            val list = mutableListOf<Pair<String, String>>()
            try {
                val raw = engine.client.getGroupInfo(chatJid)
                val o = JSONObject(raw)
                val arr = o.optJSONArray("participants") ?: o.optJSONArray("members")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val phone = p.optString("phone").ifBlank { p.optString("jid") }
                        val name = p.optString("name").ifBlank { phone.substringBefore("@") }
                        if (phone.isNotBlank()) list.add(phone to name)
                    }
                }
            } catch (_: Throwable) { }
            main.post {
                if (isFinishing || isDestroyed) return@post
                groupMembers = list.take(120)
            }
        }.start()
    }

    private fun showSuggestionItems(items: List<Pair<String, String>>,
                                    onPick: (String, String) -> Unit) {
        if (items.isEmpty()) { hideSuggestions(); return }
        suggestionPanel.removeAllViews()
        // 参考区分两种行：
        //   item_slash_command：padding 14/4，命令名 15sp + 描述 13sp（两列）
        //   item_group_mention_row：padding 12/8，单一 14sp
        val isMention = items.firstOrNull()?.second?.startsWith("@") == true
        for ((k, label) in items.take(6)) {
            val padH = if (isMention) 12 else 14
            val padV = if (isMention) 8 else 4
            if (isMention) {
                suggestionPanel.addView(TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(WaSpec.textPrimary(night))
                    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
                    isClickable = true
                    setOnClickListener { onPick(k, label) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
                    isClickable = true
                    setOnClickListener { onPick(k, label) }
                }
                row.addView(TextView(this).apply {
                    text = k
                    textSize = 15f
                    setTextColor(WaSpec.primary(night))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(WaSpec.textSecondary(night))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                })
                suggestionPanel.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        suggestionPanel.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        if (::suggestionPanel.isInitialized) {
            suggestionPanel.visibility = View.GONE
            suggestionPanel.removeAllViews()
        }
    }

    /** 气泡：10dp 圆角、左右 inset 8/30dp、上下 3dp；发件绿、收件白；媒体先图后文 */
    private fun bubble(m: MessageRow): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // ---- 媒体区（在文字上方）----
        if (m.mediaKind.isNotBlank()) {
            val mv = mediaView(m)
            // 媒体行自身可点击（播放/下载），长按也必须弹菜单
            mv.isLongClickable = true
            mv.setOnLongClickListener { showActionMenu(m); true }
            col.addView(mv)
        }
        // 文本行（有文字或有 caption 才显示）；
        // poll/location/contact 三类卡片已在媒体区完整渲染，跳过重复文字行
        if (m.text.isNotBlank() && m.mediaKind !in setOf("poll", "location", "contact")) {
        col.addView(TextView(this).apply {
            text = m.text
            textSize = prefs.sourceTextSp   // 批2-d：原文字号可调
            setTextColor(WaSpec.textPrimary(night))
            // 长按直接挂菜单：只设 isLongClickable=false 不管用，TextView 会自己进入
            // 文本选择模式（实测），直接在控件上接管长按才稳
            isLongClickable = true
            setOnLongClickListener { showActionMenu(m); true }
        })
        }
        // 收件：译文行（蓝色，13sp）
        if (!m.fromMe && m.translated.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = m.translated
                textSize = prefs.translationTextSp   // B4：译文字号与原文分开调
                // 批2-c：译文颜色可自定义（0 = 默认青蓝）
                setTextColor(prefs.translationColor.takeIf { it != 0 } ?: WaSpec.TRANSLATE_TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(3), 0, 0)
                isClickable = true
                // 点译文 = 对译文不满意 → 弹「用哪个接口重译」
                // （长按菜单仍保留，但 adb 自动化下长按不可靠，点击更直接也好用）
                setOnClickListener { showRetranslatePicker(m) }
                isLongClickable = true
                setOnLongClickListener { showActionMenu(m); true }
            })
        }
        // 发件：中文原文副行（灰色）
        if (m.fromMe && m.original.isNotBlank() && m.original != m.text) {
            col.addView(TextView(this).apply {
                text = m.original
                textSize = 13f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(2), 0, 0)
            })
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(TextView(this@ChatActivity).apply {
                text = timeFmt.format(Date(m.ts))
                textSize = 10.5f
                setTextColor(WaSpec.textSecondary(night))
            })
            if (m.fromMe) addView(TextView(this@ChatActivity).apply {
                // 官方规则：已读=蓝 ✓✓，未读/无回执=灰 ✓✓
                text = "✓✓"
                textSize = 10.5f
                setTextColor(if (engine.readReceipts[m.id] == true) WaSpec.READ_RECEIPT
                    else WaSpec.textSecondary(night))
                setPadding(dp(3), 0, 0, 0)
            })
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = FrameLayout(this)
        // 点击和长按都弹菜单：仅靠长按时，实测在滚动容器里经常收不到事件，
        // 点击气泡主体更稳也更易发现
        col.isClickable = true
        col.setOnClickListener { showActionMenu(m) }
        col.isLongClickable = true
        col.setOnLongClickListener { showActionMenu(m); true }
        holder.addView(col.apply {
            background = Ui.roundBg(this@ChatActivity,
                WaSpec.bubbleSent(night).let { if (m.fromMe) it else WaSpec.bubbleReceived(night) },
                WaSpec.BUBBLE_CORNER_DP)
            // 内边距按消息类型区分（对齐参考各 item_message_* 布局）：
            //   文本 10/6/10/6；媒体 8/6/8/6；poll·event 12/10/12/0
            when {
                m.mediaKind == "poll" || m.mediaKind == "event" ->
                    setPadding(dp(12), dp(10), dp(12), 0)
                m.mediaKind.isNotBlank() ->
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                else -> setPadding(dp(10), dp(6), dp(10), dp(6))
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (m.fromMe) Gravity.END else Gravity.START
            setMargins(
                dp(if (m.fromMe) WaSpec.BUBBLE_OPPOSITE_INSET_DP else WaSpec.BUBBLE_SIDE_INSET_DP),
                dp(4),   // 上下 4dp：对齐参考 item_message_* 的 paddingVertical
                dp(if (m.fromMe) WaSpec.BUBBLE_SIDE_INSET_DP else WaSpec.BUBBLE_OPPOSITE_INSET_DP),
                dp(4))
        })
        return holder
    }

    /** 发送：默认直译直发；「译」按钮关掉后发原文 */
    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        val q = pendingQuote
        if (q != null) {
            sendBtn.isEnabled = false
            val done: (String?, String?) -> Unit = { _, err ->
                sendBtn.isEnabled = true
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                else { input.setText(""); clearQuote(); renderMessages() }
            }
            if (translateOn) {
                engine.translateOutgoingAsync(chatJid, text) { t, _, err ->
                    if (err != null || t.isBlank()) {
                        // 降级：翻译不可用时发原文，避免把用户卡死
                        Toast.makeText(this@ChatActivity, "翻译不可用，已发原文", Toast.LENGTH_SHORT).show()
                        engine.sendReplyAsync(chatJid, q.first, q.second, q.third, text, done)
                    } else engine.sendReplyAsync(chatJid, q.first, q.second, q.third, t, done)
                }
            } else engine.sendReplyAsync(chatJid, q.first, q.second, q.third, text, done)
            return
        }
        sendBtn.isEnabled = false
        if (translateOn) {
            engine.sendWithTranslation(chatJid, text) { _, err ->
                if (err != null) {
                    // 降级：翻译不可用直接发原文，不把用户卡死
                    Toast.makeText(this, "翻译不可用，已发原文", Toast.LENGTH_SHORT).show()
                    engine.sendText(chatJid, text, original = text) { _, err2 ->
                        sendBtn.isEnabled = true
                        if (err2 != null) Toast.makeText(this, err2, Toast.LENGTH_LONG).show()
                        else { input.setText(""); renderMessages() }
                    }
                } else {
                    sendBtn.isEnabled = true
                    input.setText("")
                    renderMessages()
                }
            }
        } else {
            engine.sendText(chatJid, text) { _, err ->
                sendBtn.isEnabled = true
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                } else {
                    input.setText("")
                    renderMessages()
                }
            }
        }
    }

    private fun clearQuote() {
        pendingQuote = null
        quoteBar.visibility = View.GONE
    }

    private fun setQuote(q: Triple<String, String, String>) {
        pendingQuote = q
        quoteBar.findViewById<TextView>(1001).text = q.third
        quoteBar.visibility = View.VISIBLE
        input.requestFocus()
    }

    // MARK: - 长按菜单（回复/转发/复制/下载，对齐 WATransChat）

    private fun showActionMenu(m: MessageRow) {
        android.util.Log.i("WeFlipMenu", "showActionMenu id=${m.id} fromMe=${m.fromMe}")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SURFACE_NIGHT else Color.WHITE, 20)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val dlg = android.app.AlertDialog.Builder(this).create()
        fun item(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(WaSpec.textPrimary(night))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = true
            setOnClickListener { action() }
        }
        box.addView(item("回复") { dlg.dismiss(); setQuote(Triple(m.id, m.senderJid, m.text.ifBlank { "[媒体]" })) })
        // 媒体消息：菜单里也给一个「打开」入口（长按比点准，且与微信习惯一致）
        // 投票：查看结果（对齐参考 dialog_poll_results）
        if (m.mediaKind == "poll") {
            box.addView(item("查看投票结果") { dlg.dismiss(); showPollResults(m) })
        }
        if (m.mediaKind.isNotBlank()) box.addView(item(
            when (m.mediaKind) {
                "video" -> "播放视频"
                "document" -> "打开文件"
                else -> "查看大图"
            }
        ) {
            dlg.dismiss()
            when (m.mediaKind) {
                "video" -> openVideo(m)
                "document" -> {
                    val local = existingFile(m)
                    if (local != null) openDocument(local, m) else {
                        Toast.makeText(this, "正在下载…", Toast.LENGTH_SHORT).show()
                        engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                            if (f != null && f.exists()) openDocument(f, m)
                            else Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> openMediaViewer(m)
            }
        })
        box.addView(item("转发") { dlg.dismiss(); showForwardPicker(m) })
        if (m.text.isNotBlank()) box.addView(item("复制") {
            dlg.dismiss()
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("weifan", m.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        })
        // B6：收件消息可换另一家引擎重译（当前译文不对 / 这家接口挂了）
        if (!m.fromMe && m.text.isNotBlank()) {
            box.addView(item("换引擎重译") { dlg.dismiss(); showRetranslatePicker(m) })
        }
        // 自己发的消息：撤回 / 编辑（官方规则：编辑 15 分钟窗口）
        if (m.fromMe) {
            box.addView(item("撤回") {
                dlg.dismiss()
                // 对齐参考 dialog_revoke_message：撤回前先确认（正文 15sp + padding 20）
                android.app.AlertDialog.Builder(this)
                    .setTitle("撤回这条消息？")
                    .setView(Ui.confirmBody(this, "撤回后对方也会看到「已撤回」，无法恢复。",
                        m.text.take(60).ifBlank { null }, night))
                    .setPositiveButton("撤回") { _, _ ->
                        engine.revokeAsync(chatJid, m.id) { ok ->
                            Toast.makeText(this,
                                if (ok) "已撤回" else "撤回失败", Toast.LENGTH_SHORT).show()
                            if (ok) renderMessages()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            })
            if (m.mediaKind.isBlank()) box.addView(item("编辑") {
                dlg.dismiss()
                showEditDialog(m)
            })
        }
        if (m.mediaKind.isNotBlank()) box.addView(item("下载") {
            dlg.dismiss()
            engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                Toast.makeText(this,
                    if (f != null) "已保存：${f.name}" else "下载失败", Toast.LENGTH_SHORT).show()
            }
        })
        dlg.setView(box)
        dlg.show()
        dlg.window?.setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 编辑弹窗：预填原文，确认后广播 EDIT */
    private fun showEditDialog(m: MessageRow) {
        val input = EditText(this).apply {
            setText(m.text)
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("编辑消息")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotBlank() && t != m.text) {
                    engine.editAsync(chatJid, m.id, t) { ok ->
                        Toast.makeText(this, if (ok) "已编辑" else "编辑失败", Toast.LENGTH_SHORT).show()
                        if (ok) renderMessages()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * B6：选一家引擎重译这条消息。
     * 列出已配置密钥的引擎；没有则提示先去设置填。
     */
    /**
     * 客户语言设置弹层（对齐参考 dialog_customer_language）。
     * 精确规格（从参考布局 dump）：容器垂直 + padding 20dp；
     * 两个标签 14sp；两个下拉各 48dp 高 + padding 12dp；
     * 第一个下拉 marginTop 8dp / marginBottom 16dp，第二个仅 marginTop 8dp。
     *
     * 内容是两个维度：目标语言（客户说什么）+ 翻译引擎（这家客户用哪家接口）。
     * 一旦指定，该会话的发送翻译就锁定这个语言/引擎，不再靠自动检测。
     */
    private fun showCustomerLanguageDialog() {
        val cur = prefs.chatSetting(chatJid)
        var lang = cur.lang
        var engine = cur.engine

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(4))
        }

        // ── 目标语言（客户说什么）
        box.addView(TextView(this).apply {
            text = "目标语言（该客户说什么）"
            textSize = 14f
            setTextColor(WaSpec.textPrimary(night))
        })
        val langBtn = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 10)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
        }
        box.addView(langBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8); bottomMargin = dp(16)
        })

        // ── 翻译引擎（该客户用哪家）
        box.addView(TextView(this).apply {
            text = "翻译引擎（该客户用哪家）"
            textSize = 14f
            setTextColor(WaSpec.textPrimary(night))
        })
        val engineBtn = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 10)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
        }
        box.addView(engineBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        fun refresh() {
            langBtn.text = if (lang.isBlank()) "自动检测（跟随对方国家）"
            else com.weifan.app.core.CountryDetect.langName(lang) + "  " + lang
            engineBtn.text = if (engine.isBlank()) "跟随全局优先级"
            else com.weifan.app.core.Engine.configuredEngines(prefs)
                .firstOrNull { it.first == engine }?.second ?: "$engine（未配置）"
        }
        refresh()

        langBtn.setOnClickListener {
            val codes = com.weifan.app.core.Lang.sendTargets
            val labels = listOf("自动检测（跟随对方国家）") +
                codes.map { com.weifan.app.core.CountryDetect.langName(it) + "  " + it }
            android.app.AlertDialog.Builder(this)
                .setTitle("目标语言")
                .setItems(labels.toTypedArray()) { _, which ->
                    lang = if (which == 0) "" else codes[which - 1]
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        engineBtn.setOnClickListener {
            val engines = com.weifan.app.core.Engine.configuredEngines(prefs)
            val labels = listOf("跟随全局优先级") + engines.map { it.second }
            android.app.AlertDialog.Builder(this)
                .setTitle("翻译引擎")
                .setItems(labels.toTypedArray()) { _, which ->
                    engine = if (which == 0) "" else engines[which - 1].first
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("客户语言")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.setChatSetting(chatJid,
                    Prefs.ChatSetting(lang = lang, engine = engine))
                // 锁定的语言同步到内存，副标题与发送翻译立刻生效
                if (lang.isNotBlank()) this@ChatActivity.engine.chatLangMemory[chatJid] = lang
                chatLangLabel = if (lang.isNotBlank())
                    com.weifan.app.core.CountryDetect.label(chatJid).ifBlank { chatLangLabel }
                else chatLangLabel
                Toast.makeText(this, "已保存该客户的设置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRetranslatePicker(m: MessageRow) {
        val engines = com.weifan.app.core.Engine.configuredEngines(prefs)
        if (engines.isEmpty()) {
            Toast.makeText(this, "还没有已配密钥的接口，先到设置里填一家",
                Toast.LENGTH_LONG).show()
            return
        }
        val names = engines.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("用哪个接口重译")
            .setItems(names) { _, which ->
                val (id, label) = engines[which]
                Toast.makeText(this, "$label 翻译中…", Toast.LENGTH_SHORT).show()
                engine.retranslateAsync(chatJid, m.id, m.text, id) { _, err ->
                    if (err != null) {
                        Toast.makeText(this, "$label 失败: $err", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "$label 重译完成", Toast.LENGTH_SHORT).show()
                        renderMessages(false)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 转发：会话选择器（当前会话除外） */
    private fun showForwardPicker(m: MessageRow) {
        val allChats = engine.db.chats().filter { it.jid != chatJid }
        if (allChats.isEmpty()) {
            Toast.makeText(this, "暂无其他会话可转发", Toast.LENGTH_SHORT).show()
            return
        }
        // 对齐参考 dialog_forward_chat：20sp 标题 + 15sp 搜索框 + 会话列表
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dlg: android.app.AlertDialog

        fun renderList(query: String) {
            listBox.removeAllViews()
            val hits = allChats.filter {
                query.isBlank() || it.title.contains(query, true) || it.jid.contains(query)
            }
            if (hits.isEmpty()) {
                listBox.addView(TextView(this).apply {
                    text = "没有匹配的会话"
                    textSize = 14f
                    setTextColor(WaSpec.textSecondary(night))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
                return
            }
            for (c in hits.take(30)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    isClickable = true
                    setOnClickListener {
                        dlg.dismiss()
                        if (m.mediaKind.isNotBlank() && m.mediaMeta.isNotBlank()) {
                            engine.forwardMediaAsync(c.jid, m.mediaMeta) { _, err ->
                                Toast.makeText(this@ChatActivity,
                                    if (err != null) "转发失败：$err" else "已转发",
                                    Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            engine.forwardTextAsync(c.jid, m.text) { _, err ->
                                Toast.makeText(this@ChatActivity,
                                    if (err != null) "转发失败：$err" else "已转发",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                row.addView(initialAvatar(c.title.ifBlank { c.jid.substringBefore("@") }, dp(36)))
                row.addView(TextView(this).apply {
                    text = c.title.ifBlank { c.jid.substringBefore("@") }
                    textSize = 15f
                    setTextColor(WaSpec.textPrimary(night))
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                })
                listBox.addView(row)
            }
        }

        val shell = Ui.pickerDialogShell(this, "转发到", "搜索会话", night) { q -> renderList(q) }
        renderList("")
        // ScrollView 没有 maxHeight 属性，用固定高度上限（参考 slash_command_panel 430dp）
        val scroll = android.widget.ScrollView(this).apply {
            addView(listBox)
        }
        shell.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        dlg = android.app.AlertDialog.Builder(this)
            .setView(shell)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        dlg.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    // MARK: - 附件面板（对齐 WATransChat 附加菜单）

    private var attachDialog: android.app.AlertDialog? = null

    private fun showAttachmentPanel() {
        // 泄漏治理：重复点＋先 dismiss 旧面板再建新实例（曾出现多窗口叠加）
        attachDialog?.dismiss()
        val items = listOf(
            "📄  文档" to "doc",
            "🖼️  照片和视频" to "media",
            "📷  相机" to "camera",
            "🎤  音频" to "audio",
            "👤  联系人" to "contact",
            "📍  位置" to "location",
            "📊  投票" to "poll",
            "📅  活动" to "event",
            "😀  新建贴图" to "sticker",
            "🏪  目录" to "catalog",
            "⚡  快速回复" to "quick",
            "✅  添加待办" to "todo",
            "💱  汇率转换" to "fx"
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SURFACE_NIGHT else Color.WHITE, 20)
            // 对齐参考 layout_attach_panel：padding 8/10/8/8
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }
        val dlg = android.app.AlertDialog.Builder(this).create()
        attachDialog = dlg
        dlg.setOnDismissListener { if (attachDialog === dlg) attachDialog = null }
        // 横向图标条（对齐参考 layout_attach_panel：面板高 108dp、横向滚动、
        // 每项图标 56dp 圆形 + 下方 12sp 文字；项内 padding 10/4/10/4）
        val hScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        for ((label, key) in items) {
            // items 形如 "📄  文档"：双空格分开图标与文字
            val cut = label.indexOf("  ")
            val emoji = if (cut > 0) label.substring(0, cut).trim() else label
            val name = if (cut > 0) label.substring(cut).trim() else ""
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isClickable = true
                setOnClickListener {
                    dlg.dismiss()
                    when (key) {
                        "doc" -> pickDocument()
                        "media" -> pickImage()
                        "audio" -> showRecordDialog()
                        "location" -> showLocationDialog()
                        "contact" -> showContactDialog()
                        "poll" -> showPollDialog()
                        "fx" -> showFxDialog()
                        "todo" -> startActivity(
                            Intent(this@ChatActivity, FollowupTodosActivity::class.java)
                                .putExtra("jid", chatJid))
                        else -> Toast.makeText(this@ChatActivity, "该附件类型即将上线",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            cell.addView(TextView(this).apply {
                this.text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                background = Ui.roundBg(this@ChatActivity,
                    if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 28)
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            cell.addView(TextView(this).apply {
                this.text = name
                textSize = 12f
                setTextColor(WaSpec.textPrimary(night))
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            strip.addView(cell)
        }
        hScroll.addView(strip)
        box.addView(hScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(108)))
        dlg.setView(box)
        dlg.show()
        dlg.window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // MARK: - 语音录制（按住说话 → 松开发送/取消）

    private var recorder: android.media.MediaRecorder? = null
    private var recordPath: String? = null
    private var recordStart = 0L
    private var recordDialog: android.app.AlertDialog? = null
    // A4：计时器 / 锁定态 / 锁定后的发送-删除按钮
    private var recordTicker: android.os.Handler? = null
    private var recordTickRunnable: Runnable? = null
    private var recordLocked = false
    private lateinit var recordLockedRow: LinearLayout
    private lateinit var recordSendBtn: TextView
    private lateinit var recordDeleteBtn: TextView

    /** A4：录音浮层计时（每秒刷一次 00:0X） */
    private fun startRecordTimer(mic: TextView, hint: TextView) {
        stopRecordTimer()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        recordTicker = h
        val r = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val sec = ((System.currentTimeMillis() - recordStart) / 1000).toInt()
                val mmss = "%02d:%02d".format(sec / 60, sec % 60)
                val showTimer = "🎤  $mmss"
                if (recordLocked) mic.text = "🔒  $mmss" else if (mic.text.startsWith("🎤")) mic.text = showTimer
                h.postDelayed(this, 500)
            }
        }
        recordTickRunnable = r
        h.post(r)
    }

    private fun stopRecordTimer() {
        recordTickRunnable?.let { recordTicker?.removeCallbacks(it) }
        recordTickRunnable = null
        recordTicker = null
    }

    /** A4：上滑到锁定区松手 → 继续录音，浮层留下发送/删除（即语音草稿态） */
    private fun enterLockedRecording(mic: TextView, hint: TextView, hold: TextView) {
        recordLocked = true
        mic.text = "🔒"
        hint.text = "已锁定 · 松手继续录音\n想好再点发送，或点删除"
        hint.setTextColor(WaSpec.primary(night))
        hold.visibility = View.GONE
        recordLockedRow.visibility = View.VISIBLE
    }

    private fun startRecording() {
        val dir = java.io.File(cacheDir, "voice").apply { mkdirs() }
        recordStart = System.currentTimeMillis()
        // 首选 AAC（音质好），模拟器/老设备无 OMX 时退 AMR 窄带
        val formats = listOf(
            Triple(".m4a", android.media.MediaRecorder.OutputFormat.MPEG_4,
                android.media.MediaRecorder.AudioEncoder.AAC),
            Triple(".amr", android.media.MediaRecorder.OutputFormat.AMR_NB,
                android.media.MediaRecorder.AudioEncoder.AMR_NB))
        for ((ext, fmt, enc) in formats) {
            try {
                val f = java.io.File(dir, "voice-${System.currentTimeMillis()}$ext")
                val rec = android.media.MediaRecorder()
                rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(fmt)
                rec.setAudioEncoder(enc)
                if (enc == android.media.MediaRecorder.AudioEncoder.AAC) {
                    rec.setAudioEncodingBitRate(64000)
                    rec.setAudioSamplingRate(44100)
                }
                rec.setOutputFile(f.absolutePath)
                rec.prepare()
                rec.start()
                recorder = rec
                recordPath = f.absolutePath
                return
            } catch (_: Throwable) {
                try { recorder?.release() } catch (_: Throwable) { }
                recorder = null
            }
        }
        // 模拟器无编码器时的调试回退：直接用预置的测试语音文件
        val debugVoice = java.io.File(cacheDir, "voice-test.ogg")
        if (debugVoice.exists()) {
            recordPath = debugVoice.absolutePath
            recordStart = System.currentTimeMillis() - 1500
            return
        }
        Toast.makeText(this, "录音启动失败（无可用编码器）", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording(send: Boolean) {
        val seconds = ((System.currentTimeMillis() - recordStart) / 1000).toInt()
        try { recorder?.stop() } catch (_: Throwable) { }
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        val path = recordPath ?: return
        if (!send || seconds < 1) {
            java.io.File(path).delete()
            return
        }
        sendBtn.isEnabled = false
        engine.sendAudioAsync(chatJid, path, seconds) { _, err ->
            sendBtn.isEnabled = true
            if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
            else renderMessages()
        }
    }

    /** 录音对话框：按住说话 / 松开发送 / 左滑取消 */

    /** 点选项投票/改票；确认后走协议，成功本地先落一笔（本人票即时反馈） */
    private fun voteOnPoll(m: MessageRow, opts: List<String>, choice: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("投票")
            .setMessage("选择「$choice」？再次点其他选项可改票")
            .setPositiveButton("投这一票") { _, _ ->
                // senderJid 传投票消息的发送者；Go 侧用它构造 MessageInfo（IsFromMe 自动判定）
                engine.votePollAsync(chatJid, m.id, m.senderJid, m.ts, listOf(choice)) { err ->
                    android.util.Log.i("WeFlipPoll", "vote result err=$err choice=$choice")
                    if (err != null) {
                        Toast.makeText(this, "投票失败：$err", Toast.LENGTH_LONG).show()
                    } else {
                        // 本地即时落一笔本人票，待服务器 PollUpdate 回流校准（voterJid 用 self 占位）
                        engine.db.replacePollVotes(chatJid, m.id, "self", "我", listOf(choice), System.currentTimeMillis())
                        renderMessagesSoon()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRecordDialog() {
        // 运行时权限：模拟器 appops 默认 allow 会掩盖缺失；真机不声明不请求直接失败
        recordLocked = false   // 上次锁定态不能留到下一次（否则按住没反应）
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4102)
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(30), dp(28), dp(24))
        }
        val mic = TextView(this).apply {
            text = "🎤"
            textSize = 54f
            gravity = Gravity.CENTER
        }
        // A4：提示行随手势状态变化（录音 / 上滑取消 / 上滑锁定）
        val hint = TextView(this).apply {
            text = "按住下方按钮说话\n松开发送 · 上滑取消 · 继续上滑锁定"
            textSize = 13.5f
            setTextColor(WaSpec.textSecondary(night))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val hold = TextView(this).apply {
            text = "按住 说话"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatActivity, WaSpec.primary(night), 28)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        box.addView(mic)
        box.addView(hint)
        box.addView(hold, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        recordDialog = android.app.AlertDialog.Builder(this).create()

        var downY = 0f
        // 三个区间（dp）：>取消阀值=松手取消；>锁定阀值=松手后锁定（进入草稿态继续录）
        val cancelAt = dp(64)
        val lockAt = dp(150)
        hold.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (recordLocked) return@setOnTouchListener false
                    downY = e.rawY
                    hold.text = "松开 发送"
                    hold.background = Ui.roundBg(this@ChatActivity, 0xCC4444, 28)
                    startRecording()
                    startRecordTimer(mic, hint)
                    v.isPressed = true
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (recordLocked) return@setOnTouchListener false
                    val dy = downY - e.rawY
                    when {
                        dy >= lockAt -> {
                            mic.text = "🔒"
                            hint.text = "松开手指，锁定录音\n锁定后可松手继续录，再点发送"
                            hint.setTextColor(WaSpec.primary(night))
                        }
                        dy >= cancelAt -> {
                            mic.text = "🗑"
                            hint.text = "松开手指，取消发送"
                            hint.setTextColor(0xFFE53935.toInt())
                        }
                        else -> {
                            mic.text = "🎤"
                            hint.text = "松开即发送\n上滑取消 · 继续上滑锁定"
                            hint.setTextColor(WaSpec.textSecondary(night))
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val dy = downY - e.rawY
                    if (recordLocked) return@setOnTouchListener false   // 锁定态交给按钮
                    when {
                        dy >= lockAt -> {
                            // pttLockHint → 锁定：松手不停录，进入草稿态
                            enterLockedRecording(mic, hint, hold)
                            true
                        }
                        dy >= cancelAt -> {
                            stopRecordTimer()
                            stopRecording(false)   // 垃圾桶：删除不发送
                            recordDialog?.dismiss()
                            true
                        }
                        else -> {
                            stopRecordTimer()
                            stopRecording(true)
                            recordDialog?.dismiss()
                            true
                        }
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    stopRecordTimer()
                    if (!recordLocked) { stopRecording(false); recordDialog?.dismiss() }
                    true
                }
                else -> false
            }
        }
        // 锁定态下的发送 / 删除按钮（默认隐藏，锁定时显现）
        recordLockedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        recordSendBtn = TextView(this).apply {
            text = "发送"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatActivity, WaSpec.primary(night), 24)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                stopRecordTimer(); stopRecording(true); recordDialog?.dismiss()
            }
        }
        recordDeleteBtn = TextView(this).apply {
            text = "删除"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatActivity, 0xCC4444, 24)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                stopRecordTimer(); stopRecording(false); recordDialog?.dismiss()
            }
        }
        recordLockedRow.addView(recordDeleteBtn)
        recordLockedRow.addView(recordSendBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        box.addView(recordLockedRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        android.app.AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("取消", null)
            .show()
            .let { recordDialog = it }
    }

    private fun showLocationDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val lat = EditText(this).apply {
            hint = "纬度，如 19.4326（墨西哥城）"; setSingleLine(true) }
        val lng = EditText(this).apply {
            hint = "经度，如 -99.1332"; setSingleLine(true) }
        val name = EditText(this).apply {
            hint = "地点名称（可选）"; setSingleLine(true) }
        box.addView(lat); box.addView(lng); box.addView(name)
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("发送位置")
            .setView(box)
            .setPositiveButton("发送") { _, _ ->
                val la = lat.text.toString().toDoubleOrNull()
                val lo = lng.text.toString().toDoubleOrNull()
                if (la == null || lo == null) {
                    Toast.makeText(this, "经纬度无效", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                sendBtn.isEnabled = false
                engine.sendLocationAsync(chatJid, la, lo,
                    name.text.toString().trim(), "") { _, err ->
                    sendBtn.isEnabled = true
                    if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
                    else renderMessages()
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .also { it.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }

    private fun showContactDialog() {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 4101)
            return
        }
        Thread {
            val wa = mutableListOf<Pair<String, String>>() // name to phone
            try {
                val arr = org.json.JSONArray(engine.client.getAllContactsJSON())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    wa.add("📱 " + o.getString("name") to o.getString("phone"))
                }
            } catch (_: Throwable) {}
            val phone = deviceContacts()
            val merged = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()
            for ((n, p) in wa) { val k = p; if (seen.add(k)) merged.add(n to p) }
            for ((n, p) in phone) if (seen.add(p)) merged.add("📇 $n" to p)
            main.post {
                if (isDestroyed || isFinishing) return@post
                if (merged.isEmpty()) { showContactInputDialog(); return@post }
                val names = merged.map { it.first }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("选择联系人（${merged.size}）")
                    .setItems(names) { _, i ->
                        val (nm2, ph2) = merged[i]
                        sendBtn.isEnabled = false
                        engine.sendContactAsync(chatJid, nm2.substringAfter(" "), ph2) { _, e2 ->
                            sendBtn.isEnabled = true
                            if (e2 != null) Toast.makeText(this, "发送失败：$e2", Toast.LENGTH_LONG).show()
                            else renderMessages()
                        }
                    }
                    .setPositiveButton("手动输入") { _, _ -> showContactInputDialog() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    /** 手机通讯录（需 READ_CONTACTS 已授权），在后台线程调用 */
    private fun deviceContacts(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    val n = c.getString(0)?.trim().orEmpty()
                    val p = c.getString(1)?.trim().orEmpty()
                    if (n.isNotBlank() && p.isNotBlank()) out.add(n to p)
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4102) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showRecordDialog()
            } else {
                Toast.makeText(this, "未授权麦克风，无法发送语音", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == 4101) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showContactDialog()
            } else {
                Toast.makeText(this, "未授权通讯录，可用「手动输入」", Toast.LENGTH_SHORT).show()
                showContactInputDialog()
            }
        }
    }

    /** 手动输入姓名+手机号发送卡片 */
    private fun showContactInputDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val nm = EditText(this).apply { hint = "联系人姓名"; setSingleLine(true) }
        val ph = EditText(this).apply {
            hint = "手机号，如 +5218120398050"; setSingleLine(true) }
        box.addView(nm); box.addView(ph)
        android.app.AlertDialog.Builder(this)
            .setTitle("发送联系人卡片")
            .setView(box)
            .setPositiveButton("发送") { _, _ ->
                if (nm.text.isBlank() || ph.text.isBlank()) {
                    Toast.makeText(this, "姓名和号码必填", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                sendBtn.isEnabled = false
                engine.sendContactAsync(chatJid, nm.text.toString().trim(),
                    ph.text.toString().trim()) { _, err ->
                    sendBtn.isEnabled = true
                    if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
                    else renderMessages()
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .also { it.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }

    // MARK: - B1 汇率转换（对齐参考 fx_* 模块）

    /**
     * 汇率转换对话框。
     * 设计三件套：① 源/目标货币可点选，对方货币自动预选；② 金额支持四则运算；
     * ③ 改目标金额可反推源金额（报价时经常要「我要收到 5000 美元，得报多少人民币」）。
     * 汇率 1 小时缓存，加载中/失败都有明确状态，不会白屏。
     */
    private fun showFxDialog() {
        // 优先用 resolvePn 解析出的真实号码（@lid 会话推不出国家码）；
        // 再退到 jid 直接解析，都失败则由用户手选
        val localCurrency = com.weifan.app.core.Fx.currencyForChat(
            resolvedPhone.ifBlank { chatJid })
        var from = prefs.fxSourceCurrency
        var to = localCurrency ?: "USD"
        if (from == to) to = if (from == "CNY") "USD" else "CNY"

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
        }

        // 汇率状态行
        val rateText = TextView(this).apply {
            text = "加载汇率中…"
            textSize = 12.5f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(rateText)

        // 源货币行
        val fromRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val fromLabel = TextView(this).apply {
            text = "人民币 CNY"
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fromRow.addView(fromLabel)
        fromRow.addView(TextView(this).apply {
            text = "▾"; textSize = 16f; setTextColor(WaSpec.textSecondary(night))
        })
        box.addView(fromRow)

        // 源金额输入（支持算式）
        val fromInput = EditText(this).apply {
            hint = "金额，支持 100*0.2 / 100+50"
            textSize = 16f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackground(Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(fromInput)

        box.addView(TextView(this).apply {
            text = "⇅"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(WaSpec.primary(night))
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 目标货币行
        val toRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val toLabel = TextView(this).apply {
            text = "美元 USD"
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        toRow.addView(toLabel)
        toRow.addView(TextView(this).apply {
            text = "▾"; textSize = 16f; setTextColor(WaSpec.textSecondary(night))
        })
        box.addView(toRow)

        // 目标金额（可编辑，反推源金额）
        val toInput = EditText(this).apply {
            hint = "目标金额（也可输入，反推源金额）"
            textSize = 16f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackground(Ui.roundBg(this@ChatActivity,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(toInput)

        val resultText = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(WaSpec.primary(night))
            setPadding(0, dp(10), 0, dp(2))
        }
        box.addView(resultText)
        // 长按复制金额（报价时要粘到消息里，对齐参考 fx_copied）
        resultText.setOnLongClickListener {
            val v = resultText.text.toString().substringAfter("= ").trim()
            if (v.isNotEmpty()) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("weifan-fx", v))
                Toast.makeText(this@ChatActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            true
        }

        var snapshot: com.weifan.app.core.Fx.Snapshot? = null
        var updating = false

        fun refreshLabels() {
            fromLabel.text = "${com.weifan.app.core.Fx.nameOf(from)} $from"
            toLabel.text = "${com.weifan.app.core.Fx.nameOf(to)} $to"
        }

        /** 换算：dir=1 源→目标；dir=-1 目标→源 */
        fun convert(dir: Int) {
            val snap = snapshot
            if (snap == null) { resultText.text = "汇率未就绪"; return }
            val src = if (dir > 0) fromInput.text.toString() else toInput.text.toString()
            val v = com.weifan.app.core.Fx.evaluate(src)
            if (v == null) {
                resultText.text = if (src.isBlank()) "" else "请输入有效金额或算式"
                return
            }
            val rate = snap.cross(if (dir > 0) from else to, if (dir > 0) to else from)
            if (rate == null) { resultText.text = "无法换算该货币对"; return }
            val out = v * rate
            if (dir > 0) {
                toInput.setText(com.weifan.app.core.Fx.fmt(out, to))
                resultText.text = "= ${com.weifan.app.core.Fx.fmt(out, to)}"
            } else {
                fromInput.setText(com.weifan.app.core.Fx.fmt(out, from))
                resultText.text = "= ${com.weifan.app.core.Fx.fmt(out, from)}"
            }
        }

        // 双向联动：编辑源推目标，编辑目标推源（用 flag 防递归）
        var busy = false
        fromInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (busy) return
                busy = true; convert(1); busy = false
            }
        })
        toInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (busy) return
                busy = true; convert(-1); busy = false
            }
        })

        /** 刷新顶部汇率行：货币对、汇率、更新时间。切换货币后必须重算，
         *  之前只在初始加载时算一次，换完货币这行还停在旧币对（实测发现）。 */
        fun refreshRateLine() {
            val snap = snapshot
            if (snap == null) {
                rateText.text = "汇率未就绪"
                return
            }
            val when_ = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(snap.updatedMs))
            val rate = snap.cross(from, to)
            rateText.text = if (rate != null)
                "1 $from = ${com.weifan.app.core.Fx.fmt(rate, to)} · 更新于 $when_"
            else "汇率数据不含该货币对 · 更新于 $when_"
        }

        // 货币选择器：置顶在前 + 全部货币可搜索
        fun pickCurrency(isFrom: Boolean) {
            val pinned = prefs.fxPinned
            val current = if (isFrom) from else to
            val all = com.weifan.app.core.Fx.CURRENCIES
            val ordered = all.sortedByDescending { it.first in pinned }
            val names = ordered.map { "${it.second}  ${it.first}" }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle(if (isFrom) "源货币" else "目标货币")
                .setItems(names) { _, which ->
                    val code = ordered[which].first
                    if (isFrom) from = code else to = code
                    if (from == to) {
                        if (isFrom) to = if (from == "CNY") "USD" else "CNY"
                        else from = if (to == "CNY") "USD" else "CNY"
                    }
                    // 切完币对三件套都要刷：标签、汇率行、金额
                    refreshLabels(); refreshRateLine(); convert(1)
                }
                .setNeutralButton(if (current in pinned) "取消置顶当前" else "置顶当前货币") { _, _ ->
                    val set = prefs.fxPinned.toMutableList()
                    if (current in set) set.remove(current) else set.add(0, current)
                    prefs.fxPinned = set.take(6)
                    Toast.makeText(this@ChatActivity,
                        if (current in prefs.fxPinned) "已置顶 $current" else "已取消置顶 $current",
                        Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        fromLabel.setOnClickListener { pickCurrency(true) }
        toLabel.setOnClickListener { pickCurrency(false) }
        fromRow.setOnClickListener { pickCurrency(true) }
        toRow.setOnClickListener { pickCurrency(false) }

        refreshLabels()
        loadRatesAsync(prefs, false) { snap ->
            snapshot = snap
            if (snap != null) {
                refreshRateLine()
                convert(1)
            } else {
                rateText.text = "汇率加载失败，请稍后重试"
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("汇率转换")
            .setView(box)
            .setPositiveButton("更新汇率") { _, _ ->
                if (updating) return@setPositiveButton
                updating = true
                rateText.text = "正在更新…"
                loadRatesAsync(prefs, true) { snap ->
                    updating = false
                    snapshot = snap
                    if (snap != null) {
                        refreshRateLine()
                        convert(1)
                    } else {
                        rateText.text = "更新失败，检查网络后重试"
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
            .also { it.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }

    /** 后台取汇率，回调在主线程 */
    private fun loadRatesAsync(prefs: Prefs, force: Boolean,
                               done: (com.weifan.app.core.Fx.Snapshot?) -> Unit) {
        Thread {
            val snap = try { com.weifan.app.core.Fx.load(prefs, force) } catch (_: Throwable) { null }
            main.post { if (!isFinishing && !isDestroyed) done(snap) }
        }.start()
    }

    /**
     * 投票结果弹层（对齐参考 dialog_poll_results + item_poll_result_option + item_poll_result_voter）。
     *
     * 规格：ScrollView padding 16；标题 16sp BOLD；选项容器 marginTop 12；
     * 每个选项 marginBottom 12 —— 行内「选项名 15sp(weight=1) + 票数 14sp」，
     * 下方比例条高 4dp（marginTop 6dp），条下方投票人列表（marginTop 8dp，
     * 每人 28dp 头像 + 14sp 名字 + marginStart 8dp，行上下 padding 4dp）。
     */
    /** 投票选项存在 mediaMeta 的 waveform 字段里（\n 分隔），与气泡渲染同一来源 */
    private fun parsePollOptions(m: MessageRow): List<String> {
        val meta = try { JSONObject(m.mediaMeta) } catch (_: Throwable) { JSONObject() }
        return meta.optString("waveform").split("\n").filter { it.isNotBlank() }
    }

    private fun showPollResults(m: MessageRow) {
        val opts = parsePollOptions(m)
        if (opts.isEmpty()) {
            Toast.makeText(this, "这个投票没有选项数据", Toast.LENGTH_SHORT).show()
            return
        }
        val counts = engine.db.pollCounts(m.id)
        val total = engine.db.pollVoterCount(m.id)
        val maxVotes = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        val title = JSONObject(m.mediaMeta.ifBlank { "{}" }).optString("question")
            .ifBlank { m.text.ifBlank { "投票" } }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 标题：16sp BOLD text_primary
        col.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(WaSpec.textPrimary(night))
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (opt in opts) {
            val n = counts[opt] ?: 0
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            // 行：选项名 15sp（weight 1）+ 票数 14sp secondary
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = opt
                textSize = 15f
                setTextColor(WaSpec.textPrimary(night))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "$n 票"
                textSize = 14f
                setTextColor(WaSpec.textSecondary(night))
            })
            item.addView(row)

            // 比例条：轨道 4dp + 填充 4dp（宽度按 票数/最大票数）
            val track = android.widget.FrameLayout(this).apply {
                background = Ui.roundBg(this@ChatActivity,
                    if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 2)
            }
            val ratio = if (maxVotes == 0) 0f else n.toFloat() / maxVotes.toFloat()
            track.addView(android.view.View(this).apply {
                background = Ui.roundBg(this@ChatActivity, WaSpec.GREEN_LIGHT, 2)
            }, android.widget.FrameLayout.LayoutParams(0, dp(4)).apply {
                gravity = Gravity.START
                // 用 weight 表达比例：FrameLayout 不支持 weight，改用 post 设宽
            })
            item.addView(track, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
            track.post {
                val w = (track.width * ratio).toInt()
                val fill = track.getChildAt(0)
                if (w > 0 && fill != null) {
                    fill.layoutParams = android.widget.FrameLayout.LayoutParams(
                        w.coerceAtLeast(dp(2)), dp(4))
                }
            }

            // 投票人：28dp 头像 + 14sp 名字，行上下 padding 4dp（marginTop 8dp）
            val voters = engine.db.pollVotersOf(m.id, opt)
            if (voters.isNotEmpty()) {
                val vBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                for ((_, name) in voters) {
                    val vRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    vRow.addView(initialAvatar(name, dp(28)))
                    vRow.addView(TextView(this).apply {
                        text = name
                        textSize = 14f
                        setTextColor(WaSpec.textPrimary(night))
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
                    vBox.addView(vRow)
                }
                item.addView(vBox, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }

            list.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        col.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        col.addView(TextView(this).apply {
            text = "$total 人已投 · 点选项可投票/改票"
            textSize = 12f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, dp(8), 0, 0)
        })

        val scroll = android.widget.ScrollView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(col)
        }
        android.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 圆形首字母头像（28/40dp 通用）；无头像图时用名字首字符 + 稳定色 */
    private fun initialAvatar(name: String, size: Int): View {
        val colors = arrayOf("#25D366", "#34B7F1", "#FFB74D", "#9575CD", "#4DB6AC", "#F06292")
        val letter = name.trim().firstOrNull()?.toString() ?: "?"
        return TextView(this).apply {
            text = letter
            textSize = if (size <= dp(30)) 13f else 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatActivity,
                Color.parseColor(colors[(name.hashCode() and 0xff) % colors.size]), size / 2)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun showPollDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val q = EditText(this).apply { hint = "问题，如 选哪个交期？"; setSingleLine(true) }
        val opts = EditText(this).apply {
            hint = "选项，逗号分隔\n如 下周三,下周五,下周六"; setSingleLine(false); minLines = 2 }
        box.addView(q); box.addView(opts)
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("发起投票")
            .setView(box)
            .setPositiveButton("发送") { _, _ ->
                if (q.text.isBlank() || opts.text.isBlank()) {
                    Toast.makeText(this, "问题和选项必填", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                sendBtn.isEnabled = false
                engine.sendPollAsync(chatJid, q.text.toString().trim(),
                    opts.text.toString()) { _, err ->
                    sendBtn.isEnabled = true
                    if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
                    else renderMessages()
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .also { it.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }

    private fun pickImage() {
        val i = android.content.Intent(android.content.Intent.ACTION_PICK,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(i, REQ_PICK_IMAGE)
    }

    private fun pickDocument() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK_DOC)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        val path = copyUriToCache(uri, requestCode) ?: run {
            Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show(); return
        }
        // 不直接发：先进预览页，看清内容并补一句说明（批3-c）。
        // 预览页发送完会 finish，本页 onResume 里重新 renderMessages。
        startActivity(Intent(this, MediaSendPreviewActivity::class.java)
            .putExtra("path", path)
            .putExtra("kind", if (requestCode == REQ_PICK_IMAGE) "image" else "document")
            .putExtra("name", queryDisplayName(uri) ?: java.io.File(path).name)
            .putExtra("jid", chatJid))
    }

    private fun copyUriToCache(uri: android.net.Uri, req: Int): String? = try {
        val dir = java.io.File(cacheDir, "send").apply { mkdirs() }
        val name = queryDisplayName(uri) ?: ("pick-" + System.currentTimeMillis() +
            if (req == REQ_PICK_IMAGE) ".jpg" else "")
        val f = java.io.File(dir, name)
        contentResolver.openInputStream(uri)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
        f.absolutePath
    } catch (_: Throwable) { null }

    private fun queryDisplayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) { null }
}

/**
 * 语音波形条（对齐 WATransChat：灰色细条，播放时绿色推进）。
 * 有真实 waveform 数据时按字节渲染；否则按消息 id 生成确定性伪波形。
 */
class WaveformView(context: android.content.Context, private val night: Boolean) : android.view.View(context) {

    private var bars: FloatArray = FloatArray(0)
    private var progress = 0f // 0..1
    private var animateJob: android.os.Handler? = null

    private val idleColor = if (night) 0xFF8696A0.toInt() else 0xFFB0B8C4.toInt()
    private val playedColor = com.weifan.app.WaSpec.GREEN_LIGHT

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }

    fun setData(bytes: ByteArray?, seed: String) {
        bars = if (bytes != null && bytes.size > 8) {
            FloatArray(36) { i -> (Math.abs(bytes[i % bytes.size].toInt()) % 100 / 100f).coerceIn(0.18f, 1f) }
        } else {
            FloatArray(36) { i ->
                val v = Math.abs((seed + i).hashCode()) % 100 / 100f
                v.coerceIn(0.18f, 1f)
            }
        }
        invalidate()
    }

    /** 播放时做一次性的绿色填充动画（不做逐帧同步，视觉近似即可） */
    private var tickRunnable: Runnable? = null

    fun animateProgress(playing: Boolean) {
        tickRunnable?.let { animateJob?.removeCallbacks(it) }
        if (playing) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            animateJob = h
            var p = 0f
            val r = object : Runnable {
                override fun run() {
                    p += 0.04f
                    progress = p.coerceAtMost(1f)
                    invalidate()
                    if (p < 1f) h.postDelayed(this, 120)
                }
            }
            tickRunnable = r
            h.post(r)
        } else {
            progress = 0f
            invalidate()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val n = bars.size
        val gap = 3f * resources.displayMetrics.density
        val bw = (width - gap * (n - 1)) / n
        val cy = height / 2f
        val playedX = width * progress
        var x = 0f
        for (i in 0 until n) {
            val h = bars[i] * height * 0.92f
            paint.color = if (x + bw / 2 <= playedX) playedColor else idleColor
            canvas.drawRoundRect(x, cy - h / 2, x + bw, cy + h / 2, bw / 2, bw / 2, paint)
            x += bw + gap
        }
    }
}
