package com.example.scoreapp

import com.example.scoreapp.domain.netdisk.Netdisk
import com.example.scoreapp.domain.netdisk.NetdiskCached
import com.example.scoreapp.domain.netdisk.NetdiskConfig
import com.example.scoreapp.domain.netdisk.NetdiskEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 网盘（AList / WebDAV）纯逻辑回归。对应原型 `netdisk-test.js`。
 *
 * 锁的是**最容易走样且错了不会报错**的几条：
 * 1. **PROPFIND 会把目录自己一起返回** —— 不过滤列表里永远多一个幽灵项；
 * 2. **非 PDF 不列但要交代** —— 只筛不数，用户会以为文件丢了；
 * 3. **路径拼接要百分号编码中文**，且上一级到根就停，不能把地址削成空；
 * 4. **错误不许显示裸 HTTP 数字**（404 几乎总是地址少了 /dav）；
 * 5. **缓存只留最近 1 份**。
 */
class NetdiskTest {

    // ------------------------------------------------ 只认 PDF

    @Test
    fun `大小写都算 PDF`() {
        assertTrue(Netdisk.isPdfName("月光.pdf"))
        assertTrue(Netdisk.isPdfName("MOON.PDF"), "网盘上文件名大小写很随意")
    }

    @Test
    fun `别的后缀不算`() {
        assertFalse(Netdisk.isPdfName("说明.txt"))
        assertFalse(Netdisk.isPdfName("假货.pdfx"))
        assertFalse(Netdisk.isPdfName(""))
    }

    // ------------------------------------------------ 路径与名字

    @Test
    fun `取最后一段`() {
        assertEquals("钢琴", Netdisk.baseName("/dav/钢琴/"))
        assertEquals("a.pdf", Netdisk.baseName("a.pdf"), "没有斜杠时整个就是名字")
    }

    @Test
    fun `名字要解百分号转义`() {
        assertEquals("钢琴", Netdisk.baseName("/dav/%E9%92%A2%E7%90%B4"))
        assertEquals("a b.pdf", Netdisk.baseName("/dav/a%20b.pdf"))
    }

    @Test
    fun `规范化路径`() {
        assertEquals("/dav", Netdisk.normPath("/dav/"))
        assertEquals("/dav/钢琴", Netdisk.normPath("/dav/%E9%92%A2%E7%90%B4"))
    }

    // ------------------------------------------------ PROPFIND 解析

