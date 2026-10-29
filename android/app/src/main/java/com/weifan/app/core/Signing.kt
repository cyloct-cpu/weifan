package com.weifan.app.core

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 各家云翻译 API 的签名工具。与 extension/src/core/signing.js 保持一致。
 *
 * 六种鉴权：
 *   百度        MD5(appid + q + salt + secret)
 *   腾讯云      TC3-HMAC-SHA256（三段派生密钥）
 *   火山引擎    V4 签名（HMAC-SHA256，四段派生）
 *   阿里云      RPC 风格 HMAC-SHA1
 *   Google / DeepL / Azure   直接 API Key
 *   Yandex      Authorization: Api-Key <key>
 *
 * 只用 JDK 自带的 MessageDigest / Mac，不引任何第三方库。
 */
object Signing {

    private val HEX = "0123456789abcdef".toCharArray()

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun md5(input: String): String =
        hex(MessageDigest.getInstance("MD5").digest(utf8(input)))

    fun sha256Hex(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(utf8(text)))

    /**
     * @param algorithm HmacSHA256 或 HmacSHA1
     */
    fun hmac(algorithm: String, key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(message)
    }

    /** RFC3986 百分号编码：空格转 %20，* 转义，~ 不转义 */
    fun encodeRfc3986(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    /**
     * 腾讯云 API v3 签名。
     * @return 请求头
     */
    fun tencentTC3(
        secretId: String,
        secretKey: String,
        action: String,
        version: String,
        region: String,
        host: String,
        payload: String
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis() / 1000
        val date = java.time.Instant.ofEpochSecond(timestamp).toString().take(10)
        val contentType = "application/json; charset=utf-8"

        val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-tc-action:${action.lowercase()}\n"
        val signedHeaders = "content-type;host;x-tc-action"
        val hashedPayload = sha256Hex(payload)

        val canonicalRequest = listOf("POST", "/", "", canonicalHeaders, signedHeaders, hashedPayload).joinToString("\n")
        val credentialScope = "$date/tmt/tc3_request"
        val stringToSign = listOf(
            "TC3-HMAC-SHA256",
            timestamp.toString(),
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        val secretDate = hmac("HmacSHA256", utf8(secretKey), utf8(date))
        val secretService = hmac("HmacSHA256", secretDate, utf8("tmt"))
        val secretSigning = hmac("HmacSHA256", secretService, utf8("tc3_request"))
        val signature = hex(hmac("HmacSHA256", secretSigning, utf8(stringToSign)))

        return mapOf(
            "Authorization" to "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature",
            "Content-Type" to contentType,
            "Host" to host,
            "X-TC-Action" to action,
            "X-TC-Version" to version,
            "X-TC-Timestamp" to timestamp.toString(),
            "X-TC-Region" to region
        )
    }

    /**
     * 火山引擎 V4 签名。
     * @return 请求头
     */
    fun volcV4(
        accessKeyId: String,
        secretKey: String,
        region: String,
        service: String,
        host: String,
        body: String,
        query: Map<String, String>
    ): Map<String, String> {
        val now = java.time.Instant.now().toString()          // 2026-10-29T19:30:00Z
        val xDate = now.replace("-", "").replace(":", "").substringBefore(".") + "Z"
        val date = xDate.substring(0, 8)
        val payloadHash = sha256Hex(body)

        val canonicalHeaders = buildString {
            append("content-type:application/json; charset=utf-8\n")
            append("host:$host\n")
            append("x-content-sha256:$payloadHash\n")
            append("x-date:$xDate\n")
        }
        val signedHeaders = "content-type;host;x-content-sha256;x-date"

        val canonicalQuery = query.keys.sorted().joinToString("&") {
            "${encodeRfc3986(it)}=${encodeRfc3986(query[it] ?: "")}"
        }

        val canonicalRequest = listOf("POST", "/", canonicalQuery, canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val credentialScope = "$date/$region/$service/request"
        val stringToSign = listOf("HMAC-SHA256", xDate, credentialScope, sha256Hex(canonicalRequest)).joinToString("\n")

        val kDate = hmac("HmacSHA256", utf8(secretKey), utf8(date))
        val kRegion = hmac("HmacSHA256", kDate, utf8(region))
        val kService = hmac("HmacSHA256", kRegion, utf8(service))
        val kSigning = hmac("HmacSHA256", kService, utf8("request"))
        val signature = hex(hmac("HmacSHA256", kSigning, utf8(stringToSign)))

        return mapOf(
            "Authorization" to "HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature",
            "Content-Type" to "application/json; charset=utf-8",
            "Host" to host,
            "X-Date" to xDate,
            "X-Content-Sha256" to payloadHash
        )
    }

    /**
     * 阿里云 RPC 风格签名（HMAC-SHA1）。
     * @return 可直接作为表单体提交的参数
     */
    fun aliyunRpc(
        accessKeyId: String,
        accessKeySecret: String,
        params: Map<String, String>,
        version: String = "2018-10-12"
    ): Map<String, String> {
        val all = LinkedHashMap<String, String>()
        all["Format"] = "JSON"
        all["Version"] = version
        all["AccessKeyId"] = accessKeyId
        all["SignatureMethod"] = "HMAC-SHA1"
        all["SignatureNonce"] = "${System.currentTimeMillis()}${(Math.random() * 1000000).toInt()}"
        all["SignatureVersion"] = "1.0"
        all["Timestamp"] = java.time.Instant.now().toString().let {
            if (it.endsWith("Z")) it.substringBefore(".") + "Z" else it
        }
        all.putAll(params)

        val canonical = all.keys.sorted().joinToString("&") {
            "${encodeRfc3986(it)}=${encodeRfc3986(all[it] ?: "")}"
        }
        val stringToSign = "POST&${encodeRfc3986("/")}&${encodeRfc3986(canonical)}"
        val signature = Base64.getEncoder().encodeToString(
            hmac("HmacSHA1", utf8("$accessKeySecret&"), utf8(stringToSign))
        )

        val out = LinkedHashMap(all)
        out["Signature"] = signature
        return out
    }
}
