package com.weifan.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.data.TodoRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跟进待办（批3-a，对齐参考 FollowupTodosActivity）。
 *
 * 跨境卖家的真实工作流是「今天答应报价，明天忘了就跟丢单」，所以待办要：
 *   能挂客户（从会话页带 jid 进来只看这个人）、能设到期、过期标红、勾掉即归档。
 * 纯本地存储（todos 表，DB v9），不上传任何地方。
 */
class FollowupTodosActivity : BaseActivity() {

    private val onlyJid: String get() = intent.getStringExtra("jid") ?: ""
    /** 当前标签：ALL / OVERDUE / TODAY / DONE（对齐参考 4 个标签页） */
    private var tab = "TODAY"
    private lateinit var listBox: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var statOverdueNum: TextView
    private lateinit var statPendingNum: TextView
    private val dayFmt = SimpleDateFormat("M月d日", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }
        root.addView(Ui.topBar(this, if (onlyJid.isBlank()) "跟进待办" else "该客户的待办",
            onBack = { finish() }, night = night,
            action = TextView(this).apply {
                text = "＋"; textSize = 26f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 0, dp(6), 0)
                isClickable = true
                setOnClickListener { showNewTodoDialog() }
            }))

        // 筛选：未完成 / 全部
        // 标签条：对齐参考 HorizontalScrollView padding 12/4/12/10，chip 间距 8dp
        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll.addView(filterRow)
        root.addView(tabScroll)

        // 统计卡片行：对齐参考 padding 16/12/16/4，两卡各 weight=1，间距 6dp
        val statRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        statOverdueNum = statCard(statRow, "逾期", 0)
        statRow.addView(android.view.View(this), LinearLayout.LayoutParams(dp(6), 1))
        statPendingNum = statCard(statRow, "待跟进", 1)
        root.addView(statRow)

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        renderFilters()
        renderTodos()
    }

    override fun onResume() {
        super.onResume()
        renderTodos()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    /** 4 个标签页：全部 / 逾期 / 今日 / 已完成（对齐参考 activity_followup_todos） */
    private fun renderFilters() {
        filterRow.removeAllViews()
        for ((label, key) in listOf("全部" to "ALL", "逾期" to "OVERDUE",
                                    "今日" to "TODAY", "已完成" to "DONE")) {
            filterRow.addView(Ui.chip(this, label, tab == key, night) {
                tab = key
                renderFilters()
                renderTodos()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (filterRow.childCount > 0) marginStart = dp(8)
            })
        }
    }

    /**
     * 统计卡片（对齐参考 bg_followup_stat_card：weight=1、padding 14/12、
     * 标签 12sp secondary + 数字 22sp bold primary + marginTop 2dp）。
     */
    private fun statCard(parent: LinearLayout, label: String, index: Int): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@FollowupTodosActivity,
                if (night) WaSpec.SURFACE_NIGHT else Color.WHITE, 12, 1, WaSpec.divider(night))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(WaSpec.textSecondary(night))
        })
        val num = TextView(this).apply {
            text = "0"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(WaSpec.textPrimary(night))
        }
        card.addView(num, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        parent.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index == 0) marginEnd = 0
        })
        return num
    }

    private fun renderTodos() {
        listBox.removeAllViews()
        // 统计卡片
        val (overdueCount, pendingCount) = engine().db.todoStats()
        if (::statOverdueNum.isInitialized) statOverdueNum.text = overdueCount.toString()
        if (::statPendingNum.isInitialized) statPendingNum.text = pendingCount.toString()

        val items = engine().db.todosByFilter(tab, onlyJid)

        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = when (tab) {
                    "OVERDUE" -> "没有逾期的待办"
                    "TODAY" -> "今天没有待办"
                    "DONE" -> "还没有完成的待办"
                    else -> "点右上角 ＋ 记一条，例如「给 leon 发 500 件报价单」"
                }
                textSize = 14f
                setTextColor(WaSpec.textSecondary(night))
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }

        // 分组：今日计划 / 逾期未跟 / 稍后（对齐参考 followup_section_*）
        if (tab == "ALL" || tab == "TODAY" || tab == "OVERDUE") {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            val dayEnd = dayStart + 24L * 3600_000L
            val groups = linkedMapOf(
                "逾期未跟" to items.filter { !it.done && it.dueTs in 1 until now },
                "今日计划" to items.filter { !it.done && it.dueTs >= dayStart && it.dueTs < dayEnd },
                "稍后" to items.filter {
                    !it.done && (it.dueTs == 0L || it.dueTs >= dayEnd) },
                "已完成" to items.filter { it.done })
            for ((title, list) in groups) {
                if (list.isEmpty()) continue
                listBox.addView(sectionHeader(title))
                for (t in list) listBox.addView(todoRow(t))
            }
        } else {
            for (t in items) listBox.addView(todoRow(t))
        }
    }

    /** 分组头（对齐参考 item_followup_section：13sp bold + padding 20/16/20/6 + letterSpacing 0.02） */
    private fun sectionHeader(title: String): View = TextView(this).apply {
        text = title
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(WaSpec.textSecondary(night))
        setPadding(dp(20), dp(16), dp(20), dp(6))
        letterSpacing = 0.02f
    }

    private fun todoRow(t: TodoRow): View {
        val now = System.currentTimeMillis()
        val overdue = !t.done && t.dueTs in 1 until now
        // 对齐参考 item_followup_todo：外层 padding 16/10/16/2，
        // 卡片内 padding 上下 12 / start 8 / end 4
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(2))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@FollowupTodosActivity,
                if (night) WaSpec.SURFACE_NIGHT else Color.WHITE, 12, 1, WaSpec.divider(night))
            setPadding(dp(8), dp(12), dp(4), dp(12))
        }
        box.addView(CheckBox(this).apply {
            isChecked = t.done
            setOnCheckedChangeListener { _, checked ->
                engine().db.setTodoDone(t.id, checked)
                renderTodos()
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(TextView(this).apply {
            text = t.content
            textSize = 15f
            setTextColor(if (t.done) WaSpec.textSecondary(night) else WaSpec.textPrimary(night))
            if (t.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            maxLines = 3
        })
        val meta = StringBuilder()
        if (t.customerTitle.isNotBlank()) meta.append("👤 ${t.customerTitle}")
        if (t.dueTs > 0) {
            if (meta.isNotEmpty()) meta.append(" · ")
            meta.append(if (overdue) "⚠ 已过期 " else "到期 ")
            meta.append(dayFmt.format(Date(t.dueTs)))
        }
        if (meta.isNotEmpty()) col.addView(TextView(this).apply {
            text = meta.toString()
            textSize = 12f
            setTextColor(if (overdue) Color.parseColor("#E53935") else WaSpec.textSecondary(night))
            setPadding(0, dp(3), 0, 0)
        })
        box.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(6) })

        box.addView(TextView(this).apply {
            text = "✕"; textSize = 17f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(dp(10), dp(6), dp(6), dp(6))
            isClickable = true
            setOnClickListener {
                android.app.AlertDialog.Builder(this@FollowupTodosActivity, R.style.WeifanDialog)
                    .setTitle("删除这条待办？")
                    .setMessage(t.content)
                    .setPositiveButton("删除") { _, _ ->
                        engine().db.deleteTodo(t.id); renderTodos()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        // 挂客户的条目点一下直接跳进会话
        if (t.chatJid.isNotBlank()) {
            wrap.setOnClickListener {
                startActivity(Intent(this, ChatActivity::class.java)
                    .putExtra("jid", t.chatJid).putExtra("title", t.customerTitle))
            }
        }
        outer.addView(wrap)
        return outer
    }

    /** 新建待办：内容 + 到期（今天/明天/三天后/不指定）+ 客户（不带 jid 进入时可选） */
    private fun showNewTodoDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(8))
        }
        val input = EditText(this).apply {
            hint = "要跟进什么，例如：给 leon 发 500 件报价单"
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            minLines = 2
            gravity = Gravity.TOP
            id = 3001
        }
        box.addView(input)

        var due = 0L
        val dueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val dueLabel = TextView(this).apply {
            text = "到期：不指定"
            textSize = 13f
            setTextColor(WaSpec.textSecondary(night))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            id = 3002
        }
        dueRow.addView(dueLabel)
        box.addView(dueRow)

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val day = 24L * 3600_000L
        for ((label, offset) in listOf("今天" to 0, "明天" to 1, "三天后" to 3, "不指定" to -1)) {
            quick.addView(Ui.chip(this, label, false, night) {
                due = if (offset < 0) 0L
                else {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, offset)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                    cal.set(java.util.Calendar.MINUTE, 59)
                    cal.timeInMillis
                }
                dueLabel.text =
                    "到期：" + if (due == 0L) "不指定" else dayFmt.format(Date(due))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (quick.childCount > 0) marginStart = dp(8)
            })
        }
        box.addView(quick)

        android.app.AlertDialog.Builder(this, R.style.WeifanDialog)
            .setTitle(if (onlyJid.isBlank()) "新建待办" else "给该客户新建待办")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                // 不能对 Activity 用 findViewById：AlertDialog 有自己的 decor，取不到会归 null
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    engine().db.addTodo(onlyJid, text, due)
                    renderTodos()
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .also { it.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }

    private fun engine() = com.weifan.app.chat.ChatEngine.get(this)
}
