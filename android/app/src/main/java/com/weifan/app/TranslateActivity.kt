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
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.core.Detector
import com.weifan.app.core.Engine
import com.weifan.app.core.Lang

/**
 * 翻译页：划词与文本翻译共用。
 *
 * **划词**（Android 上没有"鼠标悬停"，对应能力是划词）：
 * 在任意 app 里选中文字 → 菜单里出现「微翻」→ 打开本页，原文自动带过来、翻译完可以
 * **一键替换回原文**（系统用 startActivityForResult 启动我们，setResult 回传即可）。
 *
 * **语言框**：三个可输入的语言框（源语言 / 目标语言 / 第二个目标语言）——
 *   - 都能直接打字，输入 "en"、"english"、"英语" 都能匹配；
 *   - 第一个「⇄」在源与目标之间互换（语言和原文/译文内容一起换）；
 *   - 第二个「⇄」在两个目标之间互换（两份译文对调）；
 *   - 填了第二个目标语言，就**同时输出两种语言的译文**。
 */
class TranslateActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var input: EditText
    private lateinit var output1: TextView
    private lateinit var output2: TextView
    private lateinit var statusText: TextView
    private lateinit var subtitle: TextView
    private lateinit var replaceButton: Button

    private lateinit var sourceField: LanguageField
    private lateinit var targetField: LanguageField
    private lateinit var target2Field: LanguageField

    /** 原文是否可被替换（选区所在的应用允不允许写回） */
    private var canReplace = false
    private var lastTranslation = ""
    private var lastTranslation2 = ""

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

        // 原文（**只有原文能输入**，译文都是只读输出）
        root.addView(Ui.label(ctx, "原文", p.text2, 12.5f, true))
        input = Ui.input(ctx, "要翻译的文字", lines = 4)
        root.addView(Ui.withMargin(ctx, input, 6, 0))

        // 翻译按钮
        root.addView(Ui.withMargin(ctx, Ui.button(ctx, "翻译", true) { translate() }, 12, 0))

        // 语言行放在中间：源 ⇄ 目标 ⇄ 第二个目标
        root.addView(Ui.label(ctx, "语言", p.text2, 12.5f, true))
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceField = LanguageField(ctx, "auto")
        targetField = LanguageField(ctx, prefs.defaultTarget)
        target2Field = LanguageField(ctx, "")
        sourceField.view.hint = "源语言"
        targetField.view.hint = "目标语言"
        target2Field.view.hint = "第二个目标"

        langRow.addView(sourceField.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        langRow.addView(swapButton { swapSourceTarget() })
        langRow.addView(targetField.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        langRow.addView(swapButton { swapTargets() })
        langRow.addView(target2Field.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(langRow)

        // 译文一：自带复制按钮
        val card1 = outputCard("译文") { copyResult(1) }
        output1 = card1.second
        root.addView(Ui.withMargin(ctx, card1.first, 10, 0))

        // 译文二：填了第二个目标语言才有内容
        val card2 = outputCard("译文（第二个目标语言）") { copyResult(2) }
        output2 = card2.second
        output2.hint = "填了第二个目标语言，这里会同时给出另一份译文"
        root.addView(Ui.withMargin(ctx, card2.first, 10, 0))

        statusText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4), 0)
        }
        root.addView(statusText)

        // 划词时的替换按钮
        replaceButton = Ui.button(ctx, "替换原文", false) { replaceSelection() }
        replaceButton.visibility = if (canReplace) View.VISIBLE else View.GONE
        root.addView(Ui.withMargin(ctx, replaceButton, 10, 0))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    /** 一张译文卡片：标题 + 复制按钮 + 只读译文框 */
    private fun outputCard(title: String, onCopy: () -> Unit): Pair<View, TextView> {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = title
            textSize = 12.5f
            setTextColor(p.text2)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(Ui.button(ctx, "复制", false) { onCopy() })
        wrap.addView(head)

        val box = Ui.resultBox(ctx)
        wrap.addView(box)
        return wrap to box
    }

    /** 「⇄」小按钮 */
    private fun swapButton(onClick: () -> Unit): View {
        val tv = TextView(this).apply {
            text = "⇄"
            textSize = 16f
            setTextColor(p.text2)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
            background = Ui.roundBg(ctx, p.surface, 8, 1, p.line)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Ui.dp(ctx, 5), 0, Ui.dp(ctx, 5), 0)
        tv.layoutParams = lp
        return tv
    }

    /* ── 划词入口 ─────────────────────────────────── */

    private fun handleIncoming(intent: Intent?) {
        val selected = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        // 只有真收到划词内容、且原文允许改写时，才给"替换原文"
        canReplace = selected.isNotBlank() &&
            intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) == false
        updateSubtitle()

        if (selected.isNotBlank()) {
            input.setText(selected)
            input.setSelection(selected.length)

            // 方向自动判断：中文 → 兜底目标语言；外文 → 简体中文
            val detected = Detector.detect(selected)?.language
            if (detected != null) {
                sourceField.setCode(detected)
                targetField.setCode(if (Lang.isChinese(detected)) prefs.defaultTarget else "zh-Hans")
            }
            translate()
        }
    }

    private fun updateSubtitle() {
        subtitle.text = if (canReplace) {
            "选中的文字已带过来，翻译后可以替换回原文。"
        } else {
            "粘贴或输入文字即可翻译。语言框可以直接打字。"
        }
        replaceButton.visibility = if (canReplace) View.VISIBLE else View.GONE
    }

    /* ── 互换 ─────────────────────────────────────── */

    /** 源语言 ⇄ 目标语言：语言和原文/译文内容一起换 */
    private fun swapSourceTarget() {
        val a = sourceField.code
        val b = targetField.code
        sourceField.setCode(b)
        targetField.setCode(a)

        val src = input.text.toString()
        val dst = output1.text.toString()
        if (dst.isNotBlank()) {
            input.setText(dst)
            output1.setText(src)
            lastTranslation = src
        }
    }

    /** 两个目标语言互换：两份译文对调 */
    private fun swapTargets() {
        if (target2Field.code.isBlank()) return
        val a = targetField.code
        val b = target2Field.code
        targetField.setCode(b)
        target2Field.setCode(a)

        val t1 = output1.text.toString()
        val t2 = output2.text.toString()
        output1.setText(t2)
        output2.setText(t1)
        lastTranslation = t2
        lastTranslation2 = t1
    }

    /* ── 翻译 ─────────────────────────────────────── */

    private fun translate() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            statusText.text = "先输入或粘贴一段文字"
            statusText.setTextColor(p.warn)
            return
        }

        val source = sourceField.code.takeIf { it.isNotBlank() && it != "auto" }
        var targets = listOf(targetField.code, target2Field.code).filter { it.isNotBlank() }

        // 源与目标同语言 = 白翻一遍，自动翻到另一边（并在状态里说明）
        var autoNote = ""
        val detected = Detector.detect(text)?.language
        if (detected != null && targets.isNotEmpty() && targets[0] == detected) {
            val flipped = if (Lang.isChinese(detected)) "en" else "zh-Hans"
            targetField.setCode(flipped)
            if (sourceField.code == "auto") sourceField.setCode(detected)
            targets = listOf(flipped) + targets.drop(1)
            autoNote = "（源与目标同语言，已自动改为 ${Lang.name(flipped)}）"
        }
        if (targets.isEmpty()) {
            statusText.text = "先选一个目标语言"
            statusText.setTextColor(p.warn)
            return
        }

        statusText.text = "翻译中…"
        statusText.setTextColor(p.text3)
        output1.text = ""
        output2.text = ""

        Thread {
            // 成功给译文，失败给错误 —— 统一成 Pair，方便两个目标各自处理
            val results: List<Pair<Engine.GenericOutcome?, String?>> = targets.map { target ->
                try {
                    Pair(Engine.translateText(text, source, target, prefs), null)
                } catch (t: Throwable) {
                    Pair(null, t.message ?: "未知错误")
                }
            }
            runOnUiThread {
                val entry1 = results.getOrNull(0)
                val r1 = entry1?.first
                if (r1 == null) {
                    statusText.text = "失败：${entry1?.second ?: "未知错误"}"
                    statusText.setTextColor(p.err)
                    return@runOnUiThread
                }
                lastTranslation = r1.text
                output1.text = r1.text

                val entry2 = results.getOrNull(1)
                if (entry2 != null) {
                    val r2 = entry2.first
                    if (r2 != null) {
                        lastTranslation2 = r2.text
                        output2.text = r2.text
                    } else {
                        output2.text = "（第二个目标语言翻译失败：${entry2.second}）"
                    }
                }

                statusText.text = buildString {
                    append(r1.provider)
                    r1.degradedFrom?.let { append("（已从 $it 降级）") }
                    append(" · ${r1.latencyMs}ms")
                    append(autoNote)
                }
                statusText.setTextColor(p.teal)
                replaceButton.isEnabled = canReplace
            }
        }.start()
    }

    private fun copyResult(which: Int) {
        val text = if (which == 1) lastTranslation else lastTranslation2
        if (text.isBlank()) {
            Toast.makeText(this, "还没有译文", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
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
