package com.weifan.app

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * 独立视频查看器（对标参考 VideoViewerActivity）。
 *
 * 为什么用 TextureView + MediaPlayer 而不是 VideoView：
 *  - TextureView 是普通 View，能被合成层捕获（截图/录屏都能看到真实画面），
 *    可验证性比 SurfaceView 强得多 —— 后者在模拟器上抓出来常是全黑，无法自证
 *  - 自己算等比缩放，避免不同比例视频被拉伸
 *
 * 交互：点画面暂停/继续，底部自带进度与时间；切后台自动暂停；保持屏幕常亮。
 */
class VideoViewerActivity : BaseActivity() {

    private var path = ""
    private var title = ""
    private lateinit var texture: TextureView
    private lateinit var hint: TextView
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var timeText: TextView

    private var player: MediaPlayer? = null
    private var prepared = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            val p = player ?: return
            try {
                val pos = p.currentPosition
                val dur = p.duration.coerceAtLeast(1)
                progress.progress = (pos * 1000 / dur)
                timeText.text = "${fmt(pos)} / ${fmt(dur)}"
            } catch (_: Throwable) { }
            ticker.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra("path") ?: ""
        title = intent.getStringExtra("name") ?: File(path).name
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        texture = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(texture)

        hint = TextView(this).apply {
            text = "正在加载视频…"
            textSize = 14f
            setTextColor(0xFFB0B8C4.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // 顶部标题
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

        // 底部进度条 + 时间
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(18))
            setBackgroundColor(0x99000000.toInt())
        }
        progress = android.widget.ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(WaSpec.GREEN_LIGHT)
        }
        bottom.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        timeText = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        bottom.addView(timeText)
        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })

        setContentView(root)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = start(Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // 点画面暂停/继续
        texture.setOnClickListener { togglePlay() }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun start(surface: Surface) {
        val f = File(path)
        if (!f.exists()) { hint.text = "文件不存在或不在这台设备上"; return }
        try {
            val p = MediaPlayer()
            p.setSurface(surface)
            p.setDataSource(f.absolutePath)
            p.setOnPreparedListener { mp ->
                prepared = true
                hint.visibility = View.GONE
                fitToView(mp.videoWidth, mp.videoHeight)
                mp.isLooping = true      // 短视频循环播放，便于查看细节
                mp.start()
                ticker.post(updater)
            }
            p.setOnErrorListener { _, what, extra ->
                hint.visibility = View.VISIBLE
                hint.text = "无法播放这个视频（$what/$extra）"
                true
            }
            p.prepareAsync()
            player = p
        } catch (e: Throwable) {
            hint.visibility = View.VISIBLE
            hint.text = "无法播放：${e.message ?: e}"
        }
    }

    /**
     * 等比适配。
     * 不用 setTransform（TextureView 的矩阵作用在「view 坐标→纹理」上，
     * 直接 setScale 会把画面拉变形，实机截图验过）；改成给 TextureView 算出
     * 与视频同比例的宽高并居中，默认拉伸填满即等于等比显示。
     */
    private fun fitToView(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        val metrics = resources.displayMetrics
        val availW = metrics.widthPixels
        // 上下留出标题条与进度条区域，避免视频被盖住
        val availH = (metrics.heightPixels * 0.82f).toInt()
        val s = minOf(availW.toFloat() / vw, availH.toFloat() / vh)
        val w = (vw * s).toInt().coerceAtLeast(1)
        val h = (vh * s).toInt().coerceAtLeast(1)
        texture.post {
            if (isFinishing || isDestroyed) return@post
            val lp = texture.layoutParams as FrameLayout.LayoutParams
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            texture.layoutParams = lp
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (!prepared) return
        try {
            if (p.isPlaying) p.pause() else p.start()
        } catch (_: Throwable) { }
    }

    override fun onPause() {
        super.onPause()
        try { if (player?.isPlaying == true) player?.pause() } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Throwable) { }
        player = null
        super.onDestroy()
    }
}
