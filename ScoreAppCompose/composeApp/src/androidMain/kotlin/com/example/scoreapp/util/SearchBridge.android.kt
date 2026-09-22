package com.example.scoreapp.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 搜索桥的 Android 实现，套路照抄 [AndroidAiBridge]：
 * `HttpURLConnection`、POST 一段 JSON、读回一段 JSON、错误翻成人话。
 *
 * 端点是 Tavily（`api.tavily.com/search`），鉴权走 `Authorization: Bearer`
 * —— 不是把 key 塞进 body（它家新版 API 已改为 header 鉴权，body 里的
 * `api_key` 字段属于旧版兼容，新接入别再用）。
 */
internal class AndroidSearchBridge : SearchBridge {

    override suspend fun search(query: String, apiKey: String): SearchReply =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext SearchReply(false, "", "还没填搜索密钥，去「我的 → AI 设置」里填一下")
            }

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(SearchFallbackEndpoint.TAVILY).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                }

                val body = buildRequestBody(query)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.let { readAll(it) }.orEmpty()

                if (code !in 200..299) {
                    return@withContext SearchReply(false, raw, explainHttpError(code, raw))
                }
                SearchReply(true, raw)
            } catch (e: Exception) {
                SearchReply(false, "", explainFailure(e))
            } finally {
                conn?.disconnect()
            }
        }

    private fun buildRequestBody(query: String): String = buildString {
        append("{\"query\":\"").append(jsonEscape(query)).append("\",")
        append("\"max_results\":").append(com.example.scoreapp.domain.csvfix.SearchFallback.MAX_HITS).append(",")
        // basic 档够用：判断「这首曲子是什么」读摘要就行，advanced 档慢且贵
        append("\"search_depth\":\"basic\"}")
    }

    private fun readAll(stream: java.io.InputStream): String =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

    /** 把状态码翻译成用户能据以行动的一句话（口径与 [AndroidAiBridge] 一致） */
    private fun explainHttpError(code: Int, body: String): String {
        val head = when (code) {
            401, 403 -> "搜索密钥被拒绝（$code），检查 Tavily Key 是否正确"
            404 -> "搜索端点不对（404）"
            429 -> "搜索被限流了（429）：免费档每月 1000 次，用超了等下月或升级套餐"
            in 500..599 -> "搜索服务出错（$code），稍后重试"
            else -> "搜索请求失败（$code）"
        }
        val detail = body.take(200).trim().replace(Regex("\\s+"), " ")
        return if (detail.isEmpty()) head else "$head：$detail"
    }

    private fun explainFailure(e: Exception): String {
        val name = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        if (name.contains("Timeout") || name.contains("SocketTimeout")) {
            return "搜索连接超时，检查网络"
        }
        if (name.contains("UnknownHost") || name.contains("ConnectException")) {
            return "连不上搜索服务，检查网络（Tavily 是境外服务，部分网络环境到不了）"
        }
        if (name.contains("SSL") || name.contains("Certificate")) {
            return "搜索服务的 TLS 握手失败"
        }
        return "搜索出错：$name${if (msg.isBlank()) "" else "（$msg）"}"
    }

    private fun jsonEscape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        /** 搜索比对话快得多，30 秒足够；挂太久用户会以为 App 死了 */
        const val READ_TIMEOUT_MS = 30_000
    }
}

/** 端点收拢成一个引用点，换服务商只改这里 */
private object SearchFallbackEndpoint {
    const val TAVILY = "https://api.tavily.com/search"
}

actual fun createSearchBridge(): SearchBridge = AndroidSearchBridge()
