package com.weifan.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.weifan.app.chat.ChatEngine
import com.weifan.app.core.MediaSaver

/**
 * 全屏媒体查看器（对齐 WhatsApp Web 点九宫格后的体验）：
 *
 *   黑底全屏 + 当前媒体大图 + 底部信息条（类型/文件名/大小/时间）
 *   左右滑切换上一个/下一个媒体（同一会话的全部媒体，按时间序）
 *   图片：本地已下载直接显示；未下载点按经代理下载后自动替换
 *   音频/文档：居中大图标 + 信息（音频提供播放按钮）
 */
class MediaViewerActivity : BaseActivity() {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var chatJid: String = ""
    private var list: List<com.weifan.app.data.MessageRow> = emptyList()
    private var pos = 0

    private lateinit var imageView: ImageView
    /** 当前图片的本地路径（长按保存到相册用；下载完成后再赋值） */
    private var currentPath: String? = null
    private lateinit var iconText: TextView
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatJid = intent.getStringExtra("jid") ?: ""
        val startId = intent.getStringExtra("msgId") ?: ""
        list = ChatEngine.get(this).db.messages(chatJid).filter { it.mediaKind.isNotBlank() }
            .sortedBy { it.ts }
        pos = list.indexOfFirst { it.id == startId }.let { if (it < 0) 0 else it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        root.addView(imageView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 非图类居中图标
        iconText = TextView(this).apply {
            textSize = 72f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(iconText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 信息条
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        infoText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        bottom.addView(infoText)
        hintText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFB0B8C4.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        bottom.addView(hintText)
        root.addView(bottom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 点击图片：未下载则下载；已下载弹隐藏（黑屏点击返回）
        imageView.isClickable = true
        imageView.setOnClickListener { finish() }
        var downX = 0f
        var isSwipe = false
        imageView.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; isSwipe = false; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.x - downX) > dp(40)) isSwipe = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    when {
                        isSwipe && dx < 0 && pos < list.size - 1 -> { pos++; render() }
                        isSwipe && dx > 0 && pos > 0 -> { pos--; render() }
                        !isSwipe -> v.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        // 长按保存到系统相册（对齐参考 media_download + MediaStore）
        imageView.setOnLongClickListener {
            val p = currentPath
            if (p.isNullOrBlank()) {
                Toast.makeText(this, "图片还没下载完", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "正在保存…", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = MediaSaver.saveToGallery(this, java.io.File(p))
                    runOnUiThread {
                        Toast.makeText(this,
                            if (ok) "已保存到相册 / WeFlip" else "保存失败",
                            Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            true
        }
        setContentView(root)
        render()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun render() {
        val m = list.getOrNull(pos) ?: return
        val kindLabel = when (m.mediaKind) {
            "image" -> "图片"; "sticker" -> "贴纸"; "video" -> "视频"
            "audio" -> "语音"; else -> "文件"
        }
        val size = when {
            m.mediaSize >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1f MB", m.mediaSize / 1048576.0)
            m.mediaSize >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", m.mediaSize / 1024.0)
            m.mediaSize > 0 -> "${m.mediaSize}B"
            else -> ""
        }
        val time = java.text.SimpleDateFormat("yyyy-M-d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.ts))
        infoText.text = listOf(
            m.mediaName.ifBlank { kindLabel }, size, "$time", "${pos + 1}/${list.size}"
        ).filter { it.isNotBlank() }.joinToString(" · ")

        when (m.mediaKind) {
            "image", "sticker", "video" -> {
                iconText.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                showImage(m)
            }
            else -> {
                imageView.visibility = View.GONE
                iconText.visibility = View.VISIBLE
                iconText.text = if (m.mediaKind == "audio") "🎧" else "📄"
                hintText.text = if (m.mediaKind == "audio") "点击下方信息无法播放，回会话页可播放" else "文档在会话页点按下载"
            }
        }
    }

    private fun showImage(m: com.weifan.app.data.MessageRow) {
        // ANR 修复：整段解码移到后台线程，主线程只贴图/提示
        fun applyBmp(bmp: android.graphics.Bitmap?) {
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                hintText.text = "左右滑动切换 · 点击图片返回"
            }
        }
        val f = if (m.mediaPath.isNotBlank()) java.io.File(m.mediaPath) else null
        currentPath = f?.takeIf { it.exists() }?.absolutePath
        // 全屏查看：降采样到屏幕宽度即可（原图常为 4000px 级，全量解码曾撑到 1.4GB RSS）
        val target = resources.displayMetrics.widthPixels
        fun sampleFile(p: String): android.graphics.Bitmap? = try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p, opt)
            var sm = 1
            while (opt.outWidth / (sm * 2) >= target && opt.outHeight / (sm * 2) >= target / 2) sm *= 2
            BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sm })
        } catch (_: Throwable) { null }
        if (f != null && f.exists()) {
            Thread {
                try {
                    val bmp = sampleFile(f.absolutePath)
                    main.post {
                        if (isDestroyed || isFinishing) return@post
                        applyBmp(bmp)
                    }
                } catch (_: Throwable) { }
            }.start()
        } else {
            // 未下载：先用自带缩略图占位（base64 小图），后台下载原图替换
            try {
                val tb = android.util.Base64.decode(m.mediaThumb, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(tb, 0, tb.size)?.let { imageView.setImageBitmap(it) }
            } catch (_: Throwable) { }
            hintText.text = "正在下载原图…"
            ChatEngine.get(this).fetchMediaAsync(chatJid, m.id) { done ->
                main.post {
                    if (isDestroyed || isFinishing) return@post
                    if (done == null) {
                        hintText.text = "下载失败，点按图片重试"
                        return@post
                    }
                    Thread {
                        try {
                            val nb = sampleFile(done.absolutePath)
                            main.post {
                                if (isDestroyed || isFinishing) return@post
                                applyBmp(nb)
                            }
                        } catch (_: Throwable) { }
                    }.start()
                }
            }
        }
    }
}
