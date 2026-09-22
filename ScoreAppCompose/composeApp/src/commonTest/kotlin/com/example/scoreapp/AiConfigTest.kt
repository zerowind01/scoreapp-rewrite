package com.example.scoreapp

import com.example.scoreapp.domain.csvfix.FixStore
import com.example.scoreapp.domain.csvfix.FixStoreHolder
import com.example.scoreapp.util.AiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AI 设置的存取与落盘。
 *
 * 这一套覆盖的是「原先那个假开关换成真配置」之后新长出来的三条路径：
 *  1. [AiConfig] 自己的 JSON 往返（含特殊字符）；
 *  2. 它并进 [FixStore] 之后不破坏原有的进度/采纳记录；
 *  3. **老存档（没有 `ai` 段）与坏存档都能优雅降级** ——
 *     这是最要紧的一条：线上用户的设备上已经躺着一份旧格式的
 *     `fix-store.json`，新版本读它时绝不能把「上次翻到第几条」一起弄丢。
 */
class AiConfigTest {

    // ------------------------------------------------------------ 基本属性

    @Test
    fun `默认配置未就绪`() {
        val c = AiConfig()
        assertEquals("https://api.deepseek.com/v1/chat/completions", c.endpoint)
        assertEquals("deepseek-chat", c.model)
        assertEquals("", c.apiKey)
        assertFalse(c.ready)
        assertFalse(c.configured)
    }

    @Test
    fun `三项填齐才算就绪`() {
        val full = AiConfig(endpoint = "https://x/v1/chat/completions", apiKey = "sk-1", model = "m")
        assertTrue(full.ready)

        // 逐项抠掉一个，都应当不就绪 —— 少任何一项都跑不起来
        assertFalse(full.copy(endpoint = "").ready)
        assertFalse(full.copy(apiKey = "").ready)
        assertFalse(full.copy(model = "").ready)
    }

    @Test
    fun `只有空白字符也不算就绪`() {
        val c = AiConfig(apiKey = "   ")
        assertTrue(c.apiKey.isNotBlank().not())
        assertFalse(c.ready)
    }

    // ------------------------------------------------------------ JSON 往返

    @Test
    fun `往返保持原值`() {
        val c = AiConfig(
            endpoint = "https://api.moonshot.cn/v1/chat/completions",
            apiKey = "sk-abc123XYZ",
            model = "kimi-k2",
        )
        assertEquals(c, AiConfig.fromJson(AiConfig.toJson(c)))
    }

    @Test
    fun `密钥里的特殊字符能安全往返`() {
        // 真实密钥里出现过引号、反斜杠、换行（多行粘贴时没剪干净）
        val nasty = "sk-\"quoted\"\\slash\nnewline\ttab"
        val c = AiConfig(apiKey = nasty)
        val back = AiConfig.fromJson(AiConfig.toJson(c))
        assertEquals(nasty, back.apiKey)
    }

    @Test
    fun `空密钥往返后仍是空`() {
        val c = AiConfig(endpoint = "https://a/v1/chat/completions", model = "m")
        val back = AiConfig.fromJson(AiConfig.toJson(c))
        assertEquals("", back.apiKey)
        assertFalse(back.ready)
    }

    // ------------------------------------------------------------ 降级

    @Test
    fun `坏 JSON 退化成默认值`() {
        assertEquals(AiConfig(), AiConfig.fromJson("{这不是 json"))
        assertEquals(AiConfig(), AiConfig.fromJson("[1,2,3]"))
        assertEquals(AiConfig(), AiConfig.fromJson("null"))
    }

    @Test
    fun `空串与 null 退化成默认值`() {
        assertEquals(AiConfig(), AiConfig.fromJson(null))
        assertEquals(AiConfig(), AiConfig.fromJson(""))
        assertEquals(AiConfig(), AiConfig.fromJson("   "))
    }

    @Test
    fun `缺字段时各自取默认`() {
        // 只有 model，没有 endpoint / apiKey
        val back = AiConfig.fromJson("{\"model\":\"glm-4\"}")
        assertEquals("glm-4", back.model)
        assertEquals(AiConfig.DEFAULT_ENDPOINT, back.endpoint)
        assertEquals("", back.apiKey)
    }

