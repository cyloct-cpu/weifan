package com.weifan.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 翻译服务商适配。与 extension/src/core/providers.js 一致。
 *
 * 只用 HttpURLConnection + org.json（系统自带），不引 OkHttp/Gson：
 * 依赖为零，APK 小，且不会因为某个库的 minSdk 抬升而卡住。
 */
object Providers {

    data class Keys(
        val google: String,
        val deepL: String,
        val deepLEndpoint: String,
        val groq: String,
        val llm: String,
        val llmBaseUrl: String,
        val llmModel: String
    )

    data class Result(val text: String, val provider: String, val detectedSource: String?)

    fun translate(
        text: String,
        source: String?,
        target: String,
        style: String,
        route: String,
        keys: Keys
    ): Result {
        val useLLM = route == "llm"
        if (useLLM) {
            if (keys.groq.isNotBlank()) {
                return llmTranslate(text, target, style, keys.groq, "https://api.groq.com/openai/v1",
                    "llama-3.3-70b-versatile", "Groq")
            }
            if (keys.llm.isNotBlank()) {
                return llmTranslate(text, target, style, keys.llm, keys.llmBaseUrl, keys.llmModel, "大模型")
            }
        }

        if (keys.deepL.isNotBlank() && Lang.supportsDeepL(target)) {
            return deepLTranslate(text, source, target, keys.deepL, keys.deepLEndpoint)
        }
        if (keys.google.isNotBlank()) {
            return googleTranslate(text, source, target, keys.google)
        }
        if (keys.groq.isNotBlank()) {
            return llmTranslate(text, target, style, keys.groq, "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile", "Groq(兜底)")
        }
        if (keys.llm.isNotBlank()) {
            return llmTranslate(text, target, style, keys.llm, keys.llmBaseUrl, keys.llmModel, "大模型(兜底)")
        }
        throw IOException("没有可用的翻译服务：请在微翻设置里填入至少一个密钥")
    }

    // MARK: - Google

    private fun googleTranslate(text: String, source: String?, target: String, key: String): Result {
        val url = "https://translation.googleapis.com/language/translate/v2?key=${enc(key)}"
        val body = JSONObject().apply {
            put("q", JSONArray().put(text))
            put("target", target)
            put("format", "text")
            if (source != null) put("source", source)
        }
        val raw = post(url, "application/json", body.toString(), mapOf("Content-Type" to "application/json"))
        val translations = JSONObject(raw).getJSONObject("data").getJSONArray("translations")
        val first = translations.getJSONObject(0)
        val translated = first.getString("translatedText")
        val detected = first.optString("detectedSourceLanguage").ifBlank { null }
        return Result(unescapeHtml(translated), "Google", detected)
    }

    // MARK: - DeepL

    private fun deepLTranslate(
        text: String,
        source: String?,
        target: String,
        key: String,
        endpoint: String
    ): Result {
        val form = buildString {
            append("text=").append(enc(text))
            append("&target_lang=").append(enc(toDeepLCode(target)))
            if (source != null) append("&source_lang=").append(enc(toDeepLCode(source)))
        }
        val raw = post(endpoint, "application/x-www-form-urlencoded", form,
            mapOf("Authorization" to "DeepL-Auth-Key $key"))
        val first = JSONObject(raw).getJSONArray("translations").getJSONObject(0)
        val detected = first.optString("detected_source_language").lowercase().ifBlank { null }
        return Result(first.getString("text"), "DeepL", detected)
    }

    private fun toDeepLCode(code: String) = when (code) {
        "zh-Hans" -> "ZH-HANS"
        "zh-Hant" -> "ZH-HANT"
        else -> code.uppercase()
    }

    // MARK: - 大模型（OpenAI 兼容）

    private fun llmTranslate(
        text: String,
        target: String,
        style: String,
        key: String,
        baseUrl: String,
        model: String,
        label: String
    ): Result {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(target, style)))
            .put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 1024)
            put("messages", messages)
        }

        val raw = post(url, "application/json", body.toString(), mapOf("Authorization" to "Bearer $key"))
        val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        if (content.isEmpty()) throw IOException("$label 返回空结果")
        return Result(content, label, null)
    }

    /** 四端唯一必须逐字一致的文本。改这里等于三端同时生效。 */
    fun systemPrompt(target: String, style: String): String {
        val tone = when (style) {
            "business" -> "正式商务语气，简洁专业，不使用缩略语和表情"
            "literal" -> "忠实直译，保留原文结构与数字格式，不润色"
            else -> "自然口语，像熟人聊天，可以简短"
        }
        return """
            你是专业的中外互译引擎，服务于跨境贸易沟通。把用户输入翻译成${Lang.name(target)}（$target）。
            要求：
            1. 只输出译文，不要解释、不要引号、不要加任何前后缀。
            2. 语气：$tone。
            3. 文本中的 ⟦数字⟧ 是占位符，必须原样保留在译文中的对应位置，不得翻译、不得改写、不得移动。
            4. 金额、数量、日期、型号必须逐字对应，不许换算、不许四舍五入。
            5. 人名、品牌名、产品型号保留原文，不要音译。
            6. 如果原文已是目标语言，原样返回。
        """.trimIndent()
    }

    // MARK: - HTTP

    private fun post(url: String, contentType: String, body: String, headers: Map<String, String>): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""

            if (code !in 200..299) {
                throw IOException("服务返回 $code：${text.take(160)}")
            }
            return text
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun unescapeHtml(s: String) = s
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
}
