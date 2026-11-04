package com.weifan.app.chat

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** P7：链接预览 og 元数据（对齐参考 view_link_preview：图 120dp + 标题 + 描述 + 域名） */
data class LinkPreviewData(val url: String, val title: String, val description: String,
                           val imageUrl: String?, val siteName: String?)

object LinkPreview {
    private val cache = HashMap<String, LinkPreviewData>()

    fun firstURL(text: String): String? {
        val m = Regex("https?://[^\\s<>\"']+") .find(text) ?: return null
        return m.value
    }

    fun cached(url: String): LinkPreviewData? = cache[url]

    /** 阻塞抓取——调用方放后台线程 */
    fun fetch(urlString: String): LinkPreviewData? {
        cache[urlString]?.let { return it }
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            val body = conn.inputStream.bufferedReader().use { it.readText() }.take(300_000)
            conn.disconnect()
            fun meta(prop: String): String? {
                for (pat in listOf(
                    Regex("<meta[^>]*property=\"og:$prop\"[^>]*content=\"([^\"]*)\"", RegexOption.IGNORE_CASE),
                    Regex("<meta[^>]*content=\"([^\"]*)\"[^>]*property=\"og:$prop\"", RegexOption.IGNORE_CASE))) {
                    val v = pat.find(body)?.groupValues?.get(1)?.trim()
                    if (!v.isNullOrEmpty()) return v
                }
                return null
            }
            val title = meta("title")
                ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(body)
                    ?.groupValues?.get(1)?.trim()
                ?: urlString
            val d = LinkPreviewData(
                url = urlString,
                title = title.take(120),
                description = (meta("description") ?: "").take(160),
                imageUrl = meta("image"),
                siteName = meta("site_name") ?: run { try { URL(urlString).host } catch (_: Throwable) { null } })
            cache[urlString] = d
            return d
        } catch (_: Throwable) {
            return null
        }
    }
}
