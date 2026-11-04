package com.weifan.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mobile.PairListener
import mobile.WeifanClient
import java.util.Locale

/**
 * 关联账号页 —— 严格按参考客户端 activity_login（res/1b.xml）1:1 重排。
 *
 * 布局骨架（与对方一致）：
 *   FrameLayout(login_page_bg) → ScrollView → LinearLayout(paddingH 24 / top 18 / bottom 24)
 *     ① logoutReasonBanner（14sp #C2410C，默认隐藏）
 *     ② stepIndicatorContainer：backArrow 44dp + ConstraintLayout（3dp 连线 + 14dp 圆点 + 12sp 标签）
 *     ③ getCodePanel：标题 28sp（top 26）→ 输入行 56dp（top 28，国家码 120dp + 间距 12dp）
 *        → 按钮 52dp 圆角 29dp（top 30）→ 二维码登录 15sp（top 20）
 *     ④ enterCodePanel（隐藏）：标题 → 副标题 14sp → 编辑 15sp → 验证码卡（padding 20dp）
 *        → 重新获取 15sp → 引导标题 16sp → 三条步骤 14sp（行距 3dp）→ 二维码登录 15sp
 *     ⑤ qrLinkPanel（隐藏）：标题 → 说明 14sp → 二维码框 → 状态 14sp → 手机号登录 15sp
 *     ⑥ statusText 14sp #9CA3AF（top 16）→ progressBar
 *   右下角 include_login_support_button：白底胶囊，marginBottom 24 / marginEnd 20
 *
 * 取值：login_title 28sp / login_input 17sp / login_button 18sp / login_link 15sp /
 *      login_subtitle·status 14sp / login_step_label 12sp / login_pair_code 40sp /
 *      login_accent #4A4AF4（夜 #6B7BF7）/ login_step_inactive #D9DCE3 /
 *      login_text_title #101828 / login_text_body #202431 / login_text_muted #6B7280 /
 *      login_text_status #9CA3AF / login_link #00B087 / login_input_row_height 56dp /
 *      login_country_code_width 120dp / cornerRadius 29dp
 */
class PairingActivity : BaseActivity(), PairListener {

    private lateinit var client: WeifanClient

    // 三个面板
    private lateinit var getCodePanel: LinearLayout
    private lateinit var enterCodePanel: LinearLayout
    private lateinit var qrPanel: LinearLayout

    private lateinit var stepDots: List<View>
    private lateinit var stepLines: List<View>
    private lateinit var stepLabels: List<TextView>

    private lateinit var countryInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var nextBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    private lateinit var pairCodeText: TextView
    private lateinit var codeCopyHint: TextView
    private lateinit var linkingText: TextView
    private lateinit var qrStatus: TextView
    private lateinit var qrImageBox: FrameLayout
    private lateinit var qrProgress: ProgressBar
    /** 二维码只发起一次（重复发起会拿到新的 QR 通道，旧的作废） */
    private var qrRequested = false

    private val main = Handler(Looper.getMainLooper())
    private var phone = ""
    private var step = 0

