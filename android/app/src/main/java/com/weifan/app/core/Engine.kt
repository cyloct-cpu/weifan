package com.weifan.app.core

import com.weifan.app.Prefs

/**
 * 内核门面。与 extension/src/core/core.js 的 translateForSending / translateIncoming 对应。
 *
 * 管道顺序不可调换：
 *   定语言 → 术语保护 → 路由 → 翻译 → 还原 → 风险判定
 * （"落地"即写回输入框，由 AccessibilityService 做）
 */
object Engine {

    data class SendOutcome(
        val translated: String,
        val target: String,
        val route: String,
        val provider: String,
        val risk: Routing.Risk,
        val latencyMs: Long,
        val languageSource: String
    )

    data class IncomingOutcome(
        val translated: String,
        val sourceLanguage: String?,
        val provider: String,
        val alreadyChinese: Boolean,
        val latencyMs: Long
    )

    fun translateForSending(
        text: String,
        prefs: Prefs,
        explicitTarget: String? = null,
        chatLanguage: String? = null,
        contextSample: String? = null
    ): SendOutcome {
        val started = System.currentTimeMillis()

        // ① 定语言：手动 > 内容检测 > 会话记忆 > 兜底
        val detection = contextSample?.let { Detector.detect(it) }
        val resolved = resolveTarget(explicitTarget, detection, chatLanguage, prefs.defaultTarget)

        // ② 术语保护
        val terms = prefs.glossary.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val protectedText = Glossary.protect(text, terms)

        // ③ 路由
        val style = "business"
        val decision = Routing.route(protectedText.masked, style, prefs.longSentenceThreshold)

        // ④ 翻译
        val result = Providers.translate(
            text = protectedText.masked,
            source = "zh-Hans",
            target = resolved.first,
            style = style,
            route = decision.route,
            keys = keys(prefs)
        )

        // ⑤ 还原
        val translated = Glossary.restore(result.text, protectedText.units)

        // ⑥ 风险
        val risk = Routing.assessRisk(
            text = text,
            detection = detection,
            glossaryHits = protectedText.hits,
            rememberedLanguage = chatLanguage,
            allowDirectSend = prefs.autoReplace,
            longThreshold = prefs.longSentenceThreshold
        )

        return SendOutcome(
            translated = translated,
            target = resolved.first,
            route = decision.route,
            provider = result.provider,
            risk = risk,
            latencyMs = System.currentTimeMillis() - started,
            languageSource = resolved.second
        )
    }

    fun translateIncoming(text: String, prefs: Prefs): IncomingOutcome {
        val started = System.currentTimeMillis()
        val detection = Detector.detect(text)

        if (detection != null && Lang.isChinese(detection.language)) {
            return IncomingOutcome(text, detection.language, "none", true,
                System.currentTimeMillis() - started)
        }

        val decision = Routing.route(text, "casual", prefs.longSentenceThreshold)
        val result = Providers.translate(
            text = text,
            source = detection?.language,
            target = "zh-Hans",
            style = "casual",
            route = decision.route,
            keys = keys(prefs)
        )

        return IncomingOutcome(
            translated = result.text,
            sourceLanguage = detection?.language ?: result.detectedSource,
            provider = result.provider,
            alreadyChinese = false,
            latencyMs = System.currentTimeMillis() - started
        )
    }

    private fun resolveTarget(
        explicit: String?,
        detection: Detector.Detection?,
        chatLanguage: String?,
        fallback: String
    ): Pair<String, String> {
        if (!explicit.isNullOrBlank()) return explicit to "manual"
        if (detection != null && detection.confidence >= 0.45 && !Lang.isChinese(detection.language)) {
            return detection.language to "detected"
        }
        if (!chatLanguage.isNullOrBlank() && Lang.all.containsKey(chatLanguage)) {
            return chatLanguage to "profile"
        }
        return fallback to "fallback"
    }

    private fun keys(prefs: Prefs) = Providers.Keys(
        google = prefs.googleKey,
        deepL = prefs.deepLKey,
        deepLEndpoint = prefs.deepLEndpoint,
        groq = prefs.groqKey,
        llm = prefs.llmKey,
        llmBaseUrl = prefs.llmBaseUrl,
        llmModel = prefs.llmModel
    )
}
