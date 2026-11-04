package com.weifan.app.chat

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * H.264 Annex-B 解码器（显示对端视频）。
 *
 * meowcaller 的 VideoSink 给的是 **Annex-B 裸流**（4 字节起始码 + NAL 单元），
 * 所以不能用 MediaExtractor，必须自己：
 *   1. 按 00 00 00 01 / 00 00 01 切 NAL
 *   2. 把含 SPS(PPS 的 NAL 送 codec 配置（configure 或 csd-0）
 *   3. 其余 NAL 逐帧 queueInputBuffer → dequeueOutputBuffer → releaseOutputBuffer(render=true)
 *
 * 为什么用 TextureView：SurfaceView 在模拟器截图里是全黑的，无法自证；
 * TextureView 是普通 View，能被合成层捕获（这个取舍在视频查看器那轮已经踩过）。
 *
 * 限制：只做解码渲染，不做编码（发送侧摄像头要单独用 MediaCodec 编码器，本轮未做）。
 */
class H264Decoder {

    private companion object { const val TAG = "WeFlipVideo" }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    @Volatile private var running = false
    private var pendingSpsPps: ByteArray? = null

    /** 挂上 TextureView；surface 就绪后才能 configure codec */
    fun attach(tv: TextureView) {
        if (tv.isAvailable) {
            val st = tv.surfaceTexture
            if (st != null) start(android.view.Surface(st))
        } else {
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    start(android.view.Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
        }
    }

    private fun start(s: Surface) {
        surface = s
        running = true
        // 宽高要从 SPS 解析，先把 configure 推迟到第一帧时用 csd 完成
    }

    /** 解析 SPS 拿到宽高（简化版：从 SPS NAL 里的 exp-golomb 读） */
    private fun parseSize(sps: ByteArray): Pair<Int, Int>? {
        return try {
        // 跳过起始码与 nal header
        var i = 0
        while (i + 3 < sps.size && !(sps[i] == 0.toByte() && sps[i + 1] == 0.toByte() &&
                (sps[i + 2] == 1.toByte() || (sps[i + 2] == 0.toByte() && sps[i + 3] == 1.toByte())))
            ) i++
        i += if (sps[i + 2] == 1.toByte()) 3 else 4
        if (i >= sps.size) return null
        i += 1   // nal header
        // profile_idc, constraint flags, level_idc, seq_parameter_set_id
        i += 3
        val bits = BitReader(sps, i)
        bits.readUE()   // seq_parameter_set_id
        val profile = sps[1].toInt() and 0xff
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 ||
            profile == 44 || profile == 83 || profile == 86 || profile == 118 ||
            profile == 128 || profile == 138 || profile == 139 || profile == 134
        ) {
            val chroma = bits.readUE()
            if (chroma == 3) bits.skip(1)
            bits.readUE(); bits.readUE(); bits.skip(1)
            if (bits.readFlag()) {
                for (k in 0 until (if (chroma != 3) 8 else 12)) {
                    if (bits.readFlag()) {
                        var last = 8; var next = 8
                        for (j in 0 until (if (k < 6) 16 else 64)) {
                            if (next != 0) next = (last + bits.readSE() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        bits.readUE()   // log2_max_frame_num
        val pocType = bits.readUE()
        if (pocType == 0) bits.readUE()
        else if (pocType == 1) {
            bits.skip(1); bits.readSE(); bits.readSE()
            val n = bits.readUE()
            for (k in 0 until n) bits.readSE()
        }
        bits.readUE(); bits.skip(1)
        val wMbs = bits.readUE() + 1
        val hMapUnits = bits.readUE() + 1
        val frameMbsOnly = bits.readFlag()
        if (!frameMbsOnly) bits.skip(1)
        bits.skip(1)
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (bits.readFlag()) {
            cropL = bits.readUE(); cropR = bits.readUE(); cropT = bits.readUE(); cropB = bits.readUE()
        }
        val width = wMbs * 16 - (cropL + cropR) * 2
        val height = (if (frameMbsOnly) 2 else 1) * hMapUnits * 16 - (cropT + cropB) * 2 * (if (frameMbsOnly) 1 else 2)
            if (width in 16..4096 && height in 16..4096) width to height else null
        } catch (_: Throwable) { null }
    }

    /** 把 Annex-B 数据按 NAL 切开并送进解码器 */
    fun feed(data: ByteArray) {
        if (!running) return
        try {
            ensureCodec(data)
            val c = codec ?: return
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(inIdx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            drain()
        } catch (e: Throwable) {
            Log.w(TAG, "feed: ${e.message}")
        }
    }

    private fun ensureCodec(data: ByteArray) {
        if (codec != null) return
        val s = surface ?: return
        // 抓 SPS/PPS（NAL type 7/8）用于 configure
        val nal = splitNal(data)
        val sps = nal.firstOrNull { (it[0].toInt() and 0x1f) == 7 }
        val pps = nal.firstOrNull { (it[0].toInt() and 0x1f) == 8 }
        if (sps != null) parseSize(sps)?.let { (w, h) -> width = w; height = h }
        if (width <= 0 || height <= 0) { width = 640; height = 360 }

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        if (sps != null) {
            val csd = java.nio.ByteBuffer.allocate(sps.size + (pps?.size ?: 0))
            csd.put(sps); pps?.let { csd.put(it) }
            csd.position(0)
            fmt.setByteBuffer("csd-0", csd)
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, s, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "AVC decoder started ${width}x$height")
    }

    /** 按 00 00 00 01 / 00 00 01 切 NAL（返回不含起始码的 NAL） */
    private fun splitNal(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) { starts.add(i + 3); i += 3; continue }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i + 4); i += 4; continue
                }
            }
            i++
        }
        val out = mutableListOf<ByteArray>()
        for ((k, s) in starts.withIndex()) {
            val e = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
            if (e > s) out.add(data.copyOfRange(s, e.coerceAtMost(data.size)))
        }
        return out
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)   // render=true 直接上屏
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                Log.i(TAG, "output ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}")
            } else break
        }
    }

    fun release() {
        running = false
        try { codec?.stop() } catch (_: Throwable) { }
        try { codec?.release() } catch (_: Throwable) { }
        codec = null
        try { surface?.release() } catch (_: Throwable) { }
        surface = null
    }
}

/** exp-golomb 位读取（SPS 解析用） */
private class BitReader(private val data: ByteArray, startByte: Int) {
    private var bitPos = startByte * 8
    fun readFlag(): Boolean = read(1) == 1
    fun skip(n: Int) { bitPos += n }
    fun read(n: Int): Int {
        var v = 0
        for (k in 0 until n) {
            val b = (data[bitPos / 8].toInt() shr (7 - bitPos % 8)) and 1
            v = (v shl 1) or b
            bitPos++
        }
        return v
    }
    fun readUE(): Int {
        var zeros = 0
        while (bitPos / 8 < data.size && read(1) == 0) zeros++
        if (zeros == 0) return 0
        return (1 shl zeros) - 1 + read(zeros)
    }
    fun readSE(): Int {
        val ue = readUE()
        return if (ue % 2 == 1) (ue + 1) / 2 else -(ue / 2)
    }
}