    @Test
    fun `字段为空串时取默认而不是留空`() {
        // 用户在设置页把地址清空后保存 → 不该变成「地址为空但系统认为已配置」
        val back = AiConfig.fromJson("{\"endpoint\":\"\",\"model\":\"\"}")
        assertEquals(AiConfig.DEFAULT_ENDPOINT, back.endpoint)
        assertEquals(AiConfig.DEFAULT_MODEL, back.model)
    }

    // ------------------------------------------------------------ 联网兜底（搜索）

    @Test
    fun `联网兜底默认开启`() {
        // 默认开而不是关：兜底只在「本来就要失败」时才发生，
        // 多花的只是一次搜索 + 一次补问 —— 默认关等于把功能藏起来
        assertTrue(AiConfig().searchOn)
    }

    @Test
    fun `searchReady 要开关与密钥都齐`() {
        // 只有开关没密钥：那种状态兜底必失败，不算 ready
        assertFalse(AiConfig().searchReady)
        assertFalse(AiConfig(searchOn = true).searchReady)
        // 只填了密钥、开关关着：也不该自动去搜
        assertFalse(AiConfig(searchKey = "tvly-1", searchOn = false).searchReady)
        assertTrue(AiConfig(searchKey = "tvly-1", searchOn = true).searchReady)
    }

    @Test
    fun `搜索配置往返保持原值`() {
        val c = AiConfig(searchOn = false, searchKey = "tvly-\"x\"\\y")
        val back = AiConfig.fromJson(AiConfig.toJson(c))
        assertFalse(back.searchOn)
        assertEquals("tvly-\"x\"\\y", back.searchKey)
    }

    @Test
    fun `老存档没有搜索字段时兜底按默认开`() {
        // 与「老存档没有 ai 段」同一个道理：旧文件里没有 searchOn / searchKey，
        // 读出来不能把兜底关掉（用户升级后功能还在，只差填一次密钥）
        val back = AiConfig.fromJson("{\"endpoint\":\"https://a/v1/chat/completions\"}")
        assertTrue(back.searchOn)
        assertEquals("", back.searchKey)
    }

    @Test
    fun `searchOn 认不出时当开`() {
        // 手改存档写成奇怪的值：宁可当「开」也不要静默关掉兜底
        assertTrue(AiConfig.fromJson("{\"searchOn\":\"true\"}").searchOn)
        assertTrue(AiConfig.fromJson("{\"searchOn\":\"1\"}").searchOn)
        assertFalse(AiConfig.fromJson("{\"searchOn\":\"0\"}").searchOn)
        assertFalse(AiConfig.fromJson("{\"searchOn\":\"false\"}").searchOn)
        // 彻底认不出 → 默认开
        assertTrue(AiConfig.fromJson("{\"searchOn\":\"whatever\"}").searchOn)
    }

    @Test
    fun `searchKey 空白与缺省等价`() {
        assertEquals("", AiConfig.fromJson("{\"searchKey\":\"\"}").searchKey)
        assertEquals("tvly-9", AiConfig.fromJson("{\"searchKey\":\"tvly-9\"}").searchKey)
    }

    // ------------------------------------------------------------ 并进 FixStore

    private fun sampleStore(ai: AiConfig = AiConfig(), cursor: Int = 37) = FixStore(
        saved = mapOf("moonlight.pdf" to mapOf("title" to "月光", "comp" to "贝多芬")),
        cursor = cursor,
        batchKey = "csv|549|moonlight.pdf",
        ai = ai,
    )

    @Test
    fun `存档带 AI 段往返`() {
        val c = AiConfig("https://api.deepseek.com/v1/chat/completions", "sk-live", "deepseek-reasoner")
        val store = sampleStore(c)
        val back = FixStore.fromJson(FixStore.toJson(store))

        assertEquals(c, back.ai)
        // 原有的进度与采纳记录不能因为这个新字段被挤掉
        assertEquals(37, back.cursor)
        assertEquals("csv|549|moonlight.pdf", back.batchKey)
        assertEquals(
            mapOf("title" to "月光", "comp" to "贝多芬"),
            back.of("moonlight.pdf"),
            "采纳记录连同值一起往返",
        )
    }

