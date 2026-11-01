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
    private lateinit var mediaTabs: LinearLayout
    private var mediaTab: String = "图片与视频"

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
            // ANR 修复：解码移到后台线程
            Thread {
                try {
                    // 大头像显示 96dp：降采样后解码
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(av.absolutePath, opt)
                    var sm = 1
                    while (opt.outWidth / (sm * 2) >= dp(192) || opt.outHeight / (sm * 2) >= dp(192)) sm *= 2
                    val bmp = BitmapFactory.decodeFile(av.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sm }) ?: return@Thread
                    main.post {
                        if (isDestroyed || isFinishing) return@post
                        avatar.text = ""
                        avatar.background = android.graphics.drawable.BitmapDrawable(resources, bmp)
                    }
                } catch (_: Throwable) { }
            }.start()
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

            // ── 客户备注（本地 CRM）：多行自由文本，存 chats.note
            box.addView(sectionLabel("客户备注（仅保存在本机）"))
            box.addView(EditText(this).apply {
                setText(engine().db.chatNote(chatJid))
                hint = "成交意向、商品偏好、跟进计划…"
                textSize = 15f
                setTextColor(WaSpec.textPrimary(night))
                background = Ui.roundBg(this@ChatInfoActivity,
                    if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minLines = 3
                gravity = Gravity.TOP
                // 2012 而不是 2002：2002 已被下方「添加成员」输入框占用，
                // 同 id 会让 findViewById 拿错控件（添加成员会误读备注内容）
                id = 2012
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            box.addView(Ui.solidButton(this, "保存客户备注", WaSpec.primary(night), Color.WHITE) {
                engine().db.setChatNote(chatJid, findViewById<EditText>(2012).text.toString())
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
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

            // 改群名
            box.addView(Ui.solidButton(this, "修改群名", WaSpec.primary(night), Color.WHITE) {
                val input = EditText(this).apply { setText(nameText.text.toString()) }
                android.app.AlertDialog.Builder(this)
                    .setTitle("修改群名")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        val n = input.text.toString().trim()
                        if (n.isNotBlank()) renameGroup(n)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })

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

        // ── 媒体分类 tabs（对齐 WhatsApp Web，去小程序/视频号）：文件/图片与视频/链接/音乐与音频/日期
        box.addView(sectionLabel("媒体、链接和文件"))
        val tabsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        mediaTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsScroll.addView(mediaTabs)
        box.addView(tabsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        mediaGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(mediaGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        renderMediaTabs("图片与视频")
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
            isLongClickable = true
            setOnLongClickListener {
                val opts = mutableListOf(
                    "移除成员" to { removeMember(phone) },
                    if (admin) ("降为普通成员" to { changeAdminRole(phone, false) })
                    else ("升为管理员" to { changeAdminRole(phone, true) }))
                android.app.AlertDialog.Builder(this@ChatInfoActivity)
                    .setTitle(phone.substringBefore("@"))
                    .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
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

    private fun renameGroup(newName: String) {
        Toast.makeText(this, "正在改群名…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = try { engine().client.setGroupName(chatJid, newName); null }
            catch (e: Throwable) { e.message ?: e.toString() }
            main.post {
                if (isDestroyed) return@post
                if (err != null) Toast.makeText(this, "修改失败：$err", Toast.LENGTH_LONG).show()
                else {
                    nameText.text = newName
                    engine().db.renameChat(chatJid, newName)
                    Toast.makeText(this, "群名已更新", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

    // MARK: - 媒体分类（文件/图片与视频/链接/音乐与音频/日期）

    private val mediaTabsDef = listOf("文件", "图片与视频", "链接", "音乐与音频", "日期")

    /** 顶部 tab 行（选中绿底白字，其余灰底） */
    private fun renderMediaTabs(active: String) {
        mediaTab = active
        mediaTabs.removeAllViews()
        for (t in mediaTabsDef) {
            val sel = t == active
            mediaTabs.addView(TextView(this).apply {
                text = t
                textSize = 13.5f
                setTextColor(if (sel) Color.WHITE else WaSpec.textSecondary(night))
                typeface = Typeface.create(if (sel) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                background = Ui.roundBg(this@ChatInfoActivity,
                    if (sel) WaSpec.primary(night)
                    else (if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6), 16)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                setOnClickListener { renderMediaTabs(t) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (mediaTabs.childCount > 0) marginStart = dp(8)
            })
        }
        renderMediaContent()
    }

    /** 升/降管理员：promote/demote（仅群主可用） */
    private fun changeAdminRole(phone: String, promote: Boolean) {
        Toast.makeText(this, if (promote) "正在升管理员…" else "正在降为成员…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = try {
                if (promote) engine().client.promoteGroupParticipants(chatJid, "[\"$phone\"]")
                else engine().client.demoteGroupParticipants(chatJid, "[\"$phone\"]")
                null
            } catch (e: Throwable) { e.message ?: e.toString() }
            main.post {
                if (isDestroyed) return@post
                if (err != null) Toast.makeText(this, "操作失败：$err", Toast.LENGTH_LONG).show()
                else {
                    Toast.makeText(this, if (promote) "已升为管理员" else "已降为普通成员", Toast.LENGTH_SHORT).show()
                    loadGroupInfo()
                }
            }
        }.start()
    }

    private fun removeMember(phone: String) {
        Toast.makeText(this, "正在移除…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = try { engine().client.removeGroupParticipants(chatJid, "[\"$phone\"]"); null }
            catch (e: Throwable) { e.message ?: e.toString() }
            main.post {
                if (isDestroyed) return@post
                if (err != null) Toast.makeText(this, "移除失败：$err", Toast.LENGTH_LONG).show()
                else { Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show(); loadGroupInfo() }
            }
        }.start()
    }

    private fun allMedia() = engine().db.messages(chatJid).filter { it.mediaKind.isNotBlank() }

    private fun fmtTime(ts: Long): String =
        java.text.SimpleDateFormat("yyyy-M-d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

    private fun fmtSize(n: Long): String = when {
        n >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
        n >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", n / 1024.0)
        n > 0 -> "${n}B"
        else -> ""
    }

    /** 按 tab 渲染内容区 */
    private fun renderMediaContent() {
        mediaGrid.removeAllViews()
        val media = allMedia()
        when (mediaTab) {
            "图片与视频" -> {
                val imgs = media.filter { it.mediaKind in listOf("image", "sticker", "video") }
                if (imgs.isEmpty()) return emptyHint()
                // 九宫格：缩略图（已下载用真图，否则占位图标+时间）
                var row: LinearLayout? = null
                for ((idx, m) in imgs.withIndex()) {
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
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                    }
                    val img = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        // ANR 修复：文件解码移到后台线程，主线程只设占位/贴图
                        val f = if (m.mediaPath.isNotBlank()) java.io.File(m.mediaPath) else null
                        if (f != null && f.exists()) {
                            setImageResource(android.R.drawable.ic_menu_gallery)
                            Thread {
                                try {
                                    // 九宫格格子约 100dp：降采样后解码
                                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(f.absolutePath, opt)
                                    var sm = 1
                                    while (opt.outWidth / (sm * 2) >= dp(200) || opt.outHeight / (sm * 2) >= dp(200)) sm *= 2
                                    val bmp = BitmapFactory.decodeFile(f.absolutePath,
                                        BitmapFactory.Options().apply { inSampleSize = sm }) ?: return@Thread
                                    main.post {
                                        if (isDestroyed || isFinishing) return@post
                                        setImageBitmap(bmp)
                                    }
                                } catch (_: Throwable) { }
                            }.start()
                        } else {
                            // 尚未下载：用消息自带缩略图（base64 小图，解码开销可忽略）
                            try {
                                val tb = android.util.Base64.decode(m.mediaThumb,
                                    android.util.Base64.DEFAULT)
                                val bmp = BitmapFactory.decodeByteArray(tb, 0, tb.size)
                                if (bmp != null) setImageBitmap(bmp)
                                else setImageResource(android.R.drawable.ic_menu_gallery)
                            } catch (_: Throwable) {
                                setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                        }
                    }
                    cell.addView(img, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
                    cell.addView(TextView(this).apply {
                        text = (if (m.mediaKind == "video") "🎬 " else "") + fmtTime(m.ts)
                        textSize = 9.5f
                        setTextColor(WaSpec.textSecondary(night))
                        gravity = Gravity.CENTER
                        maxLines = 1
                    })
                    cell.isClickable = true
                    cell.setOnClickListener {
                        startActivity(android.content.Intent(this@ChatInfoActivity,
                            MediaViewerActivity::class.java)
                            .putExtra("jid", chatJid).putExtra("msgId", m.id))
                    }
                    row?.addView(cell, LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                        marginStart = if (idx % 3 > 0) dp(4) else 0
                    })
                }
            }
            "文件" -> {
                val docs = media.filter { it.mediaKind == "document" }
                if (docs.isEmpty()) return emptyHint()
                for (m in docs) {
                    mediaGrid.addView(mediaListRow("📄", m.mediaName.ifBlank { "文件" },
                        listOf(fmtSize(m.mediaSize), fmtTime(m.ts)).filter { it.isNotBlank() }.joinToString(" · ")))
                }
            }
            "音乐与音频" -> {
                val auds = media.filter { it.mediaKind == "audio" }
                if (auds.isEmpty()) return emptyHint()
                for (m in auds) {
                    mediaGrid.addView(mediaListRow("🎧", "语音消息",
                        listOf(fmtTime(m.ts)).joinToString("")))
                }
            }
            "链接" -> {
                // 从文本消息里抽 http(s) URL
                data class Link(val url: String, val ts: Long)
                val links = mutableListOf<Link>()
                val re = Regex("https?://[\\w\\-./?#&=%+:@~]+")
                for (m in engine().db.messages(chatJid).filter { it.text.isNotBlank() }) {
                    for (u in re.findAll(m.text)) {
                        links += Link(u.value, m.ts)
                    }
                }
                if (links.isEmpty()) return emptyHint()
                for (l in links.reversed()) {
                    val host = try { java.net.URI(l.url).host ?: l.url } catch (_: Throwable) { l.url }
                    mediaGrid.addView(mediaListRow("🔗", l.url,
                        "$host · ${fmtTime(l.ts)}"))
                }
            }
            "日期" -> {
                // 全部媒体按月分组（yyyy年M月）
                val grouped = media.groupBy {
                    java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.getDefault()).format(java.util.Date(it.ts))
                }
                for ((month, list) in grouped.toSortedMap(java.util.Collections.reverseOrder())) {
                    mediaGrid.addView(TextView(this).apply {
                        text = month
                        textSize = 13.5f
                        setTextColor(WaSpec.textPrimary(night))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(0, dp(10), 0, dp(4))
                    })
                    for (m in list) {
                        val icon = when (m.mediaKind) {
                            "image", "sticker" -> "🖼"; "video" -> "🎬"
                            "audio" -> "🎧"; else -> "📄"
                        }
                        mediaGrid.addView(mediaListRow(icon,
                            m.mediaName.ifBlank { m.mediaKind },
                            listOf(fmtSize(m.mediaSize), fmtTime(m.ts)).filter { it.isNotBlank() }.joinToString(" · ")))
                    }
                }
            }
        }
    }

    private fun emptyHint() {
        mediaGrid.addView(TextView(this).apply {
            text = "该分类暂无内容"
            textSize = 13f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, dp(6), 0, 0)
        })
    }

    /** 列表行：图标 + 主文本 + 副文本 */
    private fun mediaListRow(icon: String, title: String, sub: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@ChatInfoActivity).apply {
                text = icon
                textSize = 17f
            })
            val col = LinearLayout(this@ChatInfoActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(this@ChatInfoActivity).apply {
                text = title
                textSize = 14f
                setTextColor(WaSpec.textPrimary(night))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            if (sub.isNotBlank()) col.addView(TextView(this@ChatInfoActivity).apply {
                text = sub
                textSize = 11.5f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(2), 0, 0)
            })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
        }
}
