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
import com.weifan.app.core.Theme
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
class MainActivity : BaseActivity() {


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
    private lateinit var themeGroup: RadioGroup
    private lateinit var themeNote: TextView
    private lateinit var scheduleSwitch: Switch
    private lateinit var scheduleTimes: LinearLayout
    private lateinit var scheduleStart: EditText
    private lateinit var scheduleEnd: EditText
    private lateinit var scheduleNote: TextView
    private lateinit var scrollView: ScrollView


    /** 服务商下拉里的 id，顺序与 [serviceSpinner] 一致 */
    private var serviceIds = listOf<String>()

    private val p: Palette get() = palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = p.bg
        setContentView(buildUI())
        loadValues()

        // 恢复上次的滚动位置（主题切换重建后不至于弹回顶部）
        val saved = savedInstanceState?.getInt("scrollY") ?: 0
        if (saved > 0) scrollView.post { scrollView.scrollTo(0, saved) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** 主题切换会重建界面，滚动位置要保住 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scrollView.isInitialized) {
            outState.putInt("scrollY", scrollView.scrollY)
        }
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

        // 翻译工具（划词 / 文本 / 文档）
        root.addView(groupCard(
            "翻译工具",
            "划词：在任意应用里选中文字，菜单里选「微翻」。文档：txt / md / csv / docx，本机解析不上传。",
            p.coolGrad,
            expanded = true
        ) {
            addView(ghostButton("翻译文字 / 划词") {
                startActivity(Intent(this@MainActivity, TranslateActivity::class.java))
            })
            addView(ghostButton("翻译文档") {
                startActivity(Intent(this@MainActivity, TranslateDocActivity::class.java))
            })
        })

