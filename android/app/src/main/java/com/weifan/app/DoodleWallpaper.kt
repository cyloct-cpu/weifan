package com.weifan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * WhatsApp 风格涂鸦壁纸：Canvas 手绘线稿涂鸦平铺成 Bitmap，缓存复用。
 * 参考 WhatsApp 经典 doodle 背景：米色底 + 同色系深一线的简笔小物件。
 */
object DoodleWallpaper {

    private var cached: Bitmap? = null
    private var cachedDark: Bitmap? = null

    fun background(context: Context, dark: Boolean): Bitmap {
        val existing = if (dark) cachedDark else cached
        if (existing != null) return existing
        val bmp = render(context, dark)
        if (dark) cachedDark = bmp else cached = bmp
        return bmp
    }

    fun applyTo(view: View, dark: Boolean) {
        view.background = BitmapDrawable(view.resources, background(view.context, dark))
    }

    private fun render(context: Context, dark: Boolean): Bitmap {
        val size = 720
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = if (dark) Color.parseColor("#0B141A") else Color.parseColor("#EFE5DB")
        val ink = if (dark) Color.parseColor("#182229") else Color.parseColor("#E3D8CC")
        canvas.drawColor(bg)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 3.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // 涂鸦图元：每个是一个 (路径指令) 的函数，画在 100x100 的局部坐标系
        val doodles: List<(Canvas, Paint) -> Unit> = listOf(
            ::doodleHeart, ::doodleStar, ::doodleCup, ::doodlePlane, ::doodleNote,
            ::doodleCamera, ::doodleGift, ::doodleUmbrella, ::doodleGlasses, ::doodleClock,
            ::doodleBasket, ::doodleBolt)

        val rand = java.util.Random(20261031L) // 固定种子：每次进入页面图案一致
        val cols = 4
        val cell = size / cols
        for (row in 0 until cols) for (col in 0 until cols) {
            val doodle = doodles[rand.nextInt(doodles.size)]
            val scale = 0.9f + rand.nextFloat() * 0.5f
            val rot = rand.nextInt(24) - 12
            canvas.save()
            canvas.translate(col * cell + cell / 2f, row * cell + cell / 2f)
            canvas.rotate(rot.toFloat())
            canvas.scale(scale * cell / 100f, scale * cell / 100f)
            canvas.translate(-50f, -50f)
            doodle(canvas, paint)
            canvas.restore()
        }
        return bmp
    }

    // ---- 图元（100x100 局部坐标）----

    private fun doodleHeart(c: Canvas, p: Paint) {
        val path = Path().apply {
            moveTo(50f, 82f)
            cubicTo(10f, 52f, 16f, 18f, 50f, 34f)
            cubicTo(84f, 18f, 90f, 52f, 50f, 82f)
            close()
        }
        c.drawPath(path, p)
    }

    private fun doodleStar(c: Canvas, p: Paint) {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 38f else 16f
            val a = Math.PI * i / 5 - Math.PI / 2
            val x = 50 + r * cos(a).toFloat()
            val y = 52 + r * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun doodleCup(c: Canvas, p: Paint) {
        c.drawLine(24f, 36f, 30f, 80f, p)
        c.drawLine(76f, 36f, 70f, 80f, p)
        c.drawLine(24f, 36f, 76f, 36f, p)
        c.drawLine(30f, 80f, 70f, 80f, p)
        c.drawLine(76f, 44f, 88f, 46f, p)
        c.drawLine(88f, 46f, 84f, 62f, p)
        c.drawLine(84f, 62f, 73f, 60f, p)
        c.drawArc(38f, 14f, 50f, 30f, 0f, 180f, false, p)
        c.drawArc(52f, 12f, 64f, 28f, 0f, 180f, false, p)
    }

    private fun doodlePlane(c: Canvas, p: Paint) {
        val path = Path().apply {
            moveTo(12f, 48f); lineTo(88f, 18f); lineTo(56f, 84f); lineTo(44f, 56f); close()
        }
        c.drawPath(path, p)
        c.drawLine(44f, 56f, 88f, 18f, p)
    }

    private fun doodleNote(c: Canvas, p: Paint) {
        c.drawLine(40f, 74f, 40f, 24f, p)
        c.drawCircle(32f, 76f, 10f, p)
        c.drawLine(70f, 66f, 70f, 16f, p)
        c.drawCircle(62f, 68f, 10f, p)
        c.drawLine(40f, 24f, 70f, 16f, p)
    }

    private fun doodleCamera(c: Canvas, p: Paint) {
        c.drawRoundRect(12f, 34f, 88f, 78f, 8f, 8f, p)
        c.drawCircle(50f, 56f, 15f, p)
        c.drawCircle(50f, 56f, 7f, p)
        c.drawRoundRect(34f, 24f, 52f, 34f, 3f, 3f, p)
        c.drawPoint(80f, 42f, p)
    }

    private fun doodleGift(c: Canvas, p: Paint) {
        c.drawRoundRect(20f, 40f, 80f, 84f, 4f, 4f, p)
        c.drawLine(20f, 54f, 80f, 54f, p)
        c.drawLine(50f, 40f, 50f, 84f, p)
        c.drawCircle(40f, 32f, 9f, p)
        c.drawCircle(60f, 32f, 9f, p)
        c.drawLine(50f, 40f, 44f, 30f, p)
        c.drawLine(50f, 40f, 56f, 30f, p)
    }

    private fun doodleUmbrella(c: Canvas, p: Paint) {
        c.drawArc(14f, 26f, 86f, 76f, 180f, 180f, false, p)
        c.drawLine(50f, 26f, 50f, 80f, p)
        c.drawArc(42f, 74f, 58f, 90f, 0f, 180f, false, p)
        for (i in 0..3) c.drawLine(50f, 26f, 18f + i * 21f, 51f, p)
    }

    private fun doodleGlasses(c: Canvas, p: Paint) {
        c.drawCircle(28f, 50f, 15f, p)
        c.drawCircle(72f, 50f, 15f, p)
        c.drawLine(43f, 50f, 57f, 50f, p)
        c.drawLine(13f, 50f, 4f, 44f, p)
        c.drawLine(87f, 50f, 96f, 44f, p)
    }

    private fun doodleClock(c: Canvas, p: Paint) {
        c.drawCircle(50f, 52f, 34f, p)
        c.drawLine(50f, 52f, 50f, 30f, p)
        c.drawLine(50f, 52f, 66f, 58f, p)
        c.drawLine(26f, 20f, 34f, 12f, p)
        c.drawLine(74f, 20f, 66f, 12f, p)
    }

    private fun doodleBasket(c: Canvas, p: Paint) {
        c.drawLine(20f, 42f, 28f, 82f, p)
        c.drawLine(80f, 42f, 72f, 82f, p)
        c.drawLine(28f, 82f, 72f, 82f, p)
        c.drawLine(20f, 42f, 80f, 42f, p)
        c.drawArc(28f, 14f, 72f, 54f, 180f, 180f, false, p)
        c.drawLine(36f, 42f, 40f, 82f, p)
        c.drawLine(64f, 42f, 60f, 82f, p)
    }

    private fun doodleBolt(c: Canvas, p: Paint) {
        val path = Path().apply {
            moveTo(56f, 12f); lineTo(30f, 52f); lineTo(46f, 52f)
            lineTo(40f, 88f); lineTo(70f, 44f); lineTo(54f, 44f); close()
        }
        c.drawPath(path, p)
    }
}
