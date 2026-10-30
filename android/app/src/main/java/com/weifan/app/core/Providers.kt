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
 * 翻译服务商适配。与 extension/src/core/providers.js 保持一致。
 *
 * 八家大厂免费额度 + 自定义 API：
 *   百度翻译开放平台 / 腾讯云机器翻译 TMT / Google Cloud Translation / DeepL API Free
 *   / Azure 微软翻译 / 火山引擎翻译 / 阿里云翻译 / Yandex Translate
 *
 * 降级规则：用户选的服务商优先；失败就换下一个「已配置 + 支持该目标语言」的服务，
 * 结果里带 degradedFrom，界面要写明这次是谁翻的。
 *
 * 只用 HttpURLConnection + org.json（系统自带），不引 OkHttp/Gson。
 */
object Providers {

    // MARK: - 注册表

    data class Field(
        val id: String,
        val label: String,
        val placeholder: String = "",
        val secret: Boolean = false,
        val optional: Boolean = false
    )

    data class Def(
        val id: String,
        val label: String,
        val mode: String,       // free | custom
        val kind: String,       // mt | llm
        val freeNote: String,
        val docs: String,
        val endpoint: String,           // 固定端点，用户只需填密钥
        val endpointEditable: Boolean,  // 只有 DeepL（付费版）与 Azure（区域资源）需要改
        val fields: List<Field>
    )

    /** 自定义接口（OpenAI 兼容） */
    data class CustomProfile(
        val id: String = "custom",
        val name: String = "",
        val key: String = "",
        val baseUrl: String = "",
        val model: String = ""
    ) {
        val isComplete: Boolean get() = key.isNotBlank() && baseUrl.isNotBlank()
        val displayName: String get() = name.ifBlank { model.ifBlank { "自定义接口" } }
    }

    /** 各家端点。绝大多数是固定的——用户去平台申请密钥，填进来就能用。 */
    val ENDPOINTS: Map<String, String> = mapOf(
        "baiduGeneral" to "https://fanyi-api.baidu.com/api/trans/vip/translate",
        "baiduLLM" to "https://fanyi-api.baidu.com/ait/api/aiTextTranslate",
        "deepl" to "https://api-free.deepl.com/v2/translate",
        "google" to "https://translation.googleapis.com/language/translate/v2",
        "azure" to "https://api.cognitive.microsofttranslator.com",
        "tencent" to "https://tmt.tencentcloudapi.com",
        "volc" to "https://translate.volcengineapi.com",
        "volcLLM" to "https://openspeech.bytedance.com/api/v3/machine_translation/matx_translate",
        "aliyun" to "https://mt.cn-hangzhou.aliyuncs.com",
        "yandex" to "https://translate.api.cloud.yandex.net/translate/v2/translate"
    )

