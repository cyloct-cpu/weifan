package com.weifan.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.chat.ChatEngine
import com.weifan.app.data.MessageRow

/**
 * 会话页：消息流（收件自动双语，发件点「译」替换）+ 底部输入区。
 * 与扩展端交互一致：译文跟在原文下方；金额等风险拦截由 core.Routing 决定。
 */
class ChatActivity : Activity() {

    private val p by lazy { Brand.palette(false) }
    private lateinit var engine: ChatEngine
    private val main = Handler(Looper.getMainLooper())

    private var chatJid: String = ""
    private var chatTitle: String = ""

    private lateinit var listBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var translateBtn: Button
    private lateinit var statusText: TextView

    // 消息视图缓存：id → 译文 TextView（翻译完成后原地补）
    private val translatedViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatJid = intent.getStringExtra("jid") ?: ""
        chatTitle = intent.getStringExtra("title") ?: ""
        engine = ChatEngine.get(this)

        val pad = Ui.dp(this, 12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }

        // 顶栏
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, Ui.dp(this@ChatActivity, 6))
        }
        top.addView(TextView(this).apply {
            text = "<  "
            textSize = 18f
            setTextColor(p.text2)
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = chatTitle.ifBlank { chatJid.substringBefore("@") }
            textSize = 17f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusText = TextView(this).apply { textSize = 11f; setTextColor(p.text3) }
        top.addView(statusText)
        root.addView(top)

        // 消息流
        scroll = ScrollView(this).apply { setBackgroundColor(p.bg) }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@ChatActivity, 4), pad, Ui.dp(this@ChatActivity, 4))
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部输入区
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(pad, Ui.dp(this@ChatActivity, 6), pad, pad)
        }
        val inputColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatActivity, p.surface, 12, 1, p.line)
        }
        input = EditText(this).apply {
            hint = "输入中文…"
            textSize = 14f
            setTextColor(p.text)
            setHintTextColor(p.text3)
            background = null
            maxLines = 4
            setPadding(Ui.dp(this@ChatActivity, 12), Ui.dp(this@ChatActivity, 10),
                Ui.dp(this@ChatActivity, 12), Ui.dp(this@ChatActivity, 10))
        }
        inputColumn.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(inputColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(0, 0, Ui.dp(this@ChatActivity, 8), 0) })

        val btnColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        translateBtn = Ui.button(this, "译", primary = true) { onTranslate() }.apply {
            minimumWidth = Ui.dp(this@ChatActivity, 52)
        }
        btnColumn.addView(translateBtn)
        sendBtn = Ui.button(this, "发送", primary = false) { onSend() }.apply {
            minimumWidth = Ui.dp(this@ChatActivity, 52)
        }
        btnColumn.addView(Ui.withMargin(this, sendBtn, top = 6))
        bottom.addView(btnColumn)
        root.addView(bottom)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        engine.onMessageTranslated = { jid, id, translated ->
            main.post { if (jid == chatJid) appendTranslation(id, translated) }
        }
        engine.onConnectionChanged = { c ->
            main.post { statusText.text = if (c) "● 在线" else "○ 离线" }
        }
        statusText.text = if (engine.isConnected()) "● 在线" else "○ 离线"
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
        translatedViews.clear()
        val msgs = engine.db.messages(chatJid)
        for (m in msgs) bubble(m)
        main.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 画一条消息：原文气泡 + （收件时）下方的译文行 */
    private fun bubble(m: MessageRow) {
        val pad = Ui.dp(this, 10)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 原文
        holder.addView(TextView(this).apply {
            text = m.text
            textSize = 14f
            setTextColor(p.text)
            background = Ui.roundBg(this@ChatActivity,
                if (m.fromMe) p.warm else p.surface, 12, 0)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (m.fromMe) Gravity.END else Gravity.START
                setMargins(0, Ui.dp(this@ChatActivity, 4), 0, 0)
            }
        })
        // 译文行（收件消息；有译文显示译文，无译文先占位显示「翻译中…」）
        if (!m.fromMe) {
            val tv = TextView(this).apply {
                textSize = 12.5f
                setTextColor(p.cool)
                text = if (m.translated.isBlank()) "… 翻译中" else m.translated
                setPadding(Ui.dp(this@ChatActivity, 6), Ui.dp(this@ChatActivity, 2),
                    Ui.dp(this@ChatActivity, 6), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (m.fromMe) Gravity.END else Gravity.START
                }
            }
            holder.addView(tv)
            translatedViews[m.id] = tv
        }
        listBox.addView(holder)
    }

    /** 翻译回调：原地补译文 */
    private fun appendTranslation(id: String, translated: String) {
        translatedViews[id]?.let { tv ->
            tv.text = translated
            tv.setTextColor(p.cool)
        }
    }

    /** 发件：把输入框内容翻译成对方语言，替换输入框（不自动发送） */
    private fun onTranslate() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        translateBtn.isEnabled = false
        translateBtn.text = "…"
        engine.translateOutgoingAsync(chatJid, text) { translated, _, err ->
            translateBtn.isEnabled = true
            translateBtn.text = "译"
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            } else {
                input.setText(translated)
                input.setSelection(translated.length)
                Toast.makeText(this, "已替换为译文，确认后点「发送」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 发送当前输入框内容 */
    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        sendBtn.isEnabled = false
        engine.sendText(chatJid, text) { _, err ->
            sendBtn.isEnabled = true
            if (err != null) {
                Toast.makeText(this, "发送失败：$err", Toast.LENGTH_SHORT).show()
            } else {
                input.setText("")
                renderMessages()
            }
        }
    }
}