        // 翻译服务（双向通用）
        root.addView(groupCard(
            "翻译服务",
            "自己去大厂申请密钥填进来，额度用完自动换下一家。自定义 API 接任何 OpenAI 兼容接口。",
            p.mixGrad,
            expanded = true
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

        // 外观 · 昼夜切换
        root.addView(groupCard(
            "外观 · 昼夜切换",
            "昼是浅色，夜是暗色调。跟随系统会跟着手机的深浅色走，也可以自己定夜间时段。",
            p.mixGrad
        ) {
            addView(label("主题"))
            themeGroup = RadioGroup(this@MainActivity).apply { orientation = RadioGroup.HORIZONTAL }
            themeGroup.addView(radioButton("昼", "light"))
            themeGroup.addView(radioButton("夜", "dark"))
            themeGroup.addView(radioButton("跟随系统", "auto"))
            addView(themeGroup)

            themeNote = TextView(this@MainActivity).apply {
                textSize = 11.5f
                setTextColor(p.text3)
                setPadding(0, dp(6), 0, dp(2))
            }
            addView(themeNote)

            // switchRow 已经把整行加进父容器了，这里不要再 addView（会崩）
            scheduleSwitch = switchRow("自定义夜间时段", "到这个点自动切夜间，到第二个点切回来。")

            addView(label("夜间时段"))
            scheduleTimes = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val (startWrap, startInput) = timeField("开始", "22:00")
            val (endWrap, endInput) = timeField("结束", "07:00")
            scheduleStart = startInput
            scheduleEnd = endInput
            scheduleTimes.addView(startWrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            scheduleTimes.addView(endWrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(scheduleTimes)

            scheduleNote = TextView(this@MainActivity).apply {
                textSize = 11.5f
                setTextColor(p.text3)
                setPadding(0, dp(6), 0, dp(2))
            }
            addView(scheduleNote)
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

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        return scrollView
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
    /**
     * 可折叠的分组卡片。
     *
     * 为什么折叠：设置项分组多（翻译服务 / 发件 / 收件 / 额度 / 外观 / 术语 / 试译），
     * 全部展开的话页面长得要一直滑。现在点标题就展开或收起，
     * **默认只展开最该看见的那一组**。
     *
     * @param expanded 默认是否展开
     */
    private fun groupCard(
        title: String,
        note: String,
        dotColors: IntArray,
        expanded: Boolean = false,
        content: LinearLayout.() -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(p.surface, 16, 1, p.line)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.layoutParams = withCardMargin()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(2), 0, dp(2))
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 折叠指示：展开朝下、收起朝上
        val chevron = TextView(this).apply {
            text = "▾"
            textSize = 13f
            setTextColor(p.text3)
            rotation = if (expanded) 0f else 180f
        }
        head.addView(chevron)
        card.addView(head)

        val noteView = TextView(this).apply {
            text = note
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(dp(19), dp(4), dp(4), dp(6))
        }
        card.addView(noteView)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.content()
        card.addView(body)

        // 点标题就展开 / 收起
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        head.setOnClickListener {
            val open = body.visibility == View.VISIBLE
            body.visibility = if (open) View.GONE else View.VISIBLE
            chevron.rotation = if (open) 180f else 0f
        }

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

    /**
     * 时间输入框（24 小时制）：小标签 + 输入框，横向两个时各自占一半宽。
     * 返回「容器 + 输入框」——容器才能塞进横向布局，输入框要单独拿来做读写。
     */
    private fun timeField(caption: String, initial: String): Pair<LinearLayout, EditText> {
        val input = EditText(this).apply {
            hint = initial
            textSize = 13f
            setTextColor(p.text)
            setHintTextColor(p.text3)
            background = roundBg(p.surface2, 10, 1, p.line)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            inputType = InputType.TYPE_CLASS_DATETIME
            setSingleLine(true)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(8), dp(2))
        }
        wrap.addView(TextView(this).apply {
            text = caption
            textSize = 11.5f
            setTextColor(p.text3)
            setPadding(dp(2), 0, 0, dp(4))
        })
        // 注意：竖向 LinearLayout 里 weight 分配的是高度，width=0 会让宽度真的变成 0
        wrap.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return wrap to input
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

        // 外观：主题
        for (i in 0 until themeGroup.childCount) {
            val child = themeGroup.getChildAt(i) as RadioButton
            child.isChecked = child.tag == prefs.themeMode
        }
        scheduleSwitch.isChecked = prefs.themeScheduleEnabled
        scheduleStart.setText(prefs.themeScheduleStart)
        scheduleEnd.setText(prefs.themeScheduleEnd)
        updateThemeUi()

        // 主题改了就立刻生效（不用等按保存）
        themeGroup.setOnCheckedChangeListener { _, _ ->
            updateThemeUi()
            saveTheme()
        }
        scheduleSwitch.setOnCheckedChangeListener { _, _ ->
            updateThemeUi()
            saveTheme()
        }
        for (field in listOf(scheduleStart, scheduleEnd)) {
            field.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    updateThemeUi()
                    saveTheme()
                }
            }
        }
    }

    /** 当前主题模式下各控件的可用状态与说明 */
    private fun updateThemeUi() {
        val mode = currentThemeMode()
        themeNote.text = when (mode) {
            "light" -> "昼：始终用浅色调。"
            "dark" -> "夜：始终用暗色调。"
            else -> "跟随系统：跟手机的深浅色走。"
        }

        // 自定义时段只在「跟随系统」下有意义
        val auto = mode == "auto"
        scheduleSwitch.isEnabled = auto
        scheduleTimes.visibility = if (auto && scheduleSwitch.isChecked) View.VISIBLE else View.GONE
        scheduleNote.text = Theme.scheduleNote(
            scheduleStart.text.toString().trim(),
            scheduleEnd.text.toString().trim()
        )
    }

    private fun currentThemeMode(): String {
        for (i in 0 until themeGroup.childCount) {
            val child = themeGroup.getChildAt(i) as RadioButton
            if (child.isChecked) return child.tag as String
        }
        return "auto"
    }

    /** 只存主题（改主题不用点保存） */
    private fun saveTheme() {
        prefs.themeMode = currentThemeMode()
        prefs.themeScheduleEnabled = scheduleSwitch.isChecked
        prefs.themeScheduleStart = scheduleStart.text.toString().trim().ifBlank { "22:00" }
        prefs.themeScheduleEnd = scheduleEnd.text.toString().trim().ifBlank { "07:00" }
        applyThemeChange()
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
