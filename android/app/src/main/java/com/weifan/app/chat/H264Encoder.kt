package com.weifan.app.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 本地摄像头 → H.264 编码 → meowcaller.SendVideo（视频通话发送侧）。
 *
 * 链路：Camera2 预览请求 → encoder.createInputSurface()（作为 output target）
 *      → MediaCodec 异步编码 → 输出 AVCC（4 字节长度前缀）→ 转 Annex-B（加起始码）
 *      → engine.sendVideoFrame(bytes) → Go call.SendVideo
 *
 * 为什么用 Camera2 而不是 CameraX：项目坚持零 AndroidX 依赖（build.gradle 只有 aar 一个依赖）。
 * 代价是代码长一些，但不用引整个 androidx.camera。
 *
 * 关键坑：MediaCodec 编码器输出的 SPS/PPS 在 outputFormat 的 csd-0/csd-1 里，
 * 必须**加起始码后先发一次**，否则对端解不出画面（只有 I 帧数据没有参数集）。
 * 另外请求关键帧用 PARAMETER_KEY_REQUEST_SYNC_FRAME，否则重连后长时间黑屏。
 */
class H264Encoder(
    private val ctx: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    private companion object {
        const val TAG = "WeFlipVideo"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 24
        const val BITRATE = 800_000
        const val IFRAME_INTERVAL = 2
    }

    private var codec: MediaCodec? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var inputSurface: Surface? = null
    /** 可选：本地自画面输出面（Camera2 多输出面，同一次捕获同时喂编码器与预览） */
    var previewSurface: Surface? = null
    @Volatile private var running = false
    private var headerSent = false

    /** 启动：需要 CAMERA 权限已授予 */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("没有相机权限")
            return
        }
        running = true
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
                // 低延迟：baseline + 不开 B 帧
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
            codec = c

            thread = HandlerThread("cam").also { it.start() }
            handler = Handler(thread!!.looper)

            // 编码器输出循环
            Thread { drainLoop() }.start()
            openCamera()
        } catch (e: Throwable) {
            onError(e.message ?: e.toString())
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // 优先前置（视频通话自拍），没有再退后置
        val ids = cm.cameraIdList
        val front = ids.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        }
        val id = front ?: ids.firstOrNull() ?: run { onError("没有可用摄像头"); return }
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                createSession(d)
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, error: Int) {
                onError("相机错误 $error"); d.close(); device = null
            }
        }, handler)
    }

    private fun createSession(d: CameraDevice) {
        val surf = inputSurface ?: return
        try {
            // 自画面（PIP TextureView）与编码器输入面一起挂进同一次捕获：
            // 相机帧同时到达两个面——编码器发给对端，预览给用户看自己
            val outputs = if (previewSurface != null) listOf(surf, previewSurface!!) else listOf(surf)
            @Suppress("DEPRECATION")
            d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surf)
                        if (previewSurface != null) addTarget(previewSurface!!)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    s.setRepeatingRequest(req, null, handler)
                    requestKeyFrame()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("相机会话配置失败")
                }
            }, handler)
        } catch (e: Throwable) {
            onError(e.message ?: e.toString())
        }
    }

    /** 请求一个关键帧（重连/首次必须，否则对端长时间黑屏） */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Throwable) { }
    }

    /** 编码器输出：AVCC → Annex-B */
    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        // 参数集（codec config）先发一次
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendAnnexB(data)
                            headerSent = true
                        } else {
                            sendAnnexB(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                Log.d(TAG, "key frame ${info.size}B")
                            }
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    // csd-0 = SPS, csd-1 = PPS
                    for (k in listOf("csd-0", "csd-1")) {
                        val csd = f.getByteBuffer(k) ?: continue
                        val a = ByteArray(csd.remaining())
                        csd.get(a)
                        sendAnnexB(a)
                    }
                    headerSent = true
                }
            } catch (e: Throwable) {
                if (running) Log.w(TAG, "drain: ${e.message}")
                break
            }
        }
    }

    /** AVCC（4 字节长度前缀）转 Annex-B（00 00 00 01 起始码） */
    private fun sendAnnexB(data: ByteArray) {
        if (data.size < 4) return
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var i = 0
        var found = false
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xff) shl 24) or
                ((data[i + 1].toInt() and 0xff) shl 16) or
                ((data[i + 2].toInt() and 0xff) shl 8) or
                (data[i + 3].toInt() and 0xff)
            if (len <= 0 || i + 4 + len > data.size) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(data, i + 4, len)
            i += 4 + len
            found = true
        }
        // 已经是 Annex-B 形态（首字节 00 00 00 01）就原样发
        val payload = if (found) out.toByteArray() else {
            if (data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte()) data
            else byteArrayOf(0, 0, 0, 1) + data
        }
        if (payload.isNotEmpty()) onFrame(payload)
    }

    fun stop() {
        running = false
        try { session?.stopRepeating(); session?.close() } catch (_: Throwable) { }
        session = null
        try { device?.close() } catch (_: Throwable) { }
        device = null
        try { codec?.stop(); codec?.release() } catch (_: Throwable) { }
        codec = null
        try { inputSurface?.release() } catch (_: Throwable) { }
        inputSurface = null
        try { thread?.quitSafely() } catch (_: Throwable) { }
        thread = null
        handler = null
    }
}