package com.example.scoreapp.domain.netdisk

import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.MiniJson

/**
 * 网盘（AList / WebDAV）的**纯逻辑**。
 *
 * 这一层与 `domain/csvfix/` 同一条拆法：所有判断都在 commonMain 且只进出字符串与纯数据，
 * 平台相关的发请求、落盘由 `util/NetdiskBridge`（expect/actual）与 `FileBridge` 承担。
 * 好处是「列目录的语义」可以在 commonTest 里脱离手机验证 —— 而这一块恰好是
 * 最容易出隐性 bug 的地方（幽灵项、非 PDF 悄悄消失、路径削成空）。
 *
 * ## 为什么不认识「夸克」
 *
 * 夸克官方既不提供 WebDAV 也不开放标准 API（`pan.quark.cn/webdav` 是 404，
 * 客户端走自定义协议头 + 设备指纹，token 十几分钟就过期）。能走通的原因是
 * 中间有一层 **AList** 替用户跟夸克说话、对外暴露标准 WebDAV。
 * 所以本模块只认 WebDAV：换 AList 里的存储、或者直接接坚果云 / 群晖，一样能用。
 *
 * ## 只在线打开，不入库
 *
 * 网盘谱子**不进乐谱库列表**：点开时下载到缓存，看完就丢。
 * 缓存只保留**最近 1 份**（见 [Netdisk.evictCache]）——
 * 打开新的删掉旧的，同一份再打开命中缓存不重下。
 * 由此也不需要曲库持久化：不入库就没有要记的东西。
 */

/** PROPFIND 列出来的一个条目 */
data class NetdiskEntry(
    /** 显示名（已解 `%E5%85%89` 这类转义） */
    val name: String,
    /**
     * 远端地址。
     *
     * 由本模块自己拼（[Netdisk.joinPath]），**不拿服务端返回的 href 当地址用**：
     * 服务端给的可能是绝对路径（`/dav/钢琴/x.pdf`）而不是完整 URL，
     * 直接拿去发请求必然失败。解析阶段仍保留 href，便于测试与排错。
     */
    val path: String,
    val dir: Boolean,
    val size: Long,
)

/** 已经下载到手机缓存里的一份网盘谱子 */
data class NetdiskCached(
    val name: String,
    val size: Long,
    val remotePath: String,
    val localPath: String,
)

/**
 * AList 的连接配置。
 *
 * 明文落盘（`filesDir/netdisk.json`），与 `AiConfig` 同一套取舍：
 * 这是个自用工具，为它引一套加密与密钥派生得不偿失，
 * 但**界面上会明确写出来**，不让用户在不知情的情况下把口令留在设备里。
 */
data class NetdiskConfig(
    /** WebDAV 地址。**末尾必须带 /dav**，少了它一律 404 —— 这是最常见的填错方式 */
    val addr: String = "",
    val user: String = "",
    val pass: String = "",
) {
    /** 三项都填了才算配好。空串不算（界面上清空某栏再保存不该被当成已配置） */
    val ready: Boolean get() = addr.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    companion object {
        private const val K_ADDR = "addr"
        private const val K_USER = "user"
        private const val K_PASS = "pass"

        fun toJson(config: NetdiskConfig): String = buildString {
            append('{')
            append("\"").append(K_ADDR).append("\":\"").append(AiFill.jsonEsc(config.addr)).append("\",")
            append("\"").append(K_USER).append("\":\"").append(AiFill.jsonEsc(config.user)).append("\",")
            append("\"").append(K_PASS).append("\":\"").append(AiFill.jsonEsc(config.pass)).append("\"")
            append('}')
        }

        /**
         * 从 JSON 文本还原。**任何解析失败一律退成默认值，绝不抛异常**：
         * 这段文本是从磁盘读回来的，而「老版本压根没写过这个文件」是必然会遇到的情况。
         */
        fun fromJson(text: String?): NetdiskConfig {
            if (text.isNullOrBlank()) return NetdiskConfig()
            val obj = runCatching { MiniJson.parse(text) }.getOrNull() as? MiniJson.Value.Obj
                ?: return NetdiskConfig()
            fun str(key: String): String = (obj.fields[key] as? MiniJson.Value.Str)?.value.orEmpty()
            return NetdiskConfig(addr = str(K_ADDR), user = str(K_USER), pass = str(K_PASS))
        }
    }
}

/**
 * 「名称排序」用哪个比较器。
 *
 * 中文目录要**按拼音**排才跟手机上翻目录的直觉一致，而 commonMain 里没有 `Collator`
 * —— 与本工程其它平台能力同一条路子（expect/actual）隔离出去。
 */
internal expect fun collatedCompare(a: String, b: String): Int

object Netdisk {

