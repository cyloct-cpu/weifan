package com.weifan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 统一设置页（WhatsApp 风格列表）：把翻译服务、WhatsApp 连接、翻译工具全部归纳到这里。
 * 主页 ⋮ 菜单只留「设置」一个入口，跳转到本页。
 */
class SettingsActivity : BaseActivity() {

    private val p get() = palette
    private val waGreen = Color.parseColor("#075E54")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }

        // 顶栏
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (night) WaSpec.GREEN_DARK else WaSpec.GREEN)
            setPadding(Ui.dp(this@SettingsActivity, 8), Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12))
            addView(TextView(this@SettingsActivity).apply {
                text = "‹"
                textSize = 30f
                setTextColor(Color.WHITE)
                setPadding(Ui.dp(this@SettingsActivity, 8), 0, Ui.dp(this@SettingsActivity, 12), 0)
                setOnClickListener { finish() }
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "设置"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
        })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // ---- WhatsApp 连接 ----
        list.addView(section("WhatsApp 连接"))
        list.addView(row("关联 WhatsApp 账号", "扫码码数配对 / 手机号配对码", isError = !engine().isPaired()) {
            startActivity(Intent(this, PairingActivity::class.java))
        })
        list.addView(rowValue("网络代理", prefs.waProxy.ifBlank { "直连（国内网络需要配置）" }) {
            ProxyDialog.show(this)
            true
        })
        list.addView(rowSwitch("收件自动翻译", "对方消息到达后自动出中文", prefs.waAutoTranslateIncoming) {
            prefs.waAutoTranslateIncoming = it
        })
        list.addView(rowSwitch("预览模式（测试 UI）", "跳过真实配对，用演示会话过界面", prefs.waPreviewMode) {
            prefs.waPreviewMode = it
            Toast.makeText(this, if (it) "预览模式已开启" else "已关闭", Toast.LENGTH_SHORT).show()
        })

        // ---- 翻译 ----
        list.addView(section("翻译"))
        list.addView(row("翻译服务（九家 + 自定义接口）", "密钥、优先级、额度保护、试译") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        list.addView(row("翻译工具（划词 / 文本 / 文档）", "与 WhatsApp 无关的通用翻译") {
            startActivity(Intent(this, TranslateActivity::class.java))
        })
        list.addView(row("翻译文档", "txt / md / csv / docx / PDF，本机解析") {
            startActivity(Intent(this, TranslateDocActivity::class.java))
        })

        // ---- 关于 ----
        list.addView(section("关于"))
        list.addView(rowValue("版本", "0.1.0", onClick = null))
    }

    private fun engine() = ChatEngineHolder.get(this)

    // ---- 构建小块 ----

    private fun section(title: String): View =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#075E54"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 18),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 6))
        }

    private fun row(title: String, note: String, isError: Boolean = false, onClick: () -> Unit): View {
        val this_row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(if (isError) Color.parseColor("#CC4444") else p.text)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = note
                textSize = 12.5f
                setTextColor(p.text2)
                setPadding(0, Ui.dp(this@SettingsActivity, 2), 0, 0)
            })
        }
        // 带分隔线
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(this_row)
        wrap.addView(View(this).apply {
            setBackgroundColor(p.line)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@SettingsActivity, 1))
        })
        return wrap
    }


    /** 带右侧值的行；onClick 返回 true 表示自己刷新值 */
    private fun rowValue(title: String, value: String, onClick: (() -> Boolean)?): TextView =
        TextView(this).apply {
            text = "$title\n$value"
            textSize = 15.5f
            setTextColor(p.text)
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 12))
            setOnClickListener {
                if (onClick?.invoke() == true) {
                    text = "$title\n${Prefs(this@SettingsActivity).waProxy.ifBlank { "直连（国内网络需要配置）" }}"
                }
            }
        }

    private fun rowSwitch(title: String, note: String, initial: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 10),
                Ui.dp(this@SettingsActivity, 16), Ui.dp(this@SettingsActivity, 10))
            val col = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(p.text)
            })
            col.addView(TextView(this@SettingsActivity).apply {
                text = note
                textSize = 12.5f
                setTextColor(p.text2)
                setPadding(0, Ui.dp(this@SettingsActivity, 2), Ui.dp(this@SettingsActivity, 8), 0)
            })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@SettingsActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            })
        }
}

/** 延迟拿 ChatEngine（避免 SettingsActivity 在 engine 未初始化时构建崩溃） */
private object ChatEngineHolder {
    fun get(activity: Activity) = com.weifan.app.chat.ChatEngine.get(activity)
}
