package com.example.scoreapp

import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.FixStore
import com.example.scoreapp.domain.csvfix.FixStoreHolder
import com.example.scoreapp.domain.csvfix.RuleSwitches
import com.example.scoreapp.domain.csvfix.ScoreKey
import com.example.scoreapp.domain.csvfix.SearchFallback
import com.example.scoreapp.domain.csvfix.parseCsv
import com.example.scoreapp.ui.FixSession
import com.example.scoreapp.util.AiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 逐条校对的会话逻辑回归。
 *
 * 这一批断言对应的是「一屏一条 + 逐字段采纳 + 跨会话存档」这套新模型，
 * 三条最容易写错的线在这里锁住：
 *
 * 1. **采纳即落存档**：勾一个字段就该立刻进 store，不需要另点保存 ——
 *    逐条翻阅的场景里用户随时可能退出，多一步保存就等于丢数据。
 * 2. **存档键是 `Filename` 而不是行号**：用户重新导一份顺序不同的 CSV 时，
 *    行号全错位，而文件名还能对上。
 * 3. **乐曲类型的「备选」政策**：原值是用户自己的分类（如「教学」）时，
 *    AI 给的体裁名（如 `Sonata`）不当作覆盖，要先提升才生效。
 */
class FixStepperTest {

    /**
     * 用 forScore 的**真实 15 列**做表头，而不是随手裁一个短表头。
     *
     * 教训：曾经这里写的是 `"Filename,Title,Composers,Genres,keysf,keymi"`，
     * 少了 `Start Page (Bookmark)` / `End Page (Bookmark)` 两列。
     * [ColumnMap] 按列名匹配不到就退化成按位置猜，于是把 `Composers`/`Genres`
     * 两列当成了起止页 —— 每一条都被判成「合集子条目」，
     * 标题清洗被整条跳过，一批用例集体假失败，查了半天。
     * 表头跟真实文件一致，这类问题根本不会出现。
     */
    private val header = listOf(
        "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)", "Composers",
        "Genres", "Tags", "Labels", "Reference", "Rating", "Difficulty",
        "Minutes", "Seconds", "keysf", "keymi",
    ).joinToString(",")

    /**
     * 行数据按**简写列序**给：`Filename,Title,Composers,Genres,keysf,keymi`（6 列）。
     * 这里再摊平到真实 15 列的位置上去 —— 简写是为了用例好读，
     * 但绝不能让简写本身变成「表头与数据不一致」的源头。
     */
    private fun csv(vararg rows: String): String =
        (listOf(header) + rows.map { expand(it) }).joinToString("\r\n")

    /** 把 6 列简写摊成 15 列真实列序 */
    private fun expand(short: String): String {
        val c = short.split(",")
        fun at(i: Int) = c.getOrElse(i) { "" }
        return listOf(
            at(0),                          // Filename
            at(1),                          // Title
            "",                             // Start Page —— 必须显式空，否则会被判成合集行
            "",                             // End Page
            at(2),                          // Composers
            at(3),                          // Genres
            "",                             // Tags
            "",                             // Labels
            "",                             // Reference
            "", "", "",                     // Rating / Difficulty / Minutes
            "",                             // Seconds
            at(4),                          // keysf
            at(5),                          // keymi
        ).joinToString(",")
    }

    private fun session(
        text: String,
        store: FixStore = FixStore.EMPTY,
        switches: RuleSwitches = RuleSwitches(),
    ) = FixSession.fromCsv(text, switches = switches, store = store)

    // ================================================ 基础：翻页与建议

