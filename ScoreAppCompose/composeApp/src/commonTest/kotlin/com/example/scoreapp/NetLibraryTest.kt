package com.example.scoreapp

import com.example.scoreapp.domain.library.ImportedScore
import com.example.scoreapp.domain.library.LocalStore
import com.example.scoreapp.domain.library.applyLocalMeta
import com.example.scoreapp.domain.library.saveLocalMeta
import com.example.scoreapp.domain.library.scoreKeyOf
import com.example.scoreapp.domain.library.toScore
import com.example.scoreapp.domain.netdisk.NetLibItem
import com.example.scoreapp.domain.netdisk.NetLibStore
import com.example.scoreapp.domain.netdisk.NetLibrary
import com.example.scoreapp.domain.netdisk.NetSyncState
import com.example.scoreapp.domain.netdisk.Netdisk
import com.example.scoreapp.domain.netdisk.NetdiskEntry
import com.example.scoreapp.domain.netdisk.RemoteDir
import com.example.scoreapp.model.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 网盘**入库乐谱库**的语义回归测试。
 *
 * 与 `prototype/netdisk-library-test.js` 同一批断言 —— 原型与 Compose 必须逐条对得上。
 * 重点锁两条最容易改坏的：
 *  1. **同步绝不覆盖用户改过的元数据**（破了的话用户每次同步都丢自己填的东西，且未必马上发现）；
 *  2. **条目身份是远端路径不是文件名**（不同目录下的同名谱子必须各自独立）。
 */
class NetLibraryTest {

    private fun entry(name: String, path: String, size: Long = 0L, dir: Boolean = false) =
        NetdiskEntry(name = name, path = path, dir = dir, size = size)

    private fun remote(vararg files: NetdiskEntry) = listOf(RemoteDir("/dav/乐谱", files.toList()))

    // ------------------------------------------------------------ 文件名 → 默认元数据

    @Test
    fun `文件名去扩展名当标题`() {
        assertEquals("拜厄 No.1", NetLibrary.titleFromFileName("拜厄 No.1.pdf"))
        assertEquals("MOON", NetLibrary.titleFromFileName("MOON.PDF"))
        // 曲名里的点不能被当成扩展名
        assertEquals("Op.27 No.2", NetLibrary.titleFromFileName("Op.27 No.2.pdf"))
        assertEquals("欢乐颂", NetLibrary.titleFromFileName("欢乐颂"))
        assertEquals("", NetLibrary.titleFromFileName(""))
    }

    @Test
    fun `默认元数据沿用校对页那套占位符`() {
        val it = NetLibrary.defaultMetaOf("月光.pdf", "/dav/乐谱/月光.pdf", 881869)
        assertEquals("月光", it.title)
        assertEquals("佚名", it.composer)
        assertEquals("未编目", it.type)
        assertEquals("未分类", it.instrument)
        assertEquals("未指定", it.period)
        assertEquals("—", it.level)
        assertEquals("网盘", it.source)
        assertEquals(881869L, it.size)
    }

    @Test
    fun `身份键是规范化路径 不是文件名`() {
        assertEquals("/dav/乐谱/月光.pdf", NetLibrary.metaKeyOf("/dav/乐谱/月光.pdf/"))
        assertTrue(
            NetLibrary.metaKeyOf("/dav/钢琴/拜厄.pdf") != NetLibrary.metaKeyOf("/dav/声乐/拜厄.pdf"),
            "不同目录下的同名谱子必须各自独立",
        )
    }

    // ------------------------------------------------------------ 封面卡

    @Test
    fun `封面字段过滤占位符`() {
        val bare = NetLibrary.coverFor(NetLibrary.defaultMetaOf("月光.pdf", "/dav/乐谱/月光.pdf", 1))
        assertEquals(null, bare.composer, "佚名不印")
        assertEquals(null, bare.sub, "未编目/未分类不印")
        assertTrue(bare.palette in Netdisk.COVER_PALETTES.indices)

        val full = NetLibrary.coverFor(
            NetLibrary.defaultMetaOf("月光.pdf", "/dav/乐谱/月光.pdf", 1).copy(
                composer = "贝多芬",
                type = "奏鸣曲",
                instrument = "钢琴",
            ),
        )
        assertEquals("贝多芬", full.composer)
        assertEquals("奏鸣曲 · 钢琴", full.sub)
        assertEquals(Netdisk.coverPalette("/dav/乐谱/月光.pdf"), full.palette, "配色跟远端路径走")
    }

