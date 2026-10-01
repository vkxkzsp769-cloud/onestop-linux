package com.onestop.linux.core

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * token 注入（方案 §8.2.4，**全工程唯一实现**）。
 *
 * 作用：为所有发往回环地址的请求注入 `Authorization: Bearer <token>` 与 `X-DSH-Token`，
 *      让页面内的 XHR/fetch 无需改造即可通过鉴权。
 *
 * 边界（方案 §8.2.4）：
 *  - WebSocket 握手无法拦截，若 `dsh web` 用 WS，需要 URL query token；
 *  - 不要自行改 Accept-Encoding，交给 OkHttp 处理；
 *  - 非回环请求一律返回 null，交回 WebView 默认处理。
 */
class TokenInjectingClient(
    private val okHttp: OkHttpClient,
    private val tokenProvider: () -> String?,
) {
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "::1")

    fun intercept(view: WebView?, req: WebResourceRequest): WebResourceResponse? {
        val url = req.url
        if (url.host !in loopbackHosts) return null
        val token = tokenProvider() ?: return null

        val headers = req.requestHeaders.toMutableMap().apply {
            put("Authorization", "Bearer $token")
            put("X-DSH-Token", token)
        }
        return try {
            val body = when (req.method) {
                "GET", "HEAD" -> null
                else -> ByteArray(0).toRequestBody(null)
            }
            val builder = Request.Builder().url(url.toString()).method(req.method, body)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            okHttp.newCall(builder.build()).execute().use { r ->
                val ct = r.header("Content-Type").orEmpty()
                WebResourceResponse(
                    ct.substringBefore(";").ifBlank { "text/html" },
                    Regex("charset=([\\w-]+)").find(ct)?.groupValues?.get(1),
                    r.code,
                    r.header("Reason") ?: "",
                    r.headers.toMultimap().mapValues { it.value.joinToString(",") },
                    r.body?.byteStream()
                )
            }
        } catch (e: IOException) {
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", emptyMap(), null)
        }
    }
}
