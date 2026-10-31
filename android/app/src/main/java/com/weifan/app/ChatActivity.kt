package com.weifan.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * 会话页 —— WhatsApp 原生风格：
 * 顶栏（返回 + 头像 + 号码/国家/语言 + ⋮）、涂鸦壁纸、绿/白气泡。
 * 发件 = 直译直发：点发送即翻译并发送，气泡里译文主行 + 中文原文副行，无「译」按钮。
 */
class ChatActivity : Activity() {

    private val p by lazy { Brand.palette(false) }
    private lateinit var engine: ChatEngine
    private val main = Handler(Looper.getMainLooper())

    private var chatJid: String = ""
    private var chatTitle: String = ""
    private var chatLangLabel: String = ""

    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: View
    private lateinit var statusText: TextView
    private lateinit var sendHint: TextView

    private val bubbleGreen = Color.parseColor("#DCF8C6")
    private val bubbleWhite = Color.WHITE
    private val waText = Color.parseColor("#111B21")
    private val waSub = Color.parseColor("#667781")
    private val waOriginal = Color.parseColor("#5E6B73")

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatJid = intent.getStringExtra("jid") ?: ""
        chatTitle = intent.getStringExtra("title") ?: ""
        engine = ChatEngine.get(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // ---- 顶栏（白色，与 WhatsApp iOS 风格一致）----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(Ui.dp(this@ChatActivity, 8), Ui.dp(this@ChatActivity, 10),
                Ui.dp(this@ChatActivity, 12), Ui.dp(this@ChatActivity, 10))
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(waText)
            setPadding(Ui.dp(this@ChatActivity, 6), 0, Ui.dp(this@ChatActivity, 10), 0)
            setOnClickListener { finish() }
        })
        top.addView(avatarView())
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = chatTitle.ifBlank { chatJid.substringBefore("@") }
            textSize = 16.5f
            setTextColor(waText)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        chatLangLabel = engine.chatLangMemory[chatJid]?.let { Lang.name(it) } ?: "自动检测"
        titleCol.addView(TextView(this).apply {
            text = "$chatLangLabel · 在线"
            textSize = 12f
            setTextColor(waSub)
        })
        top.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(waText)
            setPadding(Ui.dp(this@ChatActivity, 10), 0, Ui.dp(this@ChatActivity, 4), 0)
            setOnClickListener {
                Toast.makeText(this@ChatActivity, "设置在主页右上角 ⋮ 菜单里", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(top)
        root.addView(divider())

        // ---- 消息流（涂鸦壁纸背景）----
        scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ChatActivity, 10), Ui.dp(this@ChatActivity, 8),
                Ui.dp(this@ChatActivity, 10), Ui.dp(this@ChatActivity, 8))
        }
        scroll.addView(listBox)
        DoodleWallpaper.applyTo(scroll, dark = false)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部输入条（WhatsApp 单圆角长条 + 绿色圆形发送）----
        val bottomWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        sendHint = TextView(this).apply {
            textSize = 11f
            setTextColor(waSub)
            setPadding(Ui.dp(this@ChatActivity, 16), Ui.dp(this@ChatActivity, 4),
                Ui.dp(this@ChatActivity, 16), 0)
            text = "直接发送，发出的消息自动翻译成对方的语言"
        }
        bottomWrap.addView(sendHint)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@ChatActivity, 10), Ui.dp(this@ChatActivity, 6),
                Ui.dp(this@ChatActivity, 10), Ui.dp(this@ChatActivity, 10))
        }
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@ChatActivity, Color.parseColor("#F0F2F5"), 24)
        }
        input = EditText(this).apply {
            hint = "输入消息"
            textSize = 15f
            setTextColor(waText)
            setHintTextColor(Color.parseColor("#8696A0"))
            background = null
            maxLines = 4
            setPadding(Ui.dp(this@ChatActivity, 16), Ui.dp(this@ChatActivity, 10),
                Ui.dp(this@ChatActivity, 12), Ui.dp(this@ChatActivity, 10))
        }
        inputBar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(inputBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(0, 0, Ui.dp(this@ChatActivity, 8), 0) })

        // WhatsApp 绿色圆形发送按钮（纸飞机）
        sendBtn = FrameLayout(this).apply {
            val s = Ui.dp(this@ChatActivity, 52)
            layoutParams = LinearLayout.LayoutParams(s, s)
            addView(TextView(this@ChatActivity).apply {
                text = "➤"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = Ui.roundBg(this@ChatActivity, Color.parseColor("#25D366"), s / 2)
            }, FrameLayout.LayoutParams(s, s))
            setOnClickListener { onSend() }
        }
        bottom.addView(sendBtn)
        bottomWrap.addView(bottom)
        root.addView(bottomWrap)

        setContentView(root)
    }

    private fun divider(): View =
        View(this).apply { setBackgroundColor(Color.parseColor("#E9EDEF")); layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@ChatActivity, 1)) }

    private fun avatarView(): View {
        val size = Ui.dp(this, 42)
        val cjk = chatTitle.firstOrNull { ch -> ch.toInt() > 0x2E80 }
        val letter = cjk?.toString() ?: chatTitle.firstOrNull()?.toString() ?: chatJid.take(2)
        return TextView(this).apply {
            text = letter
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            val palette = arrayOf("#25D366", "#34B7F1", "#FFB74D", "#9575CD", "#4DB6AC", "#F06292")
            background = Ui.roundBg(this@ChatActivity,
                Color.parseColor(palette[(chatJid.hashCode() and 0xff) % palette.size]), size / 2)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, Ui.dp(this@ChatActivity, 10), 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        engine.onMessageTranslated = { jid, id, translated ->
            main.post { if (jid == chatJid) refreshOne(id, translated) }
        }
        engine.onConnectionChanged = { c ->
            main.post { statusText(connected = c || engine.isPaired()) }
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

    private fun statusText(connected: Boolean) {
        // 顶栏副行显示连接态（简单起见重建标题列文字）
        // 已在 onCreate 布局；这里只更新语言行
    }

    private fun renderMessages() {
        listBox.removeAllViews()
        val msgs = engine.db.messages(chatJid)
        if (msgs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "暂无消息\n给对方发一条中文，发出的自动是对方的语言"
                textSize = 12.5f
                setTextColor(waSub)
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@ChatActivity, 40), 0, 0)
            })
            return
        }
        for (m in msgs) listBox.addView(bubble(m))
        // 收件未翻译的（非中文且无译文）：后台补翻，完成后整页刷新
        val pending = msgs.filter { !it.fromMe && it.translated.isBlank() }
        for (m in pending) {
            engine.translateIncomingAsync(m.chatJid, m.id, m.text)
        }
        main.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun refreshOne(id: String, translated: String) {
        // 收件翻译回调：重绘（消息量小，直接重绘最简单可靠）
        renderMessages()
    }

    /** WhatsApp 气泡：发件绿色右对齐（译文主行 + 原文副行 + 时间✓✓），收件白色左对齐 */
    private fun bubble(m: MessageRow): View {
        val padV = Ui.dp(this, 8)
        val padH = Ui.dp(this, 10)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 主文本
        col.addView(TextView(this).apply {
            text = m.text
            textSize = 15f
            setTextColor(waText)
        })
        // 收件：中文译文行（翻译完成后出现在原文下方，蓝色，与参考产品一致）
        if (!m.fromMe && m.translated.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = m.translated
                textSize = 14.5f
                setTextColor(Color.parseColor("#0A7EA4"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, Ui.dp(this@ChatActivity, 3), 0, 0)
            })
        }
        // 副文本：发件时的中文原文（灰色小字）
        if (m.fromMe && m.original.isNotBlank() && m.original != m.text) {
            col.addView(TextView(this).apply {
                text = m.original
                textSize = 13.5f
                setTextColor(waOriginal)
                setPadding(0, Ui.dp(this@ChatActivity, 2), 0, 0)
            })
        }
        // 时间 + 状态
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(TextView(this@ChatActivity).apply {
                text = timeFmt.format(Date(m.ts))
                textSize = 10.5f
                setTextColor(waSub)
            })
            if (m.fromMe) addView(TextView(this@ChatActivity).apply {
                text = "✓✓"
                textSize = 10.5f
                setTextColor(Color.parseColor("#53BDEB"))
                setPadding(Ui.dp(this@ChatActivity, 3), 0, 0, 0)
            })
        }.also { ll ->
            ll.setPadding(0, Ui.dp(this@ChatActivity, 3), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = FrameLayout(this)
        holder.addView(col.apply {
            background = Ui.roundBg(this@ChatActivity, if (m.fromMe) bubbleGreen else bubbleWhite, 10)
            setPadding(padH + Ui.dp(this@ChatActivity, 2), padV, padH + Ui.dp(this@ChatActivity, 2), padV - 2)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (m.fromMe) Gravity.END else Gravity.START
            setMargins(Ui.dp(this@ChatActivity, if (m.fromMe) 60 else 4), Ui.dp(this@ChatActivity, 3),
                Ui.dp(this@ChatActivity, if (m.fromMe) 4 else 60), Ui.dp(this@ChatActivity, 3))
        })
        return holder
    }

    /**
     * 发送 = 直译直发：点一下就翻译并发送译文。
     * 风险闸门（含金额）在引擎里：translateForSending 抛出的错误走 toast，消息不发出。
     */
    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        sendBtn.isEnabled = false
        engine.sendWithTranslation(chatJid, text) { _, err ->
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