    val DEFS: List<Def> = listOf(
        Def(
            "deepl", "DeepL API Free", "free", "mt",
            "每月 50 万字符免费（免费版 key 以 :fx 结尾）",
            "https://www.deepl.com/pro-api",
            ENDPOINTS["deepl"]!!,
            true,
            listOf(
                Field("key", "API Key", "xxxxxxxx-xxxx-…:fx", secret = true)
            )
        ),
        Def(
            "google", "Google Cloud Translation", "free", "mt",
            "每月 50 万字符免费额度",
            "https://cloud.google.com/translate/docs/setup",
            ENDPOINTS["google"]!!,
            false,
            listOf(Field("key", "API Key", "AIza…", secret = true))
        ),
        Def(
            "azure", "Azure 微软翻译", "free", "mt",
            "F0 每月 200 万字符免费",
            "https://learn.microsoft.com/azure/ai-services/translator/",
            ENDPOINTS["azure"]!!,
            true,
            listOf(
                Field("key", "订阅密钥", "32 位十六进制", secret = true),
                Field("region", "区域（选填）", "eastasia", optional = true)
            )
        ),
        Def(
            "baidu", "百度翻译开放平台", "free", "llm",
            "通用版 5 万/月（高级版 100 万）；大模型版要另开通",
            "https://fanyi-api.baidu.com/doc/21",
            ENDPOINTS["baiduGeneral"]!!,
            false,
            listOf(
                Field("appid", "App ID", "2024……"),
                Field("secret", "Secret Key", "……", secret = true)
            )
        ),
        Def(
            "tencent", "腾讯云机器翻译 TMT", "free", "mt",
            "个人实名后每月约 500 万字符免费资源包",
            "https://cloud.tencent.com/document/product/551",
            ENDPOINTS["tencent"]!!,
            false,
            listOf(
                Field("secretId", "SecretId", "AKID……"),
                Field("secretKey", "SecretKey", "……", secret = true),
                Field("region", "地域（选填）", "ap-guangzhou", optional = true)
            )
        ),
        Def(
            "volc", "火山引擎翻译", "free", "mt",
            "新用户约 100 万字符免费额度",
            "https://www.volcengine.com/docs/4640",
            ENDPOINTS["volc"]!!,
            false,
            listOf(
                Field("accessKeyId", "AccessKey ID", "AKLT……"),
                Field("secretKey", "Secret Key", "……", secret = true)
            )
        ),
        Def(
            "aliyun", "阿里云翻译", "free", "mt",
            "个人版每月 100 万字符免费",
            "https://help.aliyun.com/zh/machine-translation/",
            ENDPOINTS["aliyun"]!!,
            false,
            listOf(
                Field("accessKeyId", "AccessKey ID", "LTAI……"),
                Field("accessKeySecret", "AccessKey Secret", "……", secret = true)
            )
        ),
        Def(
            "volcLLM", "火山引擎大模型翻译", "free", "llm",
            "大模型翻译，32 种语言；要开 volc.speech.mt 权限",
            "https://docs.volcengine.com/docs/6561/2306735",
            ENDPOINTS["volcLLM"]!!,
            false,
            listOf(Field("key", "API Key", "火山控制台获取的 API Key", secret = true))
        ),
        Def(
            "yandex", "Yandex Translate", "free", "mt",
            "按字符计费（新号有试用金），不是固定免费额度",
            "https://cloud.yandex.com/en/docs/translate/quickstart",
            ENDPOINTS["yandex"]!!,
            false,
            listOf(
                Field("key", "API Key", "AQVN…", secret = true),
                Field("folderId", "Folder ID", "b1g……")
            )
        )
    )

    /** 自动优选时的优先级（也是下拉框里的顺序） */
    val AUTO_ORDER = listOf("deepl", "google", "azure", "baidu", "tencent", "volcLLM", "volc", "aliyun", "yandex")

    // MARK: - 语种映射

