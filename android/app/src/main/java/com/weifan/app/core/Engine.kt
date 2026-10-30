package com.weifan.app.core

import com.weifan.app.Prefs
import java.util.concurrent.Executors

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
        val degradedFrom: String?,
        val risk: Routing.Risk,
        val latencyMs: Long,
        val languageSource: String
    )

    data class IncomingOutcome(
        val translated: String,
        val sourceLanguage: String?,
        val provider: String,
        val degradedFrom: String?,
        val alreadyChinese: Boolean,
        val latencyMs: Long
    )

    data class TestOutcome(val text: String, val provider: String, val latencyMs: Long)

    /** 设置页里「测试这一家」 */
    fun testProvider(providerId: String, prefs: Prefs): TestOutcome {
        val started = System.currentTimeMillis()
        val result = Providers.testProvider(
            providerId = providerId,
            text = "Hello, when can you ship the order?",
            target = "zh-Hans",
            style = "casual",
            creds = prefs.allCredentials(),
            customs = prefs.customs
        )
        return TestOutcome(result.text, result.provider, System.currentTimeMillis() - started)
    }

    data class GenericOutcome(
        val text: String,
        val provider: String,
        val degradedFrom: String?,
        val latencyMs: Long
    )

    /** 通用翻译：划词 / 文本翻译 / 文档翻译都走这里 */
    fun translateText(
        text: String,
        source: String?,
        target: String,
        prefs: Prefs
    ): GenericOutcome {
        val started = System.currentTimeMillis()
        val result = Providers.translate(
            text = text,
            source = source,
            target = target,
            style = prefs.style,
            route = if (prefs.style == "business") "llm" else "mt",
            creds = prefs.allCredentials(),
            customs = prefs.customs,
            selection = prefs.selection,
            chainOrder = prefs.serviceChainOrder,
            chainOff = prefs.serviceChainOff,
            blocked = prefs.blockedProviders()
        )
        prefs.addUsage(result.providerId, text.length)
        return GenericOutcome(
            result.text,
            result.provider,
            result.degradedFrom,
            System.currentTimeMillis() - started
        )
    }

    /**
     * 批量翻译（文档用）。并发跑，最多 4 条同时。
     * @param onProgress 已完成数
     */
    fun translateBatch(
        items: List<String>,
        target: String,
        prefs: Prefs,
        onProgress: (Int) -> Unit = {}
    ): List<String> {
        val results = arrayOfNulls<String>(items.size)
        val pool = Executors.newFixedThreadPool(4)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val futures = items.mapIndexed { index, text ->
                pool.submit {
                    val out = try {
                        translateText(text, null, target, prefs).text
                    } catch (t: Throwable) {
                        text
                    }
                    results[index] = out
                    onProgress(done.incrementAndGet())
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return results.map { it ?: "" }
    }

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
        val result = translate(protectedText.masked, "zh-Hans", resolved.first, style, decision.route, prefs)

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
            degradedFrom = result.degradedFrom,
            risk = risk,
            latencyMs = System.currentTimeMillis() - started,
            languageSource = resolved.second
        )
    }

    fun translateIncoming(text: String, prefs: Prefs): IncomingOutcome {
        val started = System.currentTimeMillis()
        val detection = Detector.detect(text)

        if (detection != null && Lang.isChinese(detection.language)) {
            return IncomingOutcome(text, detection.language, "none", null, true,
                System.currentTimeMillis() - started)
        }

        val decision = Routing.route(text, "casual", prefs.longSentenceThreshold)
        val result = translate(text, detection?.language, "zh-Hans", "casual", decision.route, prefs)

        return IncomingOutcome(
            translated = result.text,
            sourceLanguage = detection?.language ?: result.detectedSource,
            provider = result.provider,
            degradedFrom = result.degradedFrom,
            alreadyChinese = false,
            latencyMs = System.currentTimeMillis() - started
        )
    }

    /** 翻译 + 记用量（额度保护） */
    private fun translate(
        text: String,
        source: String?,
        target: String,
        style: String,
        route: String,
        prefs: Prefs
    ): Providers.Result {
        val result = Providers.translate(
            text = text,
            source = source,
            target = target,
            style = style,
            route = route,
            creds = prefs.allCredentials(),
            customs = prefs.customs,
            selection = prefs.selection,
            chainOrder = prefs.serviceChainOrder,
            chainOff = prefs.serviceChainOff,
            blocked = prefs.blockedProviders()
        )
        prefs.addUsage(result.providerId, text.length)
        return result
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
}
