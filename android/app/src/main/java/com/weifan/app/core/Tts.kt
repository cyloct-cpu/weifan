package com.weifan.app.core

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 译文朗读：语言代码 → BCP-47 朗读标签 + 系统语音合成封装。
 *
 * 与扩展侧 src/core/tts.js **同规格**（同一张映射表，同一条边界）—— 改一边两边同改。
 * 引擎是系统内置的 TextToSpeech，不联网、不花钱，符合「额度保护」哲学。
 */
object Tts {

    /** 语言代码 → BCP-47 标签（与 extension/src/core/tts.js 同表） */
    private val TAGS = mapOf(
        "zh-Hans" to "zh-CN",
        "zh-Hant" to "zh-TW",
        "en" to "en-US",
        "es" to "es-ES",
        "pt" to "pt-PT",
        "fr" to "fr-FR",
        "de" to "de-DE",
        "it" to "it-IT",
        "nl" to "nl-NL",
        "ru" to "ru-RU",
        "ar" to "ar-SA",
        "tr" to "tr-TR",
        "vi" to "vi-VN",
        "th" to "th-TH",
        "id" to "id-ID",
        "ms" to "ms-MY",
        "hi" to "hi-IN",
        "ja" to "ja-JP",
        "ko" to "ko-KR",
        "pl" to "pl-PL",
        "he" to "he-IL",
        "fil" to "fil-PH"
    )

    /** 该语言的朗读标签；没有映射返回 null（调用方置灰 / 提示，别硬读） */
    fun tag(code: String?): String? {
        val key = code?.trim() ?: return null
        return TAGS[key]
    }

    /**
     * 一个页面的朗读器：初始化引擎是异步的，页面持有它，onDestroy 里 shutdown。
     */
    class Speaker(context: Context) {

        /** TextToSpeech 的回调线程与主线程不同，用 @Volatile 保证可见性 */
        @Volatile
        private var ready = false

        private val tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }

        /**
         * 朗读一段译文。语言没有映射 / 引擎没就绪时返回 false（空操作），
         * 调用方给一句提示就行，不要弹窗打断。
         */
        fun speak(text: String, code: String?): Boolean {
            val tag = tag(code) ?: return false
            if (!ready || text.isBlank()) return false
            tts.language = Locale.forLanguageTag(tag)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "weifan-$tag")
            return true
        }

        fun shutdown() {
            tts.stop()
            tts.shutdown()
        }
    }
}