    private val LANG_CODES: Map<String, Map<String, String>> = mapOf(
        "baidu" to mapOf(
            "zh-Hans" to "zh", "zh-Hant" to "cht", "en" to "en", "es" to "spa", "pt" to "pt",
            "fr" to "fra", "de" to "de", "it" to "it", "ru" to "ru", "ar" to "ara",
            "tr" to "tr", "vi" to "vie", "th" to "th", "ja" to "jp", "ko" to "kor"
        ),
        "tencent" to mapOf(
            "zh-Hans" to "zh", "en" to "en", "es" to "es", "pt" to "pt", "fr" to "fr",
            "de" to "de", "it" to "it", "ru" to "ru", "ar" to "ar", "tr" to "tr",
            "vi" to "vi", "th" to "th", "id" to "id", "ms" to "ms", "ja" to "ja", "ko" to "ko"
        ),
        "google" to mapOf(
            "zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW", "en" to "en", "es" to "es", "pt" to "pt",
            "fr" to "fr", "de" to "de", "it" to "it", "nl" to "nl", "ru" to "ru", "ar" to "ar",
            "tr" to "tr", "vi" to "vi", "th" to "th", "id" to "id", "ms" to "ms", "hi" to "hi",
            "ja" to "ja", "ko" to "ko", "pl" to "pl", "he" to "he", "fil" to "tl"
        ),
        "deepl" to mapOf(
            "zh-Hans" to "ZH-HANS", "zh-Hant" to "ZH-HANT", "en" to "EN", "es" to "ES",
            "pt" to "PT", "fr" to "FR", "de" to "DE", "it" to "IT", "nl" to "NL",
            "ru" to "RU", "ar" to "AR", "tr" to "TR", "id" to "ID", "ja" to "JA",
            "ko" to "KO", "pl" to "PL"
        ),
        "volc" to mapOf(
            "zh-Hans" to "zh", "en" to "en", "es" to "es", "pt" to "pt", "fr" to "fr",
            "de" to "de", "it" to "it", "ru" to "ru", "ar" to "ar", "vi" to "vi",
            "th" to "th", "ms" to "ms", "id" to "id", "tr" to "tr", "ja" to "ja", "ko" to "ko"
        ),
        "aliyun" to mapOf(
            "zh-Hans" to "zh", "en" to "en", "es" to "es", "pt" to "pt", "fr" to "fr",
            "de" to "de", "it" to "it", "ru" to "ru", "vi" to "vi", "th" to "th",
            "ms" to "ms", "id" to "id", "tr" to "tr", "ja" to "ja", "ko" to "ko"
        ),
        "azure" to mapOf(
            "zh-Hans" to "zh-Hans", "zh-Hant" to "zh-Hant", "en" to "en", "es" to "es",
            "pt" to "pt", "fr" to "fr", "de" to "de", "it" to "it", "nl" to "nl",
            "ru" to "ru", "ar" to "ar", "tr" to "tr", "vi" to "vi", "th" to "th",
            "id" to "id", "ms" to "ms", "hi" to "hi", "ja" to "ja", "ko" to "ko",
            "pl" to "pl", "he" to "he", "fil" to "tl"
        ),
        "volcLLM" to mapOf(
            "zh-Hans" to "zh", "zh-Hant" to "zh-Hant", "en" to "en", "es" to "es", "pt" to "pt",
            "fr" to "fr", "de" to "de", "it" to "it", "nl" to "nl", "ru" to "ru", "ar" to "ar",
            "tr" to "tr", "vi" to "vi", "th" to "th", "id" to "id", "ms" to "ms", "hi" to "hi",
            "ja" to "ja", "ko" to "ko", "pl" to "pl", "he" to "he", "fil" to "tl"
        ),
        "yandex" to mapOf(
            "zh-Hans" to "zh", "en" to "en", "es" to "es", "pt" to "pt", "fr" to "fr",
            "de" to "de", "it" to "it", "nl" to "nl", "ru" to "ru", "ar" to "ar",
            "tr" to "tr", "vi" to "vi", "th" to "th", "id" to "id", "ms" to "ms",
            "hi" to "hi", "ja" to "ja", "ko" to "ko", "pl" to "pl", "he" to "he", "fil" to "tl"
        )
    )

    fun supportsTarget(providerId: String, code: String): Boolean =
        LANG_CODES[providerId]?.containsKey(code) == true

    fun targetCode(providerId: String, code: String): String? = LANG_CODES[providerId]?.get(code)

    /** 平台返回的语言代码 → 我们的代码 */
    fun fromPlatformCode(providerId: String, platformCode: String?): String? {
        if (platformCode.isNullOrBlank()) return null
        val map = LANG_CODES[providerId] ?: return null
        val lower = platformCode.lowercase()
        for ((ours, theirs) in map) {
            if (theirs.lowercase() == lower) return ours
        }
        return when (platformCode) {
            "cht" -> "zh-Hant"
            "zh-TW", "zh-Hant" -> "zh-Hant"
            "zh-CN", "zh" -> "zh-Hans"
            else -> null
        }
    }

    // MARK: - 翻译入口

    data class Result(
        val text: String,
        val provider: String,
        val detectedSource: String?,
        val providerId: String = "",
        val degradedFrom: String? = null
    )

    /** 降级链上的一个候选服务商 */
    data class Candidate(
        val id: String,
        val label: String,
        val kind: String,
        val values: Map<String, String>,
        val custom: CustomProfile? = null
    )