    @Test
    fun `applyMeta 只盖有的字段 空串不算`() {
        val base = NetLibrary.defaultMetaOf("月光.pdf", "/dav/乐谱/月光.pdf", 1)
        val merged = NetLibrary.applyMeta(base, mapOf("composer" to "贝多芬", "title" to "月光奏鸣曲"))
        assertEquals("月光奏鸣曲", merged.title)
        assertEquals("贝多芬", merged.composer)
        assertEquals("未编目", merged.type)
        assertEquals("佚名", base.composer, "applyMeta 不能改原对象")
        assertEquals("月光", NetLibrary.applyMeta(base, null).title)
        assertEquals("佚名", NetLibrary.applyMeta(base, mapOf("composer" to "")).composer)
    }

    // ------------------------------------------------------------ 同步

    @Test
    fun `非 PDF 与目录都不进库`() {
        val r = NetLibrary.syncLibrary(
            emptyList(), emptyMap(),
            remote(
                entry("月光.pdf", "/dav/乐谱/月光.pdf", 881869),
                entry("拜厄.pdf", "/dav/乐谱/拜厄.pdf", 245760),
                entry("目录.txt", "/dav/乐谱/目录.txt", 120),
                entry("子目录", "/dav/乐谱/子目录", dir = true),
            ),
        )
        assertEquals(2, r.items.size)
        assertEquals(2, r.added)
        assertEquals(0, r.removed)
    }

    @Test
    fun `同样的远端再同步不重复加`() {
        val one = remote(entry("月光.pdf", "/dav/乐谱/月光.pdf", 1))
        val r1 = NetLibrary.syncLibrary(emptyList(), emptyMap(), one)
        val r2 = NetLibrary.syncLibrary(r1.items, emptyMap(), one)
        assertEquals(1, r1.items.size)
        assertEquals(r1.items.size, r2.items.size)
        assertEquals(0, r2.added)
        assertEquals(0, r2.removed)
    }

    @Test
    fun `同步不覆盖用户改过的元数据`() {
        val files = remote(
            entry("月光.pdf", "/dav/乐谱/月光.pdf", 881869),
            entry("拜厄.pdf", "/dav/乐谱/拜厄.pdf", 245760),
        )
        val r1 = NetLibrary.syncLibrary(emptyList(), emptyMap(), files)
        val metas = NetLibrary.saveMetaDraft(
            emptyMap(),
            "/dav/乐谱/月光.pdf",
            mapOf("title" to "月光奏鸣曲", "composer" to "贝多芬", "instrument" to ""),
        )
        val r2 = NetLibrary.syncLibrary(r1.items, metas, files)
        assertEquals("月光奏鸣曲", r2.items.first { it.key.endsWith("月光.pdf") }.title)
        assertEquals("贝多芬", r2.items.first { it.key.endsWith("月光.pdf") }.composer)
        assertEquals("佚名", r2.items.first { it.key.endsWith("拜厄.pdf") }.composer, "没改过的仍是默认")
    }

    @Test
    fun `远端消失的条目被移除 但元数据留着`() {
        val files = remote(entry("月光.pdf", "/dav/乐谱/月光.pdf", 1))
        val r1 = NetLibrary.syncLibrary(emptyList(), emptyMap(), files)
        val metas = NetLibrary.saveMetaDraft(emptyMap(), "/dav/乐谱/月光.pdf", mapOf("composer" to "贝多芬"))
        val r2 = NetLibrary.syncLibrary(r1.items, metas, remote())
        assertEquals(0, r2.items.size)
        assertEquals(1, r2.removed)
        assertEquals(1, metas.size, "改名 / 误删的容错：元数据不跟着删")
    }

