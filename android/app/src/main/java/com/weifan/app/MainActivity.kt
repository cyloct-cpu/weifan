package com.weifan.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.core.Engine
import com.weifan.app.core.Lang
import com.weifan.app.core.Routing
import com.weifan.app.service.WeiFanAccessibilityService

/**
 * 设置页。刻意不用 AndroidX / Compose：
 * 一个设置页不值得引入整套 UI 框架，纯 framework 视图编译更快、依赖为零。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var groqField: EditText
    private lateinit var deepLField: EditText
    private lateinit var googleField: EditText
    private lateinit var llmField: EditText
    private lateinit var llmBaseField: EditText
    private lateinit var triggerField: EditText
    private lateinit var thresholdField: EditText
    private lateinit var glossaryField: EditText
    private lateinit var autoReplaceBox: CheckBox
    private lateinit var targetSpinner: Spinner
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUI())
        loadValues()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // MARK: - UI

    private fun buildUI(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(title("微翻"))
        root.addView(hint("在任意输入框打完中文，末尾加触发词即可自动翻译并替换。不会自动发送。"))

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusText)

        root.addView(button("打开无障碍设置（开启微翻服务）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(section("翻译服务（至少填一个）"))
        groqField = field("Groq API Key（推荐，长句/含数字走这里）", secret = true)
        deepLField = field("DeepL API Key（中→欧语最稳）", secret = true)
        googleField = field("Google Translation API Key（小语种兜底）", secret = true)
        llmField = field("备用大模型 Key（OpenAI 兼容，可留空）", secret = true)
        llmBaseField = field("大模型 Base URL", secret = false)

        root.addView(section("行为"))
        root.addView(hint("兜底目标语言（检测不出对方语言时用）"))
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Lang.sendTargets.map { "${Lang.name(it)}（${it}）" }
            )
        }
        root.addView(targetSpinner)

        triggerField = field("触发后缀，默认 ?译")
        thresholdField = field("长句阈值（字），超过走大模型").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        autoReplaceBox = CheckBox(this).apply {
            text = "命中后直接替换输入框（含金额时仍会拦下，改为复制译文）"
            textSize = 13f
        }
        root.addView(autoReplaceBox)

        root.addView(section("术语黑名单（一行一个，不会被翻译）"))
        glossaryField = field("例如：轻舟 Pro", secret = false).apply {
            minLines = 3
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        root.addView(button("保存") { save() })

        root.addView(section("试译（不需要 WhatsApp）"))
        root.addView(button("翻译这句：Hello, when can you ship the order?") { testTranslate() })
        resultText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(resultText)

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(22), 0, dp(8))
        setTextColor(Color.parseColor("#00796B"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#666666"))
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun field(hintText: String, secret: Boolean = false): EditText {
        val edit = EditText(this)
        edit.hint = hintText
        edit.textSize = 13f
        edit.setSingleLine(true)
        if (secret) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return edit
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 14f
        b.setOnClickListener { onClick() }
        return b
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // MARK: - 数据

    private fun loadValues() {
        groqField.setText(prefs.groqKey)
        deepLField.setText(prefs.deepLKey)
        googleField.setText(prefs.googleKey)
        llmField.setText(prefs.llmKey)
        llmBaseField.setText(prefs.llmBaseUrl)
        triggerField.setText(prefs.trigger)
        thresholdField.setText(prefs.longSentenceThreshold.toString())
        glossaryField.setText(prefs.glossary)
        autoReplaceBox.isChecked = prefs.autoReplace

        val index = Lang.sendTargets.indexOf(prefs.defaultTarget)
        if (index >= 0) targetSpinner.setSelection(index)
    }

    private fun save() {
        prefs.groqKey = groqField.text.toString().trim()
        prefs.deepLKey = deepLField.text.toString().trim()
        prefs.googleKey = googleField.text.toString().trim()
        prefs.llmKey = llmField.text.toString().trim()
        prefs.llmBaseUrl = llmBaseField.text.toString().trim().ifBlank { "https://api.deepseek.com/v1" }
        prefs.trigger = triggerField.text.toString().ifBlank { "?译" }
        prefs.longSentenceThreshold = thresholdField.text.toString().toIntOrNull() ?: 60
        prefs.glossary = glossaryField.text.toString()
        prefs.autoReplace = autoReplaceBox.isChecked
        prefs.defaultTarget = Lang.sendTargets.getOrNull(targetSpinner.selectedItemPosition) ?: "en"

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        val hasKey = prefs.hasAnyKey()
        statusText.text = buildString {
            append(if (enabled) "✅ 无障碍服务已开启" else "⚠️ 无障碍服务未开启")
            append("　")
            append(if (hasKey) "✅ 已配置密钥" else "⚠️ 还没填翻译密钥")
            append("\n触发词：${prefs.trigger}　兜底语言：${Lang.name(prefs.defaultTarget)}")
        }
        statusText.setTextColor(if (enabled && hasKey) Color.parseColor("#00796B") else Color.parseColor("#C0392B"))
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(this, WeiFanAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    // MARK: - 试译

    private fun testTranslate() {
        resultText.text = "翻译中…"
        val sample = "Hello, when can you ship the order?"

        Thread {
            val output = try {
                val incoming = Engine.translateIncoming(sample, prefs)
                val route = Routing.route(sample)
                """
                原文：$sample
                译文：${incoming.translated}
                语言：${incoming.sourceLanguage ?: "未知"} → 简体中文
                引擎：${incoming.provider}（${route.route}）· ${incoming.latencyMs}ms
                """.trimIndent()
            } catch (t: Throwable) {
                "失败：${t.message ?: "未知错误"}\n先检查密钥与网络。"
            }
            runOnUiThread { resultText.text = output }
        }.start()
    }
}