    /**
     * 翻译（带自动降级）。
     *
     * @param creds 服务商 id → 字段值
     * @param selection 'auto' / 'priority'（按我的排序）/ 具体 id（只用这家，不降级）
     * @param chainOrder 按我的排序：全量视觉顺序（左→右、上→下）
     * @param chainOff 被开关停用的渠道 id
     * @param blocked 已超今日限额的服务商，直接跳过
     */
    fun translate(
        text: String,
        source: String?,
        target: String,
        style: String,
        route: String,
        creds: Map<String, Map<String, String>>,
        customs: List<CustomProfile> = emptyList(),
        selection: String = "auto",
        chainOrder: List<String> = emptyList(),
        chainOff: List<String> = emptyList(),
        blocked: List<String> = emptyList()
    ): Result {
        val candidates = planCandidates(target, route, creds, customs, selection, chainOrder, chainOff)
            .filter { it.id !in blocked }
        if (candidates.isEmpty()) {
            throw IOException("没有可用的翻译服务：请在设置里填入至少一个密钥")
        }

        val preferred = candidates[0]
        val failures = mutableListOf<String>()

        for (cand in candidates) {
            try {
                val result = runProvider(cand, text, source, target, style)
                return result.copy(
                    provider = cand.label,
                    providerId = cand.id,
                    degradedFrom = if (cand.id == preferred.id) null else preferred.label
                )
            } catch (e: Exception) {
                failures += "${cand.label}：${e.message}"
            }
        }

        throw IOException("翻译失败（已尝试 ${candidates.size} 个服务）→ ${failures.joinToString("；")}")
    }

    /**
     * 单家直测：不走降级链，只测指定的那一家，出错就是这一家的错。
     */
    fun testProvider(
        providerId: String,
        text: String,
        target: String,
        style: String,
        creds: Map<String, Map<String, String>>,
        customs: List<CustomProfile> = emptyList()
    ): Result {
        val candidate = if (providerId.startsWith("custom:")) {
            val wanted = providerId.removePrefix("custom:")
            val p = customs.firstOrNull { it.id == wanted }
            if (p == null || !p.isComplete) throw IOException("自定义接口还没填 key 与接口地址")
            Candidate("custom:${p.id}", p.displayName, "llm", emptyMap(), p)
        } else {
            val def = DEFS.firstOrNull { it.id == providerId } ?: throw IOException("未知服务商 $providerId")
            val values = creds[def.id] ?: throw IOException("这家还没配置密钥（必填项没填全）")
            if (!isConfigured(def, values)) throw IOException("这家还没配置密钥（必填项没填全）")
            Candidate(def.id, def.label, def.kind, values)
        }
        val result = runProvider(candidate, text, null, target, style)
        return result.copy(provider = candidate.label, providerId = candidate.id)
    }

    /** 排出候选顺序（降级链） */
    /**
     * 排出候选顺序（降级链）。三种语义（D-56，与扩展 side providers.js 同规格）：
     * - 'auto'：自动优选（route 决定 llm/mt 先后，同类内按注册顺序）
     * - 'priority'：按我的排序 —— chainOrder 顺序即降级链，chainOff 里的跳过，空链显式报错
     * - 具体 id：只用这一家，不降级，不可用直接抛错
     */
    fun planCandidates(
        target: String,
        route: String,
        creds: Map<String, Map<String, String>>,
        customs: List<CustomProfile> = emptyList(),
        selection: String = "auto",
        chainOrder: List<String> = emptyList(),
        chainOff: List<String> = emptyList()
    ): List<Candidate> {
        val all = mutableListOf<Candidate>()

        for (def in DEFS) {
            val values = creds[def.id] ?: continue
            if (!isConfigured(def, values)) continue
            all += Candidate(def.id, def.label, def.kind, values)
        }
        for (c in customs) {
            if (c.isComplete) all += Candidate("custom:${c.id}", c.displayName, "llm", emptyMap(), c)
        }

        // 不支持该目标语言的服务商不进链
        val eligible = all.filter { it.kind == "llm" || supportsTarget(it.id, target) }

        // 只用这一家：选定即唯一候选，失败不降级
        if (selection.isNotEmpty() && selection != "auto" && selection != "priority") {
            val chosen = eligible.firstOrNull { it.id == selection }
                ?: throw IOException(
                    if (all.any { it.id == selection }) "这一家现在用不了：密钥没配齐，或不支持当前目标语言"
                    else "这一家不存在了，去设置 → 翻译服务里重新选"
                )
            return listOf(chosen)
        }

        // 按我的排序
        if (selection == "priority") {
            val chain = chainOrder
                .filter { it !in chainOff }
                .mapNotNull { id -> eligible.firstOrNull { it.id == id } }
            if (chain.isEmpty()) {
                throw IOException("「按我的排序」里没有可用的服务：去设置 → 翻译服务，把至少一家打开开关")
            }
            return chain
        }

        return autoOrder(eligible, route)
    }

