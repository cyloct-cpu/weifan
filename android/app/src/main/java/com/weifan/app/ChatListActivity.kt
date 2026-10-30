package com.weifan.app

import android.app.Activity
import android.content.Intent
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
import com.weifan.app.data.ChatRow

/**
 * 主页：聊天列表 + 配对入口 + 连接状态。
 * 未配对 → 显示配对引导卡；已配对 → 会话列表（点进会话页）。
 */
class ChatListActivity : Activity() {

    private val p by lazy { Brand.palette(false) }
    private lateinit var engine: ChatEngine
    private lateinit var listBox: LinearLayout
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var pairCard: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = ChatEngine.get(this)

        val pad = Ui.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }

        // 顶栏：标题 + 状态
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, Ui.dp(this@ChatListActivity, 8))
        }
        top.addView(TextView(this).apply {
            text = "微翻"
            textSize = 20f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusDot = TextView(this).apply { textSize = 12f; setTextColor(p.text3) }
        statusText = TextView(this).apply { textSize = 12f; setTextColor(p.text3) }
        top.addView(statusText)
        top.addView(Ui.withMargin(this, statusDot, top = 0).apply { minimumWidth = Ui.dp(this@ChatListActivity, 8) })
        root.addView(top)

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        engine.onChatUpdated = { main.post { renderChats() } }
        engine.onConnectionChanged = { c -> main.post { renderStatus(c) } }
        engine.connectAsync()
        renderChats()
        renderStatus(engine.isConnected())
    }

    override fun onPause() {
        super.onPause()
        engine.onChatUpdated = null
        engine.onConnectionChanged = null
    }

    private fun renderStatus(connected: Boolean) {
        statusText.text = when {
            !engine.isPaired() -> "未关联"
            connected -> "已连接"
            else -> "离线"
        }
        statusDot.text = if (connected) "●" else "○"
        statusDot.setTextColor(if (connected) 0xFF34A853.toInt() else p.text3)
        if (::pairCard.isInitialized) {
            pairCard.visibility = if (engine.isPaired()) View.GONE else View.VISIBLE
        }
    }

    private fun renderChats() {
        listBox.removeAllViews()
        val paired = engine.isPaired()
        if (paired) engine.seedPreviewData()

        // 未配对：配对引导卡
        if (!paired) {
            pairCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.roundBg(this@ChatListActivity, p.surface, 14, 1, p.line)
                setPadding(Ui.dp(this@ChatListActivity, 20), Ui.dp(this@ChatListActivity, 24),
                    Ui.dp(this@ChatListActivity, 20), Ui.dp(this@ChatListActivity, 24))
                addView(Ui.label(this@ChatListActivity, "还没有关联 WhatsApp 账号", p.text, 16f, bold = true))
                addView(Ui.withMargin(this@ChatListActivity,
                    Ui.label(this@ChatListActivity,
                        "输入手机号获取 8 位配对码，在官方 WhatsApp 里完成关联。微信式的流程，一次配对长期有效。",
                        p.text2, 13f), top = 8, bottom = 16))
                addView(Ui.button(this@ChatListActivity, "开始配对", primary = true) {
                    startActivity(Intent(this@ChatListActivity, PairingActivity::class.java))
                })
            }
            listBox.addView(pairCard)

            // 翻译工具入口保留（划词/文本/文档）
            listBox.addView(Ui.withMargin(this, toolCard(), top = 16))

            // 预览模式开关（测试用）+ 代理设置
            listBox.addView(Ui.withMargin(this,
                Ui.button(this, "预览模式（跳过配对看 UI）", primary = false) {
                    val prefs = Prefs(this@ChatListActivity)
                    prefs.waPreviewMode = !prefs.waPreviewMode
                    Toast.makeText(this@ChatListActivity,
                        if (prefs.waPreviewMode) "预览模式已开启" else "预览模式已关闭",
                        Toast.LENGTH_SHORT).show()
                    renderChats()
                }, top = 12))
            listBox.addView(Ui.withMargin(this,
                Ui.button(this, "网络代理设置（国内网络需要）", primary = false) {
                    ProxyDialog.show(this)
                }, top = 12))
            return
        }

        // 已配对：会话列表
        val chats = engine.db.chats()
        if (chats.isEmpty()) {
            listBox.addView(Ui.label(this, "暂无会话。等对方发一条消息，或从官方 App 发一条。",
                p.text3, 13f))
            return
        }
        for (chat in chats) {
            listBox.addView(chatRow(chat))
        }
        listBox.addView(Ui.withMargin(this,
            Ui.button(this, "给一个号码发消息", primary = false) { newChatDialog() }, top = 12))
        // 已配对状态下也保留预览开关与设置入口
        listBox.addView(Ui.withMargin(this,
            Ui.button(this, "设置（翻译服务 / 触发词 / 术语）", primary = false) {
                startActivity(Intent(this@ChatListActivity, MainActivity::class.java))
            }, top = 12))
        listBox.addView(Ui.withMargin(this,
            Ui.button(this, "退出预览模式", primary = false) {
                Prefs(this@ChatListActivity).waPreviewMode = false
                Toast.makeText(this@ChatListActivity, "已退出预览模式", Toast.LENGTH_SHORT).show()
                renderChats()
            }, top = 12))
    }

    private fun toolCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatListActivity, p.surface, 14, 1, p.line)
            setPadding(Ui.dp(this@ChatListActivity, 16), Ui.dp(this@ChatListActivity, 14),
                Ui.dp(this@ChatListActivity, 16), Ui.dp(this@ChatListActivity, 14))
        }
        box.addView(Ui.label(this, "翻译工具", p.text, 14f, bold = true))
        box.addView(Ui.withMargin(this,
            Ui.button(this, "文本翻译", primary = false) {
                startActivity(Intent(this, TranslateActivity::class.java))
            }, top = 10))
        box.addView(Ui.withMargin(this,
            Ui.button(this, "文档翻译", primary = false) {
                startActivity(Intent(this, TranslateDocActivity::class.java))
            }, top = 8))
        return box
    }

    private fun chatRow(chat: ChatRow): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatListActivity, p.surface, 12, 1, p.line)
            setPadding(Ui.dp(this@ChatListActivity, 14), Ui.dp(this@ChatListActivity, 12),
                Ui.dp(this@ChatListActivity, 14), Ui.dp(this@ChatListActivity, 12))
            setOnClickListener {
                startActivity(Intent(this@ChatListActivity, ChatActivity::class.java)
                    .putExtra("jid", chat.jid)
                    .putExtra("title", chat.title))
            }
        }
        val titleLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleLine.addView(TextView(this).apply {
            text = chat.title.ifBlank { chat.jid.substringBefore("@") }
            textSize = 14.5f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chat.unread > 0) {
            titleLine.addView(TextView(this).apply {
                text = chat.unread.toString()
                textSize = 11f
                setTextColor(p.ink)
                background = Ui.roundBg(this@ChatListActivity, p.warm, 10)
                setPadding(Ui.dp(this@ChatListActivity, 6), 0, Ui.dp(this@ChatListActivity, 6), 0)
            })
        }
        row.addView(titleLine)
        row.addView(Ui.withMargin(this, TextView(this).apply {
            text = chat.lastMessage
            textSize = 12.5f
            setTextColor(p.text2)
            maxLines = 1
        }, top = 3))
        return Ui.withMargin(this, row, top = 8)
    }

    /** 输入手机号/JID 开始新会话 */
    private fun newChatDialog() {
        val input = EditText(this).apply { hint = "+86 138…" }
        android.app.AlertDialog.Builder(this)
            .setTitle("新会话")
            .setMessage("输入对方手机号（含国家区号）")
            .setView(input)
            .setPositiveButton("打开") { _, _ ->
                var v = input.text.toString().trim()
                if (v.isNotBlank()) {
                    if (!v.contains("@")) {
                        v = v.replace(Regex("[\\s\\-()]"), "") + "@s.whatsapp.net"
                    }
                    startActivity(Intent(this, ChatActivity::class.java)
                        .putExtra("jid", v).putExtra("title", v.substringBefore("@")))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

/** 代理设置弹窗（独立文件级函数以便复用） */
object ProxyDialog {
    fun show(activity: Activity) {
        val prefs = Prefs(activity)
        val input = EditText(activity).apply {
            hint = "socks5://127.0.0.1:7892 或 http://…"
            setText(prefs.waProxy)
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("网络代理")
            .setMessage("国内网络连接 WhatsApp 服务器需要代理。\n支持 socks5:// 与 http://，留空为直连。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                prefs.waProxy = input.text.toString()
                Toast.makeText(activity, "已保存，重启微翻后生效", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
