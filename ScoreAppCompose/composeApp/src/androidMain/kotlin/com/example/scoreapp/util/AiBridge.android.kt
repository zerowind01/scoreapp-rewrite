package com.example.scoreapp.util

import com.example.scoreapp.domain.csvfix.AiFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * AI 能力桥的 Android 实现。
 *
 * 用 `HttpURLConnection` 而不是 OkHttp/Ktor：本工程只需要「POST 一段 JSON、
 * 读回一段 JSON」，为这个引一整套网络库（外加依赖收敛、体积、混淆规则）
 * 不划算。`HttpURLConnection` 从 API 1 就有，行为足够稳定。
 *
 * 保持这一层薄是有意的 —— 真正的逻辑在 `AiFill`（纯函数、可单测）：
 * 这里只做「拼请求体、发出去、把回答抠出来」。
 */
internal class AndroidAiBridge : AiBridge {

    override suspend fun chat(messages: List<AiFill.ChatMessage>, config: AiConfig): AiReply =
        withContext(Dispatchers.IO) {
            if (!config.ready) return@withContext AiReply(false, "", "还没配置接口地址或密钥")

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(config.endpoint).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    setRequestProperty("Accept", "application/json")
                }

                val body = buildRequestBody(messages, config.model)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                // 错误响应体常带服务端的说明，比只看状态码有用得多
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.let { readAll(it) }.orEmpty()

                if (code !in 200..299) {
                    return@withContext AiReply(false, raw, explainHttpError(code, raw))
                }
                val content = extractContent(raw)
                    ?: return@withContext AiReply(false, raw, "接口返回里没有找到回答内容")

                AiReply(true, content)
            } catch (e: Exception) {
                AiReply(false, "", explainFailure(e))
            } finally {
                conn?.disconnect()
            }
        }

    private fun buildRequestBody(messages: List<AiFill.ChatMessage>, model: String): String = buildString {
        append("{\"model\":\"").append(jsonEscape(model)).append("\",")
        // 温度给 0：这是「按规则填字段」而不是创作，同一份输入应该稳定得到同一份输出，
        // 方便用户对同一条目重试时不会因为随机性拿到完全不同的建议。
        append("\"temperature\":0,")
        append("\"messages\":[")
        messages.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append("{\"role\":\"").append(jsonEscape(m.role)).append("\",")
            append("\"content\":\"").append(jsonEscape(m.content)).append("\"}")
        }
        append("]}")
    }

    /**
     * 从 OpenAI 兼容的响应里抠出 `choices[0].message.content`。
     *
     * 手抠而不是引 JSON 库：路径是固定的，且**宽容度要求高** —— 有些服务会在
     * content 里塞 markdown 围栏、有些会多返回 reasoning_content，
     * 与其反序列化成强类型（一有额外字段就炸），不如按路径找。
     */
    private fun extractContent(json: String): String? {
        val choice = json.indexOf("\"choices\"")
        if (choice < 0) return null
        // 从 choices 往后找第一个 content（reasoning_content 会排在它前面，
        // 但那个键里含 "content" 子串，所以必须先排除）
        var from = choice
        while (true) {
            val at = json.indexOf("\"content\"", from)
            if (at < 0) return null
            // "reasoning_content" 的结尾也是 "_content"，往前看一个字符即可排除
            val prev = json.getOrNull(at - 1)
            if (prev == '_' || prev == '-') { from = at + 9; continue }
            return readJsonString(json, at + "\"content\"".length)
        }
    }

    /** 从 [colonAt] 之后读出一个 JSON 字符串字面量（跳过空白与冒号） */
    private fun readJsonString(json: String, from: Int): String? {
        var i = from
        while (i < json.length && (json[i] == ' ' || json[i] == ':' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r')) i++
        // null 或非字符串（例如 null）→ 没内容
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\') {
                i++
                if (i >= json.length) break
                when (val e = json[i]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = json.substring(i + 1, minOf(i + 5, json.length))
                        val cp = hex.toIntOrNull(16)
                        if (cp != null) { sb.append(cp.toChar()); i += 4 }
                    }
                    else -> sb.append(e)
                }
                i++
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun readAll(stream: java.io.InputStream): String =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

    /** 把状态码翻译成用户能据以行动的一句话 */
    private fun explainHttpError(code: Int, body: String): String {
        val head = when (code) {
            401, 403 -> "密钥被拒绝（$code），检查 API Key 是否正确、是否有该模型的权限"
            404 -> "接口地址或模型名不对（404），检查地址是否以 /chat/completions 结尾"
            429 -> "被限流了（429），等一会儿再试"
            in 500..599 -> "服务端出错（$code），稍后重试"
            else -> "请求失败（$code）"
        }
        // 服务端常把自己的说明放在 body 里，摘一小段附上，比只看状态码好排查
        val detail = body.take(200).trim().replace(Regex("\\s+"), " ")
        return if (detail.isEmpty()) head else "$head：$detail"
    }

    /** 把异常翻译成用户能据以行动的一句话 */
    private fun explainFailure(e: Exception): String {
        val name = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        // 超时在这两个名字上都会出现，统一说一句
        if (name.contains("Timeout") || name.contains("SocketTimeout")) {
            return "连接超时（${READ_TIMEOUT_MS / 1000} 秒），检查网络或换一个接口地址"
        }
        if (name.contains("UnknownHost") || name.contains("ConnectException")) {
            return "连不上服务器，检查网络与接口地址"
        }
        if (name.contains("SSL") || name.contains("Certificate")) {
            return "TLS 握手失败，检查接口地址是否为 https"
        }
        return "请求出错：$name${if (msg.isBlank()) "" else "（$msg）"}"
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
        const val CONNECT_TIMEOUT_MS = 15_000
        /** 长条目 + 慢模型（推理模型尤其）容易超过 30 秒，给宽一点 */
        const val READ_TIMEOUT_MS = 120_000
    }
}

actual fun createAiBridge(): AiBridge = AndroidAiBridge()
