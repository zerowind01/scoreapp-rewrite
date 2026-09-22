package com.example.scoreapp

import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.SearchFallback
import com.example.scoreapp.domain.csvfix.SearchFallback.SearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 联网兜底的纯逻辑回归。对应原型 `stepper-test.js` 的「联网兜底」段。
 *
 * 这一层锁的是语义里最容易走样的五条：
 * 1. **谁有资格触发兜底**：没认出（含解析不出）或只回曲名 —— 认出了就不搜；
 * 2. **搜索词从哪来**：框里的字优先，空了退回文件名清洗结果（与预填同一条链）；
 * 3. **来源怎么亮**：去重、滤空、最多 3 条；
 * 4. **二问提示词是「先抄事实、再判断」**：资料里写明的归属要敢抄，
 *    禁止补的只限「资料里没有的」；只确认曲名仍回 []（titleOnly 照旧不合法）；
 * 5. **二问的三种出口**要分开：真没认出、答非所问、认出来了。
 */
class SearchFallbackTest {

    // ------------------------------------------------ 资格判断

    @Test
    fun `整条没认出才有资格兜底`() {
        assertTrue(
            SearchFallback.eligible(AiFill.OneResult(), unknown = true),
            "模型回空（或整条读不出）→ 兜底",
        )
    }

    @Test
    fun `只回曲名也有资格兜底`() {
        val r = AiFill.OneResult(title = "边境的小鸟")
        assertTrue(r.titleOnly)
        assertTrue(
            SearchFallback.eligible(r, unknown = false),
            "titleOnly 是第三种形态，不算认出 → 兜底",
        )
    }

    @Test
    fun `认出来了就不兜底 哪怕字段不全`() {
        // 认出了但只有三项 —— 那是正常回答（提示词允许拿不准的留空），
        // 联网再问反而容易把已经对的答案搅浑
        val r = AiFill.OneResult(title = "Piano Sonata No.14", composer = "Beethoven", instrument = "Piano")
        assertFalse(r.titleOnly)
        assertFalse(r.isBlank)
        assertFalse(SearchFallback.eligible(r, unknown = false))
    }

    @Test
    fun `unknown 与 result 各自独立判断`() {
        // 解析不出（result == null）也该兜底 —— 只看 result 判 isBlank 会漏掉这条
        assertTrue(SearchFallback.eligible(null, unknown = true))
        assertFalse(SearchFallback.eligible(null, unknown = false), "result 为 null 且 unknown 为 false 是矛盾状态，不兜底")
    }

    // ------------------------------------------------ 搜索词

    @Test
    fun `框里的字优先`() {
        assertEquals(
            "七月的草原 合唱",
            SearchFallback.buildQuery("  七月的草原 合唱  ", "《七月的草原》_pdf"),
        )
    }

    @Test
    fun `框空了退回文件名清洗结果`() {
        assertEquals("七月的草原", SearchFallback.buildQuery("  ", "《七月的草原》_pdf"))
        assertEquals("月光奏鸣曲", SearchFallback.buildQuery("", "月光奏鸣曲.pdf"))
        // 与预填同一条链：FixSession 的预填就是这个清洗（.pdf 后缀 → cleanTitle）
        assertEquals("茉莉花", SearchFallback.fileNameQuery("《茉莉花》_pdf"))
        assertEquals("", SearchFallback.fileNameQuery(""))
        assertEquals("", SearchFallback.fileNameQuery(null))
    }

    // ------------------------------------------------ Tavily 解析

    private val tavilyJson = """
        {"query":"七月的草原","response_time":1.2,
         "results":[
           {"title":"七月的草原_百度百科","url":"https://baike.baidu.com/item/七月的草原",
            "content":"内蒙古民歌，常作合唱曲目。","score":0.98},
           {"title":"七月的草原 合唱谱_曲谱网","url":"https://www.qupu123.com/abc.html",
            "content":"降E大调合唱谱。","score":0.9},
           {"title":"无地址的条目不该被收","url":"","content":"","score":0.5}
         ]}
    """.trimIndent()

    @Test
    fun `解析 Tavily 响应取结果数组`() {
        val hits = SearchFallback.parseTavily(tavilyJson)
        assertEquals(2, hits.size, "没有 url 的条目直接丢 —— 没有来源的结果不能要")
        assertEquals("七月的草原_百度百科", hits[0].title)
        assertEquals("baike.baidu.com", hits[0].domain)
    }

    @Test
    fun `摘要截断到上限`() {
        val long = SearchFallback.parseTavily(
            """{"results":[{"title":"t","url":"https://a.com","content":"${"长".repeat(400)}"}]}""",
        )
        assertEquals(SearchFallback.SNIPPET_MAX, long.single().snippet.length)
    }

    @Test
    fun `坏响应解析成空表而不是抛`() {
        assertTrue(SearchFallback.parseTavily("这不是 json").isEmpty(), "解析不了 = 没搜到，调用方处理是同一种")
        assertTrue(SearchFallback.parseTavily("""{"results":"不是数组"}""").isEmpty())
        assertTrue(SearchFallback.parseTavily("""{"results":[]}""").isEmpty())
    }