    private val XML = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/%E9%92%A2%E7%90%B4/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/moon.pdf</d:href>
    <d:propstat><d:prop><d:getcontentlength>881869</d:getcontentlength></d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/notes.txt</d:href>
    <d:propstat><d:prop><d:getcontentlength>120</d:getcontentlength></d:prop></d:propstat>
  </d:response>
</d:multistatus>"""

    @Test
    fun `目录自身那条必须跳过`() {
        val parsed = Netdisk.parsePropfind(XML, "/dav")
        assertEquals(3, parsed.size, "4 条响应里要滤掉根目录自己")
        assertEquals("钢琴", parsed[0].name)
        assertTrue(parsed[0].dir)
        assertFalse(parsed[1].dir)
        assertEquals(881869L, parsed[1].size)
        // path 保留服务端给的 href：拿去解析/排错用，真正发请求的地址是自己拼的
        assertEquals("/dav/moon.pdf", parsed[1].path)
    }

    @Test
    fun `self 写错时过滤不生效 说明过滤靠的是 self`() {
        assertEquals(4, Netdisk.parsePropfind(XML, "/nope").size)
    }

    @Test
    fun `self 带末尾斜杠也是同一个目录`() {
        assertEquals(3, Netdisk.parsePropfind(XML, "/dav/").size)
    }

    @Test
    fun `服务端回完整 URL 也能认出目录自身`() {
        // 换一种 href 写法（有些实现回绝对 URL），过滤照样要生效
        val abs = XML.replace("<d:href>/dav/</d:href>", "<d:href>https://x.cn/dav/</d:href>")
        assertEquals(3, Netdisk.parsePropfind(abs, "https://x.cn/dav").size)
    }

    @Test
    fun `坏报文不炸 返回空`() {
        assertEquals(0, Netdisk.parsePropfind("", "/dav").size)
        assertEquals(0, Netdisk.parsePropfind("<not-xml", "/dav").size)
    }

    @Test
    fun `AList 真实形态 collection 带属性也要认出目录`() {
        // AList 对目录返回 `<D:collection xmlns:D="DAV:"/>` —— collection 标签带命名空间属性。
        // 判定正则若只认裸 `<D:collection/>`，目录会被误判成文件，症状是
        // 「列表全空，只剩一行已跳过 N 个非 PDF」（1.16 真机实锤）。
        val alist = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E5%A4%B8%E5%85%8B%E7%BD%91%E7%9B%98/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E5%A4%B8%E5%85%8B%E7%BD%91%E7%9B%98/%E6%9C%88%E5%85%89.pdf</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>881869</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
</D:multistatus>"""
        val parsed = Netdisk.parsePropfind(alist, "/dav")
        assertEquals(2, parsed.size, "根自身要被跳过")
        assertTrue(parsed[0].dir, "带属性的 collection 必须认成目录")
        assertEquals("夸克网盘", parsed[0].name)
        assertEquals(881869L, parsed[1].size)
        assertEquals(0, Netdisk.skippedCount(parsed), "目录不再被算进跳过数")
        assertEquals(listOf("月光.pdf"), Netdisk.pdfEntries(parsed).map { it.name })
    }

    // ------------------------------------------------ 排序

    @Test
    fun `目录在前 各自按名`() {
        val mixed = listOf(
            NetdiskEntry("moon.pdf", "/dav/moon.pdf", false, 1),
            NetdiskEntry("钢琴", "/dav/钢琴", true, 0),
            NetdiskEntry("apple.pdf", "/dav/apple.pdf", false, 1),
            NetdiskEntry("拜厄", "/dav/拜厄", true, 0),
        )
        val sorted = Netdisk.sortEntries(mixed)
        assertTrue(sorted[0].dir && sorted[1].dir, "两条目录排在最前")
        assertEquals("拜厄", sorted[0].name, "中文按拼音：bai 在 gang 前")
        assertFalse(sorted[2].dir)
        assertEquals(listOf("apple.pdf", "moon.pdf"), listOf(sorted[2].name, sorted[3].name))
    }

    @Test
    fun `排序不改原列表`() {
        val mixed = listOf(NetdiskEntry("b.pdf", "/b", false, 0), NetdiskEntry("a.pdf", "/a", false, 0))
        Netdisk.sortEntries(mixed)
        assertEquals("b.pdf", mixed[0].name)
    }

    // ------------------------------------------------ 挑 PDF / 统计跳过

    @Test
    fun `只要 PDF`() {
        val all = Netdisk.parsePropfind(XML, "/dav")
        assertEquals(listOf("moon.pdf"), Netdisk.pdfEntries(all).map { it.name })
    }

    @Test
    fun `非 PDF 要数出来 不能一声不响地藏掉`() {
        val all = Netdisk.parsePropfind(XML, "/dav")
        assertEquals(1, Netdisk.skippedCount(all))
    }

    @Test
    fun `目录不算被跳过`() {
        assertEquals(0, Netdisk.skippedCount(listOf(NetdiskEntry("d", "/d", true, 0))))
        assertEquals(0, Netdisk.skippedCount(listOf(NetdiskEntry("a.pdf", "/a.pdf", false, 1))))
    }

    // ------------------------------------------------ 路径拼接与回退

    @Test
    fun `拼接会转义中文`() {
        assertEquals("/dav/%E9%92%A2%E7%90%B4", Netdisk.joinPath("/dav", "钢琴"))
        // 拼出来再解回去得是原名，否则导航会越走越歪
        assertEquals("钢琴", Netdisk.baseName(Netdisk.joinPath("/dav", "钢琴")))
    }

    @Test
    fun `拼接不重复斜杠`() {
        assertEquals("/dav/a", Netdisk.joinPath("/dav/", "a"))
    }

    // ------------------------------------------------ 请求前规范地址（真机踩出来的）

    @Test
    fun `解码过的中文地址要编回去才能发请求`() {
        // 绑定目录存的是 normPath 解码后的串；不编回去，请求行里中文会变成 `?`，
        // 服务端就把路径截断在第一个 `?` 上，列到的是根目录 —— 同步「成功」但一份都没有
        assertEquals(
            "http://alist.example.com:5244/dav/quark/%E6%88%91%E7%9A%84%E5%A4%87%E4%BB%BD/Forscore%E5%90%8C%E6%AD%A5",
            Netdisk.encodeUrl("http://alist.example.com:5244/dav/quark/我的备份/Forscore同步"),
        )
    }

    @Test
    fun `规范地址幂等 已编码的不会被双重编码`() {
        val once = Netdisk.encodeUrl("http://h:5244/dav/quark/%E4%B9%90%E8%B0%B1/a.pdf")
        assertEquals(once, Netdisk.encodeUrl(once))
        assertFalse(once.contains("%25"), "双重编码会变成 %25E4 这种")
    }

    @Test
    fun `规范地址不动 host 与端口`() {
        assertEquals("http://h:5244/dav/a", Netdisk.encodeUrl("http://h:5244/dav/a"))
        assertEquals("http://h/dav/", Netdisk.encodeUrl("http://h/dav/"))
        assertEquals("http://h", Netdisk.encodeUrl("http://h"), "只有 host 没路径就原样返回")
    }

    @Test
    fun `半解半编的混合地址也能救回来`() {
        // 同步里 joinPath(解码的目录, 编码的文件名) 就会拼出这种串
        assertEquals(
            "http://h/dav/%E4%B9%90%E8%B0%B1/%E6%9C%88%E5%85%89.pdf",
            Netdisk.encodeUrl("http://h/dav/乐谱/%E6%9C%88%E5%85%89.pdf"),
        )
    }

    @Test
    fun `空格与特殊字符也要编掉`() {
        assertEquals("http://h/dav/a%20b%20c.pdf", Netdisk.encodeUrl("http://h/dav/a b c.pdf"))
        assertFalse(Netdisk.encodeUrl("http://h/dav/a?b=c").contains("?/"), "路径里的 ? 不能漏出去当查询串")
    }

    @Test
    fun `上一级`() {
        assertEquals("/dav/a", Netdisk.parentPath("/dav/a/b", "/dav"))
        assertEquals("/dav", Netdisk.parentPath("/dav/a", "/dav"), "到根就停在根")
    }

    @Test
    fun `已经在根返回 null 不许把地址削成空`() {
        assertNull(Netdisk.parentPath("/dav", "/dav"))
        assertNull(Netdisk.parentPath("/dav/", "/dav"), "根带斜杠也算同一个根")
    }

    // ------------------------------------------------ 错误文案

    @Test
    fun `401 说清是账号密码`() {
        assertTrue(Netdisk.errorText("401").contains("账号或密码"))
    }

    @Test
    fun `404 要直接点明地址末尾必须带 dav`() {
        assertTrue(Netdisk.errorText("404").contains("/dav"))
    }

    @Test
    fun `超时提醒公网地址`() {
        val t = Netdisk.errorText("timeout")
        assertTrue(t.contains("超时") || t.contains("公网"))
    }

    @Test
    fun `403 说没权限`() {
        assertTrue(Netdisk.errorText("403").contains("权限"))
    }

    @Test
    fun `未知状态码带上数字 但仍是一句人话`() {
        assertTrue(Netdisk.errorText("http:500").contains("500"))
        assertTrue(Netdisk.errorText("??").isNotEmpty(), "认不出也有兜底文案")
    }

    // ------------------------------------------------ 认证头

    @Test
    fun `Basic 头算对了`() {
        assertEquals("Basic YWRtaW46MTIzNA==", Netdisk.basicAuth("admin", "1234"))
    }

    @Test
    fun `中文密码不炸`() {
        // base64 前必须过 UTF-8 字节，否则非 ASCII 会得到错的值
        assertTrue(Netdisk.basicAuth("admin", "密码").startsWith("Basic "))
        assertTrue(Netdisk.basicAuth("admin", "密码").length > 6)
    }

    // ------------------------------------------------ 缓存

    @Test
    fun `缓存键用规范化路径`() {
        assertEquals("/dav/a b.pdf", Netdisk.cacheKeyOf("/dav/a%20b.pdf"))
    }

    @Test
    fun `淘汰后只留最近一份`() {
        val c = mapOf(
            "/dav/a.pdf" to NetdiskCached("a", 1, "/dav/a.pdf", "/tmp/a"),
            "/dav/b.pdf" to NetdiskCached("b", 2, "/dav/b.pdf", "/tmp/b"),
        )
        val kept = Netdisk.evictCache(c, "/dav/b.pdf")
        assertEquals(1, kept.size)
        assertEquals("b", kept["/dav/b.pdf"]?.name)
    }

    @Test
    fun `key 不在缓存里就全清`() {
        val c = mapOf("/dav/a.pdf" to NetdiskCached("a", 1, "/dav/a.pdf", "/tmp/a"))
        assertTrue(Netdisk.evictCache(c, "/dav/z.pdf").isEmpty())
    }

    @Test
    fun `不同目录下的同名文件不会共用一份缓存`() {
        // 只按文件名存会互相覆盖，所以整个远端路径都要编进文件名
        assertNotEquals(
            Netdisk.cacheFileName("/dav/钢琴/a.pdf"),
            Netdisk.cacheFileName("/dav/声乐/a.pdf"),
        )
        assertFalse(Netdisk.cacheFileName("/dav/钢琴/a.pdf").contains("/"), "文件名里不能出现分隔符")
    }

    @Test
    fun `服务端给的 href 要补上 host 才发得出请求`() {
        // AList 回的是**不带 host 的绝对路径**，直接 URL(...) 会抛 MalformedURLException，
        // 请求根本没发出去 —— 1.19 之前每一份谱子都「下载失败」就是这个
        assertEquals(
            "http://alist.example.com:5244/dav/quark/乐谱/月光.pdf",
            Netdisk.absoluteUrl("http://alist.example.com:5244/dav", "/dav/quark/乐谱/月光.pdf"),
        )
        assertEquals(
            "https://x.cn/dav/a.pdf",
            Netdisk.absoluteUrl("https://x.cn/dav/quark", "/dav/a.pdf"),
            "只取 scheme://host，base 自己的路径不参与",
        )
        assertEquals(
            "http://h:1/a.pdf",
            Netdisk.absoluteUrl("http://h:1", "a.pdf"),
            "没带前导斜杠的也要补分隔符",
        )
        assertEquals(
            "https://x.cn/dav/a.pdf",
            Netdisk.absoluteUrl("https://x.cn/dav", "https://x.cn/dav/a.pdf"),
            "已经带 scheme 的原样返回",
        )
    }

    @Test
    fun `超长路径的缓存文件名不会顶破文件系统上限`() {
        // 真机实测：Z-Library 那种长名编出来 281 字节，超过 ext4 的 255 上限，
        // FileOutputStream 直接抛异常 —— 那一份永远下不来
        val long = "/dav/quark/我的备份/Forscore同步/" + "扬·艾凯尔编订 (波)F.肖邦 ".repeat(12) + ".pdf"
        val name = Netdisk.cacheFileName(long)
        assertTrue(name.length <= 255, "单个名字超过 255 字节会让下载直接失败：$name")
        assertFalse(name.contains("/"), "文件名里不能出现分隔符")
        assertEquals(name, Netdisk.cacheFileName(long), "同一路径必须永远得到同一个名字")
        assertNotEquals(Netdisk.cacheFileName(long + "2.pdf"), name, "不同的路径不能撞车")
    }

    @Test
    fun `封面配色按路径稳定分配且落在色板内`() {
        assertEquals(10, Netdisk.COVER_PALETTES.size)
        assertEquals(
            Netdisk.coverPalette("/dav/quark/乐谱/月光.pdf"),
            Netdisk.coverPalette("/dav/quark/乐谱/月光.pdf"),
            "同一份谱子永远同一组颜色",
        )
        for (p in listOf("", "/dav/a.pdf", "/dav/我的备份/Forscore同步/再回首.pdf")) {
            assertTrue(Netdisk.coverPalette(p) in Netdisk.COVER_PALETTES.indices, "越界：$p")
        }
        // 取高 16 位就是为了让相近路径也能铺开 —— 520 份的库不许挤在一两格里
        val seen = (1..520).map { Netdisk.coverPalette("/dav/quark/乐谱/谱$it.pdf") }.toSet()
        assertTrue(seen.size >= 6, "520 份只落到 ${seen.size} 组配色，色板等于白设")
    }

    // ------------------------------------------------ 体积文案

    @Test
    fun `体积按量级换单位`() {
        assertEquals("", Netdisk.formatSize(0), "0 不占位")
        assertEquals("120 B", Netdisk.formatSize(120))
        assertEquals("861 KB", Netdisk.formatSize(881869))
        assertEquals("1.1 MB", Netdisk.formatSize(1204551))
    }

    // ------------------------------------------------ 配置落盘

    @Test
    fun `配置能存能读`() {
        val cfg = NetdiskConfig(addr = "https://x.cn/dav", user = "admin", pass = "密 码")
        val back = NetdiskConfig.fromJson(NetdiskConfig.toJson(cfg))
        assertEquals(cfg, back)
    }

    @Test
    fun `坏存档退成默认值 不抛异常`() {
        assertEquals(NetdiskConfig(), NetdiskConfig.fromJson(null))
        assertEquals(NetdiskConfig(), NetdiskConfig.fromJson(""))
        assertEquals(NetdiskConfig(), NetdiskConfig.fromJson("{不是 json"))
        assertEquals(NetdiskConfig(), NetdiskConfig.fromJson("[]"))
    }

    @Test
    fun `三项都填了才算配好`() {
        assertTrue(NetdiskConfig("https://x.cn/dav", "admin", "pw").ready)
        assertFalse(NetdiskConfig("", "admin", "pw").ready)
        assertFalse(NetdiskConfig("https://x.cn/dav", "", "pw").ready)
        assertFalse(NetdiskConfig("https://x.cn/dav", "admin", "").ready, "空口令不算配好")
    }

    // ------------------------------------------------ 下载浮层文案

    @Test
    fun `进度条跑满之后不许倒回正在下载`() {
        // 1.22 真机：进度跑满、随后又显示「正在下载…」，看着像卡死。
        // 100 的含义必须是「字节真的收完了」，那时该说的是「正在打开」
        assertEquals("已下载，正在打开…", Netdisk.progressLabel(100, 6_291_456, 6_291_456))
        assertEquals(
            "已下载，正在打开…",
            Netdisk.progressLabel(137, 6_291_456, 6_291_456),
            "服务端少报长度也不能退回旧文案",
        )
    }

    @Test
    fun `下载中同时报百分比与已收字节`() {
        assertEquals("37%  2.0 MB / 6.0 MB", Netdisk.progressLabel(37, 2_097_152, 6_291_456))
        assertEquals("0%", Netdisk.progressLabel(0, 0, 0), "刚开始不留空")
    }

    @Test
    fun `服务端没给总长度时只说已收多少`() {
        assertEquals("正在下载…", Netdisk.progressLabel(-1, 0, 0))
        assertEquals("正在下载…  已收 1.0 MB", Netdisk.progressLabel(-1, 1_048_576, 0))
        assertEquals(
            "正在下载…  512 KB / 6.0 MB",
            Netdisk.progressLabel(-1, 524_288, 6_291_456),
            "有总量就得把量级说出来，否则用户对还要多久没概念",
        )
    }
}
