package com.weifan.app.core

/**
 * 术语保护：把"不能乱译"的词换成占位符，翻译后还原。
 * 与 extension/src/core/glossary.js、Swift 版 Glossary.swift 行为一致。
 *
 * Android 版只实现"原样保留"策略（对应 D-08：只做不翻译黑名单）。
 * 固定译法在 Android 上暂不支持 —— 少一个设置项，少一类 bug。
 */
object Glossary {

    data class Protected(
        val masked: String,
        val units: Map<String, String>,
        val hits: List<String>
    )

    private fun token(index: Int) = "\u27E6$index\u27E7" // ⟦n⟧

    /**
     * @param terms 黑名单词条，空行已由调用方过滤
     */
    fun protect(text: String, terms: List<String>): Protected {
        // 长词优先："轻舟 Pro Max" 必须先于 "轻舟"
        val active = terms.map { it.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }

        var masked = text
        val units = linkedMapOf<String, String>()
        val hits = mutableListOf<String>()
        var index = 0

        for (term in active) {
            if (!masked.contains(term)) continue
            val t = token(index)
            masked = masked.replace(term, t)
            units[t] = term
            hits.add(term)
            index++
        }
        return Protected(masked, units, hits)
    }

    private val placeholderRegex = Regex("[\\u27E6\\[【]\\s*\\d+\\s*[\\u27E7\\]】]")

    fun restore(translated: String, units: Map<String, String>): String {
        if (units.isEmpty()) return translated
        return placeholderRegex.replace(translated) { match ->
            val digits = match.value.filter { it.isDigit() }
            units[token(digits.toIntOrNull() ?: -1)] ?: match.value
        }
    }
}
