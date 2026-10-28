package com.weifan.app.core

/**
 * 本地语言检测：文字系统判定 + 拉丁语系停用词打分。
 * 与 extension/src/core/language.js 的规则一致（改一处必须改另一处）。
 */
object Detector {

    data class Detection(
        val language: String,
        val confidence: Double,
        val basis: String,
        val runnerUp: String? = null
    )

    private enum class Script { LATIN, HAN, KANA, HANGUL, ARABIC, CYRILLIC, DEVANAGARI, THAI, HEBREW }

    fun detect(text: String, candidates: List<String> = Lang.sendTargets): Detection? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        detectByScript(trimmed)?.let { byScript ->
            if (!candidates.contains(byScript.language) && !Lang.isChinese(byScript.language)) return null
            return byScript
        }
        return detectLatin(trimmed, candidates)
    }

    private fun scriptOf(cp: Int): Script? = when {
        cp in 0x41..0x24F || cp in 0x1E00..0x1EFF -> Script.LATIN
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF -> Script.HAN
        cp in 0x3040..0x30FF -> Script.KANA
        cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF -> Script.HANGUL
        cp in 0x600..0x6FF || cp in 0x750..0x77F || cp in 0xFB50..0xFDFF -> Script.ARABIC
        cp in 0x400..0x4FF -> Script.CYRILLIC
        cp in 0x900..0x97F -> Script.DEVANAGARI
        cp in 0xE00..0xE7F -> Script.THAI
        cp in 0x590..0x5FF -> Script.HEBREW
        else -> null
    }

    private fun detectByScript(text: String): Detection? {
        val counts = mutableMapOf<Script, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            scriptOf(cp)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val dominant = counts.maxByOrNull { it.value }?.key ?: return null

        when (dominant) {
            Script.HAN -> return Detection(chineseVariant(text), 0.9, "script")
            Script.ARABIC -> return Detection("ar", 0.95, "script")
            Script.CYRILLIC -> return Detection("ru", 0.85, "script")
            Script.DEVANAGARI -> return Detection("hi", 0.95, "script")
            Script.THAI -> return Detection("th", 0.95, "script")
            Script.HEBREW -> return Detection("he", 0.95, "script")
            Script.HANGUL -> return Detection("ko", 0.95, "script")
            Script.KANA -> return Detection("ja", 0.95, "script")
            else -> return null
        }
    }

    private val TRAD = "這個們麼說時間發貨價錢單還讓對請謝".toSet()
    private val SIMP = "这们么说时间发货价钱单还让对请谢".toSet()

    private fun chineseVariant(text: String): String {
        var t = 0
        var s = 0
        for (ch in text) {
            if (TRAD.contains(ch)) t++
            if (SIMP.contains(ch)) s++
        }
        return if (t > s) "zh-Hant" else "zh-Hans"
    }

    private class Lexicon(val words: Set<String>, val markers: Map<String, Double>)

    private val lexicons: Map<String, Lexicon> = mapOf(
        "en" to Lexicon(
            setOf("the", "and", "you", "for", "with", "this", "that", "have", "will", "can", "please", "hello",
                "thanks", "when", "what", "how", "ship", "order", "price", "yes", "not", "are", "is", "to",
                "of", "it", "we", "my", "your", "good", "morning", "dear", "shipment", "payment"),
            emptyMap()
        ),
        "es" to Lexicon(
            setOf("el", "la", "los", "las", "que", "de", "en", "para", "por", "con", "hola", "gracias", "cuando",
                "puedes", "pedido", "precio", "muy", "pero", "usted", "nosotros", "buenos", "dias", "si", "no",
                "es", "un", "una", "enviar", "pago"),
            mapOf("ñ" to 2.0, "¿" to 3.0, "¡" to 3.0, "ción" to 1.0)
        ),
        "pt" to Lexicon(
            setOf("o", "a", "os", "as", "que", "de", "em", "para", "por", "com", "olá", "obrigado", "obrigada",
                "quando", "você", "pedido", "preço", "muito", "mas", "não", "sim", "é", "um", "uma", "enviar",
                "bom", "dia", "pagamento"),
            mapOf("ã" to 2.0, "õ" to 2.0, "ç" to 1.0, "ção" to 2.0, "não" to 2.0)
        ),
        "fr" to Lexicon(
            setOf("le", "la", "les", "des", "que", "de", "en", "pour", "par", "avec", "bonjour", "merci", "quand",
                "vous", "commande", "prix", "très", "mais", "oui", "non", "est", "un", "une", "nous", "pouvez",
                "paiement"),
            mapOf("è" to 1.0, "ê" to 1.0, "ç" to 1.0, "œ" to 2.0, "à" to 1.0, "ù" to 2.0, "qu'" to 2.0)
        ),
        "de" to Lexicon(
            setOf("der", "die", "das", "und", "sie", "für", "mit", "ist", "nicht", "hallo", "danke", "wann",
                "können", "bestellung", "preis", "sehr", "aber", "ja", "nein", "wir", "ein", "eine", "bitte",
                "guten", "tag", "zahlung"),
            mapOf("ß" to 3.0, "ä" to 1.0, "ö" to 1.0, "ü" to 1.0)
        ),
        "it" to Lexicon(
            setOf("il", "lo", "la", "gli", "le", "che", "di", "in", "per", "con", "ciao", "grazie", "quando",
                "puoi", "ordine", "prezzo", "molto", "ma", "sì", "no", "è", "un", "una", "noi", "buongiorno",
                "pagamento"),
            mapOf("ì" to 2.0, "ò" to 1.0, "à" to 1.0)
        ),
        "tr" to Lexicon(
            setOf("ve", "bir", "bu", "için", "ile", "merhaba", "teşekkür", "zaman", "sipariş", "fiyat", "çok",
                "ama", "evet", "hayır", "değil", "biz", "siz"),
            mapOf("ğ" to 3.0, "ş" to 2.0, "ı" to 2.0)
        ),
        "vi" to Lexicon(
            setOf("và", "của", "cho", "với", "chào", "cảm", "ơn", "khi", "nào", "đơn", "hàng", "giá", "rất",
                "nhưng", "có", "không", "chúng", "tôi", "bạn"),
            mapOf("ạ" to 2.0, "ế" to 1.0, "ộ" to 2.0, "đ" to 3.0, "ơ" to 1.0, "ư" to 2.0)
        ),
        "id" to Lexicon(
            setOf("dan", "yang", "untuk", "dengan", "ini", "itu", "halo", "terima", "kasih", "kapan", "pesanan",
                "harga", "sangat", "tapi", "ya", "tidak", "kami", "anda"),
            emptyMap()
        )
    )

    private fun detectLatin(text: String, candidates: List<String>): Detection? {
        val lowered = text.lowercase()
        val tokens = lowered.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null

        val tokenSet = tokens.toSet()
        val scores = mutableMapOf<String, Double>()

        for (code in candidates) {
            val lex = lexicons[code] ?: continue
            var score = 0.0
            for (token in tokenSet) if (lex.words.contains(token)) score += 1.0
            for ((marker, weight) in lex.markers) if (lowered.contains(marker)) score += weight
            if (score > 0) scores[code] = score
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        if (ranked.isEmpty()) return null

        val total = ranked.sumOf { it.value }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.key

        return Detection(
            language = best.key,
            confidence = minOf(0.99, best.value / maxOf(total, 1.0) * 0.9 + 0.1),
            basis = "lexicon",
            runnerUp = runnerUp
        )
    }
}
