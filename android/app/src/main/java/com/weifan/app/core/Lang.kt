package com.weifan.app.core

/**
 * 语种表。与 extension/src/core/language.js、Swift 版 Language.swift 保持一致。
 */
object Lang {

    data class Meta(val name: String, val short: String, val deepL: Boolean)

    val all: Map<String, Meta> = linkedMapOf(
        "zh-Hans" to Meta("简体中文", "中", true),
        "zh-Hant" to Meta("繁体中文", "繁", true),
        "en" to Meta("英语", "EN", true),
        "es" to Meta("西班牙语", "ES", true),
        "pt" to Meta("葡萄牙语", "PT", true),
        "fr" to Meta("法语", "FR", true),
        "de" to Meta("德语", "DE", true),
        "it" to Meta("意大利语", "IT", true),
        "nl" to Meta("荷兰语", "NL", true),
        "ru" to Meta("俄语", "RU", true),
        "ar" to Meta("阿拉伯语", "AR", true),
        "tr" to Meta("土耳其语", "TR", true),
        "vi" to Meta("越南语", "VI", false),
        "th" to Meta("泰语", "TH", false),
        "id" to Meta("印尼语", "ID", true),
        "ms" to Meta("马来语", "MS", false),
        "hi" to Meta("印地语", "HI", false),
        "ja" to Meta("日语", "JA", true),
        "ko" to Meta("韩语", "KO", true),
        "pl" to Meta("波兰语", "PL", true),
        "he" to Meta("希伯来语", "HE", false),
        "fil" to Meta("菲律宾语", "FIL", false)
    )

    val sendTargets: List<String> get() = all.keys.filter { !isChinese(it) }

    fun isChinese(code: String) = code == "zh-Hans" || code == "zh-Hant"

    fun name(code: String) = all[code]?.name ?: code

    fun short(code: String) = all[code]?.short ?: code

    fun supportsDeepL(code: String) = all[code]?.deepL == true
}
