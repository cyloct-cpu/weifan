package com.weifan.app

import android.content.Intent
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
import java.io.File

/**
 * 媒体发送前预览（批3-c，对齐参考 MediaSendPreviewActivity）。
 *
 * 之前选完图直接发出去，配文没地方写、发错了也收不回。跨境场景里
 * 「图 + 一句说明」才是一条完整报价，所以这一页要：
 *   1) 先看清要发的东西（图看缩略图，文件看名字与大小）
 *   2) 补一句说明（caption），随媒体一起发出去
 *   3) 没想好就别发——返回即取消，文件留在缓存里不产生消息
 *
 * 解码纪律：图片一律后台 + inSampleSize 降采样到显示尺寸，
 * 禁止主线程全尺寸解码（这条规矩是上一版被 ANR 与 lmkd 击杀换来的）。
 */
class MediaSendPreviewActivity : BaseActivity() {

    private var path: String = ""
    private var kind: String = "image"          // image | video | sticker | document
    private var fileName: String = ""
    private lateinit var captionInput: EditText
    private lateinit var sendBtn: TextView
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "发送预览"
        path = intent.getStringExtra("path") ?: ""
        kind = intent.getStringExtra("kind") ?: "image"
        fileName = intent.getStringExtra("name") ?: File(path).name

        if (path.isBlank() || !File(path).exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
        }
        root.addView(Ui.topBar(this, "发送预览", onBack = { finish() }, night = night, titleSp = 16f))

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 预览区
        when (kind) {
            "image" -> box.addView(imagePreview())
            "video" -> box.addView(videoPreview())
            else -> box.addView(documentCard())
        }

        // ── 说明输入
        box.addView(TextView(this).apply {
            text = "添加说明（可选，随媒体一起发出）"
            textSize = 13f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, dp(16), 0, dp(6))
        })
        captionInput = EditText(this).apply {
            hint = "例如：这是 500 件的实物图，参数按上次确认的走"
            textSize = 15f
            setTextColor(WaSpec.textPrimary(night))
            setHintTextColor(WaSpec.textSecondary(night))
            background = Ui.roundBg(this@MediaSendPreviewActivity,
                if (night) WaSpec.SEARCH_BG_NIGHT else WaSpec.GRAY6, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 3
            gravity = Gravity.TOP
        }
        box.addView(captionInput)

        box.addView(TextView(this).apply {
            text = "提示：说明会按你输入的原文发出，不会自动翻译。"
            textSize = 11.5f
            setTextColor(WaSpec.textSecondary(night))
            setPadding(0, dp(6), 0, 0)
        })

        // 底部固定发送条（放 ScrollView 外，避免长内容下被顶走）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WaSpec.surface(night))
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        sendBtn = Ui.solidButton(this, "发送", WaSpec.primary(night), Color.WHITE) { doSend() }
        bar.addView(sendBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(bar)
        setContentView(root)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    /** 图片缩略预览：后台 + 降采样到屏宽，绝不主线程全尺寸解码 */
    private fun imagePreview(): View {
        val side = resources.displayMetrics.widthPixels - dp(32)
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = side
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(160)
            background = Ui.roundBg(this@MediaSendPreviewActivity,
                if (night) WaSpec.BUBBLE_RECEIVED_NIGHT else WaSpec.GRAY6, 12)
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        Thread {
            val bmp = try {
                val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, opt)
                var s = 1
                while (opt.outWidth / (s * 2) >= side && opt.outHeight / (s * 2) >= side / 2) s *= 2
                android.graphics.BitmapFactory.decodeFile(path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && bmp != null) iv.setImageBitmap(bmp)
            }
        }.start()
        return iv
    }

    private fun videoPreview(): View {
        // 视频预览：系统缩略图（首帧）+ 居中播放图标
        val bmp = android.media.ThumbnailUtils.createVideoThumbnail(
            path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
        val frame = android.widget.FrameLayout(this)
        val iv = android.widget.ImageView(this).apply {
            if (bmp != null) { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
            else { setImageResource(android.R.drawable.ic_media_play) }
        }
        frame.addView(iv, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        frame.addView(android.widget.TextView(this).apply {
            text = "▶"; textSize = 40f; gravity = android.view.Gravity.CENTER
        }, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun documentCard(): View {
        val size = File(path).length()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(this@MediaSendPreviewActivity,
                if (night) WaSpec.BUBBLE_RECEIVED_NIGHT else WaSpec.GRAY6, 12)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            addView(TextView(this@MediaSendPreviewActivity).apply {
                text = "📄"; textSize = 30f
            })
            addView(LinearLayout(this@MediaSendPreviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MediaSendPreviewActivity).apply {
                    text = fileName
                    textSize = 15f
                    setTextColor(WaSpec.textPrimary(night))
                    maxLines = 2
                    setTypeface(typeface, Typeface.NORMAL)
                })
                addView(TextView(this@MediaSendPreviewActivity).apply {
                    text = humanSize(size)
                    textSize = 12.5f
                    setTextColor(WaSpec.textSecondary(night))
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) })
        }
    }

    private fun humanSize(b: Long): String = when {
        b >= 1 shl 20 -> String.format("%.1f MB", b / 1048576.0)
        b >= 1 shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun doSend() {
        if (sending) return
        val jid = intent.getStringExtra("jid") ?: ""
        if (jid.isBlank()) { Toast.makeText(this, "缺少会话", Toast.LENGTH_SHORT).show(); finish(); return }
        sending = true
        sendBtn.isEnabled = false
        sendBtn.text = "发送中…"
        val caption = captionInput.text.toString().trim()
        val engine = ChatEngine.get(this)
        val done: (String?) -> Unit = { err ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sending = false
                sendBtn.isEnabled = true
                sendBtn.text = "发送"
                if (err != null) Toast.makeText(this, "发送失败：$err", Toast.LENGTH_LONG).show()
                else { setResult(RESULT_OK); finish() }
            }
        }
        when (kind) {
            "image" -> engine.sendImageAsync(jid, path, caption) { _, err -> done(err) }
            // P4：视频发送（协议 SendVideoFromPath；whatsmeow MediaVideo 上传）
            "video" -> engine.sendVideoAsync(jid, path, caption) { _, err -> done(err) }
            "sticker" -> engine.sendStickerAsync(jid, path) { _, err -> done(err) }
            else -> engine.sendDocumentAsync(jid, path, fileName, caption) { _, err -> done(err) }
        }
    }
}