    private fun autoOrder(list: List<Candidate>, route: String): List<Candidate> {
        val wantLlm = route == "llm"
        return list.sortedWith { a, b ->
            val aLlm = a.kind == "llm"
            val bLlm = b.kind == "llm"
            if (aLlm != bLlm) {
                return@sortedWith if (wantLlm) {
                    if (aLlm) -1 else 1
                } else {
                    if (aLlm) 1 else -1
                }
            }
            val ai = AUTO_ORDER.indexOf(a.id).let { if (it < 0) 99 else it }
            val bi = AUTO_ORDER.indexOf(b.id).let { if (it < 0) 99 else it }
            ai.compareTo(bi)
        }
    }

    /** 必填字段齐了才算已配置 */
    fun isConfigured(def: Def, values: Map<String, String>): Boolean =
        def.fields.filter { !it.optional && it.id != "endpoint" }.all {
            !values[it.id].isNullOrBlank()
        }

    // MARK: - 各家实现

    private fun runProvider(
        cand: Candidate,
        text: String,
        source: String?,
        target: String,
        style: String
    ): Result = when {
        cand.custom != null -> customRun(cand.custom, text, target, style)
        cand.id == "google" -> googleRun(cand.values, text, source, target)
        cand.id == "deepl" -> deepLRun(cand.values, text, source, target)
        cand.id == "azure" -> azureRun(cand.values, text, source, target)
        cand.id == "baidu" -> baiduRun(cand.values, text, source, target, style)
        cand.id == "tencent" -> tencentRun(cand.values, text, target)
        cand.id == "volc" -> volcRun(cand.values, text, target)
        cand.id == "aliyun" -> aliyunRun(cand.values, text, target)
        cand.id == "yandex" -> yandexRun(cand.values, text, target)
        cand.id == "volcLLM" -> volcLLMRun(cand.values, text, source, target)
        else -> throw IOException("未知服务商 ${cand.id}")
    }

    private fun googleRun(v: Map<String, String>, text: String, source: String?, target: String): Result {
        val to = targetCode("google", target) ?: throw IOException("不支持该目标语言")
        val from = if (source.isNullOrBlank()) null else targetCode("google", source)

        val body = JSONObject().apply {
            put("q", JSONArray().put(text))
            put("target", to)
            put("format", "text")
            if (from != null) put("source", from)
        }
        val url = "${ENDPOINTS["google"]}?key=${enc(v["key"] ?: "")}"
        val raw = post(url, "application/json", body.toString(), mapOf("Content-Type" to "application/json"))
        val first = JSONObject(raw).getJSONObject("data").getJSONArray("translations").getJSONObject(0)
        val detected = first.optString("detectedSourceLanguage").ifBlank { null }
        return Result(unescapeHtml(first.getString("translatedText")), "Google", fromPlatformCode("google", detected))
    }

    private fun deepLRun(v: Map<String, String>, text: String, source: String?, target: String): Result {
        val to = targetCode("deepl", target) ?: throw IOException("不支持该目标语言")
        val from = if (source.isNullOrBlank()) null else targetCode("deepl", source)

        val form = buildString {
            append("text=").append(enc(text))
            append("&target_lang=").append(enc(to))
            if (from != null) append("&source_lang=").append(enc(from))
        }
        val endpoint = v["endpoint"]?.takeIf { it.isNotBlank() } ?: ENDPOINTS["deepl"]!!
        val raw = post(endpoint, "application/x-www-form-urlencoded", form,
            mapOf("Authorization" to "DeepL-Auth-Key ${v["key"] ?: ""}"))
        val first = JSONObject(raw).getJSONArray("translations").getJSONObject(0)
        val detected = first.optString("detected_source_language").ifBlank { null }
        return Result(first.getString("text"), "DeepL", fromPlatformCode("deepl", detected))
    }

