package com.weifan.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import mobile.PairListener
import mobile.WeifanClient
import java.util.Locale

/**
 * 配对页：输入 WhatsApp 号码 → 拿到 8 位配对码 → 用户在官方 App 里输入 → 配对完成。
 * 流程状态机：输入号码 → 请求配对码（转圈）→ 显示配对码（大字 + 三步引导）→ 等待结果 → 成功回主页。
 */
class PairingActivity : Activity(), PairListener {

    private lateinit var client: WeifanClient
    private val p by lazy { Brand.palette(false) }

    private lateinit var phoneInput: EditText
    private lateinit var requestBtn: Button
    private lateinit var statusText: TextView
    private lateinit var codeText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var hintLayout: LinearLayout

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 24)

        val root = ScrollView(this).apply { setBackgroundColor(p.bg) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3 / 2, pad, pad)
        }
        root.addView(box)

        box.addView(Ui.label(this, "关联 WhatsApp 账号", p.text, 20f, bold = true))
        box.addView(Ui.withMargin(this,
            Ui.label(this, "微翻会作为你账号的一个「关联设备」登录，与 WhatsApp 网页版同机制。", p.text2, 13f),
            top = 6, bottom = 24))

        box.addView(Ui.label(this, "第一步 · 输入你的 WhatsApp 手机号（含国家区号）", p.text2, 13f, bold = true))
        phoneInput = Ui.input(this, "+86 138 0000 0000")
        box.addView(Ui.withMargin(this, phoneInput, top = 8, bottom = 12))

        requestBtn = Ui.button(this, "获取配对码", primary = true) { onRequestCode() }
        box.addView(requestBtn)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        box.addView(Ui.withMargin(this, progress, top = 16).apply { visibility = View.GONE })

        box.addView(Ui.withMargin(this,
            Ui.label(this, "第二步 · 在官方 WhatsApp App 里输入这个配对码", p.text2, 13f, bold = true), top = 24))
        statusText = TextView(this).apply {
            visibility = View.GONE
            setTextColor(p.text2)
            textSize = 13.5f
        }
        box.addView(Ui.withMargin(this, statusText, top = 6))
        codeText = TextView(this).apply {
            visibility = View.GONE
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.warm)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        box.addView(Ui.withMargin(this, codeText, top = 16, bottom = 8).apply { visibility = View.GONE })

        hintLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(Ui.label(this@PairingActivity, "① 打开手机上的 WhatsApp → 设置 → 已关联的设备", p.text2, 13f))
            addView(Ui.withMargin(this@PairingActivity,
                Ui.label(this@PairingActivity, "② 点「关联设备」→ 选「改用手机号关联」", p.text2, 13f), top = 4))
            addView(Ui.withMargin(this@PairingActivity,
                Ui.label(this@PairingActivity, "③ 输入上面的 8 位码，等待完成", p.text2, 13f), top = 4))
        }
        box.addView(hintLayout)

        setContentView(root)

        val app = application as WeiFanApplication
        client = app.protocol()
        client.setPairListener(this)
    }

    private fun onRequestCode() {
        val raw = phoneInput.text.toString().trim()
        val phone = normalizePhone(raw)
        if (phone == null) {
            Toast.makeText(this, "请输入有效手机号（必须含国家区号，如 +86…）", Toast.LENGTH_LONG).show()
            return
        }
        progress.visibility = View.VISIBLE
        requestBtn.isEnabled = false
        statusText.visibility = View.VISIBLE
        statusText.setTextColor(p.text2)
        statusText.text = "正在连接 WhatsApp 服务器…"
        Thread {
            val err = try {
                client.pairWithPhone(phone)
                null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            if (err != null) {
                main.post {
                    progress.visibility = View.GONE
                    requestBtn.isEnabled = true
                    statusText.text = "发起配对失败：$err"
                    statusText.setTextColor(p.err)
                }
            }
        }.start()
    }

    // PairListener 回调（whatsmeow goroutine 上触发，必须切主线程改 UI）
    override fun onPairCode(code: String) = main.post {
        progress.visibility = View.GONE
        codeText.visibility = View.VISIBLE
        codeText.text = formatCode(code)
        hintLayout.visibility = View.VISIBLE
        statusText.text = "拿到配对码！请在官方 App 里输入（约 10 秒内有效，过期重新获取）"
    }.let {}

    override fun onPairSuccess(jid: String?) = main.post {
        Toast.makeText(this, "配对成功：$jid", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }.let {}

    override fun onPairError(message: String?) = main.post {
        progress.visibility = View.GONE
        requestBtn.isEnabled = true
        statusText.text = "配对失败：${message ?: "未知错误"}"
        statusText.setTextColor(p.err)
    }.let {}

    private fun normalizePhone(raw: String): String? {
        var s = raw.replace(Regex("[\\s\\-()]"), "")
        if (s.startsWith("00")) s = "+${s.substring(2)}"
        if (s.startsWith("+")) {
            val digits = s.substring(1)
            if (digits.length < 8 || !digits.all { it.isDigit() }) return null
            return s
        }
        // 无 + 前缀的纯数字也接受（与官方 App 行为一致，服务器会按原样处理）
        if (s.all { it.isDigit() } && s.length in 8..15) return s
        return null
    }

    private fun formatCode(code: String): String {
        val c = code.replace("-", "").uppercase(Locale.US)
        return if (c.length == 8) "${c.substring(0, 4)}-${c.substring(4)}" else code
    }
}