    /** 只认 PDF。网盘上文件名大小写很随意，所以忽略大小写 */
    fun isPdfName(name: String): Boolean = name.trim().endsWith(".pdf", ignoreCase = true)

    /** 取路径最后一段并解转义。`/dav/钢琴/月光.pdf` → `月光.pdf` */
    fun baseName(href: String): String {
        val s = percentDecode(href.trim()).trimEnd('/')
        val i = s.lastIndexOf('/')
        return if (i < 0) s else s.substring(i + 1)
    }

    /** 规范化路径：解转义 + 去掉末尾斜杠。**比较路径前必须过这一道** */
    fun normPath(p: String): String = percentDecode(p.trim()).trimEnd('/')

    /**
     * 解析 PROPFIND 的 207 多状态响应。
     *
     * 为什么手写正则：`commonMain` 没有 XML 解析器，为它拉一个库不划算
     * （与 `MiniJson` 同一条理由）。只认三样东西：href、是不是目录、文件大小。
     *
     * @param self 本次请求的路径。**必须传** —— PROPFIND 会把目录自己一起返回，
     *             不过滤的话列表里永远多一个跟当前目录同名的幽灵项。
     *             服务端可能回绝对路径（`/dav/x`）也可能回完整 URL，
     *             所以两侧都先剥掉 scheme://host 再比。
     */
    fun parsePropfind(xml: String, self: String?): List<NetdiskEntry> {
        val selfPath = self?.let { normPath(pathOnly(it)) }
        val out = ArrayList<NetdiskEntry>()
        for (block in RE_RESPONSE.split(xml).drop(1)) {
            val href = RE_HREF.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val name = baseName(href)
            if (name.isEmpty()) continue                              // 根自身（名字为空）
            if (selfPath != null && normPath(pathOnly(href)) == selfPath) continue // 目录自身
            val size = RE_LEN.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0L
            out.add(
                NetdiskEntry(
                    name = name,
                    path = href,
                    dir = RE_COLLECTION.containsMatchIn(block),
                    size = size,
                ),
            )
        }
        return out
    }

    /** 目录在前，PDF / 文件在后，各自按名 */
    fun sortEntries(list: List<NetdiskEntry>): List<NetdiskEntry> = list.sortedWith(
        compareByDescending<NetdiskEntry> { it.dir }
            .thenComparator { a, b -> collatedCompare(a.name, b.name) },
    )

    /** 能直接打开的东西：只要 PDF。目录另走导航 */
    fun pdfEntries(list: List<NetdiskEntry>): List<NetdiskEntry> =
        list.filter { !it.dir && isPdfName(it.name) }

    /**
     * 被跳过的非 PDF 数量。
     * 界面底部要给一行「已跳过 N 个非 PDF」—— 一声不响地藏掉会让人以为文件丢了。
     */
    fun skippedCount(list: List<NetdiskEntry>): Int =
        list.count { !it.dir && !isPdfName(it.name) }

    fun joinPath(base: String, seg: String): String =
        base.trimEnd('/') + "/" + percentEncode(seg)

    /**
     * 把服务端给的 href 补成**能发请求的完整 URL**。
     *
     * 为什么必须补：AList 的 PROPFIND 回的是**不带 host 的绝对路径**
     * （`<D:href>/dav/quark/%E4%B9%90%E8%B0%B1/x.pdf</D:href>`），
     * 而 `URL("/dav/...")` 直接抛 `MalformedURLException: no protocol`
     * —— **请求根本没发出去**，桥里 catch 到的一律翻成「网络不通」，
     * 于是每一份谱子都「下载失败」，界面上看起来就像下载功能是假的。
     * 列目录不受影响，是因为那条路径是拿配置里的地址**从头拼**的，天然带 host。
     *
     * @param base 配置里的网盘地址（`http://host:5244/dav`），这里只取它的
     *             `scheme://host[:port]` 那一段
     * @param p 服务端给的 href 或规范化路径；**已经带 scheme 就原样返回**
     */
    fun absoluteUrl(base: String, p: String): String {
        val s = p.trim()
        if (s.contains("://")) return s
        val b = base.trim()
        val i = b.indexOf("://")
        // base 自己也没 scheme：无从补起，至少保证是绝对路径，别让拼出来的串更离谱
        if (i < 0) return if (s.startsWith("/")) s else "/$s"
        val rest = b.substring(i + 3)
        val j = rest.indexOf('/')
        val origin = if (j < 0) b.substring(0, i + 3) + rest else b.substring(0, i + 3 + j)
        return if (s.startsWith("/")) origin + s else "$origin/$s"
    }

