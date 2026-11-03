package com.weifan.app.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 汇率转换（对齐参考应用 fx_* 模块）。
 *
 * 跨境报价的真实用法是「客户说这批 500 件，我按 12.5 美元/件算，一共多少人民币」，
 * 所以这一层必须做到：
 *   1) 自动认出对方国家的货币（从 chatJid 的国家码推）
 *   2) 支持四则运算（100*0.2、100/1.2、100+50），省得用户自己先按计算器
 *   3) 双向输入（改目标金额反推源金额）
 *   4) 置顶常用货币 + 一键复制
 *
 * 数据源用 open.er-api.com：免费、无需 key、一次拿全量汇率。
 * 缓存 1 小时（内存 + Prefs 落盘），离线时用旧数据但标注时间。
 */
object Fx {

    /** 一次汇率快照。rates 里一定包含 base 自身（=1.0）。 */
    data class Snapshot(val base: String, val rates: Map<String, Double>, val updatedMs: Long) {
        fun rateTo(code: String): Double? = rates[code]
        /** 交叉汇率：from → to */
        fun cross(from: String, to: String): Double? {
            if (from == to) return 1.0
            val f = rates[from] ?: return null
            val t = rates[to] ?: return null
            if (f == 0.0) return null
            return t / f
        }
    }

    /** 货币表：代码 / 中文名 / 符号。顺序即选择器默认顺序（常用在前）。 */
    val CURRENCIES: List<Triple<String, String, String>> = listOf(
        Triple("CNY", "人民币", "¥"),
        Triple("USD", "美元", "$"),
        Triple("EUR", "欧元", "€"),
        Triple("GBP", "英镑", "£"),
        Triple("JPY", "日元", "¥"),
        Triple("HKD", "港币", "HK$"),
        Triple("TWD", "新台币", "NT$"),
        Triple("KRW", "韩元", "₩"),
        Triple("SGD", "新加坡元", "S$"),
        Triple("AUD", "澳元", "A$"),
        Triple("CAD", "加元", "C$"),
        Triple("NZD", "新西兰元", "NZ$"),
        Triple("CHF", "瑞士法郎", "CHF"),
        Triple("SEK", "瑞典克朗", "kr"),
        Triple("NOK", "挪威克朗", "kr"),
        Triple("DKK", "丹麦克朗", "kr"),
        Triple("PLN", "波兰兹罗提", "zł"),
        Triple("CZK", "捷克克朗", "Kč"),
        Triple("HUF", "匈牙利福林", "Ft"),
        Triple("RON", "罗马尼亚列伊", "lei"),
        Triple("TRY", "土耳其里拉", "₺"),
        Triple("RUB", "俄罗斯卢布", "₽"),
        Triple("UAH", "乌克兰格里夫纳", "₴"),
        Triple("AED", "阿联酋迪拉姆", "د.إ"),
        Triple("SAR", "沙特里亚尔", "﷼"),
        Triple("QAR", "卡塔尔里亚尔", "﷼"),
        Triple("KWD", "科威特第纳尔", "د.ك"),
        Triple("ILS", "以色列谢克尔", "₪"),
        Triple("EGP", "埃及镑", "E£"),
        Triple("ZAR", "南非兰特", "R"),
        Triple("NGN", "尼日利亚奈拉", "₦"),
        Triple("KES", "肯尼亚先令", "KSh"),
        Triple("MAD", "摩洛哥迪拉姆", "د.م."),
        Triple("BRL", "巴西雷亚尔", "R$"),
        Triple("MXN", "墨西哥比索", "MX$"),
        Triple("ARS", "阿根廷比索", "AR$"),
        Triple("CLP", "智利比索", "CL$"),
        Triple("COP", "哥伦比亚比索", "CO$"),
        Triple("PEN", "秘鲁索尔", "S/"),
        Triple("INR", "印度卢比", "₹"),
        Triple("PKR", "巴基斯坦卢比", "₨"),
        Triple("BDT", "孟加拉塔卡", "৳"),
        Triple("LKR", "斯里兰卡卢比", "Rs"),
        Triple("IDR", "印尼盾", "Rp"),
        Triple("MYR", "马来西亚林吉特", "RM"),
        Triple("THB", "泰铢", "฿"),
        Triple("VND", "越南盾", "₫"),
        Triple("PHP", "菲律宾比索", "₱"),
        Triple("CNH", "离岸人民币", "¥")
    )

    /** 国家码前缀 → 货币（外贸高频走廊；未收录返回 null 由用户手选） */
    private val prefixCurrency: Map<String, String> = mapOf(
        "86" to "CNY", "852" to "HKD", "853" to "MOP", "886" to "TWD",
        "1" to "USD", "44" to "GBP", "353" to "EUR", "33" to "EUR",
        "49" to "EUR", "39" to "EUR", "34" to "EUR", "351" to "EUR",
        "31" to "EUR", "32" to "EUR", "43" to "EUR", "30" to "EUR",
        "48" to "PLN", "420" to "CZK", "36" to "HUF", "40" to "RON",
        "46" to "SEK", "47" to "NOK", "45" to "DKK", "358" to "EUR",
        "41" to "CHF", "90" to "TRY", "7" to "RUB", "380" to "UAH",
        "971" to "AED", "966" to "SAR", "974" to "QAR", "965" to "KWD",
        "973" to "BHD", "968" to "OMR", "972" to "ILS",
        "20" to "EGP", "27" to "ZAR", "234" to "NGN", "254" to "KES",
        "212" to "MAD", "216" to "TND", "213" to "DZD",
        "55" to "BRL", "52" to "MXN", "54" to "ARS", "56" to "CLP",
        "57" to "COP", "51" to "PEN", "58" to "VES",
        "91" to "INR", "92" to "PKR", "880" to "BDT", "94" to "LKR",
        "62" to "IDR", "60" to "MYR", "66" to "THB", "84" to "VND",
        "63" to "PHP", "65" to "SGD", "60" to "MYR",
        "61" to "AUD", "64" to "NZD", "81" to "JPY", "82" to "KRW",
        "852" to "HKD", "855" to "USD", "95" to "MMK", "856" to "LAK",
        "976" to "MNT", "998" to "UZS", "77" to "KZT"
    )

