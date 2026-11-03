package com.weifan.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 独立 PDF 查看器（对标参考 PdfViewerActivity）。
 *
 * 为什么要单独一页：之前 PDF 只能走通用 viewer 显示一个图标，等于发出去的文件自己打不开。
 * 跨境业务里报价单、PI、装箱单全是 PDF，这一页是刚需。
 *
 * 实现取舍：
 *  - 用系统自带的 android.graphics.pdf.PdfRenderer，不引第三方库（本项目刻意零依赖）
 *  - 一次只渲染一页并只持有一张 Bitmap：PdfRenderer 官方限制同一时刻只能开一页，
 *    而且多页 ARGB_8888 会迅速吃掉几百 MB（3.0.0 曾被 lmkd 击杀，这条教训不能忘）
 *  - 渲染在单线程后台串行做，带序号防竞态（快速翻页时旧结果直接丢弃）
 *  - 上下滑动 / 点按钮翻页，页码实时显示
 */
class PdfViewerActivity : BaseActivity() {

    private var path: String = ""
    private var title: String = ""
    private lateinit var imageView: ImageView
    private lateinit var pageText: TextView
    private lateinit var hintText: TextView

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var current = 0

    /** 渲染序号：只有最新一次请求的结果才允许上屏 */
    private val renderSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val lock = Any()
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra("path") ?: ""
        title = intent.getStringExtra("name") ?: File(path).name

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(imageView)

        // 顶部：返回 + 文件名
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(10))
            setBackgroundColor(0x99000000.toInt())
        }
        top.addView(TextView(this).apply {
            text = "‹"; textSize = 30f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        })

        // 底部：翻页
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
            setBackgroundColor(0x99000000.toInt())
        }
        bottom.addView(navButton("上一页") { go(-1) })
        pageText = TextView(this).apply {
            text = "—"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottom.addView(pageText)
        bottom.addView(navButton("下一页") { go(1) })
        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        hintText = TextView(this).apply {
            text = "正在打开…"
            textSize = 14f
            setTextColor(0xFFB0B8C4.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(hintText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        setContentView(root)
        open()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun navButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = Ui.roundBg(this@PdfViewerActivity, 0x33FFFFFF, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun open() {
        val f = File(path)
        if (!f.exists()) {
            hintText.text = "文件不存在"
            return
        }
        Thread {
            try {
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(d)
                pfd = d
                renderer = r
                pageCount = r.pageCount
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    hintText.visibility = View.GONE
                    if (pageCount == 0) {
                        hintText.visibility = View.VISIBLE
                        hintText.text = "这个 PDF 没有页面"
                    } else renderPage(0)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    hintText.visibility = View.VISIBLE
                    // 加密 / 损坏都会走到这儿，说清楚原因别让用户以为是应用坏了
                    val msg = e.message ?: e.toString()
                    hintText.text = if (msg.contains("password", true))
                        "这个 PDF 有打开密码，无法预览"
                    else "无法打开 PDF\n$msg"
                }
            }
        }.start()
    }

    private fun go(delta: Int) {
        val next = current + delta
        if (next < 0 || next >= pageCount) return
        renderPage(next)
    }

    /** 渲染指定页：串行后台渲染，只有最新序号的结果上屏 */
    private fun renderPage(index: Int) {
        current = index
        pageText.text = "${index + 1} / $pageCount"
        val seq = renderSeq.incrementAndGet()
        if (worker?.isAlive == true) return   // 上一张还在渲染，等它回来看序号丢弃
        worker = Thread {
            val bmp = renderBitmap(index)
            runOnUiThread {
                if (isFinishing || isDestroyed) { bmp?.recycle(); return@runOnUiThread }
                // 期间又翻了页 → 丢弃这次结果，并补渲染最新页
                if (seq != renderSeq.get()) {
                    bmp?.recycle()
                    if (current != index) renderPage(current)
                    return@runOnUiThread
                }
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    hintText.visibility = View.GONE
                } else {
                    hintText.visibility = View.VISIBLE
                    hintText.text = "这一页渲染失败"
                }
            }
        }.also { it.start() }
    }

    /** 单页渲染；宽度按屏幕宽降采样，避免超大页面吃爆内存 */
    private fun renderBitmap(index: Int): Bitmap? = synchronized(lock) {
        val r = renderer ?: return null
        return try {
            r.openPage(index).use { page ->
                val screenW = resources.displayMetrics.widthPixels
                // 页面尺寸单位是 1/72 英寸，按「适配屏宽」算缩放，最多 2 倍避免超大图
                var scale = screenW.toFloat() / page.width.toFloat()
                if (scale > 2f) scale = 2f
                if (scale <= 0f) scale = 1f
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                // PDF 页面默认是白纸；不填白底的话透明区域在黑背景上就是全黑（实机截图踩过）
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        } catch (_: Throwable) { null }
    }

    override fun onDestroy() {
        try { renderer?.close() } catch (_: Throwable) { }
        try { pfd?.close() } catch (_: Throwable) { }
        renderer = null
        pfd = null
        super.onDestroy()
    }
}
