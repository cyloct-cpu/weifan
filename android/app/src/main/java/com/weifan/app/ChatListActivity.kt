package com.weifan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.weifan.app.chat.ChatEngine
import com.weifan.app.data.ChatRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表页 —— 按参考客户端 activity_chat_list 的规格 1:1 重排：
 *
 *   顶栏（白底、非绿）：标题 20sp sans-serif-medium + 右侧 42dp 图标按钮
 *   搜索条：高 40dp、左右 12dp、圆角 20dp、内边距 14dp、图标 18dp、输入 15sp
 *   chips 行：上下 8/10dp、chip 圆角 18dp、选中 #EBEBEF + 描边 #D1D1D6
 *   连接状态条（默认隐藏）：灰底 #F2F2F7、padding 16/10dp、13sp
 *   会话行：头像 50dp、标题 15sp、预览 13.5sp、时间 11.5sp、未读绿胶囊
 *   空态：标题 15sp / 统计 14sp / 提示 13sp，居中，左右 24dp
 *
 * 注意：参考实现的顶栏是白底（wa_surface），不是 WhatsApp 官方绿顶栏 —— 这点与旧版不同。
 */
class ChatListActivity : BaseActivity() {

    private val p get() = palette
    private lateinit var engine: ChatEngine
    private lateinit var listBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var chipRow: LinearLayout
    private lateinit var connectingBar: LinearLayout
    private lateinit var emptyBox: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var filter = "所有"
    private var query = ""

