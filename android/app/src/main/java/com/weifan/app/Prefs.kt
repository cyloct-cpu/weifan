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

    /**
     * 全部自定义接口（D-56：支持多个）。
     * 存 JSON 数组；旧版只有一条（custom_name / custom_key…），第一次读到时自动迁移。
     * 与扩展侧 keys.customs 同结构。
     */
    var customs: List<Providers.CustomProfile>
        get() {
            val raw = sp.getString("customs_json", null)
            if (raw != null) {
                return try {
                    val arr = org.json.JSONArray(raw)
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Providers.CustomProfile(
                            id = o.optString("id", "custom$i"),
                            name = o.optString("name"),
                            key = o.optString("key"),
                            baseUrl = o.optString("baseUrl"),
                            model = o.optString("model")
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            // 旧数据迁移：单条字段有值就当成唯一一条
            return if (customKey.isNotBlank() && customBaseUrl.isNotBlank()) {
                listOf(Providers.CustomProfile("custom", customName, customKey, customBaseUrl, customModel))
            } else {
                emptyList()
            }
        }
        set(value) = sp.edit().putString("customs_json", org.json.JSONArray().apply {
            value.forEach { c ->
                put(org.json.JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("key", c.key)
                    put("baseUrl", c.baseUrl)
                    put("model", c.model)
                })
            }
        }.toString()).apply()

    // MARK: - 服务选择与优先级（D-56）

    /**
     * 按我的排序：渠道的全量视觉顺序（左→右、上→下），逗号连接。
     * id 里没有逗号（free = 注册 id，自定义 = custom:<id>），逗号分隔是安全的。
     */
    var serviceChainOrder: List<String>
        get() = sp.getString("chain_order", "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = sp.edit().putString("chain_order", value.joinToString(",")).apply()

    /** 被开关停用的渠道 id */
    var serviceChainOff: List<String>
        get() = sp.getString("chain_off", "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = sp.edit().putString("chain_off", value.joinToString(",")).apply()

    /** 用户选的服务商：auto / priority / 具体 id（只用这家） */
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

    // MARK: - 外观（昼夜切换）

    /** 主题模式：light = 昼，dark = 夜，auto = 跟随系统 */
    var themeMode: String
        get() = sp.getString("theme_mode", "auto") ?: "auto"
        set(value) = sp.edit().putString("theme_mode", value).apply()

    /** 是否启用自定义夜间时段（只在「跟随系统」下有意义） */
    var themeScheduleEnabled: Boolean
        get() = sp.getBoolean("theme_sched_enabled", false)
        set(value) = sp.edit().putBoolean("theme_sched_enabled", value).apply()

    /** 夜间开始时刻，如 "22:00" */
    var themeScheduleStart: String
        get() = sp.getString("theme_sched_start", "22:00") ?: "22:00"
        set(value) = sp.edit().putString("theme_sched_start", value).apply()

    /** 夜间结束时刻，如 "07:00" */
    var themeScheduleEnd: String
        get() = sp.getString("theme_sched_end", "07:00") ?: "07:00"
        set(value) = sp.edit().putString("theme_sched_end", value).apply()

    fun hasAnyKey(): Boolean =
        configuredProviders().isNotEmpty() || customs.any { it.isComplete }

    // MARK: - WhatsApp 协议接入

    /** 连接 WhatsApp 用的代理（HTTP/SOCKS5 URL），空串 = 直连。国内网络必须配。 */
    var waProxy: String
        get() = sp.getString("wa_proxy", "") ?: ""
        set(value) = sp.edit().putString("wa_proxy", value.trim()).apply()

    /** 发件自动翻译的目标语言（空 = 自动按会话记忆） */
    var waTargetLang: String
        get() = sp.getString("wa_target_lang", "") ?: ""
        set(value) = sp.edit().putString("wa_target_lang", value.trim()).apply()

    /** 收件自动翻译开关 */
    var waAutoTranslateIncoming: Boolean
        get() = sp.getBoolean("wa_auto_incoming", true)
        set(value) = sp.edit().putBoolean("wa_auto_incoming", value).apply()

    /** 发件点「译」后是否自动发送（默认关，合规底线） */
    var waAutoSend: Boolean
        get() = sp.getBoolean("wa_auto_send", false)
        set(value) = sp.edit().putBoolean("wa_auto_send", value).apply()

    /** 预览模式（测试用）：跳过真实配对，用演示数据过 UI */
    var waPreviewMode: Boolean
        get() = sp.getBoolean("wa_preview", false)
        set(value) = sp.edit().putBoolean("wa_preview", value).apply()

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
