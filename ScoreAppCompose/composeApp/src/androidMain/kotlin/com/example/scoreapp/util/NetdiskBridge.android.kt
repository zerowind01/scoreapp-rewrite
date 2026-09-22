package com.example.scoreapp.util

import com.example.scoreapp.domain.netdisk.Netdisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 网盘桥的 Android 实现。
 *
 * ## 为什么 PROPFIND 不走 HttpURLConnection
 *
 * `HttpURLConnection.setRequestMethod` 只认一份固定方法表
 * （GET / POST / HEAD / OPTIONS / PUT / DELETE / TRACE / PATCH），
 * 传 `PROPFIND` 会直接抛 `ProtocolException: Invalid HTTP method`。
 * 这是 JDK 的已知限制（JDK-7016595），Android 上的实现同样受限。
 * 反射改私有字段那条野路子在 Android P+ 会被隐藏 API 管控拦下 —— 不值得赌。
 *
 * 于是列目录这一段**手写一发 HTTP/1.1**：请求固定、响应只是一小段 XML，
 * 用 `Connection: close` 让服务端发完就收口，读流到 EOF 即可，
 * 连 Content-Length 与 chunked 都不必区分。**只有列目录走这条路** ——
 * 下载是普通的 GET，交给久经考验的 `HttpURLConnection`。
 */
internal class AndroidNetdiskBridge : NetdiskBridge {

    override suspend fun propfind(url: String, user: String, pass: String): PropfindReply =
        withContext(Dispatchers.IO) {
            // 传进来的路径可能是解过码的中文（绑定目录存的就是这种），
            // 请求行只能写 ASCII —— 不先规范，中文会变成 `?`，服务端就把它当查询串，
            // 于是列到的是根目录（详见 `Netdisk.encodeUrl` 的注释）
            when (val r = exchange("PROPFIND", Netdisk.encodeUrl(url), user, pass)) {
                is Raw.Ok -> {
                    // 207 Multi-Status 才是「列目录成功」。200 说明服务端没把它当
                    // WebDAV 处理（最常见：地址少了 /dav，被打回成一个普通页面）
                    if (r.code == 207) PropfindReply(true, r.body)
                    else PropfindReply(false, "", kindOfCode(r.code))
                }
                is Raw.Fail -> PropfindReply(false, "", r.kind)
            }
        }