    /**
     * 把地址规范成**能直接发请求**的形态：路径每一段「先解再编」。
     *
     * 为什么必须有这一步：HTTP 请求行只能是 ASCII，而界面上传下来的路径常常是
     * **已经解码的中文**（`normPath` 为了比较方便一律解码；绑定文件夹存的也是它）。
     * 这种串直接写进请求行，按 ISO-8859-1 落字节时中文一个个变成 `?`，
     * 而 `?` 在请求行里是查询串的起点 —— 服务端于是把
     * `/dav/quark/我的备份/乐谱` 当成 `/dav/quark/` 来列目录，
     * **照样回 207，只是内容换成了根目录那一层（全是子目录、一个 PDF 都没有）**。
     * 症状就是「同步成功，但一份都没进来」，而且不报任何错。
     *
     * 「先解再编」保证幂等：已编码的 `%E4%B9%90%E8%B0%B1` 解回 `乐谱` 再编回去，
     * 不会变成 `%25E4%25B9%25A6` 这种双重编码。
     */
    fun encodeUrl(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return encodeSegments(url)
        val head = url.substring(0, i + 3)
        val rest = url.substring(i + 3)
        val j = rest.indexOf('/')
        // 没有路径部分（只有 host:port）就没得编
        if (j < 0) return url
        // host:port 原样留着 —— 它是 ASCII，而且编了反而不认识
        return head + rest.substring(0, j) + encodeSegments(rest.substring(j))
    }

