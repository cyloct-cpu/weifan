package com.weifan.app.core

/**
 * 混合路由 + 发送前风险闸门。
 * 与 extension/src/core/routing.js 一致：短句走机翻，含数字/长句走大模型，金额硬拦截。
 */
object Routing {

    private val MONEY = Regex(
        "(\\d[\\d,.]*\\s*(usd|rmb|cny|eur|dollars?|yuan|元|美元|欧元|美金|万|千|k\\b|pcs|件|箱|吨))|([$￥€£]\\s*\\d)",
        RegexOption.IGNORE_CASE
    )
    private val DATE = Regex(
        "(\\d{1,4}[-/年.]\\d{1,2}[-/月.]\\d{1,2})|(\\d{1,2}\\s*(月|日|号))|(next\\s+(week|month|monday|tuesday|wednesday|thursday|friday))|(下\\s*(周|月|星期))",
        RegexOption.IGNORE_CASE
    )
    private val NUMBER = Regex("\\d")

    fun hasNumber(text: String) = NUMBER.containsMatchIn(text)
    fun hasMoney(text: String) = MONEY.containsMatchIn(text)
    fun hasDate(text: String) = DATE.containsMatchIn(text)

    data class Decision(val route: String, val reasons: List<String>)
    data class Risk(val shouldPreview: Boolean, val reasons: List<String>)

    /** @param style business / casual / literal */
    fun route(text: String, style: String = "business", longThreshold: Int = 60): Decision {
        val length = text.length
        val reasons = mutableListOf<String>()

        if (length > longThreshold) {
            reasons.add("长度 $length > $longThreshold")
            return Decision("llm", reasons)
        }
        if (hasNumber(text)) {
            reasons.add("含数字/金额，机翻易出错")
            return Decision("llm", reasons)
        }
        if (style == "business" && length > maxOf(20, longThreshold / 2)) {
            reasons.add("商务长句")
            return Decision("llm", reasons)
        }
        if (style == "literal") {
            reasons.add("单据直译")
            return Decision("llm", reasons)
        }
        reasons.add("短句无数字，机翻足够")
        return Decision("mt", reasons)
    }

    /**
     * @param hardBlock 直发模式下仍然拦截的原因，默认只有金额
     */
    fun assessRisk(
        text: String,
        detection: Detector.Detection?,
        glossaryHits: List<String>,
        rememberedLanguage: String?,
        allowDirectSend: Boolean,
        hardBlock: Set<String> = setOf("containsMoney"),
        longThreshold: Int = 60
    ): Risk {
        var reasons = mutableListOf<String>()

        if (hasMoney(text)) reasons.add("containsMoney")
        else if (hasNumber(text)) reasons.add("containsNumber")
        if (hasDate(text)) reasons.add("containsDate")
        if (glossaryHits.isNotEmpty()) reasons.add("glossaryHit")
        if (text.length > longThreshold) reasons.add("longSentence")
        if (detection != null && detection.confidence < 0.45) reasons.add("lowDetectionConfidence")
        if (rememberedLanguage != null && detection != null &&
            detection.language != rememberedLanguage && detection.confidence > 0.6
        ) {
            reasons.add("switchedLanguage")
        }

        if (allowDirectSend) {
            reasons = reasons.filter { hardBlock.contains(it) }.toMutableList()
        }
        return Risk(reasons.isNotEmpty(), reasons)
    }

    fun riskLabel(reason: String): String = when (reason) {
        "containsMoney" -> "含金额"
        "containsNumber" -> "含数字"
        "containsDate" -> "含日期"
        "glossaryHit" -> "命中专有名词"
        "longSentence" -> "长句"
        "lowDetectionConfidence" -> "语言判断不确定"
        "switchedLanguage" -> "对方语言变了"
        else -> reason
    }
}
