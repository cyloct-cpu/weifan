package com.weifan.app.core

/**
 * 按手机号前缀推断买家所在国家与语言（国际电话区号 E.164）。
 *
 * 说明：协议层拿不到对方的 IP（WhatsApp 服务器中继，客户端只见 JID），
 * 所以「自动检测」用 手机号前缀 + pushName 语言线索 双通道；这里实现前缀表。
 * 前缀按长度优先匹配（例如 1[2678]… 这类长前缀优先于国家码 1）。
 */
object CountryDetect {

    data class Info(val country: String, val lang: String)

    /** 国家码 → 国家中文名 + 主要语言 ISO 码（外贸高频走廊） */
    private val table: Map<String, Info> = mapOf(
        // 英美加澳新
        "44" to Info("英国", "en"), "1" to Info("美国/加拿大", "en"),
        "61" to Info("澳大利亚", "en"), "64" to Info("新西兰", "en"),
        "353" to Info("爱尔兰", "en"),
        // 欧洲西
        "34" to Info("西班牙", "es"), "351" to Info("葡萄牙", "pt"),
        "33" to Info("法国", "fr"), "49" to Info("德国", "de"),
        "39" to Info("意大利", "it"), "31" to Info("荷兰", "nl"),
        "32" to Info("比利时", "nl"), "41" to Info("瑞士", "de"),
        "43" to Info("奥地利", "de"),
        // 欧洲其他
        "30" to Info("希腊", "el"), "48" to Info("波兰", "pl"),
        "46" to Info("瑞典", "sv"), "47" to Info("挪威", "no"),
        "45" to Info("丹麦", "da"), "358" to Info("芬兰", "fi"),
        "420" to Info("捷克", "cs"), "40" to Info("罗马尼亚", "ro"),
        "36" to Info("匈牙利", "hu"), "380" to Info("乌克兰", "uk"),
        "7" to Info("俄罗斯/哈萨克", "ru"),
        "90" to Info("土耳其", "tr"),
        // 中东
        "971" to Info("阿联酋", "ar"), "966" to Info("沙特", "ar"),
        "972" to Info("以色列", "he"), "962" to Info("约旦", "ar"),
        "961" to Info("黎巴嫩", "ar"), "963" to Info("叙利亚", "ar"),
        "964" to Info("伊拉克", "ar"), "965" to Info("科威特", "ar"),
        "973" to Info("巴林", "ar"), "974" to Info("卡塔尔", "ar"),
        "968" to Info("阿曼", "ar"),
        // 非洲
        "20" to Info("埃及", "ar"), "27" to Info("南非", "en"),
        "234" to Info("尼日利亚", "en"), "254" to Info("肯尼亚", "en"),
        "233" to Info("加纳", "en"), "212" to Info("摩洛哥", "ar"),
        "213" to Info("阿尔及利亚", "ar"), "216" to Info("突尼斯", "ar"),
        "225" to Info("科特迪瓦", "fr"), "221" to Info("塞内加尔", "fr"),
        // 南亚东南亚
        "91" to Info("印度", "hi"), "92" to Info("巴基斯坦", "ur"),
        "880" to Info("孟加拉", "bn"), "94" to Info("斯里兰卡", "si"),
        "62" to Info("印尼", "id"), "60" to Info("马来西亚", "ms"),
        "66" to Info("泰国", "th"), "84" to Info("越南", "vi"),
        "63" to Info("菲律宾", "tl"), "65" to Info("新加坡", "en"),
        // 东亚
        "81" to Info("日本", "ja"), "82" to Info("韩国", "ko"),
        "86" to Info("中国", "zh"), "852" to Info("香港", "zh"),
        "886" to Info("台湾", "zh"),
        // 拉美
        "52" to Info("墨西哥", "es"), "55" to Info("巴西", "pt"),
        "54" to Info("阿根廷", "es"), "56" to Info("智利", "es"),
        "57" to Info("哥伦比亚", "es"), "51" to Info("秘鲁", "es"),
        "58" to Info("委内瑞拉", "es"), "593" to Info("厄瓜多尔", "es"),
        "591" to Info("玻利维亚", "es"), "595" to Info("巴拉圭", "es"),
        "598" to Info("乌拉圭", "es"), "507" to Info("巴拿马", "es"),
        "506" to Info("哥斯达黎加", "es"), "504" to Info("洪都拉斯", "es"),
        "502" to Info("危地马拉", "es"), "503" to Info("萨尔瓦多", "es"),
        "505" to Info("尼加拉瓜", "es"), "509" to Info("海地", "fr"),
        "591" to Info("玻利维亚", "es"),
        // 语言码 → 中文名（给 UI 用）
    )

    private val langNames = mapOf(
        "en" to "英语", "es" to "西班牙语", "pt" to "葡萄牙语", "fr" to "法语",
        "de" to "德语", "it" to "意大利语", "nl" to "荷兰语", "ru" to "俄语",
        "tr" to "土耳其语", "ar" to "阿拉伯语", "he" to "希伯来语",
        "hi" to "印地语", "ur" to "乌尔都语", "bn" to "孟加拉语", "si" to "僧伽罗语",
        "id" to "印尼语", "ms" to "马来语", "th" to "泰语", "vi" to "越南语",
        "tl" to "菲律宾语", "ja" to "日语", "ko" to "韩语", "zh" to "中文",
        "el" to "希腊语", "pl" to "波兰语", "sv" to "瑞典语", "no" to "挪威语",
        "da" to "丹麦语", "fi" to "芬兰语", "cs" to "捷克语", "ro" to "罗马尼亚语",
        "hu" to "匈牙利语", "uk" to "乌克兰语"
    )

    fun langName(code: String): String = langNames[code] ?: code

    /**
     * 从 chatJid（形如 447925169391@s.whatsapp.net）解析国家信息。
     * 返回 null 表示前缀未收录（显示「自动检测」兜底）。
     */
    fun detect(chatJid: String): Info? {
        val digits = chatJid.substringBefore("@").filter { it.isDigit() }
        if (digits.length < 5) return null
        // 长前缀优先（表里键都是纯国家码，长度即优先级）
        for (len in 4 downTo 1) {
            val info = table[digits.take(len)]
            if (info != null) return info
        }
        return null
    }

    /** 副标题用的展示文案：「英国 · 英语」，未收录时返回空串 */
    fun label(chatJid: String): String {
        val i = detect(chatJid) ?: return ""
        return "${i.country} · ${langName(i.lang)}"
    }
}
