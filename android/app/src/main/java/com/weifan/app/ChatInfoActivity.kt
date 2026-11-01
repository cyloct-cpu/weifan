package com.weifan.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.chat.ChatEngine
import org.json.JSONObject

/**
 * 客户 / 群详情页（对齐 WhatsApp Web 联络人资料）：
 *
 *   顶栏「联络人资料 / 群组资料」
 *   头像大图 96dp → 名称（群名可编辑）→ 手机号 / 成员数
 *   群组：成员列表（群主标 👑）+「添加成员」行
 *   媒体·链接和文件九宫格（本地 DB，点图看大图省略：显示缩略图/文件名）
 *   本地备注（存在 chats.title，官方备注在 appstate，P2 再同步）
 */
class ChatInfoActivity : BaseActivity() {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var chatJid: String = ""
    private var chatTitle: String = ""
    private var isGroup = false
    private lateinit var nameText: TextView
    private lateinit var subText: TextView
    private lateinit var memberBox: LinearLayout
    private lateinit var mediaGrid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatJid = intent.getStringExtra("jid") ?: ""
        chatTitle = intent.getStringExtra("title") ?: ""
        isGroup = chatJid.contains("@g.us")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }
        root.addView(Ui.topBar(this, if (isGroup) "群组资料" else "联络人资料",
            onBack = { finish() }, night = night))

        val scroll = ScrollView(this).apply { setBackgroundColor(WaSpec.surface(night)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // ── 头像 96dp（复用首字母占位 + 缓存照片）
        val avatar = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = Ui.roundBg(this@ChatInfoActivity, WaSpec.GREEN,
                WaSpec.LIST_AVATAR_SIZE_DP)
        }
        box.addView(avatar, LinearLayout.LayoutParams(dp(96), dp(96)))
        engine().fetchAvatarAsync(chatJid) { av ->
            if (av == null || isDestroyed) return@fetchAvatarAsync
            try {
                val bmp = BitmapFactory.decodeFile(av.absolutePath) ?: return@fetchAvatarAsync
                avatar.text = ""
                avatar.background = android.graphics.drawable.BitmapDrawable(resources, bmp)
            } catch (_: Throwable) { }
        }

        // ── 名称 + 副行
        nameText = TextView(this).apply {
            text = chatTitle
            textSize = 22f
            setTextColor(WaSpec.textPrimary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        box.addView(nameText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        subText = TextView(this).apply {
            text = if (isGroup) "群组" else phoneLabel()
            textSize = 15f
            setTextColor(WaSpec.textSecondary(night))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(subText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!isGroup) {
            // 个人号：编辑本地备注（= 重命名）
            box.addView(sectionLabel("本地备注（仅保存在 WeFlip）"))
            box.addView(EditText(this).apply {
                setText(chatTitle)
                hint = "给这个客户起个名字"
                textSize = 15f
                setTextColor(WaSpec.textPrimary(night))
                background = Ui.roundBg(this@ChatInfoActivity,
                    if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                id = 2001
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            box.addView(Ui.solidButton(this, "保存备注", WaSpec.primary(night), Color.WHITE) {
                val t = (findViewById<EditText>(2001)).text.toString().trim()
                if (t.isNotBlank()) {
                    engine().db.renameChat(chatJid, t)
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        } else {
            // ── 群成员
            box.addView(sectionLabel("成员"))
            memberBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(memberBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
            loadGroupInfo()

            // 加成员
            val addRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.roundBg(this@ChatInfoActivity,
                    if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 24)
                setPadding(dp(14), dp(6), dp(6), dp(6))
            }
            addRow.addView(EditText(this).apply {
                hint = "输入手机号添加成员（如 +5218120398050）"
                textSize = 14f
                setTextColor(WaSpec.textPrimary(night))
                setHintTextColor(WaSpec.textSecondary(night))
                setBackgroundResource(0)
                setSingleLine(true)
                id = 2002
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addRow.addView(TextView(this).apply {
                text = "添加"
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = Ui.roundBg(this@ChatInfoActivity, WaSpec.primary(night), 20)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isClickable = true
                setOnClickListener { onAddMember() }
            })
            box.addView(addRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }

        // ── 媒体九宫格
        box.addView(sectionLabel("媒体、链接和文件"))
        mediaGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(mediaGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        renderMediaGrid()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun engine() = ChatEngine.get(this)

    private fun phoneLabel(): String {
        // LID 会话显示解析出的真实号码
        val pn = engine().lastPnFor(chatJid)
        val raw = pn.ifBlank { chatJid }
        val digits = raw.substringBefore("@").filter { it.isDigit() }
        return if (digits.length > 7) "+$digits" else "自动检测"
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(WaSpec.textSecondary(night))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(22), 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun loadGroupInfo() {
        Thread {
            val info: JSONObject? = try {
                JSONObject(engine().client.getGroupInfo(chatJid))
            } catch (_: Throwable) { null }
            main.post {
                if (isDestroyed) return@post
                if (info == null) {
                    subText.text = "群信息获取失败（可能不在群里）"
                    return@post
                }
                nameText.text = info.optString("name").ifBlank { chatTitle }
                val members = info.optJSONArray("participants")
                subText.text = "${members?.length() ?: 0} 位成员"
                memberBox.removeAllViews()
                if (members != null) {
                    for (i in 0 until members.length()) {
                        val m = members.getJSONObject(i)
                        memberBox.addView(memberRow(
                            m.optString("phone").ifBlank { m.optString("jid") },
                            m.optBoolean("isAdmin"), i == 0))
                    }
                }
            }
        }.start()
    }

    private fun memberRow(phone: String, admin: Boolean, owner: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@ChatInfoActivity).apply {
                text = "👤"
                textSize = 16f
            })
            addView(TextView(this@ChatInfoActivity).apply {
                text = (if (owner) "👑 " else "") +
                    (if (phone.contains("@lid")) "成员" else phone.substringBefore("@"))
                textSize = 15f
                setTextColor(WaSpec.textPrimary(night))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
            addView(TextView(this@ChatInfoActivity).apply {
                text = if (admin) "管理员" else ""
                textSize = 12f
                setTextColor(WaSpec.textSecondary(night))
            })
        }

    private fun onAddMember() {
        val input = findViewById<EditText>(2002)
        val raw = input.text.toString().trim()
        if (raw.isBlank()) return
        val digits = raw.replace("+", "").filter { it.isDigit() }
        if (digits.length !in 8..15) {
            Toast.makeText(this, "号码格式不对", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在添加…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = try {
                engine().client.addGroupParticipants(chatJid, "[\"+$digits\"]")
                null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            main.post {
                if (isDestroyed) return@post
                if (err != null) Toast.makeText(this, "添加失败：$err", Toast.LENGTH_LONG).show()
                else {
                    Toast.makeText(this, "已添加 +$digits", Toast.LENGTH_SHORT).show()
                    input.setText("")
                    loadGroupInfo()
                }
            }
        }.start()
    }

    /** 本地媒体九宫格：每行 3 格，方形缩略图；文档显示 📄+名 */
    private fun renderMediaGrid() {
        mediaGrid.removeAllViews()
        val msgs = engine().db.messages(chatJid).filter { it.mediaKind.isNotBlank() }
        if (msgs.isEmpty()) {
            mediaGrid.addView(TextView(this).apply {
                text = "暂无媒体"
                textSize = 13f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(6), 0, 0)
            })
            return
        }
        var row: LinearLayout? = null
        for ((idx, m) in msgs.withIndex()) {
            if (idx % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                mediaGrid.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = Ui.roundBg(this@ChatInfoActivity,
                    if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 8)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                weightSum = 1f
            }
            val label = when (m.mediaKind) {
                "image", "sticker" -> "🖼"
                "video" -> "🎬"
                "audio" -> "🎧"
                else -> "📄 ${m.mediaName.take(8)}"
            }
            cell.addView(TextView(this).apply {
                text = label
                textSize = 22f
                gravity = Gravity.CENTER
            })
            cell.addView(TextView(this).apply {
                text = m.mediaName.ifBlank { m.mediaKind }
                textSize = 10.5f
                setTextColor(WaSpec.textSecondary(night))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            row?.addView(cell, LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                marginStart = if (idx % 3 > 0) dp(4) else 0
            })
        }
    }
}