    @Test
    fun `老存档没有 ai 段也能读出进度`() {
        // 这一条模拟「用户设备上已经躺着的那份旧 fix-store.json」——
        // 新版本读它时必须拿到进度，而不是因为缺字段整份丢掉
        val legacy = """
            {"v":1,"cursor":88,"batch":"csv|549|a.pdf",
             "saved":{"a.pdf":["title"],"b.pdf":["genre","keysf"]}}
        """.trimIndent()
        val back = FixStore.fromJson(legacy)

        assertEquals(88, back.cursor)
        assertEquals("csv|549|a.pdf", back.batchKey)
        // v1 的老存档：只记字段名、没有值 → 值留空串，进度照旧
        assertEquals(mapOf("title" to ""), back.of("a.pdf"))
        assertEquals(mapOf("genre" to "", "keysf" to ""), back.of("b.pdf"))
        // 缺 ai 段 → 默认配置（界面上显示「未配置」，让用户去填一次）
        assertEquals(AiConfig(), back.ai)
    }

    @Test
    fun `ai 段是坏对象时不拖累进度`() {
        val weird = """
            {"v":1,"cursor":12,"batch":"lib|30|x.pdf","saved":{},"ai":"not-an-object"}
        """.trimIndent()
        val back = FixStore.fromJson(weird)

        assertEquals(12, back.cursor)
        assertEquals(AiConfig(), back.ai)
    }

    @Test
    fun `清空存档不会顺手抹掉 AI 设置`() {
        // FixStore.cleared 只清采纳与位置；ai 默认值是 AiConfig()。
        // 这里断言的是「cleared 后的默认构造不含配置」，调用方（clearFixStore）
        // 必须自己把 ai 并回去 —— 见下一条测试
        val cleared = FixStore.cleared("csv|10|a.pdf")
        assertEquals(AiConfig(), cleared.ai)
    }

    @Test
    fun `清空存档后调用方把 AI 设置并回去`() {
        // 复现 ScoreAppState.clearFixStore 之后的写法：
        // store.copy(ai = <内存里那份>)。这条守住「清进度不丢密钥」这个语义
        val live = AiConfig("https://a/v1/chat/completions", "sk-keep", "m")
        val afterClear = FixStore.cleared("csv|10|a.pdf").copy(ai = live)

        assertEquals(live, afterClear.ai)
        assertEquals(0, afterClear.savedCount)
        assertEquals(0, afterClear.cursor)
    }

    // ------------------------------------------------------------ Holder 落盘

    @Test
    fun `Holder 写下去再读回来拿到同一份配置`() {
        var disk: String? = null
        val holder = FixStoreHolder(
            read = { disk },
            write = { json -> disk = json; true },
        )

        val c = AiConfig("https://b/v1/chat/completions", "sk-disk", "m2")
        assertTrue(holder.update(holder.load().copy(ai = c)))

        // 换一个 holder 模拟「下次启动」，从同一块「盘」上读
        val fresh = FixStoreHolder(read = { disk }, write = { true })
        assertEquals(c, fresh.load().ai)
    }

    @Test
    fun `保存配置不会弄丢已经写下的进度`() {
        var disk: String? = null
        val holder = FixStoreHolder(read = { disk }, write = { json -> disk = json; true })

        // 先有一份带进度的存档
        holder.update(sampleStore(cursor = 200))
        // 用户去设置页改了配置，落盘
        holder.update(holder.load().copy(ai = AiConfig("https://c/v1/chat/completions", "sk-x", "m3")))

        val back = FixStore.fromJson(disk)
        assertEquals(200, back.cursor)
        assertEquals(mapOf("title" to "月光", "comp" to "贝多芬"), back.of("moonlight.pdf"))
        assertEquals("sk-x", back.ai.apiKey)
    }
}