    private fun azureRun(v: Map<String, String>, text: String, source: String?, target: String): Result {
        val to = targetCode("azure", target) ?: throw IOException("不支持该目标语言")
        val from = if (source.isNullOrBlank()) null else targetCode("azure", source)

        val base = v["endpoint"]?.takeIf { it.isNotBlank() }
            ?: ENDPOINTS["azure"]!!
        val params = buildString {
            append("api-version=3.0&to=").append(enc(to))
            if (from != null) append("&from=").append(enc(from))
        }
        val headers = mutableMapOf(
            "Ocp-Apim-Subscription-Key" to (v["key"] ?: ""),
            "Content-Type" to "application/json"
        )
        v["region"]?.takeIf { it.isNotBlank() }?.let { headers["Ocp-Apim-Subscription-Region"] = it }

        val body = JSONArray().put(JSONObject().put("text", text)).toString()
        val raw = post("${base.trimEnd('/')}/translate?$params", "application/json", body, headers)
        val first = JSONArray(raw).getJSONObject(0)
        val dst = first.getJSONArray("translations").getJSONObject(0).getString("text")
        val detected = first.optJSONObject("detectedLanguage")?.optString("language")
        return Result(dst, "Azure", fromPlatformCode("azure", detected))
    }

    /** 百度官方错误码表（fanyi-api.baidu.com/doc/28）。只看 error_msg 查不出原因，必须带码。 */
    private val BAIDU_ERRORS = mapOf(
        52001 to "请求超时，检查文本或语种是否正常",
        52002 to "系统错误，稍后重试",
        52003 to "未授权：APP ID 不对，或这个翻译服务还没开通（管理控制台 → 总览 → 开通服务）",
        54000 to "必填参数为空",
        54001 to "签名错误：密钥不对，或 APP ID 填错了",
        54003 to "访问频率超限：标准版 1 QPS，个人认证切高级版可到 10 QPS",
        54004 to "账户余额不足，去管理控制台充值",
        54005 to "长文本请求太频繁，稍等 3 秒再试",
        58000 to "IP 非法：开发者信息里填的服务器 IP 不对（可以留空）",
        58001 to "该语种不支持：标准版/高级版只支持 28 个常见语种",
        58002 to "服务当前已关闭，去管理控制台开启",
        58003 to "此 IP 今日已被封禁：同一 IP 当天用了多个 APP ID，次日自动解封",
        58004 to "模型参数错误：model_type 只能是 llm 或 nmt",
        59002 to "翻译指令太长（reference 上限 500 字符）",
        59003 to "请求文本太长（q 上限 6000 字符）",
        59004 to "QPS 超限",
        59005 to "tag_handling 只能是 0 或 1",
        59006 to "标签解析失败：标签没闭合或是空的",
        59007 to "ignore_tags 最多 20 个标签",
        90107 to "认证未通过或未生效，去「我的认证」看进度"
    )

    /** 百度「大模型文本翻译」的翻译指令（reference）——把我们的语气设置映射过去 */
    private fun baiduReference(style: String): String = when (style) {
        "business" -> "使用正式、专业的商务语气翻译，简洁得体，不使用缩略语"
        "literal" -> "忠实直译，保留原文结构与数字格式，不做润色"
        else -> "使用自然、口语化的语气翻译，像日常聊天那样"
    }

