package com.weifan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

import com.weifan.app.core.Theme

/**
 * 设置页 —— 视觉语言与会话列表统一（对齐 activity_chat_list 的顶栏与列表规格）：
 *
 *   顶栏：白底、‹ 44dp + 标题 20sp medium、右侧昼夜图标 42dp，底部分隔线
 *   分组标题：13sp medium、色 #6B7280、paddingH 16 / top 18 / bottom 6
 *   行：标题 15.5sp、说明 12.5sp、paddingH 16 / V 12，1px 分隔线（从 16dp 起）
 *   开关行：右侧 Switch，与文字垂直居中
 *
 * 说明：参考客户端没有独立设置页（设置项从会话列表菜单进入），
 * 所以本页保留自有信息结构，只把版式语言对齐。
 */
class SettingsActivity : BaseActivity() {

    private val p get() = palette

    /** 顶部那枚昼夜图标：昼 → 夜 → 跟随系统 三档循环 */
    private fun themeGlyph(mode: String): String = when (mode) {
        Theme.MODE_LIGHT -> "☀"
        Theme.MODE_DARK -> "☾"
        else -> "◐"
    }

    /** 版本号取自安装包信息，避免和 build.gradle 里的版本各说各话 */
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (e: Exception) {
        "—"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }

        // ── 顶栏：与列表页一致（白底 / 20sp medium / 右侧 42dp 图标）
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minimumHeight = dp(WaSpec.TOOLBAR_HEIGHT_DP)
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            contentDescription = "返回"
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(TextView(this).apply {
            text = "设置"
            textSize = WaSpec.LIST_TITLE_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        // 昼夜切换：昼 → 夜 → 跟随系统 → 昼，点一下立即生效
        top.addView(Ui.headerIconButton(this, themeGlyph(prefs.themeMode), night, 20f) {
            prefs.themeMode = Theme.nextMode(prefs.themeMode)
            recreate()
        })
        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(WaSpec.divider(night)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
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
        list.addView(rowValue("版本", appVersion(), onClick = null))
    }


    private fun dp(v: Int) = Ui.dp(this, v)

    private fun engine() = ChatEngineHolder.get(this)

    // ---- 构建小块 ----

    /** 分组标题：13sp medium、#6B7280（夜 #8696A0）、paddingH 16 / top 18 / bottom 6 */
    private fun section(title: String): View =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(WaSpec.textSecondary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(18), dp(16), dp(6))
        }

    private fun row(title: String, note: String, isError: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(if (isError) Color.parseColor("#CC4444") else WaSpec.textPrimary(night))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = note
                textSize = 12.5f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(2), 0, 0)
            })
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row)
        wrap.addView(View(this).apply { setBackgroundColor(WaSpec.divider(night)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
            })
        return wrap
    }

    /** 带右侧值的行；onClick 返回 true 表示自己刷新值 */
    private fun rowValue(title: String, value: String, onClick: (() -> Boolean)?): TextView =
        TextView(this).apply {
            text = "$title\n$value"
            textSize = 15.5f
            setTextColor(WaSpec.textPrimary(night))
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val col = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(WaSpec.textPrimary(night))
            })
            col.addView(TextView(this@SettingsActivity).apply {
                text = note
                textSize = 12.5f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(2), dp(8), 0)
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
