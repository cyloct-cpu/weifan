package com.weifan.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.core.Engine
import com.weifan.app.core.Lang
import com.weifan.app.core.Providers
import com.weifan.app.core.Routing
import com.weifan.app.service.WeiFanAccessibilityService

/**
 * 设置页。刻意不用 AndroidX / Compose：一个设置页不值得引入整套 UI 框架。
 *
 * 视觉按品牌规范（brand/brand-spec.png）：
 * - 暖 #FD6C4E = 发件（触发词、阈值、自动替换）；冷 #2FB6FF = 收件；
 *   #00E9DD = 双向通用（服务、术语）。界面里唯一的彩色就是「方向」。
 * - 浅色为默认，深色跟随系统（uiMode），两套 token 见 [palette]。
 *
 * 服务商表单由 Providers.DEFS 自动生成 —— 加一家服务商不用改这个文件。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var triggerField: EditText
    private lateinit var thresholdField: EditText
    private lateinit var glossaryField: EditText
    private lateinit var limitField: EditText
    private lateinit var autoReplaceSwitch: Switch
    private lateinit var targetSpinner: Spinner
    private lateinit var serviceSpinner: Spinner
    private lateinit var providerGridView: GridLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var resultText: TextView


    /** 服务商下拉里的 id，顺序与 [serviceSpinner] 一致 */
    private var serviceIds = listOf<String>()

    // MARK: - 品牌色板

    private class Palette(
        val bg: Int,
        val surface: Int,
        val surface2: Int,
        val line: Int,
        val text: Int,
        val text2: Int,
        val text3: Int,
        val warm: Int,
        val cool: Int,
        val teal: Int,
        val ink: Int,
        val warn: Int,
        val err: Int
    ) {
        val warmGrad: IntArray get() = intArrayOf(Color.parseColor("#FF4353"), Color.parseColor("#FF9064"))
        val coolGrad: IntArray get() = intArrayOf(Color.parseColor("#4C9BF7"), Color.parseColor("#00E9DD"))
        val mixGrad: IntArray get() = intArrayOf(Color.parseColor("#FD6C4E"), Color.parseColor("#2FB6FF"))
    }

    private val palette: Palette
        get() {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                Palette(
                    bg = Color.parseColor("#0F1720"),
                    surface = Color.parseColor("#161C24"),
                    surface2 = Color.parseColor("#1D242E"),
                    line = Color.parseColor("#2A333F"),
                    text = Color.parseColor("#E6EAF0"),
                    text2 = Color.parseColor("#A9B4C2"),
                    text3 = Color.parseColor("#8B96A6"),
                    warm = Color.parseColor("#FD6C4E"),
                    cool = Color.parseColor("#2FB6FF"),
                    teal = Color.parseColor("#00E9DD"),
                    ink = Color.parseColor("#1A0E0A"),
                    warn = Color.parseColor("#F1A45C"),
                    err = Color.parseColor("#F15C6D")
                )
            } else {
                Palette(
                    bg = Color.parseColor("#F4F6F8"),
                    surface = Color.WHITE,
                    surface2 = Color.parseColor("#F5F7F9"),
                    line = Color.parseColor("#E7EAEE"),
                    text = Color.parseColor("#161B22"),
                    text2 = Color.parseColor("#55606E"),
                    text3 = Color.parseColor("#8B97A5"),
                    warm = Color.parseColor("#FD6C4E"),
                    cool = Color.parseColor("#2FB6FF"),
                    teal = Color.parseColor("#00E9DD"),
                    ink = Color.parseColor("#1A0E0A"),
                    warn = Color.parseColor("#B06A1F"),
                    err = Color.parseColor("#D63C41")
                )
            }
        }

    private val p: Palette get() = palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = p.bg
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
            setBackgroundColor(p.bg)
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // 品牌行：标识 + 字标
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(14))
        }
        val mark = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val wordmark = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        wordmark.addView(TextView(this).apply {
            text = "微翻"
            textSize = 19f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        wordmark.addView(TextView(this).apply {
            text = "WEIFAN"
            textSize = 9.5f
            setTextColor(p.text3)
            letterSpacing = 0.32f
        })
        brand.addView(mark)
        brand.addView(wordmark)
        root.addView(brand)

        root.addView(hint("在任意输入框打完中文，末尾加触发词即可自动翻译并替换。不会自动发送。"))

        // 状态卡
        statusText = TextView(this).apply {
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val openAccess = withMargin(
            primaryButton("打开无障碍设置（开启微翻服务）") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            dp(12)
        )
        root.addView(card(listOf(statusText, openAccess)))

        // 翻译服务（双向通用）
        root.addView(groupCard(
            "翻译服务",
            "自己去大厂申请密钥填进来，额度用完自动换下一家。自定义 API 接任何 OpenAI 兼容接口。",
            p.mixGrad
        ) {
            // 模式
            addView(label("模式"))
            modeGroup = RadioGroup(this@MainActivity).apply { orientation = RadioGroup.HORIZONTAL }
            modeGroup.addView(radioButton("免费额度", "free"))
            modeGroup.addView(radioButton("自定义 API", "custom"))
            addView(modeGroup)

            // 翻译服务商
            addView(label("翻译服务（失败会自动降级到下一家）"))
            serviceSpinner = spinner(emptyList())
            addView(serviceSpinner)

            // 服务商胶囊：两列排布，点开才是配置表单（竖着排九家太长）
            providerGridView = providerGrid()
            addView(providerGridView)
        })

        // 发件（暖）
        root.addView(groupCard("发件 · 打完中文怎么出去", "触发词命中后自动翻译并原地替换，不发送。", p.warmGrad) {
            addView(label("兜底目标语言（判断不出对方语言时用）"))
            targetSpinner = spinner(Lang.sendTargets.map { "${Lang.name(it)}（$it）" })
            addView(targetSpinner)
            triggerField = field("触发后缀，默认 ?译", secret = false)
            thresholdField = field("长句阈值（字），超过走大模型", secret = false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            addView(triggerField); addView(thresholdField)
            autoReplaceSwitch = switchRow("命中后直接替换输入框", "关掉则只把译文复制到剪贴板。含金额时仍会拦下。")
        })

        // 额度保护（双向通用）
        root.addView(groupCard(
            "额度保护",
            "免费额度有上限，超了平台可能开始计费。设个每日上限，到线当天自动换下一家。",
            p.mixGrad
        ) {
            limitField = field("单个服务商每日字符上限，0 = 不限制", secret = false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            addView(limitField)
        })

        // 术语（双向通用）
        root.addView(groupCard("术语 · 不要乱译的词", "一行一个，命中后原样保留。", p.mixGrad) {
            glossaryField = field("例如：轻舟 Pro", secret = false).apply {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            addView(glossaryField)
        })

        // 保存
        root.addView(withMargin(primaryButton("保存") { save() }, dp(6)))

        // 试译（引擎自检）
        root.addView(groupCard("试译 · 不需要 WhatsApp", "验证密钥与网络是否可用。", p.coolGrad) {
            addView(ghostButton("翻译这句：Hello, when can you ship the order?") { testTranslate() })
            resultText = TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(p.text2)
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(10), 0, 0)
            }
            addView(resultText)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        return scroll
    }

    /* ── 服务商胶囊（两列排布，点开弹配置框） ─────────────── */

    /** 模式切换后重建网格（免费额度 ↔ 自定义 API 显示不同成员） */
    private fun rebuildProviderGrid() {
        if (!::providerGridView.isInitialized) return
        val parent = providerGridView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(providerGridView)
        val fresh = providerGrid()
        parent.removeView(providerGridView)
        parent.addView(fresh, index)
        providerGridView = fresh
    }

    /**
     * 服务商网格：按当前模式显示。
     * 免费额度 → 九家大厂；自定义 API → 自定义接口胶囊 + 添加按钮。
     * （自定义接口是这一组的成员，不再另开一个同名分组）
     */
    private fun providerGrid(): GridLayout {
        val grid = GridLayout(this).apply { columnCount = 2 }
        if (currentMode() == "custom") {
            grid.addView(customCapsule())
            grid.addView(addCustomButton())
        } else {
            for (def in Providers.DEFS) {
                grid.addView(providerCapsule(def))
            }
        }
        return grid
    }

    private fun providerCapsule(def: Providers.Def): View {
        val configured = prefs.isConfigured(def.id)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(p.surface2, 20, 1, if (configured) p.warm else p.line)
            setPadding(dp(13), dp(11), dp(13), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { openProviderDialog(def) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = def.label
            textSize = 13f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (configured) p.warm else p.line)
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        })
        box.addView(top)

        box.addView(TextView(this).apply {
            text = def.freeNote
            textSize = 10.5f
            setTextColor(p.text3)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        box.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        return box
    }

    /** 自定义接口的胶囊（目前一个档案） */
    private fun customCapsule(): View {
        val configured = prefs.customKey.isNotBlank() && prefs.customBaseUrl.isNotBlank()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(p.surface2, 20, 1, if (configured) p.warm else p.line)
            setPadding(dp(13), dp(11), dp(13), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { openCustomDialog() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = prefs.customName.ifBlank { "自定义接口" }
            textSize = 13f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (configured) p.warm else p.line)
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
        })
        box.addView(top)
        box.addView(TextView(this).apply {
            text = if (configured) prefs.customBaseUrl else "点开填写 key + 接口地址"
            textSize = 10.5f
            setTextColor(p.text3)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        box.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        return box
    }

    /** 「+ 添加接口」按钮 */
    private fun addCustomButton(): View {
        val add = Button(this).apply {
            text = "+ 添加接口"
            textSize = 12f
            setTextColor(p.text2)
            background = roundBg(p.surface, 20, 1, p.line)
            isAllCaps = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { openCustomDialog() }
        }
        add.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        return add
    }

    /** 弹窗里的一个字段：独立标签 + 输入框（hint 只当占位提示） */
    private fun dialogField(
        labelText: String,
        hintText: String,
        value: String,
        secret: Boolean
    ): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = labelText
            textSize = 11.5f
            setTextColor(p.text3)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        val input = EditText(this).apply {
            hint = hintText
            textSize = 13.5f
            setTextColor(p.text)
            setHintTextColor(p.text3)
            setSingleLine(true)
            background = roundBg(p.surface2, 10, 1, p.line)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setText(value)
        }
        wrap.addView(input)
        wrap.tag = input
        return wrap
    }

    /** 某家服务商的配置弹窗 */
    private fun openProviderDialog(def: Providers.Def) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(4))
        }
        body.addView(TextView(this).apply {
            text = def.label
            textSize = 16f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = def.freeNote
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(0, dp(4), 0, 0)
        })

        var endpointInput: EditText? = null
        if (def.endpointEditable) {
            val wrap = dialogField(
                if (def.id == "deepl") "端点（付费版改成 https://api.deepl.com/v2/translate）"
                else "终结点（区域资源填自己的子域名）",
                def.endpoint,
                def.endpoint,
                false
            )
            endpointInput = wrap.tag as EditText
            body.addView(wrap)
        } else {
            body.addView(TextView(this).apply {
                text = "接口 ${def.endpoint}"
                textSize = 11f
                setTextColor(p.text3)
                setPadding(0, dp(12), 0, 0)
            })
        }

        val inputs = mutableMapOf<String, EditText>()
        val saved = prefs.credential(def.id)
        for (f in def.fields) {
            val wrap = dialogField(
                f.label + (if (f.optional) "（选填）" else ""),
                f.placeholder,
                saved[f.id] ?: "",
                f.secret
            )
            inputs[f.id] = wrap.tag as EditText
            body.addView(wrap)
        }

        val status = TextView(this).apply {
            visibility = View.GONE
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(status)

        val dialog = AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试这一家", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveProviderValues(def, inputs, endpointInput)
                dialog.dismiss()
                refreshStatus()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            // 中性按钮不关闭弹窗，好让用户看到测试结果
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                saveProviderValues(def, inputs, endpointInput)
                status.visibility = View.VISIBLE
                status.setTextColor(p.text3)
                status.text = "测试中…"
                Thread {
                    val outcome = try {
                        val result = Engine.testProvider(def.id, prefs)
                        "✓ ${result.text}（${result.provider} · ${result.latencyMs}ms）"
                    } catch (t: Throwable) {
                        "✗ ${t.message ?: "未知错误"}"
                    }
                    val ok = outcome.startsWith("✓")
                    runOnUiThread {
                        status.text = outcome
                        status.setTextColor(if (ok) p.teal else p.err)
                    }
                }.start()
            }
        }
        dialog.show()
    }

    /** 自定义接口的配置弹窗 */
    private fun openCustomDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(4))
        }
        body.addView(TextView(this).apply {
            text = "自定义接口"
            textSize = 16f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = "任意 OpenAI 兼容接口：DeepSeek / 硅基流动 / 自建网关"
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(0, dp(4), 0, 0)
        })

        val nameWrap = dialogField("名称", "DeepSeek", prefs.customName, false)
        val baseWrap = dialogField("接口地址", "https://api.deepseek.com/v1", prefs.customBaseUrl, false)
        val keyWrap = dialogField("API Key", "sk-…", prefs.customKey, true)
        val modelWrap = dialogField("模型名", "deepseek-chat", prefs.customModel, false)
        val nameField = nameWrap.tag as EditText
        val baseField = baseWrap.tag as EditText
        val keyField = keyWrap.tag as EditText
        val modelField = modelWrap.tag as EditText
        body.addView(nameWrap); body.addView(baseWrap); body.addView(keyWrap); body.addView(modelWrap)

        val status = TextView(this).apply {
            visibility = View.GONE
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(status)

        val dialog = AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试这个接口", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveCustomValues(nameField, baseField, keyField, modelField)
                dialog.dismiss()
                refreshStatus()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                saveCustomValues(nameField, baseField, keyField, modelField)
                status.visibility = View.VISIBLE
                status.setTextColor(p.text3)
                status.text = "测试中…"
                Thread {
                    val outcome = try {
                        val result = Engine.testProvider("custom:custom", prefs)
                        "✓ ${result.text}（${result.provider} · ${result.latencyMs}ms）"
                    } catch (t: Throwable) {
                        "✗ ${t.message ?: "未知错误"}"
                    }
                    val ok = outcome.startsWith("✓")
                    runOnUiThread {
                        status.text = outcome
                        status.setTextColor(if (ok) p.teal else p.err)
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun saveProviderValues(
        def: Providers.Def,
        inputs: Map<String, EditText>,
        endpointInput: EditText?
    ) {
        for ((fieldId, input) in inputs) {
            prefs.setCredential(def.id, fieldId, input.text.toString().trim())
        }
        endpointInput?.let { prefs.setCredential(def.id, "endpoint", it.text.toString().trim()) }
    }

    private fun saveCustomValues(
        nameField: EditText,
        baseField: EditText,
        keyField: EditText,
        modelField: EditText
    ) {
        prefs.customName = nameField.text.toString().trim()
        prefs.customBaseUrl = baseField.text.toString().trim().ifBlank { "https://api.deepseek.com/v1" }
        prefs.customKey = keyField.text.toString().trim()
        prefs.customModel = modelField.text.toString().trim().ifBlank { "deepseek-chat" }
    }

    // MARK: - 组件工厂

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundBg(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun withMargin(view: View, top: Int, bottom: Int = 0): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        lp.bottomMargin = dp(bottom)
        view.layoutParams = lp
        return view
    }

    /** 分组卡片：标题左侧一个方向色点 */
    private fun groupCard(title: String, note: String, dotColors: IntArray, content: LinearLayout.() -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(p.surface, 16, 1, p.line)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.layoutParams = withCardMargin()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = dotColors
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        }
        head.addView(dot)
        head.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        })
        card.addView(head)
        card.addView(TextView(this).apply {
            text = note
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(dp(19), dp(4), 0, dp(6))
        })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.content()
        card.addView(body)
        return card
    }

    private fun withCardMargin(): ViewGroup.MarginLayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        return lp
    }

    private fun card(children: List<View>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(p.surface, 16, 1, p.line)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.layoutParams = withCardMargin()
        children.forEach { card.addView(it) }
        return card
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(p.text2)
        setPadding(dp(4), dp(2), dp(4), dp(4))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(p.text2)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(6))
    }

    /** 卡片内的小标题 */
    private fun subLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(p.text3)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun field(hintText: String, secret: Boolean): EditText {
        val edit = EditText(this)
        edit.hint = hintText
        edit.textSize = 13.5f
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.text3)
        edit.setSingleLine(true)
        edit.background = roundBg(p.surface, 10, 1, p.line)
        edit.setPadding(dp(12), dp(11), dp(12), dp(11))
        if (secret) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return withMargin(edit, dp(8), dp(8)) as EditText
    }

    private fun radioButton(text: String, value: String): RadioButton {
        val rb = RadioButton(this)
        rb.text = text
        rb.textSize = 13f
        rb.setTextColor(p.text2)
        rb.tag = value
        rb.id = View.generateViewId()
        return rb
    }

    private fun spinner(items: List<String>): Spinner {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.background = roundBg(p.surface2, 10, 1, p.line)
        spinner.setPadding(dp(10), dp(6), dp(10), dp(6))
        return withMargin(spinner, dp(4), dp(4)) as Spinner
    }

    /** 开关行：左侧标题+说明，右侧 Switch。整行加进父容器，返回 Switch 供读写状态。 */
    private fun LinearLayout.switchRow(title: String, note: String): Switch {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            setTextColor(p.text)
        })
        texts.addView(TextView(this@MainActivity).apply {
            text = note
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(0, dp(2), 0, 0)
        })
        val sw = Switch(this@MainActivity).apply {
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList.valueOf(p.warm)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        addView(row)
        return sw
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13.5f
        b.setTextColor(p.ink)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.background = roundBg(p.warm, 10)
        b.setPadding(dp(16), dp(4), dp(16), dp(4))
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun ghostButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.setTextColor(p.text2)
        b.background = roundBg(p.surface2, 10, 1, p.line)
        b.setPadding(dp(14), dp(4), dp(14), dp(4))
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return withMargin(b, dp(8)) as Button
    }

    // MARK: - 数据

    private fun loadValues() {
        triggerField.setText(prefs.trigger)
        thresholdField.setText(prefs.longSentenceThreshold.toString())
        limitField.setText(prefs.dailyCharLimit.toString())
        glossaryField.setText(prefs.glossary)
        autoReplaceSwitch.isChecked = prefs.autoReplace

        val index = Lang.sendTargets.indexOf(prefs.defaultTarget)
        if (index >= 0) targetSpinner.setSelection(index)

        // 模式
        for (i in 0 until modeGroup.childCount) {
            val child = modeGroup.getChildAt(i) as RadioButton
            child.isChecked = child.tag == prefs.mode
        }
        modeGroup.setOnCheckedChangeListener { _, _ ->
            refreshServiceSpinner()
            rebuildProviderGrid()
        }
        refreshServiceSpinner()
    }

    /** 服务商下拉：按当前模式列出已配置的服务商，第一项永远是「自动」 */
    private fun refreshServiceSpinner() {
        val mode = currentMode()
        val items = mutableListOf("自动（按语种优选）")
        val ids = mutableListOf("auto")

        if (mode == "custom") {
            if (prefs.customProfile().isComplete) {
                items.add(prefs.customProfile().displayName)
                ids.add("custom:custom")
            }
        } else {
            for (def in Providers.DEFS) {
                if (prefs.isConfigured(def.id)) {
                    items.add(def.label)
                    ids.add(def.id)
                }
            }
        }

        serviceIds = ids
        serviceSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        val saved = prefs.selection
        val position = ids.indexOf(saved)
        if (position >= 0) serviceSpinner.setSelection(position)
    }

    private fun currentMode(): String {
        for (i in 0 until modeGroup.childCount) {
            val child = modeGroup.getChildAt(i) as RadioButton
            if (child.isChecked) return child.tag as String
        }
        return "free"
    }

    private fun save() {

        prefs.mode = currentMode()
        prefs.selection = serviceIds.getOrNull(serviceSpinner.selectedItemPosition) ?: "auto"
        prefs.trigger = triggerField.text.toString().ifBlank { "?译" }
        prefs.longSentenceThreshold = thresholdField.text.toString().toIntOrNull() ?: 60
        prefs.dailyCharLimit = limitField.text.toString().toIntOrNull() ?: 0
        prefs.glossary = glossaryField.text.toString()
        prefs.autoReplace = autoReplaceSwitch.isChecked
        prefs.defaultTarget = Lang.sendTargets.getOrNull(targetSpinner.selectedItemPosition) ?: "en"

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        refreshStatus()
        refreshServiceSpinner()
    }

    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        val hasKey = prefs.hasAnyKey()
        val ok = enabled && hasKey
        statusText.text = buildString {
            append(if (enabled) "✅ 无障碍服务已开启" else "⚠️ 无障碍服务未开启")
            append("　")
            append(if (hasKey) "✅ 已配置密钥" else "⚠️ 还没填翻译密钥")
            append("\n触发词：${prefs.trigger}　兜底语言：${Lang.name(prefs.defaultTarget)}")
            append("\n已配置服务商：${prefs.configuredProviders().size} 家")
        }
        statusText.setTextColor(if (ok) p.teal else p.err)
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
                buildString {
                    appendLine("原文：$sample")
                    appendLine("译文：${incoming.translated}")
                    appendLine("语言：${incoming.sourceLanguage ?: "未知"} → 简体中文")
                    appendLine("引擎：${incoming.provider}（${route.route}）· ${incoming.latencyMs}ms")
                    incoming.degradedFrom?.let { appendLine("（已从 $it 自动降级）") }
                }.trimIndent()
            } catch (t: Throwable) {
                "失败：${t.message ?: "未知错误"}\n先检查密钥与网络。"
            }
            runOnUiThread { resultText.text = output }
        }.start()
    }
}
