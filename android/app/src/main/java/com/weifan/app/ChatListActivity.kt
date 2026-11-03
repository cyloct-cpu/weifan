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
import android.widget.Toast
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
    // A3 多选：selectionBar（对齐参考 selectionClose / selectionCount / selectionAssign）
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private val selectedJids = linkedSetOf<String>()
    private var selectMode = false
    private val main = Handler(Looper.getMainLooper())
    private var filter = "所有"
    private var query = ""

    private val chips = listOf("所有", "未读", "特别关注", "群组", "已归档")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = ChatEngine.get(this)

        // 入口逻辑：未配对 → 直接进配对页（配对成功后自动回到本页）
        if (!engine.isPaired()) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }

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

        // ── 多选栏（A3，对齐参考 selectionBar：✕ 40dp + 计数占宽 + 批量指派 40dp）
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        selectionBar.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            setOnClickListener { exitSelectMode() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        selectionCount = TextView(this).apply {
            text = ""
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectionBar.addView(selectionCount)
        selectionBar.addView(TextView(this).apply {
            text = "👥"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            isClickable = true
            setOnClickListener { batchAssignLabels() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(selectionBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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

    /** chips：固定五项 + 用户标签（本地标签系统）；＋ 新建标签 */
    private fun renderChips() {
        chipRow.removeAllViews()
        val userLabels = engine.db.labels().map { it.second }
        for (name in chips + userLabels) {
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
            isClickable = true
            setOnClickListener { showNewLabelDialog() }
        }, LinearLayout.LayoutParams(
            dp(WaSpec.CHIP_ADD_BUTTON_DP), dp(WaSpec.CHIP_ADD_BUTTON_DP)).apply {
            marginStart = dp(WaSpec.CHIP_GAP_DP)
        })
    }

    /** 给会话打标签（复选对话框；可顺手新建） */
    private fun showLabelPicker(chat: ChatRow) {
        val all = engine.db.labels()
        if (all.isEmpty()) {
            Toast.makeText(this, "还没有标签，点列表顶部「＋」先建一个", Toast.LENGTH_LONG).show()
            return
        }
        val names = all.map { it.second }.toTypedArray()
        val checked = engine.db.chatLabelNames(chat.jid).toSet()
        val states = names.map { it in checked }.toBooleanArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("设置标签：${chat.title.ifBlank { chat.jid.substringBefore("@") }}")
            .setMultiChoiceItems(names, states) { _, which, isChecked ->
                states[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val ids = all.filterIndexed { i, _ -> states[i] }.map { it.first }
                engine.db.setChatLabels(chat.jid, ids)
                renderChats()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建标签（chips ＋） */
    private fun showNewLabelDialog() {
        val input = EditText(this).apply { hint = "标签名，如 高意向客户" }
        android.app.AlertDialog.Builder(this)
            .setTitle("新建标签")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotBlank()) {
                    engine.db.createLabel(n)
                    filter = n
                    renderChips()
                    renderChats()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
                "未读" -> c.unread > 0 && !c.archived
                "群组" -> c.jid.contains("@g.us") && !c.archived
                "特别关注" -> c.pinned && !c.archived
                "已归档" -> c.archived
                else -> if (filter in engine.db.labels().map { it.second }) {
                    // 用户标签：只显示打该标签的会话
                    engine.db.chatJidsOfLabel(filter).contains(c.jid)
                } else !c.archived
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

    // MARK: - A3 多选 + 批量指派（对齐参考 selectionBar）


    private fun enterSelectMode(first: String) {
        selectMode = true
        selectedJids.clear()
        selectedJids.add(first)
        selectionBar.visibility = View.VISIBLE
        renderChats()
        updateSelectionCount()
    }

    private fun exitSelectMode() {
        selectMode = false
        selectedJids.clear()
        selectionBar.visibility = View.GONE
        renderChats()
    }

    private fun toggleSelect(jid: String) {
        if (!selectedJids.remove(jid)) selectedJids.add(jid)
        if (selectedJids.isEmpty()) { exitSelectMode(); return }
        renderChats()
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        selectionCount.text = "已选 ${selectedJids.size} 个会话"
    }

    /** 批量指派：把选中的会话一次性打上标签（复用本地 labels 表） */
    private fun batchAssignLabels() {
        if (selectedJids.isEmpty()) return
        val all = engine.db.labels()
        if (all.isEmpty()) {
            Toast.makeText(this, "还没有标签，先在列表顶部「＋」建一个", Toast.LENGTH_LONG).show()
            return
        }
        val names = all.map { it.second }.toTypedArray()
        // 取交集作为初始勾选（所有选中会话都已有的标签）
        val common = selectedJids.map { engine.db.chatLabelNames(it).toSet() }
            .reduceOrNull { a, b -> a intersect b } ?: emptySet()
        val states = names.map { it in common }.toBooleanArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("批量设置标签（${selectedJids.size} 个会话）")
            .setMultiChoiceItems(names, states) { _, which, isChecked -> states[which] = isChecked }
            .setPositiveButton("应用") { _, _ ->
                val ids = all.filterIndexed { i, _ -> states[i] }.map { it.first }
                for (jid in selectedJids) engine.db.setChatLabels(jid, ids)
                Toast.makeText(this, "已为 ${selectedJids.size} 个会话设置标签",
                    Toast.LENGTH_SHORT).show()
                exitSelectMode()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 会话长按菜单：置顶/归档/静音/重命名（P1-2/3 本地实现） */
    private fun showChatMenu(chat: ChatRow) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@ChatListActivity,
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
        box.addView(item("新建群聊") { dlg.dismiss(); showNewGroupDialog() })
        box.addView(item("设置标签") { dlg.dismiss(); showLabelPicker(chat) })
        box.addView(item("该客户的待办") {
            dlg.dismiss()
            startActivity(Intent(this, FollowupTodosActivity::class.java).putExtra("jid", chat.jid))
        })
        box.addView(item(if (chat.pinned) "取消置顶" else "置顶聊天") {
            dlg.dismiss()
            engine.db.setChatFlags(chat.jid, pinned = !chat.pinned)
            renderChats()
        })
        box.addView(item(if (chat.archived) "取消归档" else "归档聊天") {
            dlg.dismiss()
            engine.db.setChatFlags(chat.jid, archived = !chat.archived)
            renderChats()
        })
        box.addView(item(if (chat.muted) "取消静音" else "静音通知") {
            dlg.dismiss()
            engine.db.setChatFlags(chat.jid, muted = !chat.muted)
            renderChats()
        })
        box.addView(item("拉黑") {
            dlg.dismiss()
            android.app.AlertDialog.Builder(this@ChatListActivity)
                .setTitle("拉黑此会话？")
                .setMessage("拉黑后对方消息不再出现在应用内，并同步拉黑到 WhatsApp 官方端（官方客户端也收不到）。可在 设置 → 已拉黑联系人 中恢复。")
                .setPositiveButton("拉黑") { _, _ ->
                    engine.db.setChatFlags(chat.jid, blocked = true)
                    Thread {
                        val err = try {
                            engine.client.updateBlocklist(chat.jid, "block"); null
                        } catch (e: Throwable) { e.message ?: e.toString() }
                        main.post {
                            if (err != null) Toast.makeText(this, "本地已屏蔽；官方端同步失败：$err", Toast.LENGTH_LONG).show()
                            else Toast.makeText(this, "已拉黑（含官方端）", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                    renderChips(); renderChats()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        box.addView(item("重命名") {
            dlg.dismiss()
            val input = EditText(this).apply { setText(chat.title) }
            android.app.AlertDialog.Builder(this@ChatListActivity)
                .setTitle("重命名会话")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val t = input.text.toString().trim()
                    if (t.isNotBlank()) {
                        engine.db.renameChat(chat.jid, t)
                        renderChats()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        })
        dlg.setView(box)
        dlg.show()
        dlg.window?.setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 建群：群名 + 成员手机号（逗号分隔），先校验后创建 */
    private fun showNewGroupDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val name = EditText(this).apply { hint = "群名称（25 字内）" }
        val members = EditText(this).apply {
            hint = "成员手机号，逗号分隔\n如 +5218120398050,+447925169391"
            setSingleLine(false)
            minLines = 2
        }
        box.addView(name)
        box.addView(members)
        android.app.AlertDialog.Builder(this)
            .setTitle("新建群聊")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val n = name.text.toString().trim()
                val list = members.text.toString().split(",", "，", "\n")
                    .map { it.trim().replace("+", "").filter { c -> c.isDigit() } }
                    .filter { it.length in 8..15 }
                if (n.isBlank() || list.isEmpty()) {
                    Toast.makeText(this, "群名和至少一个成员号码必填", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "正在创建…", Toast.LENGTH_SHORT).show()
                val phones = org.json.JSONArray(list).toString()
                Thread {
                    val jid = try { ChatEngine.get(this).client.createGroup(n, phones) }
                        catch (e: Throwable) { null }
                    main.post {
                        if (jid != null) {
                            Toast.makeText(this, "群已创建", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, ChatActivity::class.java)
                                .putExtra("jid", jid).putExtra("title", n))
                        } else {
                            Toast.makeText(this, "创建失败（可能号码未注册或无权限）", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
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
                // ANR 修复：解码+圆裁移到后台线程
                Thread {
                    try {
                        // 头像显示 ≤50dp：降采样后解码
                        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opt)
                        var sm = 1
                        while (opt.outWidth / (sm * 2) >= dp(100) || opt.outHeight / (sm * 2) >= dp(100)) sm *= 2
                        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sm })
                            ?: return@Thread
                        // 裁成正圆
                        val s = minOf(bmp.width, bmp.height)
                        val out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
                        val cv = android.graphics.Canvas(out)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        paint.shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP,
                            android.graphics.Shader.TileMode.CLAMP)
                        cv.drawCircle(s / 2f, s / 2f, s / 2f, paint)
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

    /** 会话行：头像 50dp + 标题 15sp + 预览 13.5sp + 右侧时间 11.5sp / 未读绿胶囊 */
    private fun chatRow(chat: ChatRow, timeFmt: SimpleDateFormat, dayFmt: SimpleDateFormat): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // 多选模式下选中行高亮（对齐参考选择态）
            if (selectMode && chat.jid in selectedJids) {
                setBackgroundColor(if (night) 0xFF1F2C34.toInt() else 0xFFE7F5EE.toInt())
            }
            isClickable = true
            isLongClickable = true
            isLongClickable = true
            setOnClickListener {
                // 多选模式下点行 = 勾选/取消（对齐参考 selectionBar 行为）
                if (selectMode) {
                    toggleSelect(chat.jid)
                } else {
                    startActivity(Intent(this@ChatListActivity, ChatActivity::class.java)
                        .putExtra("jid", chat.jid).putExtra("title", chat.title))
                }
            }
            // 多选模式：长按弹菜单；平时长按直接进多选（比先进菜单再选更顺手）
            setOnLongClickListener {
                if (!selectMode) { enterSelectMode(chat.jid); true }
                else { showChatMenu(chat); true }
            }
        }
        row.addView(avatar(chat.jid, chat.title))

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = (if (chat.pinned) "📌 " else "") + chat.title.ifBlank { chat.jid.substringBefore("@") }
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
                background = Ui.roundBg(this@ChatListActivity,
                    if (chat.muted) WaSpec.textSecondary(night) else WaSpec.GREEN_LIGHT, 11)
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

