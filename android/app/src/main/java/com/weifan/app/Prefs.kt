package com.weifan.app

import android.content.Context
import com.weifan.app.core.Providers

/**
 * 设置存储。用 SharedPreferences 而不是 DataStore —— 后者要引 AndroidX，这里刻意零依赖。
 *
 * 密钥以明文存在应用私有目录（/data/data/com.weifan.app/shared_prefs），
 * 未 root 的设备上其他应用读不到；但请不要把手机交给别人。
 *
 * 服务商凭据统一存在 cred_<providerId>_<fieldId>，加一家服务商不用改这里。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("weifan", Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    // MARK: - 服务商凭据

    /** 某个服务商已填的字段 */
    fun credential(providerId: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val def = Providers.DEFS.firstOrNull { it.id == providerId } ?: return out
        for (field in def.fields) {
            val value = sp.getString("cred_${def.id}_${field.id}", "") ?: ""
            if (value.isNotBlank()) out[field.id] = value
        }
        return out
    }

    /** 所有已填的服务商 */
    fun allCredentials(): Map<String, Map<String, String>> {
        val out = mutableMapOf<String, Map<String, String>>()
        for (def in Providers.DEFS) {
            val cred = credential(def.id)
            if (cred.isNotEmpty()) out[def.id] = cred
        }
        return out
    }

    fun setCredential(providerId: String, fieldId: String, value: String) {
        sp.edit().putString("cred_${providerId}_$fieldId", value.trim()).apply()
    }

    /** 某家是否已配置（必填字段齐了） */
    fun isConfigured(providerId: String): Boolean {
        val def = Providers.DEFS.firstOrNull { it.id == providerId } ?: return false
        return Providers.isConfigured(def, credential(providerId))
    }

    /** 已配置的服务商 id 列表 */
    fun configuredProviders(): List<String> =
        Providers.DEFS.filter { isConfigured(it.id) }.map { it.id }

    // MARK: - 自定义接口（OpenAI 兼容）

    var customName: String
        get() = sp.getString("custom_name", "") ?: ""
        set(value) = sp.edit().putString("custom_name", value).apply()

    var customKey: String
        get() = sp.getString("custom_key", "") ?: ""
        set(value) = sp.edit().putString("custom_key", value).apply()

    var customBaseUrl: String
        get() = sp.getString("custom_base", "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1"
        set(value) = sp.edit().putString("custom_base", value).apply()

    var customModel: String
        get() = sp.getString("custom_model", "deepseek-chat") ?: "deepseek-chat"
        set(value) = sp.edit().putString("custom_model", value).apply()

    fun customProfile() = Providers.CustomProfile(
        id = "custom",
        name = customName,
        key = customKey,
        baseUrl = customBaseUrl,
        model = customModel
    )

    // MARK: - 模式与服务商选择

    /** free = 免费额度池，custom = 自定义 API */
    var mode: String
        get() = sp.getString("mode", "free") ?: "free"
        set(value) = sp.edit().putString("mode", value).apply()

    /** 用户选的服务商 id，auto = 自动优选 */
    var selection: String
        get() = sp.getString("selection", "auto") ?: "auto"
        set(value) = sp.edit().putString("selection", value).apply()

    /** 单个服务商每日字符上限，0 = 不限 */
    var dailyCharLimit: Int
        get() = sp.getInt("daily_limit", 0)
        set(value) = sp.edit().putInt("daily_limit", value).apply()

    // MARK: - 用量与额度保护

    private fun today(): String {
        val now = java.time.LocalDate.now()
        return "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    }

    fun usageToday(providerId: String): Int =
        sp.getInt("usage_${today()}_$providerId", 0)

    fun addUsage(providerId: String, chars: Int) {
        if (providerId.isBlank() || chars <= 0) return
        val key = "usage_${today()}_$providerId"
        sp.edit().putInt(key, usageToday(providerId) + chars).apply()
    }

    /** 今天已超限额的服务商 */
    fun blockedProviders(): List<String> {
        val limit = dailyCharLimit
        if (limit <= 0) return emptyList()
        return Providers.DEFS.map { it.id }.filter { usageToday(it) >= limit }
    }

    // MARK: - 其它设置

    /** 默认语气：business / casual / literal */
    var style: String
        get() = sp.getString("style", "business") ?: "business"
        set(value) = sp.edit().putString("style", value).apply()

    /** 兜底目标语言（检测不出对方语言时用） */
    var defaultTarget: String
        get() = sp.getString("target", "en") ?: "en"
        set(value) = sp.edit().putString("target", value).apply()

    /** 触发后缀。默认「?译」——打完后自动翻译并原地替换 */
    var trigger: String
        get() = sp.getString("trigger", "?译") ?: "?译"
        set(value) = sp.edit().putString("trigger", value).apply()

    /** 长句阈值：超过就走大模型 */
    var longSentenceThreshold: Int
        get() = sp.getInt("threshold", 60)
        set(value) = sp.edit().putInt("threshold", value).apply()

    /** 是否启用自动替换（关掉后只复制译文到剪贴板） */
    var autoReplace: Boolean
        get() = sp.getBoolean("auto_replace", true)
        set(value) = sp.edit().putBoolean("auto_replace", value).apply()

    /** "不翻译"黑名单，一行一个词 */
    var glossary: String
        get() = sp.getString("glossary", "") ?: ""
        set(value) = sp.edit().putString("glossary", value).apply()

    fun hasAnyKey(): Boolean =
        configuredProviders().isNotEmpty() || customProfile().isComplete

    // MARK: - 旧版本数据迁移

    /**
     * 老版本只有 google / deepl / groq / llm 四个平铺字段。
     * 迁到新的 cred_* 结构：Google、DeepL 原地搬，Groq 与大模型并进「自定义接口」。
     */
    private fun migrateLegacy() {
        if (sp.getBoolean("migrated_v2", false)) return

        val google = sp.getString("google", "") ?: ""
        if (google.isNotBlank() && credential("google").isEmpty()) {
            sp.edit().putString("cred_google_key", google).apply()
        }

        val deepL = sp.getString("deepl", "") ?: ""
        if (deepL.isNotBlank() && credential("deepl").isEmpty()) {
            sp.edit().putString("cred_deepl_key", deepL).apply()
        }
        val deepLEndpoint = sp.getString("deepl_endpoint", "") ?: ""
        if (deepLEndpoint.isNotBlank() && !credential("deepl").containsKey("endpoint")) {
            sp.edit().putString("cred_deepl_endpoint", deepLEndpoint).apply()
        }

        val llm = sp.getString("llm", "") ?: ""
        val groq = sp.getString("groq", "") ?: ""
        if (customKey.isBlank()) {
            if (llm.isNotBlank()) {
                sp.edit()
                    .putString("custom_key", llm)
                    .putString("custom_base", sp.getString("llm_base", "https://api.deepseek.com/v1"))
                    .putString("custom_model", sp.getString("llm_model", "deepseek-chat"))
                    .apply()
            } else if (groq.isNotBlank()) {
                // Groq 也是 OpenAI 兼容接口，搬进自定义档案
                sp.edit()
                    .putString("custom_name", "Groq")
                    .putString("custom_key", groq)
                    .putString("custom_base", "https://api.groq.com/openai/v1")
                    .putString("custom_model", "llama-3.3-70b-versatile")
                    .apply()
            }
        }

        sp.edit()
            .remove("google").remove("deepl").remove("deepl_endpoint")
            .remove("groq").remove("llm").remove("llm_base").remove("llm_model")
            .putBoolean("migrated_v2", true)
            .apply()
    }
}