    private fun encodeSegments(path: String): String =
        path.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) seg else percentEncode(percentDecode(seg))
        }

    /**
     * 上一级。到根返回 null —— 再往上削会把地址削成空路径。
     */
    fun parentPath(p: String, root: String): String? {
        val s = normPath(p)
        val r = normPath(root)
        if (s == r) return null
        val i = s.lastIndexOf('/')
        if (i < 0) return null
        val up = s.substring(0, i)
        return if (up.length < r.length) r else up
    }

    /**
     * 状态码翻人话。**不许把裸 HTTP 数字丢给用户** ——
     * 最常见的是 404，而它几乎总是「地址少了 /dav」。
     *
     * @param kind `401` / `403` / `404` / `timeout` / `ssl` / `net` / `http:<码>` / `none`
     */
    fun errorText(kind: String): String = when (kind) {
        "401" -> "账号或密码不对，去设置里改一下"
        "403" -> "这个账号没权限看这个目录"
        "404" -> "地址不对。AList 的 WebDAV 地址末尾要带 /dav"
        "timeout" -> "连不上，超时了。地址填对了吗？在家外面要用公网地址"
        "ssl" -> "证书有问题，换 https 地址试试"
        "net" -> "网络不通，检查手机联网"
        else -> {
            val code = kind.removePrefix("http:").toIntOrNull()
            if (code != null) "服务端返回 $code" else "连不上"
        }
    }

    /**
     * Basic 认证头。密码可能有中文，所以先过 UTF-8 字节再 base64
     * （直接对字符串编码在非 ASCII 上会得到错的值）。
     */
    fun basicAuth(user: String, pass: String): String =
        "Basic " + base64("$user:$pass".encodeToByteArray())

    /** 缓存键：规范化后的远端路径 */
    fun cacheKeyOf(remotePath: String): String = normPath(remotePath)

    /**
     * 缓存文件名。
     *
     * 把**整个远端路径**编码进去而不是只取文件名：不同目录下同名谱子很常见
     * （`钢琴/拜厄.pdf` 与 `声乐/拜厄.pdf`），只按文件名存会互相覆盖。
     */
    fun cacheFileName(remotePath: String): String {
        val raw = percentEncode(normPath(remotePath))
        // 文件系统对单个名字有 **255 字节** 的硬上限。整条路径编进来本来是为了让
        // 不同目录下的同名谱子互不覆盖，但 Z-Library 那种长名（真机实测 281 字节）
        // 会让 FileOutputStream 直接抛异常 —— 那一份就永远下载失败。
        // 超长则**截断 + 补一段哈希**：哈希保证不撞车，截断留头方便人认。
        return if (raw.length <= CACHE_NAME_LIMIT) "$raw.pdf"
        else raw.substring(0, CACHE_NAME_TRIM) + "-" + shortHash(raw) + ".pdf"
    }

    /** FNV-1a 32 位。只为生成短后缀，不需要抗碰撞强度，但要**稳定**（同一路径永远同一值） */
    private fun shortHash(s: String): String {
        var h = -0x6e38e6c3L // 0x811c9dc5
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFFL)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h.toString(16).padStart(8, '0')
    }

    /**
     * 缓存淘汰：**只留最近 1 份**。
     *
     * Jackson 定的语义是「看完就丢」，所以打开新的就把旧的删掉，
     * 不留一堆谱子在手机上。调用方负责把被淘汰那条的本地文件删掉。
     */
    fun evictCache(cache: Map<String, NetdiskCached>, keep: String?): Map<String, NetdiskCached> {
        val kept = keep?.let { cache[it] } ?: return emptyMap()
        return mapOf(keep to kept)
    }

    /** 行尾那个体积标签。0 给空串（目录 / 服务端没给长度时不占位） */
    fun formatSize(n: Long): String = when {
        n <= 0 -> ""
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> {
            val tenths = (n * 10) / (1024 * 1024)
            "${tenths / 10}.${tenths % 10} MB"
        }
    }

    /**
     * 下载浮层上那行进度文案。
     *
     * 四种状态必须分开说，关键是 **[pct] >= 100 单独一支**：到这一步字节已经落盘，
     * 还在说「正在下载…」用户就以为卡死了 —— 1.22 真机上「进度条跑满又跳回正在下载」
     * 就是少了这一支。
     *
     * [total] <= 0 = 服务端没给 Content-Length，只报已收多少，不报百分比。
     */
    fun progressLabel(pct: Int, done: Long, total: Long): String = when {
        pct >= 100 -> "已下载，正在打开…"
        pct < 0 -> "正在下载…" + byteSuffix(done, total)
        else -> "$pct%" + byteSuffix(done, total)
    }

    /** 已收 / 总量。有总量写「3.2 / 5.6 MB」，只有已收写「已收 3.2 MB」，都没有就不写 */
    private fun byteSuffix(done: Long, total: Long): String {
        if (done <= 0) return ""
        if (total <= 0) return "  已收 ${formatSize(done)}"
        return "  ${formatSize(done)} / ${formatSize(total)}"
    }

    // ---------------------------------------------------------------- 内部

    // 标签正则一律容忍标签上的任意属性（`(?:\s[^>]*)?`）——
    // AList 真机实测对目录返回 `<D:collection xmlns:D="DAV:"/>`，
    // 只认裸 `<D:collection/>` 会把**目录判成文件**，整个列表空得只剩一行跳过提示。
    private val RE_RESPONSE = Regex("""<(?:\w+:)?response(?:\s[^>]*)?>""")
    private val RE_HREF = Regex("""<(?:\w+:)?href(?:\s[^>]*)?>([\s\S]*?)</(?:\w+:)?href>""")
    private val RE_COLLECTION = Regex("""<(?:\w+:)?collection(?:\s[^>]*)?/?>""")
    private val RE_LEN =
        Regex("""<(?:\w+:)?getcontentlength(?:\s[^>]*)?>([\s\S]*?)</(?:\w+:)?getcontentlength>""")

    /** 剥掉 `scheme://host[:port]`，只留路径。用来让两种 href 写法可比 */
    private fun pathOnly(p: String): String {
        val s = p.trim()
        val i = s.indexOf("://")
        if (i < 0) return s
        val rest = s.substring(i + 3)
        val j = rest.indexOf('/')
        return if (j < 0) "/" else rest.substring(j)
    }

    private const val HEX = "0123456789ABCDEF"

    /** 缓存文件名的长度上限（.pdf 之外那一段）。留 11 字节余量给扩展名 */
    private const val CACHE_NAME_LIMIT = 240

    /** 超长时截到这么长，再补 `-` + 8 位哈希 */
    private const val CACHE_NAME_TRIM = 180

    private fun hexOf(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * 解 `%E5%85%89` 这类转义。
     *
     * 整串收集成字节再一次性 `decodeToString`，而不是逐字符拼：
     * 中文是多字节的，逐字符拼会把一个字拆成两段乱码。
     * 坏转义（不是两位十六进制）按原样留下，不抛异常。
     */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) {
                run.toString().encodeToByteArray().forEach { bytes.add(it) }
                run.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hexOf(s[i + 1])
                val lo = hexOf(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    flush()
                    bytes.add(((hi shl 4) + lo).toByte())
                    i += 3
                    continue
                }
            }
            run.append(c)
            i++
        }
        flush()
        return ByteArray(bytes.size) { bytes[it] }.decodeToString()
    }

    /** 路径分段编码。`/` 也编掉 —— 段名里不该出现分隔符 */
    private fun percentEncode(s: String): String {
        val sb = StringBuilder(s.length)
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            val keep = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '-' || c == '.' || c == '_' || c == '~'
            if (keep) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
        }
        return sb.toString()
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 标准 base64。自己写是因为 commonMain 没有 `java.util.Base64` */
    private fun base64(bytes: ByteArray): String {
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val v = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(B64[(v shr 18) and 63])
                .append(B64[(v shr 12) and 63])
                .append(B64[(v shr 6) and 63])
                .append(B64[v and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val v = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(B64[(v shr 18) and 63]).append(B64[(v shr 12) and 63]).append("==")
            }
            2 -> {
                val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(v shr 18) and 63])
                    .append(B64[(v shr 12) and 63])
                    .append(B64[(v shr 6) and 63])
                    .append('=')
            }
        }
        return sb.toString()
    }
}
