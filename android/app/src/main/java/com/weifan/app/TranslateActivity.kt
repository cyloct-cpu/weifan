package com.weifan.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.core.Detector
import com.weifan.app.core.Engine
import com.weifan.app.core.Lang

/**
 * 翻译页：划词与文本翻译共用一套界面。
 *
 * **划词**（Android 上没有"鼠标悬停"，对应能力是划词）：
 * 在任意 app 里选中文字 → 菜单里出现「微翻」→ 打开本页，原文自动带过来、翻译完可以
 * **一键替换回原文**（系统用 startActivityForResult 启动我们，setResult 回传即可）。
 *
 * 官方约定：EXTRA_PROCESS_TEXT 是选中的文本，EXTRA_PROCESS_TEXT_READONLY 表示
 * 原文能不能被替换；替换的做法是 setResult(RESULT_OK) + 结果里再放一份 EXTRA_PROCESS_TEXT。
 */
class TranslateActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var targetSpinner: Spinner
    private lateinit var replaceButton: Button
    private lateinit var statusText: TextView
    private lateinit var subtitle: TextView

    /** 原文是否可被替换（选区所在的应用允不允许写回） */
    private var canReplace = false
    private var lastTranslation = ""

    private val p: Palette get() = Brand.palette(this)
    private val ctx: Context get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = p.bg
        setContentView(buildUI())

        handleIncoming(intent)
    }

    /** 页面已开着时，第二次划词会走 onNewIntent —— 两种入口都要处理 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** 划词入口：系统把选中的文字放进 EXTRA_PROCESS_TEXT */
    private fun handleIncoming(intent: Intent?) {
        val selected = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        canReplace = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) == false
        updateSubtitle()

        if (selected.isNotBlank()) {
            input.setText(selected)
            input.setSelection(selected.length)

            // 划词的默认方向：中文 → 兜底目标语言；外文 → 简体中文。
            // （否则选中一段英文、目标也是英语，译文会原样返回，看着像没翻）
            val detected = Detector.detect(selected)?.language
            val autoTarget = if (detected != null && Lang.isChinese(detected)) prefs.defaultTarget else "zh-Hans"
            val index = Lang.all.keys.toList().indexOf(autoTarget)
            if (index >= 0) targetSpinner.setSelection(index)

            translate()
        }
    }

    private fun updateSubtitle() {
        subtitle.text = if (canReplace) {
            "选中的文字已带过来，翻译后可以替换回原文。"
        } else {
            "粘贴或输入文字即可翻译。"
        }
        replaceButton.visibility = if (canReplace) View.VISIBLE else View.GONE
    }

    private fun buildUI(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 28))
        }

        // 标题
        root.addView(TextView(this).apply {
            text = "微翻 · 翻译"
            textSize = 19f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
        })
        subtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(p.text3)
            setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 12))
        }
        root.addView(subtitle)

        // 原文
        root.addView(Ui.label(ctx, "原文", p.text2, 12.5f, true))
        input = Ui.input(ctx, "要翻译的文字", lines = 4)
        root.addView(Ui.withMargin(ctx, input, 6, 0))

        // 目标语言
        root.addView(Ui.label(ctx, "目标语言", p.text2, 12.5f, true))
        targetSpinner = Ui.spinner(ctx, Lang.all.keys.map { "${Lang.name(it)}（$it）" })
        val index = Lang.all.keys.toList().indexOf(prefs.defaultTarget)
        if (index >= 0) targetSpinner.setSelection(index)
        root.addView(Ui.withMargin(ctx, targetSpinner, 6, 0))

        // 翻译按钮
        root.addView(Ui.withMargin(ctx, Ui.button(ctx, "翻译", true) { translate() }, 12, 0))

        // 译文
        root.addView(Ui.label(ctx, "译文", p.text2, 12.5f, true))
        output = Ui.resultBox(this)
        root.addView(Ui.withMargin(ctx, output, 6, 0))

        statusText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4), 0)
        }
        root.addView(statusText)

        // 操作行
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(Ui.button(ctx, "复制译文", false) { copyResult() })
        replaceButton = Ui.button(ctx, "替换原文", false) { replaceSelection() }
        replaceButton.visibility = if (canReplace) android.view.View.VISIBLE else android.view.View.GONE
        actions.addView(replaceButton)
        root.addView(Ui.withMargin(ctx, actions, 10, 0))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun translate() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            statusText.text = "先输入或粘贴一段文字"
            statusText.setTextColor(p.warn)
            return
        }

        val target = Lang.all.keys.toList().getOrNull(targetSpinner.selectedItemPosition) ?: "en"
        statusText.text = "翻译中…"
        statusText.setTextColor(p.text3)
        output.text = ""

        Thread {
            val outcome: Pair<Engine.GenericOutcome?, String?> = try {
                Engine.translateText(text, null, target, prefs) to null
            } catch (t: Throwable) {
                null to (t.message ?: "未知错误")
            }
            runOnUiThread {
                val result = outcome.first
                if (result == null) {
                    statusText.text = "失败：${outcome.second}"
                    statusText.setTextColor(p.err)
                    return@runOnUiThread
                }
                lastTranslation = result.text
                output.text = result.text
                statusText.text = buildString {
                    append(result.provider)
                    result.degradedFrom?.let { append("（已从 $it 降级）") }
                    append(" · ${result.latencyMs}ms")
                }
                statusText.setTextColor(p.teal)
                replaceButton.isEnabled = canReplace
            }
        }.start()
    }

    private fun copyResult() {
        if (lastTranslation.isBlank()) {
            Toast.makeText(this, "还没有译文", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", lastTranslation))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    /** 把译文写回原来的选区 */
    private fun replaceSelection() {
        if (lastTranslation.isBlank()) return
        val result = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, lastTranslation)
        setResult(RESULT_OK, result)
        Toast.makeText(this, "已替换原文", Toast.LENGTH_SHORT).show()
        finish()
    }
}
