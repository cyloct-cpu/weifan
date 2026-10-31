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
        chatLangLabel = engine.chatLangMemory[chatJid]?.let { Lang.name(it) } ?: "自动检测"
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
        for (glyph in listOf("＋", "😊")) {
            capsule.addView(TextView(this).apply {
                text = glyph
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(WaSpec.textPrimary(night))
            }, LinearLayout.LayoutParams(
                dp(WaSpec.ATTACH_BUTTON_SIZE_DP), dp(WaSpec.ATTACH_BUTTON_SIZE_DP)))
        }
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
            main.post { subtitle.text = (if (c) "在线" else "离线") + " · $chatLangLabel" }
        }
        engine.db.markChatRead(chatJid)
        renderMessages()
        engine.connectAsync()
    }

    override fun onPause() {
        super.onPause()
        engine.onMessageTranslated = null
        engine.onConnectionChanged = null
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
        for (m in msgs.filter { !it.fromMe && it.translated.isBlank() }) {
            engine.translateIncomingAsync(m.chatJid, m.id, m.text)
        }
        main.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 气泡：10dp 圆角、左右 inset 8/30dp、上下 3dp；发件绿、收件白 */
    private fun bubble(m: MessageRow): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = m.text
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
        })
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
                text = "✓✓"
                textSize = 10.5f
                setTextColor(WaSpec.READ_RECEIPT)
                setPadding(dp(3), 0, 0, 0)
            })
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = FrameLayout(this)
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
        sendBtn.isEnabled = false
        if (translateOn) {
            engine.sendWithTranslation(chatJid, text) { _, err ->
                sendBtn.isEnabled = true
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                } else {
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
}
