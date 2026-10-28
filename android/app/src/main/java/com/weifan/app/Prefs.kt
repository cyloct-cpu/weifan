package com.weifan.app

import android.content.Context

/**
 * 设置存储。用 SharedPreferences 而不是 DataStore —— 后者要引 AndroidX，这里刻意零依赖。
 *
 * 密钥以明文存在应用私有目录（/data/data/com.weifan.app/shared_prefs），
 * 未 root 的设备上其他应用读不到；但请不要把手机交给别人。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("weifan", Context.MODE_PRIVATE)

    var googleKey: String
        get() = sp.getString("google", "") ?: ""
        set(value) = sp.edit().putString("google", value).apply()

    var deepLKey: String
        get() = sp.getString("deepl", "") ?: ""
        set(value) = sp.edit().putString("deepl", value).apply()

    var deepLEndpoint: String
        get() = sp.getString("deepl_endpoint", "https://api-free.deepl.com/v2/translate")
            ?: "https://api-free.deepl.com/v2/translate"
        set(value) = sp.edit().putString("deepl_endpoint", value).apply()

    var groqKey: String
        get() = sp.getString("groq", "") ?: ""
        set(value) = sp.edit().putString("groq", value).apply()

    var llmKey: String
        get() = sp.getString("llm", "") ?: ""
        set(value) = sp.edit().putString("llm", value).apply()

    var llmBaseUrl: String
        get() = sp.getString("llm_base", "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1"
        set(value) = sp.edit().putString("llm_base", value).apply()

    var llmModel: String
        get() = sp.getString("llm_model", "deepseek-chat") ?: "deepseek-chat"
        set(value) = sp.edit().putString("llm_model", value).apply()

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
        googleKey.isNotBlank() || deepLKey.isNotBlank() || groqKey.isNotBlank() || llmKey.isNotBlank()
}
