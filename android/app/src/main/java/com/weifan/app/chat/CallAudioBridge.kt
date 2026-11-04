package com.weifan.app.chat

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * 通话音频桥（Kotlin 侧）。
 *
 * 分工：
 *   采集 → AudioRecord(16k mono PCM16) → client.feedMicPcm(bytes) → Go 侧 PCMStream → call.Play
 *   播放 ← client 回调 OnAudioFrame(bytes) ← Go 侧 SinkFunc ← call.Receive
 *
 * 为什么是 16kHz 单声道：meowcaller 的 AudioSource/AudioSink 固定为 16 kHz mono s16le
 * （MLOW 编解码基频），采样率对不上会听到变调或杂音。
 *
 * 线程：采集循环在独立线程跑，播放走 MODE_STREAM 写队列。
 * 必须成对 start/stop，否则 AudioRecord 会一直占着麦克风。
 */
class CallAudioBridge(
    private val engine: ChatEngine,
    private val onError: (String) -> Unit = {}
) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    /** 开始采集与播放。需已接听或已发起通话 */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        try {
            // 播放器：16k mono PCM16 流式
            val outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
                .coerceAtLeast(4096)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build())
                .setBufferSizeInBytes(outBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play() }

            // 录音器
            val inBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
                .coerceAtLeast(4096)
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_IN)
                        .build())
                .setBufferSizeInBytes(inBuf * 2)
                .build()

            running = true
            record?.startRecording()
            engine.attachAudioSink { bytes -> track?.write(bytes, 0, bytes.size) }

            captureThread = Thread {
                val buf = ByteArray(640)   // 20ms @16k mono s16 = 640 字节
                while (running) {
                    val n = record?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) {
                        // 截取实际长度再喂给 Go，避免把脏数据也发出去
                        engine.feedMicPcm(if (n == buf.size) buf else buf.copyOf(n))
                    } else if (n < 0) {
                        break
                    }
                }
            }.also { it.start() }
        } catch (e: Throwable) {
            onError(e.message ?: e.toString())
            stop()
        }
    }

    /** 停止并释放 */
    fun stop() {
        running = false
        try { captureThread?.join(500) } catch (_: Throwable) { }
        captureThread = null
        try { record?.stop(); record?.release() } catch (_: Throwable) { }
        record = null
        try { track?.stop(); track?.release() } catch (_: Throwable) { }
        track = null
        engine.attachAudioSink(null)
        engine.stopAudioEngine()
    }
}