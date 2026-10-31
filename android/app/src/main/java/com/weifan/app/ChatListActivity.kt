package com.weifan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.chat.ChatEngine
import com.weifan.app.data.ChatRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页 —— WhatsApp 原生风格聊天列表：
 * 浅色顶栏（标题 + 会员图标位 + ⋮ 菜单）、搜索框、筛选 chips、头像会话行。
 * 设置收在右上角 ⋮ 弹出菜单里（参考 同类客户端 / WhatsApp 布局）。
 */
class ChatListActivity : BaseActivity() {

    private val p get() = palette
    private lateinit var engine: ChatEngine
    private lateinit var listBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var chipRow: HorizontalScrollView
    private lateinit var emptyText: TextView
    private val main = Handler(Looper.getMainLooper())
    private var filter = "全部"
    private var query = ""

    private val waGreen = WaSpec.GREEN                    // #128C7E 官方顶栏绿
    private val chipSel = WaSpec.CHIP_SELECTED            // #EBEBEF 官方选中灰
    private val chipBorder = WaSpec.CHIP_BORDER           // #E0E0E4
    private val bubbleGreen = Color.parseColor("#DCF8C6")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = ChatEngine.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (night) Color.parseColor("#0E1621") else Color.WHITE)
        }

        // ---- 顶栏：标题 + 会员图标 + ⋮ ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (night) WaSpec.GREEN_DARK else WaSpec.GREEN)
            setPadding(Ui.dp(this@ChatListActivity, 16), Ui.dp(this@ChatListActivity, 12),
                Ui.dp(this@ChatListActivity, 8), Ui.dp(this@ChatListActivity, 12))
        }
        top.addView(TextView(this).apply {
            text = "微翻"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 会员/状态图标（紫色圆 + 人形，对齐参考图占位）
        top.addView(menuButton())
        root.addView(top)

        // ---- 搜索框 ----
        val searchWrap = FrameLayout(this).apply {
            setBackgroundColor(if (night) Color.parseColor("#0E1621") else Color.WHITE)
            setPadding(Ui.dp(this@ChatListActivity, 12), Ui.dp(this@ChatListActivity, 8),
                Ui.dp(this@ChatListActivity, 12), Ui.dp(this@ChatListActivity, 4))
        }
        searchInput = EditText(this).apply {
            hint = "🔍  搜索会话"
            textSize = 14f
            setTextColor(p.text)
            setHintTextColor(Color.parseColor("#9AA5AD"))
            setBackgroundResource(0)
            background = Ui.roundBg(this@ChatListActivity, WaSpec.GRAY6, 22)  // #F2F2F7 官方搜索底
            setSingleLine(true)
            setPadding(Ui.dp(this@ChatListActivity, 18), Ui.dp(this@ChatListActivity, 9),
                Ui.dp(this@ChatListActivity, 12), Ui.dp(this@ChatListActivity, 9))
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; renderChats() }
        })
        searchWrap.addView(searchInput, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(searchWrap)

        // ---- 筛选 chips ----
        chipRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(if (night) Color.parseColor("#0E1621") else Color.WHITE)
            setPadding(Ui.dp(this@ChatListActivity, 8), 0, 0, Ui.dp(this@ChatListActivity, 4))
        }
        root.addView(chipRow)
        renderChips()

        // ---- 会话列表 ----
        val scroll = ScrollView(this).apply { setBackgroundColor(if (night) Color.parseColor("#0E1621") else Color.WHITE) }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyText = TextView(this).apply {
            text = ""
            gravity = Gravity.CENTER
        }

        setContentView(root)
    }


    private fun menuButton(): View =
        TextView(this).apply {
            text = "⋮"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@ChatListActivity, 10), 0, Ui.dp(this@ChatListActivity, 10), 0)
            setOnClickListener { showMenu() }
        }

    private fun showMenu() {
        // 统一归纳：菜单只留「设置」，其余全部收进 SettingsActivity
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun renderChips() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("全部", "未读", "群组", "特别关注")) {
            val selected = name == filter
            row.addView(TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(if (selected) WaSpec.CHIP_TEXT_SELECTED else WaSpec.CHIP_TEXT_GREEN)
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                background = Ui.roundBg(this@ChatListActivity,
                    if (selected) chipSel else WaSpec.CHIP_UNSELECTED, 18,
                    1, if (selected) WaSpec.CHIP_SELECTED_BORDER else chipBorder)
                setPadding(Ui.dp(this@ChatListActivity, 16), Ui.dp(this@ChatListActivity, 6),
                    Ui.dp(this@ChatListActivity, 16), Ui.dp(this@ChatListActivity, 6))
                setOnClickListener { filter = name; renderChips(); renderChats() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, Ui.dp(this@ChatListActivity, 8), 0)
            })
        }
        chipRow.removeAllViews()
        chipRow.addView(row)
    }

    override fun onResume() {
        super.onResume()
        engine.onChatUpdated = { main.post { renderChats() } }
        engine.onConnectionChanged = { _ -> main.post { renderChats() } }
        engine.connectAsync()
        renderChats()
    }

    override fun onPause() {
        super.onPause()
        engine.onChatUpdated = null
        engine.onConnectionChanged = null
    }

    private fun renderChats() {
        listBox.removeAllViews()
        if (!engine.isPaired()) {
            listBox.addView(unpairedCard())
            listBox.addView(hintRow("提示：右上角 ⋮ 可打开设置与预览模式"))
            return
        }
        engine.seedPreviewData()
        val all = engine.db.chats()
        val filtered = all.filter { c ->
            val matchFilter = when (filter) {
                "未读" -> c.unread > 0
                "群组" -> c.jid.contains("@g.us")
                "特别关注" -> false // TODO: 星标
                else -> true
            }
            val matchQuery = query.isBlank() || c.title.contains(query, true) ||
                c.lastMessage.contains(query, true) || c.jid.contains(query)
            matchFilter && matchQuery
        }
        if (filtered.isEmpty()) {
            listBox.addView(hintRow(if (all.isEmpty()) "暂无会话，等对方发一条消息" else "没有匹配的会话"))
            return
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFmt = SimpleDateFormat("M月d日", Locale.getDefault())
        for (chat in filtered) {
            listBox.addView(chatRow(chat, timeFmt, dayFmt))
        }
    }

    private fun unpairedCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@ChatListActivity, 24), Ui.dp(this@ChatListActivity, 32),
                Ui.dp(this@ChatListActivity, 24), Ui.dp(this@ChatListActivity, 16))
            addView(TextView(this@ChatListActivity).apply {
                text = "还没有关联 WhatsApp 账号"
                textSize = 17f
                setTextColor(p.text)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@ChatListActivity).apply {
                text = "输入手机号获取 8 位配对码，在官方 WhatsApp 里完成关联（与网页版同机制）。"
                textSize = 13.5f
                setTextColor(p.text2)
                setPadding(0, Ui.dp(this@ChatListActivity, 8), 0, Ui.dp(this@ChatListActivity, 16))
            })
            addView(Ui.button(this@ChatListActivity, "开始配对", primary = true) {
                startActivity(Intent(this@ChatListActivity, PairingActivity::class.java))
            })
        }

    private fun hintRow(text: String): View =
        TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(p.text3)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@ChatListActivity, 24), Ui.dp(this@ChatListActivity, 24),
                Ui.dp(this@ChatListActivity, 24), Ui.dp(this@ChatListActivity, 8))
        }

    private fun avatar(jid: String, title: String): View {
        val size = Ui.dp(this, 50)
        val cjk = title.firstOrNull { ch -> ch.toInt() > 0x2E80 }
        val letter = cjk?.toString() ?: title.firstOrNull()?.toString() ?: jid.take(2)
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, Ui.dp(this@ChatListActivity, 12), 0)
            }
            addView(TextView(this@ChatListActivity).apply {
                text = letter
                textSize = 19f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                // 按会话 hash 取色（WhatsApp 的多彩头像池）
                val palette = arrayOf("#25D366", "#34B7F1", "#FFB74D", "#9575CD", "#4DB6AC", "#F06292")
                val color = Color.parseColor(palette[(jid.hashCode() and 0xff) % palette.size])
                background = Ui.roundBg(this@ChatListActivity, color, Ui.dp(this@ChatListActivity, size / 2) / 2)
            }, FrameLayout.LayoutParams(size, size))
        }
    }

    private fun chatRow(chat: ChatRow, timeFmt: SimpleDateFormat, dayFmt: SimpleDateFormat): View {
        val pad = Ui.dp(this, 12)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@ChatListActivity, 14), pad, Ui.dp(this@ChatListActivity, 14), pad)
            setBackgroundResource(0)
            setOnClickListener {
                startActivity(Intent(this@ChatListActivity, ChatActivity::class.java)
                    .putExtra("jid", chat.jid).putExtra("title", chat.title))
            }
        }
        row.addView(avatar(chat.jid, chat.title))

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mid.addView(TextView(this@ChatListActivity).apply {
            text = chat.title.ifBlank { chat.jid.substringBefore("@") }
            textSize = 15.5f
            setTextColor(if (night) Color.parseColor("#E9EBEE") else WaSpec.TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        mid.addView(TextView(this@ChatListActivity).apply {
            text = chat.lastMessage
            textSize = 13.5f
            setTextColor(if (night) Color.parseColor("#8696A0") else Color.parseColor("#667781"))
            maxLines = 1
            setPadding(0, Ui.dp(this@ChatListActivity, 2), 0, 0)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        }
        right.addView(TextView(this@ChatListActivity).apply {
            text = timeFmt.format(Date(chat.lastTs))
            textSize = 11.5f
            setTextColor(if (chat.unread > 0) WaSpec.GREEN_LIGHT else WaSpec.TEXT_SECONDARY)
        })
        if (chat.unread > 0) {
            right.addView(TextView(this@ChatListActivity).apply {
                text = chat.unread.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = Ui.roundBg(this@ChatListActivity, WaSpec.GREEN_LIGHT, 11)
                setPadding(Ui.dp(this@ChatListActivity, 7), 1, Ui.dp(this@ChatListActivity, 7), 1)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, Ui.dp(this@ChatListActivity, 6), 0, 0) })
        }
        row.addView(right)
        return row
    }

}

/** 代理设置弹窗（设置页与列表页共用） */
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
                android.widget.Toast.makeText(activity, "已保存，重启微翻后生效", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