    /** 从会话 jid 推对方货币；识别不出返回 null */
    fun currencyForChat(chatJid: String): String? {
        val digits = chatJid.substringBefore("@").filter { it.isDigit() }
        if (digits.length < 5) return null
        for (len in 4 downTo 1) {
            prefixCurrency[digits.take(len)]?.let { return it }
        }
        return null
    }

    fun symbolOf(code: String): String =
        CURRENCIES.firstOrNull { it.first == code }?.third ?: code

    fun nameOf(code: String): String =
        CURRENCIES.firstOrNull { it.first == code }?.second ?: code

    // MARK: - 四则运算求值

    /**
     * 求值四则运算表达式，支持 + - * / 与括号、一元正负号。
     * 非法输入返回 null（不抛异常，UI 只显示"—"）。
     *
     * 为什么手写而不用 ScriptEngine：Android 上没有 Nashorn，且数学表达式
     * 用不到脚本引擎的全部能力，手写递归下降反而更快更安全。
     */
    fun evaluate(expr: String): Double? {
        val s = expr.trim().replace(" ", "").replace("，", "").replace(",", "")
        if (s.isEmpty()) return null
        if (s.any { !(it.isDigit() || it == '.' || it in "+-*/()") }) return null
        val p = Parser(s)
        val v = try { p.parseExpr() } catch (_: Throwable) { return null }
        if (!p.atEnd()) return null
        return if (v.isFinite()) v else null
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        private fun peek(): Char? = if (i < s.length) s[i] else null

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= parseFactor() }
                    '/' -> {
                        i++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("div0")
                        v /= d
                    }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            when (peek()) {
                '-' -> { i++; return -parseFactor() }
                '+' -> { i++; return parseFactor() }
                '(' -> {
                    i++
                    val v = parseExpr()
                    if (peek() == ')') i++ else throw IllegalStateException("paren")
                    return v
                }
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalStateException("num")
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalStateException("num")
        }
    }

    // MARK: - 汇率获取（带缓存）

    private const val TTL_MS = 60L * 60 * 1000        // 1 小时
    private var cached: Snapshot? = null

    /**
     * 拿汇率。force=true 强制刷新（用户点「更新」时）。
     * 失败时返回已有缓存（可为 null），不抛异常 —— 由 UI 决定显示什么。
     */
    fun load(prefs: com.weifan.app.Prefs, force: Boolean = false): Snapshot? {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { if (now - it.updatedMs < TTL_MS) return it }
            val disk = readFromPrefs(prefs)
            if (disk != null && now - disk.updatedMs < TTL_MS) {
                cached = disk
                return disk
            }
        }
        val fresh = fetch()
        if (fresh != null) {
            cached = fresh
            writeToPrefs(prefs, fresh)
            return fresh
        }
        return cached ?: readFromPrefs(prefs)
    }

    private fun fetch(): Snapshot? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (o.optString("result") != "success") return null
            val r = o.getJSONObject("rates")
            val map = mutableMapOf<String, Double>()
            val keys = r.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = r.optDouble(k, Double.NaN)
            }
            val ts = o.optLong("time_last_update_unix", 0L) * 1000
            Snapshot("USD", map.filterValues { it.isFinite() && it > 0 },
                if (ts > 0) ts else System.currentTimeMillis())
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    private fun readFromPrefs(prefs: com.weifan.app.Prefs): Snapshot? {
        val raw = prefs.fxSnapshot
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val r = o.getJSONObject("rates")
            val map = mutableMapOf<String, Double>()
            val keys = r.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = r.optDouble(k, Double.NaN)
            }
            Snapshot(o.optString("base", "USD"), map.filterValues { it.isFinite() && it > 0 },
                o.optLong("ts", 0L))
        } catch (_: Throwable) { null }
    }

    private fun writeToPrefs(prefs: com.weifan.app.Prefs, snap: Snapshot) {
        val r = JSONObject()
        for ((k, v) in snap.rates) r.put(k, v)
        prefs.fxSnapshot = JSONObject().apply {
            put("base", snap.base); put("ts", snap.updatedMs); put("rates", r)
        }.toString()
    }

    /** 金额格式化：≥1000 加千分位，≤2 位小数，去掉无意义的 .0 */
    fun fmt(v: Double, code: String): String {
        if (!v.isFinite()) return "—"
        val sym = symbolOf(code)
        val rounds = if (code == "VND" || code == "IDR" || code == "JPY" || code == "KRW") 0 else 2
        val s = String.format("%,.${rounds}f", v)
        return "$sym$s"
    }
}
