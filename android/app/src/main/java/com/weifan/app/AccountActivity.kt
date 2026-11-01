package com.weifan.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 账号页 —— 按参考客户端 AccountLoginActivity 的版式 1:1 重排（文案换成我们自己的）：
 *
 *   返回圈 32dp → 大标题 45sp → 副标题 14sp → 引导 13sp
 *   试用横幅：浅紫底 #EEF2FF、padding 14dp、图标 20dp、文字 14sp 紫蓝
 *   输入框：高 56dp、圆角 14dp、描边 #E5E7EB、内边距 14dp、图标 22dp、文字 15sp
 *   忘记密码：14sp 紫蓝右对齐
 *   主按钮：渐变 #667EEA→#8B5CF6、圆角 28dp、16sp 白字、paddingV 16dp
 *   页脚：14sp 灰、居中
 *
 * 说明：本应用不需要自建账号（配对即用），这一页只作为「账号 / 授权」入口的版式载体，
 * 承载订阅、试用状态与客服入口，不做注册登录。
 */
class AccountActivity : BaseActivity() {

    private lateinit var emailInput: EditText
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply { setBackgroundColor(WaSpec.authBg(night)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(WaSpec.AUTH_PADDING_H_DP), dp(44), dp(WaSpec.AUTH_PADDING_H_DP), dp(24))
        }
        root.addView(box)

        // ── 返回圈 32dp
        box.addView(TextView(this).apply {
            text = "‹"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.authMuted(night))
            background = Ui.roundBg(this@AccountActivity,
                if (night) WaSpec.SURFACE_NIGHT else WaSpec.GRAY6, 16)
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(WaSpec.AUTH_BACK_BTN_DP), dp(WaSpec.AUTH_BACK_BTN_DP)))

        // ── 大标题 45sp
        box.addView(TextView(this).apply {
            text = "WeFlip"
            textSize = WaSpec.AUTH_TITLE_SP.toFloat()
            setTextColor(WaSpec.authText(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        box.addView(TextView(this).apply {
            text = "WhatsApp 消息翻译工具"
            textSize = WaSpec.AUTH_DESC_SP.toFloat()
            setTextColor(WaSpec.authMuted(night))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        box.addView(TextView(this).apply {
            text = "不懂外语，也能轻松做全球生意"
            textSize = WaSpec.AUTH_HINT_SP.toFloat()
            setTextColor(WaSpec.authHint(night))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // ── 试用横幅
        box.addView(Ui.authBanner(this, "🎁", "新用户注册即获得 1 天免费试用", night),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            })

        // ── 输入框 56dp
        val (emailRow, email) = Ui.authInputRow(this, "✉", "邮箱地址", night)
        emailInput = email
        box.addView(emailRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(WaSpec.AUTH_INPUT_HEIGHT_DP)).apply {
            topMargin = dp(20)
        })

        val (passRow, _) = Ui.authInputRow(this, "🔒", "密码", night, secret = true)
        box.addView(passRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(WaSpec.AUTH_INPUT_HEIGHT_DP)).apply {
            topMargin = dp(12)
        })

        // ── 忘记密码（右对齐 14sp 紫蓝）
        box.addView(TextView(this).apply {
            text = "忘记密码？"
            textSize = 14f
            setTextColor(WaSpec.primary(night))
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        message = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(WaSpec.LOGOUT_BANNER_TEXT)
        }
        box.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // ── 主按钮：渐变胶囊 28dp
        box.addView(Ui.authButton(this, "登录账号") { onLogin() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(28)
            })

        // ── 页脚
        box.addView(TextView(this).apply {
            text = "还没有账户？立即注册"
            textSize = WaSpec.AUTH_FOOTER_SP.toFloat()
            setTextColor(WaSpec.authHint(night))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(32)
        })

        setContentView(root)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun onLogin() {
        message.visibility = View.VISIBLE
        message.text = "本应用不需要账号：关联 WhatsApp 后即可使用，全部功能在本机运行。"
        message.setTextColor(WaSpec.authMuted(night))
    }
}