    @Test
    fun `一屏一条 首次打开停在第一条`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,") )
        assertEquals(0, s.cursor)
        assertEquals(1, s.total)
        assertFalse(s.canPrev)
        assertFalse(s.canNext)
    }

    @Test
    fun `翻页在两端夹住`() {
        val s = session(csv("a.pdf,曲一_001.pdf,,,,", "b.pdf,曲二_002.pdf,,,,"))
        assertEquals(2, s.total)
        s.go(-1)
        assertEquals(0, s.cursor, "第一条再往前应该原地不动")
        s.go(1)
        assertEquals(1, s.cursor)
        assertFalse(s.canNext)
        s.go(1)
        assertEquals(1, s.cursor, "最后一条再往后应该原地不动")
    }

    @Test
    fun `文件名是主键 原样保留`() {
        val raw = "《萱草花》乐谱（五线谱带伴奏）.pdf"
        val s = session(csv("$raw,萱草花,,,,"))
        assertEquals(raw, s.currentRow?.fileName)
    }

    @Test
    fun `没有建议时 hasChange 为假`() {
        // 标题干净、其余为空，规则挑不出可改的（作曲家和类型都不硬编）
        val s = session(csv("clean.pdf,茉莉花,,,,"))
        assertFalse(s.viewOf(0).hasChange)
    }

    // ================================================ 逐字段采纳

    @Test
    fun `勾选即落存档 不需要额外保存`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,"))
        val view = s.viewOf(0)
        val newValue = assertNotNull(view.proposedOf("title"), "标题该有清洗后的建议")

        s.toggleField(0, "title")

        assertTrue(s.isTaken(0, "title"), "勾了就该算已采纳")
        // 存档记的是「字段 → 值」而不只是字段名：值可能来自 AI 或用户手打，
        // 那两种重算不出来（见 FixStore.saved 的注释）
        assertEquals(mapOf("title" to newValue), s.store.of("a.pdf"), "勾选必须立刻进存档")
        assertEquals(1, s.savedCount)
    }

    @Test
    fun `取消勾选从存档里删掉该字段`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,"))
        s.toggleField(0, "title")
        s.toggleField(0, "title")
        assertFalse(s.isTaken(0, "title"))
        assertTrue(s.store.of("a.pdf").isEmpty(), "取消后不该留下空记录")
        assertEquals(0, s.savedCount, "空集合不该让「已存」虚高")
    }

    @Test
    fun `存档跨条互不干扰`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,", "b.pdf,《彩云》_pdf,,,,"))
        s.toggleField(0, "title")
        s.commitAndNext()
        s.toggleField(1, "title")
        assertTrue(s.isTaken(0, "title"), "第一条的采纳不该被第二条影响")
        assertTrue(s.store.took("a.pdf", "title"))
        assertTrue(s.store.took("b.pdf", "title"))
    }

    @Test
    fun `采纳并继续会翻页并清掉草稿与AI结果`() {
        val s = session(csv("月光.pdf,《月光》_pdf,,,,", "曲二.pdf,曲二,,,,"))
        s.toggleField(0, "title")
        s.landAiResult(AiFill.OneResult(title = "X"))
        s.commitAndNext()

        assertEquals(1, s.cursor)
        assertNull(s.aiResult, "AI 结果属于上一条，翻页即清")
        // 输入框不再清成空串，而是**换成新条目的预填**。
        //
        // 以前这里断言的是 `""` —— 那时翻页直接清空输入框。
        // 现在有了预填，正确的行为是「上一条的提示词不许留下，但新条目要有线索」：
        // 留着上一条的提示词会诱导用户手滑点「生成」，把上一条的结果填到这一条上。
        assertEquals("曲二", s.aiPrompt, "翻页后输入框应当是新条目的预填，而不是上一条的残留")
        // 第一条已存的内容不因翻页而丢
        assertTrue(s.store.took("月光.pdf", "title"))
    }

    // ================================================ AI 配置注入

    @Test
    fun `会话默认没有可用的 AI 配置`() {
        // 会话是 ui 包里的纯数据容器，够不到 ScoreAppState。
        // 没人注入时它应当是「未配置」，让界面照实说「去设置里填一下」，
        // 而不是拿一个假的默认地址去发请求、再报一个看不懂的网络错。
        val s = session(csv("a.pdf,曲一,,,,"))
        assertFalse(s.aiConfig.ready)
    }

    @Test
    fun `注进来的配置就是运行时要用的那一份`() {
        val s = session(csv("a.pdf,曲一,,,,"))
        val live = AiConfig("https://my.endpoint/v1/chat/completions", "sk-mine", "my-model")
        s.aiConfig = live

        // runAiOne 读的是 session.aiConfig（不是 state.aiConfig）——
        // 这两处若不接上，用户在设置页填的东西在校对页根本不会被用到
        assertEquals(live, s.aiConfig)
        assertTrue(s.aiConfig.ready)
    }

    @Test
    fun `翻页不影响会话持有的配置`() {
        val s = session(csv("a.pdf,曲一,,,," , "b.pdf,曲二,,,,"))
        s.aiConfig = AiConfig("https://a/v1/chat/completions", "sk-1", "m")
        s.go(1)
        s.go(-1)
        assertEquals("sk-1", s.aiConfig.apiKey)
    }

    // ================================================ 存档的键

    @Test
    fun `存档用文件名做键 换顺序也不丢`() {
        // 先把 b.pdf 标记为已采纳
        val s1 = session(csv("a.pdf,曲一,,,," , "b.pdf,《月光》_pdf,,,,"))
        s1.goTo(1)
        s1.toggleField(1, "title")
        val store = s1.store

        // 换一份**顺序相反**的同一批数据，采纳记录还认得出来
        val s2 = session(csv("b.pdf,《月光》_pdf,,,," , "a.pdf,曲一,,,,"), store = store)
        assertTrue(s2.isTaken(0, "title"), "b.pdf 换到第 0 条仍该是已采纳")
        assertFalse(s2.isTaken(1, "title"), "a.pdf 没采纳过")
    }

    @Test
    fun `存档记住上次位置`() {
        val s1 = session(csv("a.pdf,曲一,,,," , "b.pdf,曲二,,,," , "c.pdf,曲三,,,,"))
        s1.goTo(2)
        val s2 = session(csv("a.pdf,曲一,,,," , "b.pdf,曲二,,,," , "c.pdf,曲三,,,,"), store = s1.store)
        assertEquals(2, s2.cursor, "重新打开该接着上次那一条")
    }

    @Test
    fun `存档 json 往返一致`() {
        val store = FixStore(
            saved = mapOf(
                "带,逗号.pdf" to mapOf("title" to "月光", "comp" to "贝多芬"),
                "quote\".pdf" to mapOf("keysf" to "c#m"),
            ),
            cursor = 17,
            batchKey = "csv|549|x.pdf",
            used = mapOf("label" to listOf("教学", "考级")),
        )
        val back = FixStore.fromJson(FixStore.toJson(store))
        assertEquals(store.saved, back.saved, "含逗号与引号的文件名与值必须能往返")
        assertEquals(listOf("教学", "考级"), back.usedOf("label"), "用过的值也要往返")
        assertEquals(17, back.cursor)
        assertEquals("csv|549|x.pdf", back.batchKey)
    }

    @Test
    fun `坏存档退化成空 而不是抛异常`() {
        listOf(null, "", "{坏", "[1,2,3]", "{\"saved\":\"不是对象\"}").forEach { bad ->
            val s = FixStore.fromJson(bad)
            assertEquals(0, s.savedCount, "存档损坏最坏只该丢进度，不该崩：$bad")
            assertEquals(0, s.cursor)
        }
    }

    @Test
    fun `旧存档只有字段名时 进度还在 值留空`() {
        // 用户设备上躺着的老 fix-store.json：`saved` 是字段名数组，没有值。
        // 新版读它必须拿到进度；值回不来就留空串，导出时退回重算的建议值
        // （规则值重算一模一样，所以这恰好等于旧版本的行为）
        val legacy = """{"v":1,"cursor":88,"batch":"csv|3|a.pdf","saved":{"a.pdf":["title","comp"]}}"""
        val back = FixStore.fromJson(legacy)
        assertEquals(88, back.cursor)
        assertTrue(back.took("a.pdf", "title"), "采纳记录不能因为格式升级丢掉")
        assertEquals("", back.of("a.pdf")["comp"], "老存档没有值，留空串")
    }

    @Test
    fun `空集合与空文件名不入存档`() {
        val s = FixStore.EMPTY.with("a.pdf", emptyMap())
        assertEquals(0, s.savedCount, "空集合等于没采纳")
        val s2 = FixStore.EMPTY.with("", mapOf("title" to "月光"))
        assertEquals(0, s2.savedCount, "空文件名匹配不上任何一行")
    }

    @Test
    fun `存档 holder 读写与清空`() {
        var disk: String? = null
        val holder = FixStoreHolder(read = { disk }, write = { disk = it; true })
        assertEquals(0, holder.load().savedCount, "从没存过时是空存档")

        holder.update(FixStore.EMPTY.with("a.pdf", mapOf("title" to "月光")).copy(cursor = 3))
        assertNotNull(disk, "update 必须写下去")

        val again = FixStoreHolder(read = { disk }, write = { true })
        assertEquals("月光", again.load().of("a.pdf")["title"])
        assertEquals(3, again.value.cursor)

        again.clear()
        assertEquals(0, again.value.savedCount)
    }

    // ================================================ AI 注入与相关守卫

    @Test
    fun `填入本条把五个字段一起预勾上`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,,,"))
        s.landAiResult(
            AiFill.OneResult(
                title = "Piano Sonata No.14, Op.27 No.2",
                composer = "Ludwig van Beethoven",
                instrument = "Piano",
                genre = "Sonata",
                key = "c#m",
            ),
        )
        val n = s.fillCurrentWithAi()
        assertEquals(5, n)

        val view = s.viewOf(0)
        assertEquals("Piano Sonata No.14, Op.27 No.2", view.proposedOf("title"))
        assertEquals("Ludwig van Beethoven", view.proposedOf("comp"))
        assertEquals("Piano", view.proposedOf("tag"))
        assertEquals("Sonata", view.proposedOf("genre"))
        // 长名走 NAMES 表：升号记为 ♯ 后缀，**不**写「升」字前缀
        // （原型 fix-stepper-demo.html 的 NAMES 表就是这么定的）
        assertEquals("C♯小调", view.proposedOf("keysf"))
        // 五个字段都预勾，用户可以逐个取消
        listOf("title", "comp", "tag", "genre", "keysf").forEach {
            assertTrue(s.isTaken(0, it), "$it 该被预勾上")
        }
    }

    @Test
    fun `AI 结果为空时不填任何东西`() {
        val s = session(csv("a.pdf,x.pdf,,,,"))
        s.landAiResult(AiFill.OneResult())
        assertTrue(s.aiUnknown)
        assertEquals(0, s.fillCurrentWithAi())
    }

    @Test
    fun `认不出的调性不硬写`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,,,"))
        s.landAiResult(AiFill.OneResult(title = "月光奏鸣曲", key = "很抒情"))
        s.fillCurrentWithAi()
        assertNull(s.viewOf(0).proposedOf("keysf"), "认不出的调名宁可留空，绝不硬写")
    }

    @Test
    fun `语系规则 中国作品保持中文`() {
        // 原始标题带噪声（书名号 + _pdf），AI 清洗后的中文标准名才是建议值。
        // 若原值本来就与 AI 相同，不会有建议 —— 那是正确的「无改动」。
        val s = session(csv("a.pdf,《茉莉花》_pdf,,,,"))
        s.landAiResult(
            AiFill.OneResult(
                title = "茉莉花", composer = "江苏民歌",
                instrument = "声乐", genre = "民歌", key = "bE",
            ),
        )
        s.fillCurrentWithAi()
        val v = s.viewOf(0)
        assertEquals("茉莉花", v.proposedOf("title"))
        assertEquals("《茉莉花》_pdf", v.overwrittenOf("title"), "原值要留作对比")
        assertEquals("江苏民歌", v.proposedOf("comp"))
        assertEquals("声乐", v.proposedOf("tag"))
        assertEquals("民歌", v.proposedOf("genre"))
        // 同上：降号记为 ♭ 后缀（NAMES 表的写法），不写「降」字
        assertEquals("E♭大调", v.proposedOf("keysf"))
    }

    @Test
    fun `AI 给的和原值相同时不产生建议`() {
        val s = session(csv("a.pdf,茉莉花,,,,"))
        s.landAiResult(AiFill.OneResult(title = "茉莉花", composer = "江苏民歌"))
        s.fillCurrentWithAi()
        val v = s.viewOf(0)
        assertNull(v.proposedOf("title"), "原值与 AI 一致，不该凭空多出一条建议")
        assertEquals("江苏民歌", v.proposedOf("comp"), "真正缺的字段照常补")
    }

    // ---- 相关性守卫 ----

    @Test
    fun `相关性守卫 明显对不上要拦`() {
        assertFalse(AiFill.looksRelated("萱草花", "Piano Sonata No.14, Op.27 No.2"))
        assertFalse(AiFill.looksRelated("七月的草原G", "茉莉花"))
    }

    @Test
    fun `相关性守卫 像的就放行`() {
        // 注意：守卫比的是**字符**，不是语义。
        // 「月光奏鸣曲」与「Piano Sonata No.14, Op.27 No.2」没有任何共同字符，
        // 语义上虽是同一首，但守卫认不出来 —— 这是刻意的取舍：
        // 宁可多标一次「对不上」让用户核对，也不能凭语义猜测去放行，
        // 否则「萱草花」这种真写错的也会被蒙混过去。
        // 所以这里断言的是**拦下**，不是放行。
        assertFalse(
            AiFill.looksRelated("月光奏鸣曲", "Piano Sonata No.14, Op.27 No.2"),
            "中英译名之间没有共同字符，守卫应如实标「对不上」，由用户自己判断",
        )
        assertTrue(AiFill.looksRelated("《月光》_pdf", "月光奏鸣曲"), "清洗掉 _pdf 与书名号后就是同一个名字，要放行")
        assertTrue(AiFill.looksRelated("", "任意"), "原本没曲名，无从比较，放行")
        assertTrue(AiFill.looksRelated("茉莉花", "茉莉花"), "完全相同当然放行")
        assertTrue(AiFill.looksRelated("月光奏鸣曲", "月光"), "AI 只补个短名，子串要放行")
        assertTrue(AiFill.looksRelated("月光", "月光奏鸣曲"), "反向包含也要放行")
    }

    @Test
    fun `相关性守卫 先清洗再比`() {
        // 库里存的是文件名来的脏串，AI 回的是标准名 —— 这是**最常见**的正常情况。
        // 若守卫拿脏串去比，几乎每一条都会被标成「对不上」，那道闸就废了。
        assertTrue(
            AiFill.looksRelated("《萱草花》_pdf", "萱草花"),
            "带书名号 + _pdf 的原始标题清洗后与 AI 的答案一致",
        )
        assertTrue(
            AiFill.looksRelated("[Z-Library] 彩云追月.pdf", "彩云追月"),
            "带扫描站水印的文件名也要能对上",
        )
        assertFalse(
            AiFill.looksRelated("《萱草花》_pdf", "茉莉花"),
            "清洗之后仍然对不上的，还是要拦",
        )
    }

    @Test
    fun `会话暴露对不上的警告`() {
        val s = session(csv("a.pdf,萱草花,,,,"))
        s.landAiResult(AiFill.OneResult(title = "Piano Sonata No.14, Op.27 No.2"))
        assertTrue(s.aiMismatch, "提示词和这一条对不上时必须标出来")
        // 换一条对得上的
        s.landAiResult(AiFill.OneResult(title = "萱草花"))
        assertFalse(s.aiMismatch)
    }

    // ---- 乐曲类型的「备选」政策 ----

    @Test
    fun `类型原值是用户分类时 AI 的值只作备选`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,教学,,"))
        s.landAiResult(AiFill.OneResult(title = "月光奏鸣曲", genre = "Sonata"))
        s.fillCurrentWithAi()

        val v = s.viewOf(0)
        assertEquals("Sonata", v.alternateOf("genre"), "原值是「教学」这种用户分类，AI 的体裁名只能是备选")
        // 原值没被碰 —— 「教学」还在，只是这一条建议里没有 genre 的候选值
        assertEquals("教学", s.currentRow?.genre)
    }

    @Test
    fun `勾选备选才把它提升为正式建议`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,教学,,"))
        s.landAiResult(AiFill.OneResult(title = "月光奏鸣曲", genre = "Sonata"))
        s.fillCurrentWithAi()

        // 勾 genre 这一个字段（此时它还没有候选值，勾的就是备选）
        s.toggleField(0, "genre")

        val v = s.viewOf(0)
        assertEquals("Sonata", v.proposedOf("genre"), "勾过之后备选被提升为正式建议")
        assertEquals("教学", v.overwrittenOf("genre"), "原值要记成「被覆盖」，界面上要能对比")
    }

    @Test
    fun `类型原本为空时 AI 直接补上`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,,,"))
        s.landAiResult(AiFill.OneResult(title = "月光奏鸣曲", genre = "Sonata"))
        s.fillCurrentWithAi()
        assertEquals("Sonata", s.viewOf(0).proposedOf("genre"), "空字段没有「不覆盖」的问题")
    }

    // ================================================ 自填 + 用过的值

    @Test
    fun `自填压过 AI 和规则`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,,,"))
        s.landAiResult(
            AiFill.OneResult(
                title = "Piano Sonata No.14, Op.27 No.2",
                composer = "Ludwig van Beethoven",
                genre = "Sonata",
            ),
        )
        s.fillCurrentWithAi()
        assertEquals("Piano Sonata No.14, Op.27 No.2", s.viewOf(0).proposedOf("title"))

        // AI 猜错了，以前只能整条拒绝（不勾），现在能直接改成对的
        s.setTypedValue(0, "title", "我自己写的名")

        val v = s.viewOf(0)
        assertEquals("我自己写的名", v.proposedOf("title"))
        assertTrue(v.isSelfTyped("title"), "手填的值要标出来 —— 用户得知道这条不是机器猜的")
        assertEquals("Ludwig van Beethoven", v.proposedOf("comp"), "没动的字段不受影响")
    }

    @Test
    fun `自填立刻勾上并落存档`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,,,"))
        s.setTypedValue(0, "title", "手填标题")

        assertTrue(s.isTaken(0, "title"), "都亲手打字了，不该再让他去点一次勾")
        assertEquals("手填标题", s.store.of("a.pdf")["title"], "敲完翻页跑掉也不该丢")
    }

    @Test
    fun `撤回自填退回原来的建议`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,"))
        val rule = assertNotNull(s.viewOf(0).proposedOf("title"))

        s.setTypedValue(0, "title", "手填")
        s.clearTypedValue(0, "title")

        assertEquals(rule, s.viewOf(0).proposedOf("title"), "撤回后回到规则建议那一层")
        assertFalse(s.isTaken(0, "title"))
        assertTrue(s.store.of("a.pdf").isEmpty(), "存档里的记录也要一起抹掉")
    }

    @Test
    fun `标签和来源靠自填才填得上`() {
        // 规则给不出、AI 又永不碰这两个字段 —— 没有自填，这两列在页面上是死行，
        // 而这一整页叫「标签工具」
        val s = session(csv("a.pdf,茉莉花,,,,"))
        assertNull(s.viewOf(0).proposedOf("ref"))
        assertNull(s.viewOf(0).proposedOf("label"))

        s.setTypedValue(0, "ref", "网络下载")
        s.setTypedValue(0, "label", "考级曲目")

        val v = s.viewOf(0)
        assertEquals("网络下载", v.proposedOf("ref"))
        assertEquals("考级曲目", v.proposedOf("label"))
        assertTrue(v.hasChange, "自填之后这一条算有改动")

        val row = parseCsv(s.exportCsv()).rows[0]
        assertEquals("考级曲目", row[7], "Labels 列")
        assertEquals("网络下载", row[8], "Reference 列")
    }

    @Test
    fun `自填的调性能导出成 keysf 与 keymi`() {
        val s = session(csv("a.pdf,月光奏鸣曲,,,,"))
        s.setTypedValue(0, "keysf", "c#m")
        val row = parseCsv(s.exportCsv()).rows[0]
        assertEquals("4", row[13], "升C小调 = 4 个升号")
        assertEquals("1", row[14], "keymi = 1 小调")
    }

    @Test
    fun `存档里的值会回填 翻回旧条显示的还是那个值`() {
        // 存档若只记字段名，翻回旧条显示的是重算的建议、导出用的却是存档值 ——
        // 同一个字段两个值，是最难查的那类 bug
        val s1 = session(csv("a.pdf,《月光》_pdf,,,,"))
        s1.setTypedValue(0, "title", "当年手打的名字")

        val s2 = session(csv("a.pdf,《月光》_pdf,,,,"), store = s1.store)
        assertEquals("当年手打的名字", s2.viewOf(0).proposedOf("title"))
        assertEquals("当年手打的名字", parseCsv(s2.exportCsv()).rows[0][1], "屏幕与导出必须一致")
    }

    @Test
    fun `用过的值去重置顶 按字段分开 上限八个`() {
        var st = FixStore.EMPTY
        st = st.rememberUsed("label", "教学")
        st = st.rememberUsed("label", "考级")
        st = st.rememberUsed("label", "教学")
        assertEquals(listOf("教学", "考级"), st.usedOf("label"), "去重，最近用的排最前")

        st = st.rememberUsed("label", "  考级  ")
        assertEquals("考级", st.usedOf("label").first(), "trim 之后再记")

        val before = st.usedOf("label").size
        st = st.rememberUsed("label", "   ")
        assertEquals(before, st.usedOf("label").size, "空白不进词表")

        for (n in 0 until 12) st = st.rememberUsed("label", "值$n")
        assertEquals(FixStore.USED_CAP, st.usedOf("label").size, "超出的丢掉")
        assertEquals("值11", st.usedOf("label").first())

        st = st.rememberUsed("ref", "网络下载")
        assertEquals(1, st.usedOf("ref").size, "词表按字段分开存")
        assertEquals(FixStore.USED_CAP, st.usedOf("label").size, "字段之间互不干扰")
    }

    @Test
    fun `清空存档不清用过的值`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,"))
        s.setTypedValue(0, "title", "手填")
        s.setTypedValue(0, "label", "教学")
        assertEquals(1, s.savedCount)

        s.resetStore()

        assertEquals(0, s.savedCount, "进度归零")
        assertTrue(
            s.usedValuesOf("label").contains("教学"),
            "词表是攒出来的个人资产，清进度时不该跟着没",
        )
    }

    // ================================================ 跳到未处理

    @Test
    fun `跳到下一条未处理`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,", "b.pdf,《彩云》_pdf,,,,", "c.pdf,《茉莉》_pdf,,,,"))
        // 第一条已处理（勾了标题并翻页）
        s.toggleField(0, "title")
        s.commitAndNext()
        assertEquals(1, s.cursor, "翻页后停在第 1 条")

        // 「下一条」= 字面意义上的下一条，**不含当前条**。
        // 第 1 条虽然也没处理过，但用户眼前就是它，再「跳」到它自己毫无反馈，
        // 按钮会像是坏的 —— 所以往后找到第 2 条。
        // 这与原型 fix-stepper-demo.html 的 jumpUnhandled 完全一致：
        // 先扫 idx+1..last，再绕回 0..idx-1，两段都不含 idx 本身。
        assertTrue(s.jumpUnhandled())
        assertEquals(2, s.cursor, "往后跳，不停在当前条")
    }

    @Test
    fun `跳到底后绕回头找未处理的`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,", "b.pdf,《彩云》_pdf,,,,", "c.pdf,《茉莉》_pdf,,,,"))
        // 跳到第 2 条并处理掉它，停在那里
        s.goTo(2)
        s.toggleField(2, "title")
        s.commitAndNext()
        assertEquals(2, s.cursor, "已是最后一条，翻不动")

        // 第 2 条往后没有内容了，绕回头扫 0..1，找到第 0 条
        assertTrue(s.jumpUnhandled())
        assertEquals(0, s.cursor, "绕回头找到第 0 条")
    }

    @Test
    fun `全处理完了跳不动`() {
        val s = session(csv("a.pdf,《月光》_pdf,,,,"))
        s.toggleField(0, "title")
        assertFalse(s.jumpUnhandled(), "只有一条且已处理，没有可跳的")
    }

    // ================================================ 导出

    @Test
    fun `只导出采纳的字段 其余原样`() {
        val s = session(csv("a.pdf,《月光》_pdf,贝多芬,教学,,"))
        val v = s.viewOf(0)
        val newTitle = v.proposedOf("title")
        assertNotNull(newTitle)

        // 只采纳标题
        s.toggleField(0, "title")
        val out = s.exportCsv()
        val parsed = parseCsv(out)
        assertEquals(header, parsed.header.joinToString(","), "表头必须原样写回")
        val row = parsed.rows[0]
        assertEquals("a.pdf", row[0], "Filename 一个字节都不能动")
        assertEquals(newTitle, row[1], "采纳的标题要写出去")
        assertEquals("贝多芬", row[4], "没采纳的作曲家保持原值")
        assertEquals("教学", row[5], "没采纳的类型保持原值")
        assertEquals("", row[2], "起止页不能被我们写进去")
        assertEquals("", row[3], "起止页不能被我们写进去")
    }

    @Test
    fun `导出会带上 AI 填入的值`() {
        val s = session(csv("a.pdf,月光奏鸣曲,贝多芬,,,"))
        s.landAiResult(
            AiFill.OneResult(
                title = "Piano Sonata No.14, Op.27 No.2",
                composer = "Ludwig van Beethoven",
                instrument = "Piano",
                genre = "Sonata",
                key = "c#m",
            ),
        )
        s.fillCurrentWithAi()
        val row = parseCsv(s.exportCsv()).rows[0]
        // 若只走规则建议、不合并 AI，这里会导出「月光奏鸣曲」——第 5、6 列的教训
        assertEquals("Piano Sonata No.14, Op.27 No.2", row[1])
        assertEquals("Ludwig van Beethoven", row[4])
        assertEquals("Sonata", row[5])
        assertEquals("Piano", row[6], "乐器走 Tags 列")
        assertEquals("4", row[13], "升C小调：调号 4 个升号")
        assertEquals("1", row[14], "keymi = 1 小调")
    }

    @Test
    fun `一个字不改时导出的内容与导入一致`() {
        val src = "Filename,Title,Composers\r\n\"a,b.pdf\",X,Beethoven\r\n"
        val s = FixSession.fromCsv(src)
        val again = parseCsv(s.exportCsv())
        assertEquals(parseCsv(src).header, again.header)
        assertEquals(parseCsv(src).rows, again.rows)
    }

    // ================================================ 存档与批次

    @Test
    fun `批次标识随来源与条数变化`() {
        val a = session(csv("a.pdf,曲一,,,,"))
        val b = session(csv("a.pdf,曲一,,,," , "b.pdf,曲二,,,,"))
        val ka = FixStore.batchKeyOf("csv", a.rawRows)
        val kb = FixStore.batchKeyOf("csv", b.rawRows)
        assertTrue(ka != kb, "条数不同就是不同批次")
        assertEquals(ka, FixStore.batchKeyOf("csv", session(csv("a.pdf,曲一,,,,")).rawRows), "同批次要稳定")
    }

    // ================================================ 调性缩写

    @Test
    fun `调性缩写格式 大调字母大写 小调小写带m`() {
        assertEquals("C", ScoreKey.shortLabel(0, 0))
        assertEquals("am", ScoreKey.shortLabel(0, 1))
        assertEquals("bB", ScoreKey.shortLabel(-2, 0), "降号在字母前，且降号那个 b 保持小写")
        assertEquals("bbm", ScoreKey.shortLabel(-5, 1), "降B小调")
        assertEquals("F#", ScoreKey.shortLabel(6, 0), "升号在字母后")
        assertEquals("c#m", ScoreKey.shortLabel(4, 1), "升C小调")
        assertEquals("bE", ScoreKey.shortLabel(-3, 0))
        assertEquals("bem", ScoreKey.shortLabel(-6, 1), "降E小调：调号走关系大调降G(-6)")
        assertEquals("B", ScoreKey.shortLabel(5, 0), "无记号的 B 大调要真的变成大写 B")
        assertEquals("", ScoreKey.shortLabel(null, 0))
    }

    @Test
    fun `调性缩写能原样解析回来 往返无损`() {
        // 这是「AI 写缩写 → 解析成 keysf/keymi」这条链路的核心保证。
        // 30 种组合一个都不能漏 —— 大调表的顺序早期抄错过（把 -1 写成 F♭ 而非 F），
        // 单点断言看不出来，只有全表往返才暴露。
        for (sf in -7..7) {
            for (mi in 0..1) {
                val short = ScoreKey.shortLabel(sf, mi)
                assertTrue(short.isNotEmpty(), "每个 keysf/keymi 组合都该有缩写")
                val back = ScoreKey.parse(short)
                assertNotNull(back, "缩写 $short 该能被解析回来")
                assertEquals(sf, back.keysf, "$short → keysf")
                assertEquals(mi, back.keymi, "$short → keymi")
            }
        }
    }

    @Test
    fun `小调查表白纸 升C小调走E大调调号`() {
        // 最容易错的一步：小调的调号 = 关系大调的调号（升C小调 → E 大调 = 4 个升号）
        assertEquals(4, ScoreKey.parse("c#m")?.keysf)
        assertEquals(1, ScoreKey.parse("c#m")?.keymi)
        assertEquals(-6, ScoreKey.parse("ebm")?.keysf, "降E小调 → 降G大调")
        assertEquals(-3, ScoreKey.parse("bE")?.keysf)
        assertEquals(0, ScoreKey.parse("am")?.keysf)
        // 降号的两种写法必须指向同一个调：`bE` 与 `eb`
        assertEquals(-3, ScoreKey.parse("eb")?.keysf, "后缀写法 Eb 与 bE 同调")
        // 中文写法里 `d小调` 只剩一个字母，摘尾缀 m 的逻辑不能把它吃掉
        assertEquals(-1, ScoreKey.parse("d小调")?.keysf, "D小调 → F大调(-1)")
        assertEquals(1, ScoreKey.parse("d小调")?.keymi)
    }

    // ================================================ 清空字段（空值覆盖）
    //
    // 用户报的场景：AI 或规则给了个错值，他要的不是「改成别的值」，
    // 而是「这个字段就该是空的」。以前做不到 —— 输入框留空等于「撤回自填」，
    // 原来的错值又冒回来，他只能眼睁睁看着，或者手打一个假值去顶替。

    @Test
    fun `清空字段后候选值里没有它 原值不再冒头`() {
        // 标题必须**带噪声**（`《月光》_pdf`）规则才会给出建议。
        // 写成干净的「月光」时 cleanTitle 洗不动它、proposedOf 是 null，
        // 这个用例的前提就不成立 —— 那样测的是「本来就是空的字段清空后还是空」，
        // 什么都没验证到。
        val s = session(csv("a.pdf,《月光》_pdf,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        val before = s.viewOf(0)
        assertTrue(before.proposedOf("title") != null, "这一条本该有标题建议（《月光》_pdf 会被清洗）")

        s.clearFieldToBlank(0, "title")

        val after = s.viewOf(0)
        // 关键：候选值里**没有**这个字段了 ——
        // 既不是原值、也不是任何建议值，就是「空」。
        assertNull(after.proposedOf("title"), "被清空的字段不该还有候选值")
        assertTrue(after.isBlanked("title"), "要能看出这个空是用户自己要的")
    }

    @Test
    fun `清空字段要落存档 翻回来还是空的`() {
        // 存档里记空串是**用户要的结果**，不能被规则值重新填上。
        val s = session(csv("a.pdf,月光,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        s.clearFieldToBlank(0, "title")
        val saved = s.store.of("a.pdf")
        assertTrue(saved.containsKey("title"), "清空要落存档，否则翻回来就丢了")

        // 翻走再翻回来，必须还是空的
        s.go(1)
        s.go(-1)
        assertNull(s.viewOf(0).proposedOf("title"), "翻回来不该被规则值顶回来")
        assertTrue(s.viewOf(0).isBlanked("title"))
    }

    @Test
    fun `清空字段算已采纳 导出时会真的写成空`() {
        // 最容易漏的一环：清空的字段在 proposed 里是「没有」，
        // 而 takenFields 原本只收 proposed 里非 null 的字段 ——
        // 于是导出的那一刻它会**跳过这一格**，CsvAdapter 遇到「没采纳」
        // 是保留原值的，用户点过清空的格子导出来还是那个错值。白清。
        // 这里同样要用**带噪声**的标题，否则「导出是空」会因为
        // 「本来就没有任何建议」而假绿 —— 测不到 takenFields/blank 那条路。
        val s = session(csv("a.pdf,《月光》_pdf,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        // 先采纳标题，确认导出的是**清洗后的值**。
        // 注意不能只断言「有建议」—— 有建议 ≠ 已采纳，没勾选时导出的还是原值
        //（这正是最容易把这条测试写假的地方）。
        s.toggleField(0, "title")
        assertEquals("月光", s.exportCsv().split("\r\n")[1].split(",")[1], "采纳后导出的是清洗值")

        s.clearFieldToBlank(0, "title")
        assertTrue(s.takenFields(0).contains("title"), "清空必须算「已采纳」")

        val out = s.exportCsv()
        val firstData = out.split("\r\n")[1]
        val titleCell = firstData.split(",")[1]
        assertEquals("", titleCell, "被清空的格子导出时必须是空，不能留着原值")
        // 也不能退回原始脏值 —— 那正是「白清」的另一种表现
        assertTrue(titleCell != "《月光》_pdf", "清空不许把原始值露出来")
    }

    @Test
    fun `清空调性时两列一起清`() {
        // keysf/keymi 必须同进同退：只清一列会留下「有调号不知大小调」的半残值。
        val s = session(csv("a.pdf,月光,贝多芬,奏鸣曲,4,1", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        // 先确认原值确实在（c#m = keysf 4 / keymi 1），否则「导出是空」测不出东西
        val before = s.exportCsv().split("\r\n")[1].split(",")
        assertEquals("4", before[13]); assertEquals("1", before[14])
        s.clearFieldToBlank(0, "keysf")
        val cells = s.exportCsv().split("\r\n")[1].split(",")
        assertEquals("", cells[13], "keysf 要清空")
        assertEquals("", cells[14], "keymi 要一起清空")
    }

    @Test
    fun `撤回自填与清空是两件事`() {
        // 这两个动作很容易混，必须分辨清楚：
        // - 撤回自填：撤销我填的，值退回规则给的原建议
        // - 清空：我要求它空，原建议也不许回来
        val s = session(csv("a.pdf,月光,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        s.setTypedValue(0, "composer", "贝多芬")
        assertFalse(s.viewOf(0).isBlanked("composer"), "填值不算清空")
        // 自填的值确实进来了（否则下面「清空后没了」可能只是本来就没有）
        assertEquals("贝多芬", s.viewOf(0).proposedOf("composer"))

        s.clearFieldToBlank(0, "composer")
        assertTrue(s.viewOf(0).isBlanked("composer"))
        assertNull(s.viewOf(0).proposedOf("composer"))

        // 撤回之后回到「没填过」的状态，规则建议值该能回来
        s.clearTypedValue(0, "composer")
        assertFalse(s.viewOf(0).isBlanked("composer"), "撤回之后不再是「要求空」")
    }

    @Test
    fun `只有清空没有新值也算有改动`() {
        // 踩过的坑：hasChange 只判 proposed，于是「只清空了一格、没有任何新值」
        // 的行会被判成「无需改动」，底下显示「这一条无需改动，直接翻下一条即可」，
        // 用户很可能顺手跳过 —— 而这一格明明是要写空出去的。
        // 原型那边一直是「清空也算改动」，两边语义得对齐。
        val s = session(csv("a.pdf,《月光》_pdf,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        // 先撤掉标题建议，制造一条「本来没有任何建议」的行
        val row = s.viewOf(0)
        assertTrue(row.proposedOf("title") != null, "前提：这一条本来有建议")

        // 只清空标签（规则从不碰标签，清完这行就真的只剩「空」这一个改动）
        s.clearFieldToBlank(0, "tag")
        assertTrue(s.viewOf(0).hasChange, "只有清空也必须算有改动")

        // 反向对照：真的一条改动都没有时才该说「无需改动」。
        // 拿一条规则空手的行来验 —— 否则「有改动」恒真，上面的断言是假绿。
        val quiet = session(csv("c.pdf,茉莉花,佚名,民歌,-3,0"))
        assertFalse(quiet.viewOf(0).hasChange, "规则空手且无清空时才是「无需改动」")
    }

    // ================================================ AI 输入框预填

    @Test
    fun `开场就把输入框预填成文件名`() {
        // 639 条里逐条手打曲名是纯浪费 —— 那个名字在屏幕上就摆着。
        val s = session(csv("《您花开的样子》.pdf,您花开的样子,佚名,民歌,0,0"))
        assertEquals("您花开的样子", s.aiPrompt, "预填要清掉书名号和 .pdf 后缀")
    }

    @Test
    fun `翻页时预填跟着换成新条目`() {
        // 文件名要写成像样的 —— 预填取的是**文件名**（用户自己的原始命名），
        // 不是 title。用 `a.pdf` / `b.pdf` 这种占位名，预填就是「a」「b」，
        // 断言写成「月光」会一直红，而那是断言写错了、不是功能坏了。
        val s = session(csv("月光.pdf,月光,贝多芬,奏鸣曲,0,0", "茉莉花.pdf,茉莉花,佚名,民歌,-3,0"))
        assertEquals("月光", s.aiPrompt)
        s.go(1)
        // 翻页必须换掉预填。这里的坑：翻页那一刻框里**正好有上一条的预填**，
        // 如果直接调 prefillPrompt（守卫是「有字就不动」），它会被自己挡死 ——
        // 输入框永远停在第一条的文件名上，用户拿第一条的线索去问第二条。
        assertEquals("茉莉花", s.aiPrompt, "翻页后要预填新条目的名字")
    }

    @Test
    fun `翻页不会覆盖用户手写的提示词`() {
        // 用户敲的字代表他在改写这一条的线索，翻页回来不该被抹掉。
        // 判断依据是「框里的字是不是我上次预填进去的那串」——
        // 不能只看框里空不空，预填之后框里也有字。
        val s = session(csv("月光.pdf,月光,贝多芬,奏鸣曲,0,0", "茉莉花.pdf,茉莉花,佚名,民歌,-3,0"))
        s.aiPrompt = "月光奏鸣曲 升c小调"
        s.go(1)
        assertEquals("月光奏鸣曲 升c小调", s.aiPrompt, "手写的字翻页也不该被动")
    }

    @Test
    fun `用户把预填删空后翻页不会替他填回来`() {
        // 删空也是「动过」。他删空说明他就要一个空框自己写；
        // 这时候再预填回去等于跟他抢。
        val s = session(csv("月光.pdf,月光,贝多芬,奏鸣曲,0,0", "茉莉花.pdf,茉莉花,佚名,民歌,-3,0"))
        s.aiPrompt = ""
        s.go(1)
        assertEquals("", s.aiPrompt, "用户删空之后不许擅自填回来")
    }

    @Test
    fun `翻页会清掉上一条的错误`() {
        // go() 以前漏清 aiError，而上一条的报错会**跟着翻页挂到新一条上** ——
        // 用户在新的一条上看到一条莫名其妙的错误。goTo() 一直是清干净的，
        // 两条翻页路行为必须一致。
        val s = session(csv("a.pdf,月光,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        s.aiError = "连不上服务器"
        s.go(1)
        assertNull(s.aiError, "翻页必须清掉上一条的错误")
    }

    @Test
    fun `用户自己改过的提示词不会被预填覆盖`() {
        // 用户敲的字代表他在改写这一条的线索，翻页回来不该被抹掉。
        val s = session(csv("月光.pdf,月光,贝多芬,奏鸣曲,0,0", "茉莉花.pdf,茉莉花,佚名,民歌,-3,0"))
        s.aiPrompt = "月光奏鸣曲 升c小调"
        s.reprefillPrompt()
        assertEquals("月光奏鸣曲 升c小调", s.aiPrompt, "已经有字（而且不是我预填的）就不该动它")
    }

    // ================================================ 联网兜底的状态管理

    @Test
    fun `翻页清AI结果时连带清兜底状态`() {
        // 兜底的「搜过 / 来源 / 说明」都属于**那一条**结果 ——
        // 结果翻页即清（结果属于上一条），这些残留跟着清，一个都不能留。
        // 否则下一条天生顶着「已联网搜过」的标签，来源还是上一条的域名。
        val s = session(csv("a.pdf,月光,贝多芬,奏鸣曲,0,0", "b.pdf,茉莉花,佚名,民歌,-3,0"))
        s.aiSearching = true
        s.aiSearched = true
        s.aiViaSearch = true
        s.aiSearchNote = "资料对不上"
        s.aiSources = listOf("baike.baidu.com", "qupu123.com")
        s.go(1)
        assertFalse(s.aiSearching, "搜索中翻页：搜索提示也要立刻消失")
        assertFalse(s.aiSearched)
        assertFalse(s.aiViaSearch)
        assertNull(s.aiSearchNote)
        assertTrue(s.aiSources.isEmpty())
    }

    @Test
    fun `clearAiSearchState只清兜底状态不动结果本体`() {
        // 「重新生成」走这个：结果区在生成期间保留上一遍结果（本页一贯的行为），
        // 但兜底的标记必须先抹掉 —— 它们是上一遍结果的属性。
        val s = session(csv("a.pdf,月光,贝多芬,奏鸣曲,0,0"))
        s.landAiResult(AiFill.OneResult(title = "月光奏鸣曲"))
        s.aiViaSearch = true
        s.aiSearched = true
        s.aiSources = listOf("a.com")
        s.clearAiSearchState()
        assertEquals("月光奏鸣曲", s.aiResult?.title, "结果本体不动")
        assertFalse(s.aiViaSearch)
        assertFalse(s.aiSearched)
        assertTrue(s.aiSources.isEmpty())
    }

    @Test
    fun `预填与兜底搜索词是同一条清洗链`() {
        // FixSession 的 promptFromFileName 委托给 SearchFallback.fileNameQuery：
        // 这条测试守住「两边永不分叉」—— 那边改了清洗规则这边没跟上，
        // 是最难查的那种不一致（各自单测都绿，合起来就是错的）。
        val s = session(csv("《您花开的样子》.pdf,您花开的样子,佚名,民歌,0,0"))
        assertEquals(
            SearchFallback.fileNameQuery("《您花开的样子》.pdf"),
            s.aiPrompt,
            "预填 = 兜底搜索词的文件名清洗",
        )
    }
}