    @Test
    fun `两个绑定目录命中同一份只进一次`() {
        val file = entry("月光.pdf", "/dav/乐谱/月光.pdf", 999)
        val r = NetLibrary.syncLibrary(
            emptyList(), emptyMap(),
            listOf(RemoteDir("/dav/乐谱", listOf(file)), RemoteDir("/dav/备份", listOf(file))),
        )
        assertEquals(1, r.items.size)
    }

    @Test
    fun `体积以远端为准`() {
        val r = NetLibrary.syncLibrary(emptyList(), emptyMap(), remote(entry("月光.pdf", "/dav/乐谱/月光.pdf", 555)))
        assertEquals(555L, r.items[0].size)
        val again = NetLibrary.syncLibrary(r.items, emptyMap(), remote(entry("月光.pdf", "/dav/乐谱/月光.pdf", 777)))
        assertEquals(777L, again.items[0].size)
    }

    // ------------------------------------------------------------ 编辑草稿

    @Test
    fun `草稿只存非空字段 全空则整条删掉`() {
        val m1 = NetLibrary.saveMetaDraft(emptyMap(), "/a.pdf", mapOf("composer" to "贝多芬", "title" to ""))
        assertEquals(listOf("composer"), m1["/a.pdf"]?.keys?.toList())
        val m2 = NetLibrary.saveMetaDraft(m1, "/a.pdf", mapOf("composer" to ""))
        assertEquals(null, m2["/a.pdf"])
        assertEquals(0, m2.size)
        val m3 = NetLibrary.saveMetaDraft(mapOf("/b.pdf" to mapOf("title" to "拜厄")), "/a.pdf", mapOf("composer" to "车尔尼"))
        assertEquals("拜厄", m3["/b.pdf"]?.get("title"), "不影响别的条目")
        assertEquals(1, m1.size, "saveMetaDraft 不改原对象")
    }

    // ------------------------------------------------------------ 绑定

    @Test
    fun `绑定与解绑`() {
        var b = emptyList<String>()
        b = NetLibrary.toggleBound(b, "/dav/乐谱")
        assertTrue(NetLibrary.isBound(b, "/dav/乐谱"))
        b = NetLibrary.toggleBound(b, "/dav/伴奏")
        assertEquals(2, b.size)
        b = NetLibrary.toggleBound(b, "/dav/乐谱")
        assertFalse(NetLibrary.isBound(b, "/dav/乐谱"))
        assertTrue(NetLibrary.isBound(listOf("/dav/乐谱"), "/dav/乐谱/"), "末尾斜杠不算两个")
        assertEquals("已绑定 2 个文件夹", NetLibrary.boundSummary(listOf("/a", "/b")))
        assertEquals("还没绑定文件夹", NetLibrary.boundSummary(emptyList()))
    }

