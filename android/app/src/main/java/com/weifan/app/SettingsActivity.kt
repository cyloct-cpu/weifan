package com.weifan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

import com.weifan.app.core.Theme

/**
 * 设置页 —— 视觉语言与会话列表统一（对齐 activity_chat_list 的顶栏与列表规格）：
 *
 *   顶栏：白底、‹ 44dp + 标题 20sp medium、右侧昼夜图标 42dp，底部分隔线
 *   分组标题：13sp medium、色 #6B7280、paddingH 16 / top 18 / bottom 6
 *   行：标题 15.5sp、说明 12.5sp、paddingH 16 / V 12，1px 分隔线（从 16dp 起）
 *   开关行：右侧 Switch，与文字垂直居中
 *
 * 说明：参考客户端没有独立设置页（设置项从会话列表菜单进入），
 * 所以本页保留自有信息结构，只把版式语言对齐。
 */
class SettingsActivity : BaseActivity() {

    private val p get() = palette

    /** 昼夜模式展示名：浅色 / 深色 / 跟随系统 */
    private fun themeModeLabel(mode: String): String = when (mode) {
        Theme.MODE_LIGHT -> "浅色"
        Theme.MODE_DARK -> "深色"
        else -> "跟随系统"
    }

    /** 版本号取自安装包信息，避免和 build.gradle 里的版本各说各话 */
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (e: Exception) {
        "—"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }

        // ── 顶栏：与列表页一致（白底 / 20sp medium / 右侧 42dp 图标）
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            minimumHeight = dp(WaSpec.TOOLBAR_HEIGHT_DP)
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(WaSpec.textPrimary(night))
            contentDescription = "返回"
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(TextView(this).apply {
            text = "设置"
            textSize = WaSpec.LIST_TITLE_SP.toFloat()
            setTextColor(WaSpec.textPrimary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(WaSpec.divider(night)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // ---- 通用 ----
        list.addView(section("通用"))
        list.addView(rowValue("外观", themeModeLabel(prefs.themeMode)) {
            prefs.themeMode = com.weifan.app.core.Theme.nextMode(prefs.themeMode)
            recreate()
            true
        })

        // ---- WhatsApp 连接 ----
        list.addView(section("WhatsApp 连接"))
        list.addView(rowSwitch("收件自动翻译", "对方消息到达后自动出中文", prefs.waAutoTranslateIncoming) {
            prefs.waAutoTranslateIncoming = it
        })
        // ---- 翻译设置 ----
        list.addView(section("翻译设置"))
        list.addView(row("翻译服务（九家 + 自定义接口）", "密钥、优先级、额度保护、试译") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        list.addView(row("打赏", "觉得好用请作者吗杯奶茶 ☕") {
            showDonateDialog()
        })

        // ---- 客户管理（本地 CRM，批3-a）----
        list.addView(section("客户管理"))
        list.addView(row("跟进待办", "记要跟进的事与到期时间，纯本地不上传") {
            startActivity(Intent(this, FollowupTodosActivity::class.java))
        })
        list.addView(row("备份数据", "会话/媒体/设置打包成 zip，存到手机 Download 目录") {
            showBackupDialog()
        })
        list.addView(row("恢复数据", "从 Download 里的备份 zip 恢复（会覆盖当前数据）") {
            showRestorePicker()
        })
        list.addView(row("设备管理", "查看本账号有几台设备在线（仅查看，无法远程注销）") {
            showDevicesDialog()
        })
        list.addView(row("新建联系人", "查号是否在 WhatsApp + 存进手机通讯录") {
            startActivity(Intent(this, NewContactActivity::class.java))
        })

        // ---- 拉黑管理 ----
        list.addView(section("隐私与屏蔽"))
        list.addView(row("已拉黑联系人", "查看与解除应用内拉黑") { showBlockedList() })

        // ---- 关于 ----
        list.addView(section("关于"))
        list.addView(rowValue("版本", appVersion(), onClick = null))
    }

    /** 已拉黑联系人：解除拉黑 */
    private fun showBlockedList() {
        val rows = engine().db.blockedChats()
        if (rows.isEmpty()) {
            Toast.makeText(this, "暂无拉黑联系人", Toast.LENGTH_SHORT).show(); return
        }
        val names = rows.map { it.title.ifBlank { it.jid } }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("已拉黑联系人")
            .setItems(names) { _, i ->
                engine().db.setChatFlags(rows[i].jid, blocked = false)
                Thread {
                    val err = try { engine().client.updateBlocklist(rows[i].jid, "unblock"); null }
                    catch (e: Throwable) { e.message ?: e.toString() }
                    runOnUiThread {
                        Toast.makeText(this, if (err == null) "已解除（含官方端）：${names[i]}"
                            else "本地已恢复；官方端同步失败：$err", Toast.LENGTH_LONG).show()
                    }
                }.start()
                Toast.makeText(this, "已解除：${names[i]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 打赏弹窗：大图二维码 + 提示保存后微信扫码 */
    private fun showDonateDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        box.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.qr_donate)
            adjustViewBounds = true
            maxWidth = dp(320)
            // 长按图片也能保存
            setOnLongClickListener { saveQrToGallery(); true }
        })
        box.addView(TextView(this).apply {
            text = "长按图片或点下方按钮保存，再用微信扫码"
            textSize = 14f
            setTextColor(WaSpec.textSecondary(night))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        box.addView(Ui.solidButton(this, "保存到相册", WaSpec.primary(night), Color.WHITE) {
            saveQrToGallery()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(12)
        })
        android.app.AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 二维码存入相册 Pictures/WeFlip（Android 10+ 走 MediaStore，无需权限） */
    private fun saveQrToGallery() {
        try {
            val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.qr_donate)
            val name = "WeFlip-donate-${System.currentTimeMillis()}.jpg"
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeFlip")
                }
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(uri)!!.use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
            }
            Toast.makeText(this, "已保存到相册 Pictures/WeFlip，打开微信扫一扫即可", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "保存失败：${e.message ?: "未知"}", Toast.LENGTH_LONG).show()
        }
    }


    private fun dp(v: Int) = Ui.dp(this, v)

    private fun engine() = ChatEngineHolder.get(this)

    // ---- 构建小块 ----

    /** 分组标题：13sp medium、#6B7280（夜 #8696A0）、paddingH 16 / top 18 / bottom 6 */
    /**
     * 备份弹层（对齐参考 dialog_backup_restore + dialog_restore_options + dialog_backup_progress）。
     * 先列出将备份的 5 类内容及体积（20/22/20/18 + 17sp），确认后后台打包并显示进度。
     */
    private fun showBackupDialog() {
        val items = com.weifan.app.core.Backup.summary(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for ((name, size) in items) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
            }
            r.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(WaSpec.textPrimary(night))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(TextView(this).apply {
                text = size
                textSize = 13f
                setTextColor(WaSpec.textSecondary(night))
            })
            content.addView(r)
        }
        content.addView(TextView(this).apply {
            text = "备份包含设备凭据，请妥善保管，不要发给他人。"
            textSize = 12f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, dp(10), 0, 0)
        })

        android.app.AlertDialog.Builder(this)
            .setView(Ui.formDialogShell(this, "备份数据", night, content))
            .setPositiveButton("开始备份") { _, _ -> runBackup() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 设备管理（只读）。
     *
     * 为什么只能看不能注销：whatsmeow 没有导出通用 IQ 发送口（SendIQ / Send(Node) 均无），
     * 而「注销某台设备」需要发 w:devices 私有 IQ。能用的只有 USync 的 GetUserDevices，
     * 它返回设备 JID 列表 —— 足够回答「我账号有没有被陌生设备登着」这个安全关切。
     */
    private fun showDevicesDialog() {
        val body = TextView(this).apply {
            text = "正在查询…"
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            minimumHeight = dp(60)
            gravity = Gravity.CENTER_VERTICAL
        }
        val shell = Ui.formDialogShell(this, "设备管理", night, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body)
        })
        android.app.AlertDialog.Builder(this)
            .setView(shell)
            .setPositiveButton("关闭", null)
            .show()

        com.weifan.app.chat.ChatEngine.get(this).listDevicesAsync { count, cur, err ->
            if (isFinishing || isDestroyed) return@listDevicesAsync
            body.text = when {
                err == "NOT_CONNECTED" ->
                    "未连接到 WhatsApp。\n请确认已配对且网络正常后重试。"
                err == "NOT_PAIRED" -> "尚未配对设备。"
                err != null -> "查询失败：$err"
                count <= 0 -> "没有查到设备信息。"
                else -> buildString {
                    append("本账号共 $count 台设备在线。\n\n")
                    append("当前设备 ID：$cur\n\n")
                    append("说明：这里只能查看数量，无法远程注销其它设备——")
                    append("注销需要 WhatsApp 未公开的管理接口。若发现陌生设备，")
                    append("请在手机 WhatsApp 的「已连接的设备」里手动退出。")
                }
            }
        }
    }

    /** 选备份文件（对齐 dialog_backup_restore 的文件选择） */
    private fun showRestorePicker() {
        val files = com.weifan.app.core.Backup.availableBackups()
        if (files.isEmpty()) {
            Toast.makeText(this, "Download 里没有备份文件", Toast.LENGTH_LONG).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        val labels = files.map {
            "${it.name}\n${fmt.format(java.util.Date(it.lastModified()))} · ${it.length() / 1024} KB"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择备份")
            .setItems(labels) { _, i -> showRestoreOptions(files[i]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 逐项勾选 + 覆盖警告（对齐 dialog_restore_options + dialog_confirm_restore） */
    private fun showRestoreOptions(zip: java.io.File) {
        val choices = listOf(
            "chats" to "会话与消息", "creds" to "设备凭据与联系人",
            "media" to "媒体文件", "avatars" to "头像缓存", "prefs" to "翻译设置与偏好")
        val checked = booleanArrayOf(true, true, false, false, true)
        android.app.AlertDialog.Builder(this)
            .setTitle("恢复选项")
            .setMultiChoiceItems(choices.map { it.second }.toTypedArray(), checked) { _, w, v ->
                checked[w] = v
            }
            .setPositiveButton("下一步") { _, _ ->
                val opts = choices.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                if (opts.isEmpty()) {
                    Toast.makeText(this, "至少选一项", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                confirmRestore(zip, opts)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 覆盖警告（对齐 dialog_confirm_restore：15sp + 左右 20dp） */
    private fun confirmRestore(zip: java.io.File, opts: Set<String>) {
        android.app.AlertDialog.Builder(this)
            .setView(Ui.confirmBody(this, "确定从这份备份恢复？",
                "恢复会话与消息将覆盖当前所有聊天记录，且无法撤销。恢复完成后应用会自动重启。",
                night))
            .setPositiveButton("恢复") { _, _ -> runRestore(zip, opts) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 关连接 → 覆盖 → 杀进程重启 */
    private fun runRestore(zip: java.io.File, opts: Set<String>) {
        val stageText = TextView(this).apply {
            this.text = "准备中…"
            textSize = 14f
            setTextColor(WaSpec.textPrimary(night))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            addView(TextView(this@SettingsActivity).apply {
                text = "正在恢复"
                textSize = 17f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(WaSpec.textPrimary(night))
            })
            addView(stageText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            val err = try {
                // ① 先把我们自己的库关掉（不关会锁库）
                runOnUiThread { stageText.text = "正在关闭数据库…" }
                com.weifan.app.chat.ChatEngine.get(this).db.closeForRestore()
                // ② 解压 + 覆盖（内部带 .bak 回滚）
                val n = com.weifan.app.core.Backup.restore(this, zip, opts) { stage ->
                    runOnUiThread { if (!isFinishing && !isDestroyed) stageText.text = stage }
                }
                if (n == 0) "没有写入任何文件（备份内容与选项不匹配）" else null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Throwable) { }
                if (err != null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("恢复失败")
                        .setMessage("$err\n\n原有数据已保留（未成功写入的文件已回滚）。")
                        .setPositiveButton("知道了") { _, _ -> recreate() }
                        .show()
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("恢复完成")
                        .setMessage("应用即将重启以加载新数据。")
                        .setCancelable(false)
                        .setPositiveButton("立即重启") { _, _ ->
                            finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                        .show()
                }
            }
        }.start()
    }

    /** 后台打包 + 进度弹层（对齐参考 dialog_backup_progress：padding 20/22/20/20） */
    private fun runBackup() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS)
        val target = java.io.File(dir, "weifan-backup-$stamp.zip")

        val progressText = TextView(this).apply {
            text = "准备中…"
            textSize = 14f
            setTextColor(WaSpec.textPrimary(night))
        }
        val bar = android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(WaSpec.primary(night))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            addView(TextView(this@SettingsActivity).apply {
                text = "正在备份"
                textSize = 17f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(WaSpec.textPrimary(night))
            })
            addView(progressText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            val err = try {
                com.weifan.app.core.Backup.export(this, target) { done, total ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            val pct = if (total <= 0) 0 else (done * 1000 / total).toInt()
                            bar.progress = pct
                            progressText.text = "已写入 ${done / 1024} KB / ${total / 1024} KB"
                        }
                    }
                }
                null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try { dlg.dismiss() } catch (_: Throwable) { }
                if (err == null) {
                    val kb = target.length() / 1024
                    android.app.AlertDialog.Builder(this)
                        .setTitle("备份完成")
                        .setMessage("已保存：\nDownload/${target.name}\n共 $kb KB")
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("备份失败")
                        .setMessage(err)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    private fun section(title: String): View =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(WaSpec.textSecondary(night))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(18), dp(16), dp(6))
        }

    private fun row(title: String, note: String, isError: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(if (isError) Color.parseColor("#CC4444") else WaSpec.textPrimary(night))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = note
                textSize = 12.5f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(2), 0, 0)
            })
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row)
        wrap.addView(View(this).apply { setBackgroundColor(WaSpec.divider(night)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
            })
        return wrap
    }

    /** 带右侧值的行；onClick 返回 true 表示自己刷新值 */
    private fun rowValue(title: String, value: String, onClick: (() -> Boolean)?): TextView =
        TextView(this).apply {
            text = "$title\n$value"
            textSize = 15.5f
            setTextColor(WaSpec.textPrimary(night))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                if (onClick?.invoke() == true) {
                    text = "$title\n${Prefs(this@SettingsActivity).waProxy.ifBlank { "直连（国内网络需要配置）" }}"
                }
            }
        }

    private fun rowSwitch(title: String, note: String, initial: Boolean, onChange: (Boolean) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val col = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15.5f
                setTextColor(WaSpec.textPrimary(night))
            })
            col.addView(TextView(this@SettingsActivity).apply {
                text = note
                textSize = 12.5f
                setTextColor(WaSpec.textSecondary(night))
                setPadding(0, dp(2), dp(8), 0)
            })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@SettingsActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            })
        }
}

/** 延迟拿 ChatEngine（避免 SettingsActivity 在 engine 未初始化时构建崩溃） */
private object ChatEngineHolder {
    fun get(activity: Activity) = com.weifan.app.chat.ChatEngine.get(activity)
}