    private val chips = listOf("所有", "未读", "特别关注", "群组")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = ChatEngine.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }

        // ── 顶栏：标题 20sp medium + 右侧菜单按钮
        val top = Ui.listHeader(this, "WeFlip", night, listOf(
            Ui.headerIconButton(this, "⋮", night, 24f) { openSettings() }
        ))
        root.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 搜索条：40dp 高、12dp 外边距、20dp 圆角
        val (bar, input) = Ui.searchBar(this, "搜索或开始新聊天", night) { q ->
            query = q
            renderChats()
        }
        searchInput = input
        root.addView(Ui.searchBarRow(this, bar), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(WaSpec.SEARCH_BAR_HEIGHT_DP) + dp(WaSpec.SEARCH_BAR_MARGIN_TOP_DP)))

        // ── chips 行：上下 8/10dp，左右 12dp，间距 8dp
        val chipWrap = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(WaSpec.CHIP_ROW_PADDING_H_DP), dp(WaSpec.CHIP_ROW_MARGIN_TOP_DP),
                dp(WaSpec.CHIP_ROW_PADDING_H_DP), dp(WaSpec.CHIP_ROW_MARGIN_BOTTOM_DP))
            setBackgroundColor(WaSpec.surface(night))
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipWrap.addView(chipRow)
        root.addView(chipWrap)
        renderChips()

        // ── 连接状态条（默认隐藏，连接中才显示）
        connectingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (night) WaSpec.SURFACE_NIGHT else WaSpec.GRAY6)
            setPadding(dp(WaSpec.CONNECTING_BAR_PADDING_H_DP), dp(WaSpec.CONNECTING_BAR_PADDING_V_DP),
                dp(WaSpec.CONNECTING_BAR_PADDING_H_DP), dp(WaSpec.CONNECTING_BAR_PADDING_V_DP))
            visibility = View.GONE
            addView(TextView(this@ChatListActivity).apply {
                text = "◌"
                textSize = 14f
                setTextColor(WaSpec.textSecondary(night))
            })
            addView(TextView(this@ChatListActivity).apply {
                text = "正在连接…"
                textSize = WaSpec.CONNECTING_TEXT_SP.toFloat()
                setTextColor(WaSpec.textSecondary(night))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(WaSpec.CONNECTING_GAP_DP)
            })
        }
        root.addView(connectingBar)

        // ── 会话列表 + 空态（FrameLayout 叠放，对齐 activity_chat_list）
        val listFrame = FrameLayout(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(WaSpec.surface(night)) }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        listFrame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(WaSpec.EMPTY_PADDING_H_DP), 0, dp(WaSpec.EMPTY_PADDING_H_DP), 0)
            visibility = View.GONE
        }
        listFrame.addView(emptyBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.addView(listFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** chips：选中态 #EBEBEF + 描边 #D1D1D6；未选白底 + #E0E0E4 描边；末尾 ＋ 按钮 32dp */
    private fun renderChips() {
        chipRow.removeAllViews()
        for (name in chips) {
            val selected = name == filter
            chipRow.addView(Ui.chip(this, name, selected, night) {
                filter = name
                renderChips()
                renderChats()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (chipRow.childCount > 0) marginStart = dp(WaSpec.CHIP_GAP_DP)
            })
        }
        chipRow.addView(TextView(this).apply {
            text = "＋"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            background = Ui.roundBg(this@ChatListActivity, WaSpec.chipFill(false, night), 16, 1,
                WaSpec.chipStroke(false, night))
            setPadding(dp(WaSpec.CHIP_ADD_PADDING_DP), 0, dp(WaSpec.CHIP_ADD_PADDING_DP), 0)
        }, LinearLayout.LayoutParams(
            dp(WaSpec.CHIP_ADD_BUTTON_DP), dp(WaSpec.CHIP_ADD_BUTTON_DP)).apply {
            marginStart = dp(WaSpec.CHIP_GAP_DP)
        })
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
        emptyBox.removeAllViews()

        val paired = engine.isPaired()
        connectingBar.visibility = if (!paired) View.VISIBLE else View.GONE

        if (!paired) {
            showEmpty(
                title = "还没有关联 WhatsApp 账号",
                stats = "输入手机号获取 8 位配对码，在官方 WhatsApp 里完成关联",
                hint = "关联后，会话会自动出现在这里"
            )
            emptyBox.addView(Ui.solidButton(this, "开始配对", WaSpec.GREEN, Color.WHITE) {
                startActivity(Intent(this, PairingActivity::class.java))
            }, LinearLayout.LayoutParams(dp(200), dp(46)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
            emptyBox.visibility = View.VISIBLE
            return
        }

        val all = engine.db.chats()
        val filtered = all.filter { c ->
            val matchFilter = when (filter) {
                "未读" -> c.unread > 0
                "群组" -> c.jid.contains("@g.us")
                "特别关注" -> false
                else -> true
            }
            val matchQuery = query.isBlank() || c.title.contains(query, true) ||
                c.lastMessage.contains(query, true) || c.jid.contains(query)
            matchFilter && matchQuery
        }

        if (filtered.isEmpty()) {
            showEmpty(
                title = if (all.isEmpty()) "同步完成，暂无会话" else "没有匹配的会话",
                stats = if (all.isEmpty()) "等对方发一条消息即可" else "",
                hint = if (all.isEmpty()) "点击右上角 ＋ 开始新聊天" else "换个筛选条件试试"
            )
            emptyBox.visibility = View.VISIBLE
            return
        }

        emptyBox.visibility = View.GONE
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFmt = SimpleDateFormat("M月d日", Locale.getDefault())
        for (chat in filtered) listBox.addView(chatRow(chat, timeFmt, dayFmt))
    }

    /** 空态：标题 15sp / 统计 14sp / 提示 13sp，间距 12dp、8dp、8dp（对齐 emptyView 一组）*/
    private fun showEmpty(title: String, stats: String, hint: String) {
        emptyBox.addView(TextView(this).apply {
            text = title
            textSize = WaSpec.EMPTY_TITLE_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (stats.isNotBlank()) emptyBox.addView(TextView(this).apply {
            text = stats
            textSize = WaSpec.EMPTY_STATS_SP.toFloat()
            setTextColor(WaSpec.textSecondary(night))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(WaSpec.EMPTY_GAP_2_DP)
        })
        if (hint.isNotBlank()) emptyBox.addView(TextView(this).apply {
            text = hint
            textSize = WaSpec.EMPTY_HINT_SP.toFloat()
            setTextColor(WaSpec.textSecondary(night))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(WaSpec.EMPTY_GAP_2_DP)
        })
    }

    /** 会话行头像：50dp 正圆。先占位字，真头像拉到后换成照片 */
    private fun avatar(jid: String, title: String): View {
        val size = dp(WaSpec.LIST_AVATAR_SIZE_DP)
        val cjk = title.firstOrNull { ch -> ch.toInt() > 0x2E80 }
        val letter = cjk?.toString() ?: title.firstOrNull()?.toString() ?: jid.take(2)
        val colors = arrayOf("#25D366", "#34B7F1", "#FFB74D", "#9575CD", "#4DB6AC", "#F06292")
        val color = Color.parseColor(colors[(jid.hashCode() and 0xff) % colors.size])
        return TextView(this).apply {
            text = letter
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.roundBg(this@ChatListActivity, color, WaSpec.LIST_AVATAR_SIZE_DP / 2)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(12)
            }
            val view = this
            engine.fetchAvatarAsync(jid) { f ->
                if (isDestroyed || isFinishing || f == null) return@fetchAvatarAsync
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return@fetchAvatarAsync
                    // 裁成正圆
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

    /** 会话行：头像 50dp + 标题 15sp + 预览 13.5sp + 右侧时间 11.5sp / 未读绿胶囊 */
    private fun chatRow(chat: ChatRow, timeFmt: SimpleDateFormat, dayFmt: SimpleDateFormat): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@ChatListActivity, ChatActivity::class.java)
                    .putExtra("jid", chat.jid).putExtra("title", chat.title))
            }
        }
        row.addView(avatar(chat.jid, chat.title))

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = chat.title.ifBlank { chat.jid.substringBefore("@") }
            textSize = 15.5f
            setTextColor(WaSpec.textPrimary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        mid.addView(TextView(this).apply {
            text = chat.lastMessage
            textSize = 13.5f
            setTextColor(WaSpec.textSecondary(night))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(TextView(this).apply {
            text = timeFmt.format(Date(chat.lastTs))
            textSize = 11.5f
            setTextColor(if (chat.unread > 0) WaSpec.GREEN_LIGHT else WaSpec.textSecondary(night))
        })
        if (chat.unread > 0) {
            right.addView(TextView(this).apply {
                text = chat.unread.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = Ui.roundBg(this@ChatListActivity, WaSpec.GREEN_LIGHT, 11)
                setPadding(dp(7), dp(1), dp(7), dp(1))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                gravity = Gravity.END
            })
        }
        row.addView(right, LinearLayout.LayoutParams(
            Ui.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // 固定宽度，避免被长预览挤截断
            marginStart = dp(6)
        })

        // 底部细分隔线（wa_divider），与参考列表一致
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row)
        wrap.addView(View(this).apply {
            setBackgroundColor(WaSpec.divider(night))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(14 + WaSpec.LIST_AVATAR_SIZE_DP + 12)
        })
        return wrap
    }
}