    @Test
    fun `缓存只有一份 所以除了它都要下载`() {
        assertTrue(NetLibrary.needDownload("/a.pdf", emptySet()))
        assertFalse(NetLibrary.needDownload("/a.pdf", setOf("/a.pdf")))
        assertTrue(NetLibrary.needDownload("/a.pdf", setOf("/b.pdf")))
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `多久之前`() {
        assertEquals("从没同步过", NetLibrary.formatAgo(0))
        assertEquals("刚刚", NetLibrary.formatAgo(30_000))
        assertEquals("5 分钟前", NetLibrary.formatAgo(5 * 60_000))
        assertEquals("2 小时前", NetLibrary.formatAgo(2 * 3_600_000))
        assertEquals("3 天前", NetLibrary.formatAgo(3 * 86_400_000))
    }

    @Test
    fun `同步条三种态都要说清`() {
        assertEquals("正在同步…", NetLibrary.syncStatusText(NetSyncState(busy = true, boundCount = 1)))
        assertEquals(
            "还没绑定文件夹，点「绑定文件夹」去选一个目录",
            NetLibrary.syncStatusText(NetSyncState(boundCount = 0)),
        )
        assertEquals(
            "还没同步过，点「立即同步」",
            NetLibrary.syncStatusText(NetSyncState(boundCount = 1, syncedAt = 0)),
        )
        val err = NetLibrary.syncStatusText(
            NetSyncState(boundCount = 1, error = "连不上网盘", syncedAt = 1, ageMs = 5 * 60_000),
        )
        assertTrue(err.contains("同步失败") && err.contains("5 分钟前"), err)
        val ok = NetLibrary.syncStatusText(
            NetSyncState(boundCount = 1, boundPaths = listOf("/dav/乐谱"), syncedAt = 1, ageMs = 30_000),
        )
        assertTrue(ok.contains("已同步") && ok.contains("刚刚"), ok)
    }

    // ------------------------------------------------------------ 存档

    @Test
    fun `网盘存档往返 中文与空都不丢`() {
        val item = NetLibItem(
            key = "/dav/夸克网盘/乐谱/月光 Op.27.pdf",
            title = "月光 Op.27", composer = "贝多芬", instrument = "钢琴",
            type = "奏鸣曲", period = "古典", level = "高级",
            source = "网盘", remotePath = "/dav/夸克网盘/乐谱/月光 Op.27.pdf", size = 881869,
        )
        val store = NetLibStore(
            items = listOf(item),
            metas = mapOf(item.key to mapOf("composer" to "贝多芬")),
            bound = listOf("/dav/夸克网盘/乐谱"),
            syncedAt = 1_700_000_000_000L,
        )
        val back = NetLibStore.fromJson(store.toJson())
        assertEquals(1, back.items.size)
        assertEquals("月光 Op.27", back.items[0].title)
        assertEquals("贝多芬", back.items[0].composer)
        assertEquals(881869L, back.items[0].size)
        assertEquals("贝多芬", back.metas[item.key]?.get("composer"))
        assertEquals(listOf("/dav/夸克网盘/乐谱"), back.bound)
        assertEquals(1_700_000_000_000L, back.syncedAt)
    }

    @Test
    fun `坏存档不炸 一律退空`() {
        assertEquals(0, NetLibStore.fromJson(null).items.size)
        assertEquals(0, NetLibStore.fromJson("").items.size)
        assertEquals(0, NetLibStore.fromJson("{不是 json").items.size)
        assertEquals(0, NetLibStore.fromJson("{\"items\":\"oops\"}").items.size)
    }

    // ------------------------------------------------------------ 本机那半持久化

    /** `Score` 的 type / instrument / period / level / source 都没有默认值，构造时要带齐 */
    private fun score(
        id: Long = 0L,
        title: String = "月光",
        composer: String = "贝多芬",
        assetPdf: String? = null,
        filePath: String? = null,
    ): Score = Score(
        id = id,
        title = title,
        composer = composer,
        type = "奏鸣曲",
        instrument = "钢琴",
        period = "古典",
        level = "高级",
        source = "Henle 原版",
        assetPdf = assetPdf,
        filePath = filePath,
    )

    @Test
    fun `本机条目的身份键优先 assetPdf`() {
        assertEquals("asset:moonlight.pdf", scoreKeyOf(score(id = 1, assetPdf = "moonlight.pdf")))
        assertEquals(
            "file:/data/x.pdf",
            scoreKeyOf(score(id = 2, title = "拜厄", composer = "佚名", filePath = "/data/x.pdf")),
        )
    }

    @Test
    fun `本机元数据改得回去也退得回来`() {
        val s = score(id = 1, assetPdf = "moonlight.pdf")
        val applied = applyLocalMeta(s, mapOf("title" to "月光奏鸣曲", "level" to ""))
        assertEquals("月光奏鸣曲", applied.title)
        assertEquals(s.level, applied.level, "空串不生效")
        assertEquals("月光", s.title, "applyLocalMeta 不改原对象")
    }

    @Test
    fun `本机草稿存与清`() {
        val m1 = saveLocalMeta(emptyMap(), "asset:m.pdf", mapOf("composer" to "贝多芬", "title" to "  "))
        assertEquals(listOf("composer"), m1["asset:m.pdf"]?.keys?.toList())
        val m2 = saveLocalMeta(m1, "asset:m.pdf", mapOf("composer" to ""))
        assertEquals(0, m2.size)
    }

    @Test
    fun `导入记录能重建曲库条目`() {
        val rec = ImportedScore(
            title = "拜厄", composer = "佚名", type = "未编目", instrument = "未分类",
            period = "未指定", level = "—", source = "本地导入", pages = 12,
            dateAdded = 1_700_000_000_000L, filePath = "/data/beyer.pdf",
            thumbSeed = 42, thumbRows = 5,
        )
        val s = rec.toScore(7)
        assertEquals(7L, s.id)
        assertEquals("拜厄", s.title)
        assertEquals("/data/beyer.pdf", s.filePath)
        assertEquals(12, s.pages)
        assertEquals(42, s.thumbSeed)
        assertEquals(null, s.remotePath, "本机条目没有远端地址")
    }

    @Test
    fun `本机存档往返`() {
        val rec = ImportedScore(
            title = "喀秋莎", composer = "佚名", type = "伴奏", instrument = "手风琴",
            period = "现代", level = "—", source = "本地导入", pages = 3,
            dateAdded = 1_700_000_000_000L, filePath = "/data/k.pdf", thumbSeed = 9, thumbRows = 3,
        )
        val store = LocalStore(
            metas = mapOf("asset:m.pdf" to mapOf("title" to "月光奏鸣曲")),
            imported = listOf(rec),
            hidden = setOf("asset:gone.pdf"),
        )
        val back = LocalStore.fromJson(store.toJson())
        assertEquals(1, back.imported.size)
        assertEquals("喀秋莎", back.imported[0].title)
        assertEquals("/data/k.pdf", back.imported[0].filePath)
        assertEquals("月光奏鸣曲", back.metas["asset:m.pdf"]?.get("title"))
        assertEquals(setOf("asset:gone.pdf"), back.hidden)
        assertEquals(0, LocalStore.fromJson("坏文本").imported.size)
    }

    @Test
    fun `网盘条目映射成的曲库条目带远端地址`() {
        // 首页靠 remotePath 判断「要先下载」，靠 isNet 分标签页 —— 这两条缺一不可
        val item = NetLibrary.defaultMetaOf("月光.pdf", "/dav/乐谱/月光.pdf", 881869)
        val score = Score(
            id = 900_000L,
            title = item.title,
            composer = item.composer,
            type = item.type,
            instrument = item.instrument,
            period = item.period,
            level = item.level,
            source = item.source,
            remotePath = item.remotePath,
        )
        assertTrue(score.isNet)
        assertEquals("/dav/乐谱/月光.pdf", score.remotePath)
        assertEquals("/dav/乐谱/月光.pdf", Netdisk.normPath(score.remotePath!!))
    }

    // ------------------------------------------------ 打开请求（界面 effect 的 key）

    @Test
    fun `每次点击都是一次新请求`() {
        // key 不变 effect 就不会重跑：连点同一份谱子也得拿到不同的对象，
        // 否则用户看到的是「点了没反应」
        val a = NetLibrary.nextOpenRequest(null, "/dav/乐谱/月光.pdf")
        assertEquals(1, a.seq, "第一次从 1 起")
        val b = NetLibrary.nextOpenRequest(a, "/dav/乐谱/月光.pdf")
        assertNotEquals(a, b, "同一份谱子连点两次也要是两个请求")
        assertEquals(2, b.seq)
        assertEquals("/dav/乐谱/月光.pdf", b.remotePath)
    }

    @Test
    fun `请求带着要打开哪一份`() {
        val r = NetLibrary.nextOpenRequest(null, "/dav/乐谱/拜厄.pdf")
        assertEquals("/dav/乐谱/拜厄.pdf", r.remotePath, "effect 得知道下哪一份")
        assertEquals(
            NetLibrary.nextOpenRequest(r, "/dav/声乐/练声.pdf").remotePath,
            "/dav/声乐/练声.pdf",
            "换一份就换路径",
        )
    }
}