    override suspend fun download(
        url: String,
        user: String,
        pass: String,
        destPath: String,
        onProgress: (pct: Int, done: Long, total: Long) -> Unit,
    ): DownloadReply = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            // 与列目录同理：下载的地址也可能带解码后的中文，先规范成 ASCII 再发
            conn = (URL(Netdisk.encodeUrl(url)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                // 下载不能跟列目录共用读超时：AList 挂在夸克前面，没缓存过的文件
                // 要等它先从夸克取回来，**头一个字节可能几十秒才到**。
                // 用列目录那个 30 秒会把它翻成「网络不通」
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Authorization", Netdisk.basicAuth(user, pass))
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext DownloadReply(false, kindOfCode(code), "HTTP $code")

            val total = conn.contentLengthLong
            val tmp = File("$destPath.part")
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // **读到 EOF 之前封顶 99**：Content-Length 只是服务端的说法，
                        // 顶满 100 会让进度条先跑完、随后界面退回「正在下载…」（1.22 真机）
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else -1
                        if (pct != last) {
                            last = pct
                            onProgress(pct, done, total)
                        }
                    }
                }
            }
            // 真的读完了才报 100 —— 界面拿它当「字节收完」的唯一信号
            onProgress(100, done, if (total > 0) total else done)
            // 先写 .part 再改名：下载中途失败不会在缓存目录里留一个残缺的 PDF，
            // 而「这份已经在缓存里」的判据正是文件存在 —— 留半个文件会骗过命中判断
            val dest = File(destPath)
            if (!tmp.renameTo(dest)) return@withContext DownloadReply(
                false, "net", "rename failed -> $destPath",
            )
            DownloadReply(true)
        } catch (e: Exception) {
            File("$destPath.part").delete()
            DownloadReply(false, kindOf(e), "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- 手写 HTTP/1.1

    private sealed interface Raw {
        data class Ok(val code: Int, val body: String, val location: String?) : Raw
        data class Fail(val kind: String) : Raw
    }

    /** 最多跟 3 跳：反代 http→https、或者 AList 换了路径前缀都靠它兜住 */
    private fun exchange(method: String, start: String, user: String, pass: String): Raw {
        var target = start
        var hops = 0
        while (true) {
            val u = runCatching { URL(target) }.getOrNull() ?: return Raw.Fail("net")
            val r = once(method, u, user, pass)
            val loc = (r as? Raw.Ok)?.location
            if (r is Raw.Ok && r.code in 301..308 && !loc.isNullOrBlank() && hops < MAX_REDIRECTS) {
                hops++
                target = runCatching { URL(u, loc).toString() }.getOrDefault(loc)
                continue
            }
            return r
        }
    }

    private fun once(method: String, u: URL, user: String, pass: String): Raw = try {
        val host = u.host
        val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
        val socket = connect(u, host, port)
        socket.use { s ->
            val path = if (u.path.isBlank()) "/" else u.path
            val req = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host)
                if (port != 80 && port != 443) append(':').append(port)
                append("\r\n")
                append("Authorization: ").append(Netdisk.basicAuth(user, pass)).append("\r\n")
                append("Depth: 1\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n")
                append("User-Agent: ScoreApp\r\n")
                append("Accept: */*\r\n")
                append("\r\n")
            }
            // 头一律按 ISO-8859-1 写：Basic 认证串是 base64，
            // 路径已由 `Netdisk.encodeUrl` 规范成百分号编码，两边都是纯 ASCII
            val out = s.getOutputStream()
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            parseResponse(readAll(s.getInputStream()))
        }
    } catch (e: Exception) {
        Raw.Fail(kindOf(e))
    }

    private fun connect(u: URL, host: String, port: Int): Socket {
        if (u.protocol != "https") {
            return Socket().apply {
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        }
        val s = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        s.soTimeout = READ_TIMEOUT_MS
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        applySni(s, host)
        s.startHandshake()
        // SSLSocketFactory 不做主机名校验 —— 不补这一句，中间人换一张
        // 受信任的证书就能通过，等于没加密
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
            throw SSLHandshakeException("主机名校验失败：$host")
        }
        return s
    }

    /**
     * 补 SNI。不加的话，同一 IP 上挂了多个域名的反向代理会拿默认证书来握手，
     * 直接失败。IP 形式的地址按规范不该带 SNI（`SNIHostName` 也会拒绝）。
     */
    private fun applySni(s: SSLSocket, host: String) {
        if (host.indexOf(':') >= 0 || host.matches(Regex("\\d+(\\.\\d+){3}"))) return
        try {
            val p: SSLParameters = s.sslParameters
            p.serverNames = listOf(SNIHostName(host))
            s.sslParameters = p
        } catch (e: Exception) {
            // 主机名不合规就按普通握手走，最坏是服务端不支持
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val buf = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 按**字节**找头尾分隔，再各自按合适的编码解：头是 ASCII，正文是 UTF-8 */
    private fun parseResponse(raw: ByteArray): Raw {
        val sep = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        val i = indexOf(raw, sep)
        if (i < 0) return Raw.Fail("net")
        val head = String(raw, 0, i, Charsets.ISO_8859_1)
        val body = String(raw, i + sep.size, raw.size - i - sep.size, Charsets.UTF_8)
        val lines = head.split("\r\n")
        val code = lines.firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.trim()
            ?.toIntOrNull()
            ?: return Raw.Fail("net")
        var location: String? = null
        for (line in lines.drop(1)) {
            val p = line.indexOf(':')
            if (p > 0 && line.substring(0, p).equals("location", ignoreCase = true)) {
                location = line.substring(p + 1).trim()
            }
        }
        return Raw.Ok(code, body, location)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ---------------------------------------------------------------- 错误分类

    private fun kindOfCode(code: Int): String = when (code) {
        401 -> "401"
        403 -> "403"
        404 -> "404"
        else -> "http:$code"
    }

    /** 异常翻分类码。用户不需要知道 `SocketTimeoutException` 是什么 */
    private fun kindOf(e: Exception): String {
        val n = e.javaClass.simpleName.lowercase(Locale.ROOT)
        return when {
            "timeout" in n -> "timeout"
            "ssl" in n || "certificate" in n || "handshake" in n -> "ssl"
            "unknownhost" in n || "connect" in n || "socket" in n -> "net"
            else -> "net"
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        /** 列目录是小事，但家宽上传到公网 AList 可能很慢，给足 30 秒 */
        const val READ_TIMEOUT_MS = 30_000
        /** 下载的读超时：见上面 `readTimeout` 处的说明 */
        const val DOWNLOAD_READ_TIMEOUT_MS = 120_000
        const val MAX_REDIRECTS = 3
    }
}

actual fun createNetdiskBridge(): NetdiskBridge = AndroidNetdiskBridge()
