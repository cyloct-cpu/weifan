package com.weifan.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.chat.ChatEngine
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 新建联系人（批3-b，对齐参考 NewContactActivity）。
 *
 * 跨境卖家的场景是「客户在 WhatsApp 上来了，但我手机里没这个人」，
 * 所以这一页要一次做完三件事：
 *   1) 查这个号在不在 WhatsApp（协议侧 isOnWhatsApp，避免存了个发不出去的号）
 *   2) 写进手机系统通讯录（这样下次发联系人卡片、别人看你也有名字）
 *   3) 直接跳进会话开聊
 *
 * 号码归一化：去掉 + 空格 横线 括号，只留数字；国家码单独一栏，避免用户把 +86 打进手机号。
 */
class NewContactActivity : BaseActivity() {

    private lateinit var nameInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var statusText: TextView
    private var checking = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "新建联系人"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }
        root.addView(Ui.topBar(this, "新建联系人", onBack = { finish() }, night = night))

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        box.addView(fieldLabel("姓名"))
        nameInput = field("例如：Leon van Dijk")
        box.addView(nameInput)

        box.addView(fieldLabel("国家码 / 手机号"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 国家码必须是内容而不是 hint：之前只写 hint 导致 fullNumber() 永远为空，
        // 查询与保存全部走「号码不完整」分支（实机测试才暴露）
        codeInput = field("国家码")
        codeInput.setText("+86")
        codeInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
        codeInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
        row.addView(codeInput, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        phoneInput = field("手机号，不含国家码")
        phoneInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
        row.addView(phoneInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(10) })
        box.addView(row)

        statusText = TextView(this).apply {
            text = "填完后可先查一下这个号在不在 WhatsApp 上"
            textSize = 13f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, dp(10), 0, dp(14))
        }
        box.addView(statusText)

        box.addView(Ui.solidButton(this, "查询是否在 WhatsApp",
            if (night) WaSpec.SURFACE_NIGHT else Color.WHITE,
            WaSpec.textPrimary(night)) { checkOnWhatsApp() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
                .apply { topMargin = dp(4) })

        box.addView(Ui.solidButton(this, "保存到手机通讯录", WaSpec.primary(night), Color.WHITE) {
            saveToPhoneContacts()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            .apply { topMargin = dp(10) })

        box.addView(Ui.solidButton(this, "保存并去发消息", WaSpec.GREEN, Color.WHITE) {
            saveToPhoneContacts(); openChat()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
            .apply { topMargin = dp(10) })
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(WaSpec.textSecondary(night))
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(WaSpec.textPrimary(night))
        setHintTextColor(WaSpec.textSecondary(night))
        background = Ui.roundBg(this@NewContactActivity,
            if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        maxLines = 1
    }

    /** 归一化成纯数字（含国家码）；不合法返回 null */
    private fun fullNumber(): String? {
        val code = codeInput.text.toString().filter { it.isDigit() }
        val local = phoneInput.text.toString().filter { it.isDigit() }
        if (code.isEmpty() || local.length < 6) return null
        return code + local
    }

    private fun checkOnWhatsApp() {
        val digits = fullNumber()
        if (digits == null) {
            statusText.text = "号码不完整：请填国家码和至少 6 位手机号"
            return
        }
        if (!checking.compareAndSet(false, true)) return
        statusText.text = "正在查询 +$digits …"
        Thread {
            val msg = try {
                val arr = JSONArray()
                arr.put("+$digits")
                val raw = ChatEngine.get(this).client.isOnWhatsApp(arr.toString())
                val res = JSONArray(raw)
                val on = (0 until res.length()).any {
                    val o = res.optJSONObject(it)
                    (o?.optBoolean("on_whatsapp") ?: o?.optBoolean("onWhatsApp") ?: false)
                }
                if (on) "✅ +$digits 在 WhatsApp 上，可以发消息"
                else "⚠ +$digits 目前不在 WhatsApp 上，存了也发不出去"
            } catch (e: Throwable) {
                "查询失败：${e.message ?: e.toString()}"
            }
            checking.set(false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) statusText.text = msg
            }
        }.start()
    }

    /** 写系统通讯录（需要 WRITE_CONTACTS；模拟器 appops 会默认放行，真机要弹窗授权） */
    private fun saveToPhoneContacts() {
        val name = nameInput.text.toString().trim()
        val digits = fullNumber()
        if (name.isEmpty() || digits == null) {
            Toast.makeText(this, "姓名和手机号都要填", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_CONTACTS), 4103)
            return
        }
        try {
            val raw = "+$digits"
            // 必须插 RawContacts：插 Contacts.CONTENT_URI 不会真正建联系人
            val id = contentResolver.insert(
                ContactsContract.RawContacts.CONTENT_URI, ContentValues())!!
                .lastPathSegment!!.toLong()
            contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, id)
                put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            })
            contentResolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, id)
                put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, raw)
                put(ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            })
            Toast.makeText(this, "已存入手机通讯录", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "写入通讯录失败：${e.message ?: e.toString()}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openChat() {
        val digits = fullNumber() ?: return
        val name = nameInput.text.toString().trim().ifBlank { "+$digits" }
        startActivity(Intent(this, ChatActivity::class.java)
            .putExtra("jid", "$digits@s.whatsapp.net")
            .putExtra("title", name))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4103 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            saveToPhoneContacts()
        }
    }
}