    private val accent get() = WaSpec.primary(night)
    private val textTitle get() = if (night) Color.parseColor("#E8EAED") else Color.parseColor("#101828")
    private val textBody get() = if (night) Color.parseColor("#E8EAED") else Color.parseColor("#202431")
    private val textMuted get() = if (night) Color.parseColor("#AEAEB2") else Color.parseColor("#6B7280")
    private val textStatus get() = if (night) Color.parseColor("#8E8E93") else Color.parseColor("#9CA3AF")
    private val linkColor get() = if (night) Color.parseColor("#34D399") else Color.parseColor("#00B087")
    private val stepInactive get() = if (night) Color.parseColor("#48484A") else Color.parseColor("#D9DCE3")
    private val stepLabelInactive get() = if (night) Color.parseColor("#8E8E93") else Color.parseColor("#ADB3BF")
    private val pageBg get() = if (night) Color.parseColor("#1C1C1E") else Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frame = FrameLayout(this).apply { setBackgroundColor(pageBg) }
        val root = ScrollView(this).apply { setBackgroundColor(pageBg) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        root.addView(box)
        frame.addView(root)

        // ── ② 步骤条：backArrow 44dp + 轨道区
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(textTitle)
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))

        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        // 连线 + 圆点：圆点居中于三个标签格，连线填满相邻圆点之间的空隙（对齐 stepLine1/2 + stepDot1/2/3）
        val trackBox = FrameLayout(this)
        stepLines = listOf(0, 1).map { _ ->
            View(this).apply { setBackgroundColor(stepInactive) }.also {
                trackBox.addView(it, FrameLayout.LayoutParams(dp(14), dp(3)).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                })
            }
        }
        stepDots = listOf(0, 1, 2).map { _ ->
            View(this).apply {
                background = Ui.roundBg(this@PairingActivity, stepInactive, 7)
            }.also {
                trackBox.addView(it, FrameLayout.LayoutParams(dp(14), dp(14)).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                })
            }
        }
        stepper.addView(trackBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        stepLabels = listOf("获取代码", "输入代码", "完成!").map { t ->
            TextView(this).apply {
                text = t
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(stepLabelInactive)
            }.also {
                labelRow.addView(it, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(6) })
            }
        }
        stepper.addView(labelRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stepRow.addView(stepper, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(16)
        })
        stepRow.addView(View(this), LinearLayout.LayoutParams(dp(44), 1))
        box.addView(stepRow)

        // ── ③ 获取代码面板
        getCodePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        getCodePanel.addView(TextView(this).apply {
            text = "输入 WhatsApp 手机号"
            textSize = 28f
            setTextColor(textTitle)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(26)
        })

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        countryInput = EditText(this).apply {
            setText("+86")
            textSize = 17f
            setTextColor(textBody)
            setBackgroundResource(0)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        inputRow.addView(roundField(countryInput), LinearLayout.LayoutParams(dp(120),
            ViewGroup.LayoutParams.MATCH_PARENT))
        phoneInput = EditText(this).apply {
            hint = "请输入手机号码"
            textSize = 17f
            setTextColor(textBody)
            setHintTextColor(textStatus)
            setBackgroundResource(0)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        inputRow.addView(roundField(phoneInput), LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
        getCodePanel.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(28) })

        nextBtn = pillButton("下一步") { onRequestCode() }
        getCodePanel.addView(nextBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(30) })

        getCodePanel.addView(link("二维码登录") { showPanel(2) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            })
        box.addView(getCodePanel)

        // ── ④ 输入验证码面板（默认隐藏）
        enterCodePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        enterCodePanel.addView(TextView(this).apply {
            text = "您的 WhatsApp 验证码"
            textSize = 28f
            setTextColor(textTitle)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })
        linkingText = TextView(this).apply {
            text = "正在关联 $phone"
            textSize = 14f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
        }
        enterCodePanel.addView(linkingText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        enterCodePanel.addView(link("编辑", linkColor) { showPanel(0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            })

        val codeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.roundBg(this@PairingActivity, fieldBg, 16)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        pairCodeText = TextView(this).apply {
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        codeCard.addView(pairCodeText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        codeCopyHint = TextView(this).apply {
            text = "在 WhatsApp 中粘贴后，需立即返回这里"
            textSize = 16f
            setTextColor(textBody)
            gravity = Gravity.CENTER
        }
        codeCard.addView(codeCopyHint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        enterCodePanel.addView(codeCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        enterCodePanel.addView(link("重新获取验证码", accent) { onRequestCode() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
            })

        enterCodePanel.addView(TextView(this).apply {
            text = "立即前往 WhatsApp 中粘贴验证码"
            textSize = 16f
            setTextColor(textBody)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })

        enterCodePanel.addView(TextView(this).apply {
            text = "1. 打开 WhatsApp，点右上角「⋮」→「已关联的设备」→「关联新设备」→「改用电话号码关联」。\n" +
                "2. 立即粘贴或输入上面的验证码。\n" +
                "3. 看到「配对成功」后返回本应用即可。"
            textSize = 14f
            setTextColor(textMuted)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        enterCodePanel.addView(link("二维码登录") { showPanel(2) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            })
        box.addView(enterCodePanel)

        // ── ⑤ 二维码面板（默认隐藏）
        qrPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        qrPanel.addView(TextView(this).apply {
            text = "关联 WhatsApp"
            textSize = 28f
            setTextColor(textTitle)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(26)
        })
        qrPanel.addView(TextView(this).apply {
            text = "用手机 WhatsApp 扫描下方二维码即可关联"
            textSize = 14f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        qrImageBox = FrameLayout(this).apply {
            background = Ui.roundBg(this@PairingActivity, fieldBg, 16)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        qrProgress = ProgressBar(this).apply { visibility = View.GONE }
        qrImageBox.addView(qrProgress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        qrPanel.addView(qrImageBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })

        qrStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(textStatus)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        qrPanel.addView(qrStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        qrPanel.addView(link("重新获取二维码", accent) {
            qrRequested = false
            requestQR()
        },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
            })
        qrPanel.addView(link("手机号登录") { showPanel(0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(20)
            })
        box.addView(qrPanel)

        // ── ⑥ 状态文字 14sp + 进度条
        statusText = TextView(this).apply {
            text = "请输入手机号码，点击「下一步」获取验证码"
            textSize = 14f
            setTextColor(textStatus)
            gravity = Gravity.CENTER
        }
        box.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        progress = ProgressBar(this).apply { visibility = View.GONE }
        box.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })


        setContentView(frame)

        val app = application as WeiFanApplication
        client = app.protocol()
        client.setPairListener(this)
        setStep(0)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private val fieldBg get() = if (night) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7")

    /** 灰色胶囊输入框（bg_login_input_round）：圆角 29dp、无描边 */
    private fun roundField(field: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.roundBg(this@PairingActivity, fieldBg, 29)
        addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 主按钮：52dp 高、圆角 29dp、18sp 白字 */
    private fun pillButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = Ui.roundBg(this@PairingActivity, accent, 29)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 15sp 文字链接（login_link）*/
    private fun link(text: String, color: Int = textMuted, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(color)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 面板切换（0=手机号 / 1=验证码 / 2=二维码）*/
    private fun showPanel(which: Int) {
        getCodePanel.visibility = if (which == 0) View.VISIBLE else View.GONE
        enterCodePanel.visibility = if (which == 1) View.VISIBLE else View.GONE
        qrPanel.visibility = if (which == 2) View.VISIBLE else View.GONE
        // 第一次进入二维码面板就发起配对：不发起的话二维码永远是空的（之前就是这个 bug）
        if (which == 2 && !qrRequested) {
            qrRequested = true
            requestQR()
        }
    }

    /** 发起二维码配对。二维码约 20 秒刷新一次，通过 onQRImage 推图过来 */
    private fun requestQR() {
        qrProgress.visibility = View.VISIBLE
        qrStatus.text = "正在获取二维码…"
        qrStatus.setTextColor(textStatus)
        Thread {
            val err = try { client.pairWithQR(); null } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            if (err != null) {
                main.post {
                    qrProgress.visibility = View.GONE
                    qrStatus.text = "获取二维码失败：$err"
                    qrStatus.setTextColor(WaSpec.LOGOUT_BANNER_TEXT)
                    qrRequested = false
                }
            }
        }.start()
    }

    /** 步骤条：圆点与连线的颜色/宽度随当前步变化（对齐 stepLine1/2 + stepDot1/2/3）*/
    private fun setStep(current: Int) {
        step = current
        val count = 3
        stepDots.forEachIndexed { i, dot ->
            dot.background = Ui.roundBg(this, if (i <= current) accent else stepInactive, 7)
        }
        stepLabels.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == current) textBody else stepLabelInactive)
            tv.typeface = Typeface.create(
                if (i == current) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        // 连线：stepLine1 = 第一段（到当前步），stepLine2 = 第二段
        (stepDots.first().parent as? ViewGroup)?.post {
            val track = stepDots.first().parent as? ViewGroup ?: return@post
            val w = track.width
            if (w <= 0) return@post
            val cell = w / 3
            val dot = dp(14)
            // 圆点 x = 每个格子的中心
            stepDots.forEachIndexed { i, d ->
                val lp = d.layoutParams as FrameLayout.LayoutParams
                lp.width = dot
                lp.height = dot
                lp.leftMargin = (cell * (i + 0.5f)).toInt() - dot / 2
                d.layoutParams = lp
            }
            // 连线：从圆点 i 的右缘到圆点 i+1 的左缘
            stepLines.forEachIndexed { i, line ->
                val lp = line.layoutParams as FrameLayout.LayoutParams
                val start = (cell * (i + 0.5f)).toInt() + dot / 2
                val end = (cell * (i + 1.5f)).toInt() - dot / 2
                lp.width = maxOf(end - start, 0)
                lp.leftMargin = start
                line.layoutParams = lp
                line.setBackgroundColor(if (i < current) accent else stepInactive)
            }
        }
    }

    private fun onRequestCode() {
        val raw = phoneInput.text.toString().trim()
        val country = countryInput.text.toString().trim()
        val normalized = normalizePhone(country, raw)
        if (normalized == null) {
            Toast.makeText(this, "请输入有效手机号（含国家区号，如 +86…）", Toast.LENGTH_LONG).show()
            return
        }
        phone = normalized
        progress.visibility = View.VISIBLE
        nextBtn.isEnabled = false
        statusText.setTextColor(textStatus)
        statusText.text = "正在检查号码…"
        Thread {
            // 先校验该号码是否注册过 WhatsApp（P1-1）
            var notOnWhatsApp = false
            var bizName = ""
            try {
                val arr = org.json.JSONArray(client.isOnWhatsApp("[\"$normalized\"]"))
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    notOnWhatsApp = !o.optBoolean("onWhatsApp")
                    bizName = o.optString("businessName")
                }
            } catch (_: Throwable) { }
            if (notOnWhatsApp) {
                main.post {
                    progress.visibility = View.GONE
                    nextBtn.isEnabled = true
                    statusText.text = "该号码未注册 WhatsApp，请确认后重试"
                    statusText.setTextColor(WaSpec.LOGOUT_BANNER_TEXT)
                }
                return@Thread
            }
            val bizHint = if (bizName.isNotBlank()) "（认证企业：$bizName）" else ""
            main.post { statusText.text = "正在连接 WhatsApp 服务器…$bizHint" }
            val err = try {
                client.pairWithPhone(normalized)
                null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            if (err != null) {
                main.post {
                    progress.visibility = View.GONE
                    nextBtn.isEnabled = true
                    statusText.text = "发起配对失败：$err"
                    statusText.setTextColor(WaSpec.LOGOUT_BANNER_TEXT)
                }
            }
        }.start()
    }

    // PairListener 回调（whatsmeow goroutine 上触发，必须切主线程改 UI）
    override fun onPairCode(code: String) = main.post {
        progress.visibility = View.GONE
        nextBtn.isEnabled = true
        pairCodeText.text = formatCode(code)
        // 自动复制到剪贴板：用户直接去 WhatsApp「关联设备」里长按粘贴，省一个手抄步骤
        copyToClipboard(code)
        linkingText.text = "正在关联 $phone"
        setStep(1)
        showPanel(1)
        statusText.text = "验证码约 10 秒内有效，过期点「重新获取验证码」"
    }.let {}

    /** 二维码原始字符串（含配对秘钥，不复制到剪贴板，避免误发） */
    override fun onQRCode(code: String) = main.post {
        progress.visibility = View.GONE
        qrStatus.text = "用手机 WhatsApp 扫描上方二维码"
        qrStatus.setTextColor(textStatus)
    }.let {}

    /** 渲染好的二维码 PNG → 直接贴到 qrImageBox */
    override fun onQRImage(png: ByteArray?) {
        val data = png ?: return
        if (data.isEmpty()) return
        main.post {
        try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bmp != null) {
                qrImageBox.removeAllViews()
                qrImageBox.addView(android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bmp)
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                qrProgress.visibility = View.GONE
            }
        } catch (e: Throwable) {
            qrStatus.text = "二维码显示失败：${e.message}"
            qrStatus.setTextColor(WaSpec.LOGOUT_BANNER_TEXT)
        }
        }
    }

    /** 复制到剪贴板并提示（Android 13+ 系统会自带复制提示，这里只在低版本弹 Toast） */
    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("weifan-paircode", text))
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "验证码已复制，去 WhatsApp 长按粘贴", Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) { }
    }

    override fun onPairSuccess(jid: String?) = main.post {
        setStep(2)
        Toast.makeText(this, "配对成功：$jid", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }.let {}

    override fun onPairError(message: String?) = main.post {
        progress.visibility = View.GONE
        nextBtn.isEnabled = true
        statusText.text = "配对失败：${message ?: "未知错误"}"
        statusText.setTextColor(WaSpec.LOGOUT_BANNER_TEXT)
    }.let {}

    /** 国家码 + 号码拼成 E.164；号码里带 + 时以号码自身为准 */
    private fun normalizePhone(country: String, raw: String): String? {
        var n = raw.replace(Regex("[\\s\\-()]"), "")
        if (n.isEmpty()) return null
        if (n.startsWith("+")) {
            val digits = n.substring(1)
            return if (digits.length in 8..15 && digits.all { it.isDigit() }) n else null
        }
        var cc = country.replace(Regex("[\\s\\-()]"), "")
        if (cc.startsWith("00")) cc = "+${cc.substring(2)}"
        if (!cc.startsWith("+")) cc = "+$cc"
        if (!cc.substring(1).all { it.isDigit() }) return null
        if (n.startsWith("0")) n = n.substring(1)
        val full = cc + n
        return if (n.length in 6..14) full else null
    }

    private fun formatCode(code: String): String {
        val c = code.replace("-", "").uppercase(Locale.US)
        return if (c.length == 8) "${c.substring(0, 4)}-${c.substring(4)}" else code
    }
}