    // ------------------------------------------------ 来源域名

    @Test
    fun `域名去重滤空最多三条`() {
        val hits = listOf(
            SearchHit("t1", "s", "https://www.qupu123.com/a.html"),
            SearchHit("t2", "s", "https://qupu123.com/b.html"),   // 同站：去掉 www 后重复
            SearchHit("t3", "s", "https://baike.baidu.com/x"),
            SearchHit("t4", "s", "https://www.bilibili.com/y"),
        )
        assertEquals(listOf("qupu123.com", "baike.baidu.com", "bilibili.com"), SearchFallback.domainsOf(hits))
    }

    @Test
    fun `掐协议与路径取域名`() {
        assertEquals("api.tavily.com", SearchFallback.domainOf("https://api.tavily.com/search"))
        assertEquals("mojim.com", SearchFallback.domainOf("http://mojim.com/tw100x.htm"))
        assertEquals("tieba.baidu.com", SearchFallback.domainOf("tieba.baidu.com/p/123"))
        assertEquals("", SearchFallback.domainOf("   "), "空串进空串出，调用方滤掉")
    }

    // ------------------------------------------------ 二问提示词

    @Test
    fun `二问带着资料与曲目`() {
        val msgs = SearchFallback.buildSecondMessages(
            "七月的草原",
            listOf(SearchHit("七月的草原_百度百科", "内蒙古民歌，常作合唱。", "https://baike.baidu.com/x")),
        )
        assertEquals(2, msgs.size)
        val sys = msgs[0].content
        val user = msgs[1].content
        assertTrue(sys.startsWith(AiFill.DEFAULT_PROMPT), "语系规则等原有约束原样生效 —— 二问不是另一种问答")
        assertTrue(user.contains("七月的草原"))
        assertTrue(user.contains("内蒙古民歌，常作合唱。"))
        assertTrue(user.contains("baike.baidu.com"), "资料要带域名：模型得能分清百度百科和贴吧帖子")
    }

    @Test
    fun `二问提示词先抄事实再判断`() {
        // 1.14 真机教训：旧版「不许按曲名去猜」把模型吓得不敢单独用资料，
        // 《Bella siccome un angelo》搜到了 Donizetti 还是只抄一遍曲名。
        val sys = SearchFallback.buildSecondMessages(
            "q",
            listOf(SearchHit("t", "s", "https://a.com")),
        )[0].content
        assertTrue(sys.contains("照实抄进答案，不算猜"), "这句必须有 —— 否则模型把「凡靠曲名搜来的」都当猜，只敢再抄一遍曲名")
        assertTrue(sys.contains("只有曲名、没有任何归属信息"), "资料只确认曲名时必须封口：仍回 []，不许交 titleOnly")
        assertTrue(sys.contains("不许靠记忆或常识补"), "反硬编收窄到「资料里没有的」")
        assertTrue(sys.contains("[]"), "认不出时二问也要能回空数组")
        assertFalse(sys.contains("曲名是用户敲进来的，不算资料"), "旧措辞就是吓住模型的那句，不许改回去")
    }

    // ------------------------------------------------ 二问裁决

    @Test
    fun `二问答非所问要跟没认出分开`() {
        val o = SearchFallback.adjudicate("我觉得这个问题需要更多信息才能回答")
        assertNull(o.result)
        assertEquals("第二次回答没解析出内容", o.note, "这是模型没好好回答，不是搜索的问题 —— 文案混了用户会去查网络")
    }

    @Test
    fun `二问老实回空就维持没认出`() {
        val o = SearchFallback.adjudicate("[]")
        assertNull(o.result, "结果维持第一遍的空，界面继续「没认出」")
        assertNull(o.note, "note 为 null → 界面用默认措辞「资料里没有可靠信息」")
    }

    @Test
    fun `二问认出来了就照实采信`() {
        val one = AiFill.parseOneReply("""[{"i":0,"title":"七月的草原","composers":"内蒙古民歌","tags":"Voice","genres":"民歌","key":"bE"}]""")
        val o = SearchFallback.adjudicate("""[{"i":0,"title":"七月的草原","composers":"内蒙古民歌","tags":"Voice","genres":"民歌","key":"bE"}]""")
        assertEquals(one, o.result)
        assertNull(o.note)
    }

    @Test
    fun `二问只回曲名也照实给 兜底不豁免闸门`() {
        // 联网不是通行证：搜回来的 titleOnly 照样要被单独标出来（界面层判 titleOnly）
        val o = SearchFallback.adjudicate("""[{"i":0,"title":"边境的小鸟"}]""")
        assertEquals("边境的小鸟", o.result?.title)
        assertTrue(o.result?.titleOnly == true)
        assertEquals("", o.result?.composer, "其余四项是空串不是 null —— OneResult 五个字段都是非空 String")
    }
}
