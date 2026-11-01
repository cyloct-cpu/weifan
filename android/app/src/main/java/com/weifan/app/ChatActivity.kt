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

    private var chatJid: String = ""
    private var chatTitle: String = ""
    private var chatLangLabel: String = ""

    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: View
    private lateinit var subtitle: TextView
    private lateinit var translateBtn: TextView

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
        top.addView(avatarView(), LinearLayout.LayoutParams(
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
            val lb = com.weifan.app.core.CountryDetect.label(pn.ifBlank { chatJid })
            if (lb.isNotBlank()) {
                chatLangLabel = lb
                subtitle.text = "最近上线 · $chatLangLabel"
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
        top.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })

        top.addView(TextView(this).apply {
            text = "⋮"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(WaSpec.CHAT_BACK_BTN_DP), dp(WaSpec.CHAT_BACK_BTN_DP)))

        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(WaSpec.divider(night)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // ── 消息流（涂鸦壁纸）
        scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(WaSpec.MESSAGE_LIST_PADDING_H_DP), dp(8),
                dp(WaSpec.MESSAGE_LIST_PADDING_H_DP), dp(8))
        }
        scroll.addView(listBox)
        // 向上翻页：滚到顶自动拉更早历史（P0-5）
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (scroll.scrollY == 0 && !loadingOlder && listBox.childCount > 0) {
                loadingOlder = true
                engine.loadOlderMessagesAsync(chatJid) {
                    loadingOlder = false
                    if (it) main.post { renderMessages() }
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
            }
        })

        // 译按钮 36dp：开/关切换（关闭时发原文）
        translateBtn = TextView(this).apply {
            text = "译"
            textSize = WaSpec.TRANSLATE_BUTTON_TEXT_SP.toFloat()
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatActivity, WaSpec.GREEN_LIGHT, 10)
            setPadding(dp(WaSpec.TRANSLATE_BUTTON_PADDING_H_DP), 0,
                dp(WaSpec.TRANSLATE_BUTTON_PADDING_H_DP), 0)
            isClickable = true
            setOnClickListener {
                translateOn = !translateOn
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
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
        row.addView(capsule, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 发送圆钮 48dp，左间距 4dp
        sendBtn = TextView(this).apply {
            text = "➤"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.roundBg(this@ChatActivity, WaSpec.INPUT_CIRCLE_GREEN,
                WaSpec.PTT_SEND_BUTTON_SIZE_DP / 2)
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
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return@fetchAvatarAsync
                    val s = minOf(bmp.width, bmp.height)
                    val out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(out)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP,
                        android.graphics.Shader.TileMode.CLAMP)
                    cv.drawCircle(s / 2f, s / 2f, s / 2f, paint)
                    view.text = ""
                    view.background = android.graphics.drawable.BitmapDrawable(resources, out)
                } catch (_: Throwable) { }
            }
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
                subtitle.text = subtitleText(c)
            }
        }
        engine.onReceiptChanged = { jid, _, _ ->
            main.post { if (jid == chatJid) renderMessages() }
        }
        engine.onTypingChanged = { jid, typing ->
            main.post {
                if (jid == chatJid) {
                    subtitle.text = if (typing) "正在输入…"
                        else subtitleText(engine.isConnected())
                }
            }
        }
        engine.db.markChatRead(chatJid)
        renderMessages()
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

    private fun renderMessages() {
        listBox.removeAllViews()
        val msgs = engine.db.messages(chatJid)
        if (msgs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "暂无消息\n给对方发一条中文，发出的自动是对方的语言"
                textSize = WaSpec.EMPTY_HINT_SP.toFloat()
                setTextColor(WaSpec.textSecondary(night))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        for (m in msgs) listBox.addView(bubble(m))
        // 图片/贴纸/语音/文档自动下载（视频仍手动点）；下载完重渲染一次
        for (m in msgs.filter { it.mediaKind.isNotBlank() && it.mediaKind != "video" &&
            it.mediaPath.isBlank() }) {
            engine.fetchMediaAsync(m.chatJid, m.id) { _ -> main.post { renderMessages() } }
        }
        for (m in msgs.filter { !it.fromMe && it.translated.isBlank() && it.text.isNotBlank() }) {
            engine.translateIncomingAsync(m.chatJid, m.id, m.text)
        }
        for (m in msgs.filter { !it.fromMe && it.translated.isBlank() }) {
            engine.translateIncomingAsync(m.chatJid, m.id, m.text)
        }
        main.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
            val bmp = f?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
                ?: decodeThumb(m.mediaThumb)
            if (bmp != null) {
                imageView.setImageBitmap(roundBmp(bmp))
            } else {
                // 无缩略图且未下载：可见占位框
                imageView.setImageDrawable(null)
                imageView.background = Ui.roundBg(this@ChatActivity,
                    if (night) WaSpec.SURFACE_NIGHT else WaSpec.GRAY6, 8, 1, WaSpec.divider(night))
                imageView.minimumHeight = dp(120)
                imageView.minimumWidth = dp(180)
            }
        }


        return when (m.mediaKind) {
            "image", "sticker", "video" -> {
                showImage(localFile())
                if (m.mediaKind == "video") {
                    // ▶ 遮罩叠层
                    val wrap = android.widget.FrameLayout(this)
                    wrap.addView(imageView, android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val play = TextView(this).apply {
                        text = "▶"
                        textSize = 22f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background = Ui.roundBg(this@ChatActivity, 0x66000000, 24)
                    }
                    wrap.addView(play, android.widget.FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                        gravity = Gravity.CENTER
                    })
                    wrap.isClickable = true
                    wrap.setOnClickListener { engine.fetchMediaAsync(m.chatJid, m.id) { f -> showImage(f) } }
                    wrap
                } else {
                    imageView.isClickable = true
                    imageView.setOnClickListener {
                        engine.fetchMediaAsync(m.chatJid, m.id) { f -> showImage(f) }
                    }
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
                        engine.fetchMediaAsync(m.chatJid, m.id) { f ->
                            Toast.makeText(this@ChatActivity,
                                if (f != null) "已保存：${f.name}" else "下载失败",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    row
                }
            }
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
        // 文本行（有文字或有 caption 才显示）
        if (m.text.isNotBlank()) {
        col.addView(TextView(this).apply {
            text = m.text
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            })
        }
        // 收件：译文行（蓝色，13sp）
        if (!m.fromMe && m.translated.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = m.translated
                textSize = WaSpec.TRANSLATION_TEXT_SP.toFloat()
                setTextColor(WaSpec.TRANSLATE_TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(3), 0, 0)
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
        col.isLongClickable = true
        col.setOnLongClickListener { showActionMenu(m); true }
        holder.addView(col.apply {
            background = Ui.roundBg(this@ChatActivity,
                WaSpec.bubbleSent(night).let { if (m.fromMe) it else WaSpec.bubbleReceived(night) },
                WaSpec.BUBBLE_CORNER_DP)
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (m.fromMe) Gravity.END else Gravity.START
            setMargins(
                dp(if (m.fromMe) WaSpec.BUBBLE_OPPOSITE_INSET_DP else WaSpec.BUBBLE_SIDE_INSET_DP),
                dp(3),
                dp(if (m.fromMe) WaSpec.BUBBLE_SIDE_INSET_DP else WaSpec.BUBBLE_OPPOSITE_INSET_DP),
                dp(3))
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
        box.addView(item("转发") { dlg.dismiss(); showForwardPicker(m) })
        if (m.text.isNotBlank()) box.addView(item("复制") {
            dlg.dismiss()
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("weifan", m.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        })
        // 自己发的消息：撤回 / 编辑（官方规则：编辑 15 分钟窗口）
        if (m.fromMe) {
            box.addView(item("撤回") {
                dlg.dismiss()
                engine.revokeAsync(chatJid, m.id) { ok ->
                    Toast.makeText(this, if (ok) "已撤回" else "撤回失败", Toast.LENGTH_SHORT).show()
                    if (ok) renderMessages()
                }
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

    /** 转发：会话选择器（当前会话除外） */
    private fun showForwardPicker(m: MessageRow) {
        val chats = engine.db.chats().filter { it.jid != chatJid }
        if (chats.isEmpty()) {
            Toast.makeText(this, "暂无其他会话可转发", Toast.LENGTH_SHORT).show()
            return
        }
        val names = chats.map { it.title.ifBlank { it.jid.substringBefore("@") } }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("转发到")
            .setItems(names) { _, i ->
                val target = chats[i].jid
                if (m.mediaKind.isNotBlank() && m.mediaMeta.isNotBlank()) {
                    engine.forwardMediaAsync(target, m.mediaMeta) { _, err ->
                        Toast.makeText(this, if (err != null) "转发失败：$err" else "已转发",
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    engine.forwardTextAsync(target, m.text) { _, err ->
                        Toast.makeText(this, if (err != null) "转发失败：$err" else "已转发",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // MARK: - 附件面板（对齐 WATransChat 附加菜单）

    private fun showAttachmentPanel() {
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
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val dlg = android.app.AlertDialog.Builder(this).create()
        for ((label, key) in items) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(WaSpec.textPrimary(night))
                setPadding(dp(20), dp(13), dp(20), dp(13))
                isClickable = true
                setOnClickListener {
                    dlg.dismiss()
                    when (key) {
                        "doc" -> pickDocument()
                        "media" -> pickImage()
                        else -> Toast.makeText(this@ChatActivity, "该附件类型即将上线",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        dlg.setView(box)
        dlg.show()
        dlg.window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        sendBtn.isEnabled = false
        if (requestCode == REQ_PICK_IMAGE) {
            engine.sendImageAsync(chatJid, path, "") { _, err ->
                sendBtn.isEnabled = true
                if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
                else renderMessages()
            }
        } else {
            val name = queryDisplayName(uri) ?: "file"
            engine.sendDocumentAsync(chatJid, path, name) { _, err ->
                sendBtn.isEnabled = true
                if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
                else renderMessages()
            }
        }
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