    /**
     * 百度两家端点：
     *   大模型文本翻译 /ait/api/aiTextTranslate —— 质量更好，支持翻译指令（JSON 请求体）
     *   通用文本翻译   /api/trans/vip/translate  —— 老牌机翻（表单请求体）
     * 两家要分别开通；先试大模型，没开通就退回通用。
     */
    private fun baiduRequest(endpoint: String, payload: Map<String, String>, json: Boolean): Result {
        val contentType = if (json) "application/json" else "application/x-www-form-urlencoded"
        val body = if (json) {
            JSONObject().apply {
                for ((k, v) in payload) {
                    // 大模型端点要求 salt 是 uint64 数字，写成字符串会报 53001
                    if (k == "salt") put(k, v.toLongOrNull() ?: 0L) else put(k, v)
                }
            }.toString()
        } else {
            payload.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        }

        val raw = post(endpoint, contentType, body, mapOf("Content-Type" to contentType))
        val data = JSONObject(raw)
        if (data.has("error_code")) {
            val code = data.opt("error_code").toString()
            val hint = BAIDU_ERRORS[code.toIntOrNull() ?: 0] ?: ""
            val msg = data.optString("error_msg")
            throw IOException(
                "错误 $code：$msg" + (if (hint.isNotEmpty()) " —— $hint" else "")
            )
        }
        val array = data.getJSONArray("trans_result")
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            if (i > 0) sb.append('\n')
            sb.append(array.getJSONObject(i).getString("dst"))
        }
        if (sb.isEmpty()) throw IOException("返回空结果")
        return Result(sb.toString(), "百度", fromPlatformCode("baidu", data.optString("from")))
    }

    private fun baiduRun(v: Map<String, String>, text: String, source: String?, target: String, style: String): Result {
        val to = targetCode("baidu", target) ?: throw IOException("不支持该目标语言")
        val from = if (source.isNullOrBlank()) "auto" else (targetCode("baidu", source) ?: "auto")

        val appid = v["appid"] ?: ""
        val salt = (Math.random() * 1_000_000_000).toLong().toString()
        // 官方要求：拼接签名时 q 不做 URL encode，生成 sign 之后再编码
        val sign = Signing.md5(appid + text + salt + (v["secret"] ?: ""))
        val payload = mapOf(
            "appid" to appid, "q" to text, "from" to from, "to" to to, "salt" to salt, "sign" to sign
        )

        return try {
            baiduRequest(
                ENDPOINTS["baiduLLM"]!!,
                payload + mapOf("model_type" to "llm", "reference" to baiduReference(style)),
                json = true
            )
        } catch (e: IOException) {
            // 只有"服务没开通"才退回通用端点；签名错、余额不足这类错误直接抛
            val message = e.message ?: ""
            if (!message.contains("52003") && !message.contains("58002")) throw e
            baiduRequest(
                ENDPOINTS["baiduGeneral"]!!,
                payload,
                json = false
            )
        }
    }

    private fun tencentRun(v: Map<String, String>, text: String, target: String): Result {
        val to = targetCode("tencent", target) ?: throw IOException("不支持该目标语言")

        val payload = JSONObject().apply {
            put("SourceText", text)
            put("Source", "auto")
            put("Target", to)
            put("ProjectId", 0)
        }.toString()

        val headers = Signing.tencentTC3(
            secretId = v["secretId"] ?: "",
            secretKey = v["secretKey"] ?: "",
            action = "TextTranslate",
            version = "2018-03-21",
            region = v["region"]?.takeIf { it.isNotBlank() } ?: "ap-guangzhou",
            host = "tmt.tencentcloudapi.com",
            payload = payload
        )

        val raw = post(ENDPOINTS["tencent"]!!, "application/json; charset=utf-8", payload, headers)
        val response = JSONObject(raw).getJSONObject("Response")
        if (response.has("Error")) {
            val error = response.getJSONObject("Error")
            throw IOException(error.optString("Message").ifBlank { "腾讯云错误 ${error.optString("Code")}" })
        }
        val dst = response.optString("TargetText")
        if (dst.isEmpty()) throw IOException("返回空结果")
        return Result(dst, "腾讯云", fromPlatformCode("tencent", response.optString("Source")))
    }

    private fun volcRun(v: Map<String, String>, text: String, target: String): Result {
        val to = targetCode("volc", target) ?: throw IOException("不支持该目标语言")

        val query = mapOf("Action" to "TranslateText", "Version" to "2020-06-01")
        val body = JSONObject().apply {
            put("TargetLanguage", to)
            put("SourceLanguage", "auto")
            put("TextList", JSONArray().put(text))
        }.toString()

        val headers = Signing.volcV4(
            accessKeyId = v["accessKeyId"] ?: "",
            secretKey = v["secretKey"] ?: "",
            region = "cn-north-1",
            service = "translate",
            host = "translate.volcengineapi.com",
            body = body,
            query = query
        )

        val raw = post("${ENDPOINTS["volc"]}?Action=TranslateText&Version=2020-06-01",
            "application/json; charset=utf-8", body, headers)
        val response = JSONObject(raw).getJSONObject("Response")
        if (response.has("Error")) {
            val error = response.getJSONObject("Error")
            throw IOException(error.optString("Message").ifBlank { "火山引擎错误 ${error.optString("Code")}" })
        }
        val first = response.getJSONArray("TranslationList").getJSONObject(0)
        return Result(first.getString("Translation"), "火山引擎",
            fromPlatformCode("volc", first.optString("SourceLanguage")))
    }

    private fun aliyunRun(v: Map<String, String>, text: String, target: String): Result {
        val to = targetCode("aliyun", target) ?: throw IOException("不支持该目标语言")

        // 目标语言必须在签名前写进参数，否则签的是另一个值
        val params = Signing.aliyunRpc(
            accessKeyId = v["accessKeyId"] ?: "",
            accessKeySecret = v["accessKeySecret"] ?: "",
            params = mapOf(
                "Action" to "TranslateGeneral",
                "SourceLanguage" to "auto",
                "TargetLanguage" to to,
                "SourceText" to text,
                "FormatType" to "text"
            )
        )

        val form = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val raw = post(ENDPOINTS["aliyun"]!!,
            "application/x-www-form-urlencoded", form, mapOf("Content-Type" to "application/x-www-form-urlencoded"))

        val data = JSONObject(raw)
        val code = data.optInt("code", 200)
        if (code != 200) throw IOException(data.optString("message").ifBlank { "阿里云错误 $code" })
        val dst = data.getJSONObject("data").optString("translated")
        if (dst.isEmpty()) throw IOException("返回空结果")
        return Result(dst, "阿里云",
            fromPlatformCode("aliyun", data.getJSONObject("data").optString("detectedSourceLanguage")))
    }

    private fun yandexRun(v: Map<String, String>, text: String, target: String): Result {
        val to = targetCode("yandex", target) ?: throw IOException("不支持该目标语言")

        val body = JSONObject().apply {
            put("folderId", v["folderId"] ?: "")
            put("texts", JSONArray().put(text))
            put("targetLanguageCode", to)
        }.toString()

        val raw = post(ENDPOINTS["yandex"]!!,
            "application/json", body,
            mapOf("Content-Type" to "application/json", "Authorization" to "Api-Key ${v["key"] ?: ""}"))

        val data = JSONObject(raw)
        if (data.has("message")) throw IOException(data.optString("message"))
        val first = data.getJSONArray("translations").getJSONObject(0)
        return Result(first.getString("text"), "Yandex",
            fromPlatformCode("yandex", first.optString("detectedLanguageCode")))
    }

    /** 火山引擎「机器翻译大模型」错误码 */
    private val VOLC_LLM_ERRORS = mapOf(
        45000001 to "请求参数错误（target_language 必填）",
        45000130 to "载荷过大：text_list 最多 16 条、单条最多 1024 tokens",
        55000001 to "服务内部错误，稍后重试"
    )

    /** 火山引擎「机器翻译大模型」（豆包语音）：只要一个 API Key，不用 AK/SK 签名 */
    private fun volcLLMRun(v: Map<String, String>, text: String, source: String?, target: String): Result {
        val to = targetCode("volcLLM", target) ?: throw IOException("不支持该目标语言")
        val from = if (source.isNullOrBlank()) null else targetCode("volcLLM", source)

        val body = JSONObject().apply {
            put("target_language", to)
            put("text_list", JSONArray().put(text))
            if (from != null) put("source_language", from)
        }.toString()

        val headers = mapOf(
            "X-Api-Key" to (v["key"] ?: ""),
            "X-Api-Resource-Id" to "volc.speech.mt",
            "X-Api-Request-Id" to java.util.UUID.randomUUID().toString(),
            "Content-Type" to "application/json"
        )

        val raw = post(ENDPOINTS["volcLLM"]!!, "application/json", body, headers)
        val data = JSONObject(raw)
        val code = data.optInt("code", 0)
        if (code != 20000000) {
            val hint = VOLC_LLM_ERRORS[code] ?: ""
            throw IOException(
                "错误 $code：${data.optString("message")}" + (if (hint.isNotEmpty()) " —— $hint" else "")
            )
        }
        val first = data.getJSONObject("data").getJSONArray("translation_list").getJSONObject(0)
        val dst = first.optString("translation")
        if (dst.isEmpty()) throw IOException("返回空结果")
        return Result(dst, "火山大模型", fromPlatformCode("volcLLM", first.optString("detected_source_language")))
    }

    private fun customRun(profile: CustomProfile, text: String, target: String, style: String): Result {
        val base = profile.baseUrl.trimEnd('/')
        val model = profile.model.ifBlank { "deepseek-chat" }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(target, style)))
            .put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 2048)
            put("messages", messages)
        }.toString()

        val raw = post("$base/chat/completions", "application/json", body,
            mapOf("Authorization" to "Bearer ${profile.key}"), timeoutMs = 20000)
        val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        if (content.isEmpty()) throw IOException("${profile.displayName} 返回空结果")
        return Result(content, profile.displayName, null)
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

    private fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Int = 8000
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs + 7000
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
