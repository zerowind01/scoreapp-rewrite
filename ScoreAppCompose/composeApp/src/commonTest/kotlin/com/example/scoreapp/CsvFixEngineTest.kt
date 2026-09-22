package com.example.scoreapp

import com.example.scoreapp.domain.csvfix.ColumnMap
import com.example.scoreapp.domain.csvfix.CsvTable
import com.example.scoreapp.domain.csvfix.FixProposer
import com.example.scoreapp.domain.csvfix.FixRow
import com.example.scoreapp.domain.csvfix.FixRules
import com.example.scoreapp.domain.csvfix.RuleSwitches
import com.example.scoreapp.domain.csvfix.cell
import com.example.scoreapp.domain.csvfix.parseCsv
import com.example.scoreapp.domain.csvfix.toCsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CSV 读写、列定位、清洗规则、调性编码的一致性测试。
 *
 * 这一整套是 `tools/csvfix-test.js`（190 项断言）的 Kotlin 对译 —— 网页版
 * 与 Compose 版共用同一套业务规则，两边测试**必须**同时跑，否则规则会各自漂移。
 * 网页版作废后，本文件即成为唯一基准。
 *
 * 中文列头取自 Jackson 的 forScore 实际导出（`乐曲类型` = Genres、`编配` = Tags、
 * `标签` = Labels、`声部` = Rating、`困难度` = Difficulty）。
 */
class CsvFixEngineTest {

    private val header = listOf(
        "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)", "Composers",
        "乐曲类型", "编配", "标签", "Reference", "声部", "困难度", "Minutes", "Seconds", "keysf", "keymi",
    )
    private val col = ColumnMap.of(header)

    /** 按列代号造一行（只用代号，免得到处数下标） */
    private fun row(vararg kv: Pair<String, String>): FixRow {
        var r = FixRow()
        kv.forEach { (k, v) ->
            r = when (k) {
                ColumnMap.FILE -> r.copy(fileName = v)
                ColumnMap.TITLE -> r.copy(title = v)
                ColumnMap.COMP -> r.copy(composer = v)
                ColumnMap.GENRE -> r.copy(genre = v)
                ColumnMap.TAG -> r.copy(tag = v)
                ColumnMap.LABEL -> r.copy(label = v)
                ColumnMap.REF -> r.copy(reference = v)
                ColumnMap.SP -> r.copy(startPage = v)
                ColumnMap.EP -> r.copy(endPage = v)
                ColumnMap.KEY_KEYSF -> r.copy(keysf = v.toIntOrNull())
                ColumnMap.KEY_KEYMI -> r.copy(keymi = v.toIntOrNull())
                else -> r
            }
        }
        return r
    }

    // ============================================================== 1. CSV 读写

    @Test
    fun `parseCsv 拆表头与行`() {
        val p = parseCsv("a,b,c\n1,2,3\n")
        assertEquals("a,b,c", p.header.joinToString(","))
        assertEquals(1, p.rows.size)
        assertEquals("3", p.rows[0][2])
    }

    @Test
    fun `parseCsv 处理引号内逗号与双引号转义`() {
        val p = parseCsv("x,y\n\"a,b\",\"say \"\"hi\"\"\nline2\"\n")
        assertEquals("a,b", p.rows[0][0])
        assertEquals("say \"hi\"\nline2", p.rows[0][1])
    }

    @Test
    fun `parseCsv 处理 CRLF`() {
        assertEquals(1, parseCsv("a,b\r\n1,2\r\n").rows.size)
    }

    @Test
    fun `parseCsv 去掉 BOM`() {
        assertEquals("a", parseCsv("\uFEFFa,b\n1,2\n").header[0])
    }

    @Test
    fun `parseCsv 丢弃全空行`() {
        assertEquals(1, parseCsv("a,b\n1,2\n\n\n").rows.size)
    }

    @Test
    fun `toCsv 往返后内容一致`() {
        val p = parseCsv("a,b,c\n1,2,3\n")
        assertEquals("2", parseCsv(toCsv(p.header, p.rows)).rows[0][1])
    }

    @Test
    fun `toCsv 给含逗号的单元格加引号`() {
        assertTrue(toCsv(listOf("h"), listOf(listOf("a,b"))).contains("\"a,b\""))
    }

    @Test
    fun `toCsv 转义内部双引号`() {
        assertTrue(toCsv(listOf("h"), listOf(listOf("say \"x\""))).contains("\"\""))
    }

    @Test
    fun `toCsv 用 CRLF 换行`() {
        assertTrue(toCsv(listOf("h"), listOf(listOf("v"))).contains("\r\n"))
    }

    // ============================================================ 2. 列定位

    @Test
    fun `locate 认出中文列头`() {
        assertEquals(0, col[ColumnMap.FILE])
        assertEquals(1, col[ColumnMap.TITLE])
        assertEquals(4, col[ColumnMap.COMP])
        assertEquals(5, col[ColumnMap.GENRE])
        assertEquals(6, col[ColumnMap.TAG])
        assertEquals(7, col[ColumnMap.LABEL])
        assertEquals(8, col[ColumnMap.REF])
        assertEquals(9, col[ColumnMap.RATING])
        assertEquals(10, col[ColumnMap.DIFF])
    }

    @Test
    fun `locate 自己就该认出 keysf keymi`() {
        // 曾经这里返回 null，导致整个调性功能在界面上静默失效：
        // 列头是静态 HTML 看着还在，但每个格子都是空的
        assertEquals(13, col.keysf)
        assertEquals(14, col.keymi)
    }

    @Test
    fun `locate 认英文列头`() {
        val l2 = ColumnMap.of(
            listOf(
                "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)",
                "Composers", "Genres", "Tags", "Labels", "Reference", "Rating",
                "Difficulty", "Minutes", "Seconds", "keysf", "keymi",
            ),
        )
        assertEquals(5, l2[ColumnMap.GENRE])
        assertEquals(6, l2[ColumnMap.TAG])
        assertEquals(9, l2[ColumnMap.RATING])
        assertEquals(13, l2.keysf)
        assertEquals(14, l2.keymi)
    }

    @Test
    fun `短表不硬塞 keysf keymi`() {
        // 源 CSV 未必是 forScore 格式，硬塞会让导出时多出两列、把别人的表写坏
        val l3 = ColumnMap.of(listOf("Filename", "Title", "Composers"))
        assertEquals(null, l3.keysf)
        assertEquals(null, l3.keymi)
    }

    // ============================================================== 3. 曲名规则

    @Test
    fun `标题规则非空`() {
        assertTrue(FixRules.TITLE_RULES.isNotEmpty())
    }

    @Test
    fun `标题去掉 pdf 后缀`() {
        val p = FixProposer.propose(row(ColumnMap.TITLE to "《萱草花》乐谱（五线谱带伴奏）.pdf"), RuleSwitches())
        assertFalse(p.title.orEmpty().contains(".pdf"), "得到 ${p.title}")
    }

    // ============================================================ 4. 作曲家清洗

    @Test
    fun `splitArrangers 按编配动词分家`() {
        val sp = FixRules.splitArrangers("具本哲 配伴奏, 姚峰 编合唱, 曹火星")
        assertEquals(1, sp.composers.size)
        assertEquals(2, sp.arrangers.size)
        assertEquals("曹火星", sp.composers[0])
        assertTrue(sp.arrangers.contains("具本哲 配伴奏"))
    }

    @Test
    fun `无编配动词时全算作曲家`() {
        val sp = FixRules.splitArrangers("Beethoven")
        assertEquals(0, sp.arrangers.size)
        assertEquals(1, sp.composers.size)
    }

    @Test
    fun `isJunk 是精确列表匹配`() {
        assertTrue(FixRules.isJunk("WPSSCAN"))
        assertTrue(FixRules.isJunk("Administrator"))
        assertTrue(FixRules.isJunk("  WPS  "))
        // 空串与「未知」都不在垃圾词表里 —— 工具不该替用户猜它们是不是占位符
        assertFalse(FixRules.isJunk(""))
        assertFalse(FixRules.isJunk("Bach"))
        assertFalse(FixRules.isJunk("未知"))
    }

    @Test
    fun `isSuspicious 只认短字母串或单个汉字`() {
        assertFalse(FixRules.isSuspicious("n.a."))
        assertTrue(FixRules.isSuspicious("ab"))
        assertTrue(FixRules.isSuspicious("abc"))
        assertTrue(FixRules.isSuspicious("张"))
        assertFalse(FixRules.isSuspicious("张三丰"))
        assertFalse(FixRules.isSuspicious("Beethoven"))
        assertFalse(FixRules.isSuspicious(""))
    }

    @Test
    fun `isOrg 认机构与出版社`() {
        assertTrue(FixRules.isOrg("广东省教育考试院"))
        assertTrue(FixRules.isOrg("ABRSM"))
        assertTrue(FixRules.isOrg("Z-Library"))
        assertFalse(FixRules.isOrg("Beethoven"))
    }

    @Test
    fun `拼写规范化`() {
        assertEquals("Frédéric Chopin", FixRules.normalizeName("Federick Chopin").value)
        assertEquals("Frédéric Chopin", FixRules.normalizeName("Chopin").value)
        assertEquals("Johann Sebastian Bach", FixRules.normalizeName("Bach").value)
        assertEquals("金承志", FixRules.normalizeName("金承志").value)
    }

    // ============================================================ 5. 类型推断

    @Test
    fun `inferType 按顺序命中`() {
        assertEquals("基本功练习", FixRules.inferType("Chopin Etude Op.10", ""))
        assertEquals("Sonata", FixRules.inferType("Beethoven Sonata No.14", ""))
        // 小奏鸣曲必须排在奏鸣曲前面，否则会被后者抢走
        assertEquals("Sonatina", FixRules.inferType("Sonatina Op.36", ""))
        assertEquals("合唱", FixRules.inferType("外婆合唱", ""))
        assertEquals("英皇考级曲目", FixRules.inferType("ABRSM Grade 4", ""))
        assertEquals("", FixRules.inferType("Xyzzy", ""))
    }

    // ============================================================ 6. 书签行

    @Test
    fun `书签行不吃标题改动`() {
        val r = row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.SP to "10", ColumnMap.EP to "12")
        val p = FixProposer.propose(r, RuleSwitches())
        assertEquals(null, p.title, "合集子条目的标题各自有意义，绝不能被清洗规则误伤")
    }

    @Test
    fun `isBookmarkRow 起止页任一非空即为真`() {
        assertTrue(FixProposer.isBookmarkRow(row(ColumnMap.SP to "10")))
        assertTrue(FixProposer.isBookmarkRow(row(ColumnMap.EP to "12")))
        assertFalse(FixProposer.isBookmarkRow(row(ColumnMap.TITLE to "A")))
    }

    // ============================================================ 7. 导出保真

    @Test
    fun `导出保留引号包裹的逗号`() {
        val p = parseCsv("Filename,Title\n\"a,b.pdf\",X\n")
        assertEquals("a,b.pdf", parseCsv(toCsv(p.header, p.rows)).rows[0][0])
        assertEquals(1, parseCsv(toCsv(p.header, p.rows)).rows.size)
    }

    // ======================================================= 8. AI 挑行与组装

    private val compField = listOf(com.example.scoreapp.domain.csvfix.AiField("composers", ColumnMap.COMP, "作曲家"))

    @Test
    fun `aiPickTargets 只挑真空的`() {
        val rows = listOf(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to ""),
            row(ColumnMap.FILE to "b.pdf", ColumnMap.TITLE to "B", ColumnMap.COMP to "Bach"),
            // 「未填」不在 JUNK 精确表里，不算空
            row(ColumnMap.FILE to "c.pdf", ColumnMap.TITLE to "C", ColumnMap.COMP to "未知"),
        )
        assertEquals(1, com.example.scoreapp.domain.csvfix.AiFill.pickTargets(rows, col, compField).size)
        assertEquals(
            3,
            com.example.scoreapp.domain.csvfix.AiFill
                .pickTargets(rows, col, compField, onlyEmpty = false, skipBookmark = false).size,
        )
    }

    @Test
    fun `aiPickTargets 跳过书签行`() {
        val rows = listOf(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.SP to "10", ColumnMap.EP to "12"),
            row(ColumnMap.FILE to "b.pdf", ColumnMap.TITLE to "B"),
        )
        assertEquals(listOf(1), com.example.scoreapp.domain.csvfix.AiFill.pickTargets(rows, col, compField))
        assertEquals(
            2,
            com.example.scoreapp.domain.csvfix.AiFill
                .pickTargets(rows, col, compField, skipBookmark = false).size,
        )
    }

    @Test
    fun `aiBuildItems 带行号文件名与字段键`() {
        val rows = listOf(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to ""),
            row(ColumnMap.FILE to "b.pdf", ColumnMap.TITLE to "B", ColumnMap.COMP to "Bach"),
            row(ColumnMap.FILE to "c.pdf", ColumnMap.TITLE to "C", ColumnMap.COMP to "未知"),
        )
        val items = com.example.scoreapp.domain.csvfix.AiFill.buildItems(rows, col, listOf(0, 2), compField)
        assertEquals(2, items.size)
        assertEquals("0", items[0]["i"])
        assertEquals("a.pdf", items[0]["file"])
        assertEquals("", items[0]["composers"])
        // 刻意不带起止页 / 评分 / 难度：白白烧 token 还容易诱导模型乱改
        assertEquals(null, items[0]["sp"])
        assertEquals(null, items[0]["ep"])
    }

    @Test
    fun `AI_FIELDS 含调性字段`() {
        // 字段数刻意收窄到 **5**：曲名 / 作曲家 / 乐器 / 乐曲类型 / 调性。
        // 标签(Labels) 与 来源(Reference) 是用户自己的分类体系，AI 一律不碰 ——
        // 让模型去猜「这份谱该归到哪个标签」只会污染他自己的分类。
        assertEquals(5, com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS.size)
        assertTrue(com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS.any { it.key == "key" })
        // 不碰的字段必须真的不在表里
        assertFalse(com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS.any { it.key == "labels" })
        assertFalse(com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS.any { it.key == "ref" })
        assertTrue(com.example.scoreapp.domain.csvfix.AiFill.DEFAULT_PROMPT.length > 20)
    }

    @Test
    fun `aiBuildMessages 只两条且硬格式约束钉在末尾`() {
        val items = listOf(linkedMapOf("i" to "0", "file" to "a.pdf", "title" to "A", "composers" to ""))
        val msgs = com.example.scoreapp.domain.csvfix.AiFill.buildMessages(items, compField, "MY CUSTOM PROMPT")
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("user", msgs[1].role)
        assertTrue(msgs[0].content.contains("MY CUSTOM PROMPT"))
        // 待补字段清单自动追加，不让用户手抄
        assertTrue(msgs[0].content.contains("作曲家"))
        // 长提示词里末尾指令的约束力最强，格式要求必须钉在最后
        val tail = msgs[0].content.trimEnd()
        assertTrue(
            tail.endsWith("只输出 JSON。") || tail.endsWith("只输出 JSON"),
            "末尾是 ${msgs[0].content.takeLast(20)}",
        )
        assertTrue(msgs[0].content.contains("Let me analyze"))
        // 末尾格式约束必须严格晚于待补字段清单
        assertTrue(
            msgs[0].content.indexOf("待补全字段") < msgs[0].content.indexOf("【最高优先级】"),
            "字段清单要排在格式约束之前",
        )
        // user 消息里带行号，模型才知道每条建议落在哪一行
        assertTrue(msgs[1].content.contains("\"i\""))
    }

    // ======================================================== 9. 脏回答解析

    @Test
    fun `aiParseReply 吃干净 JSON`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        assertEquals(1, com.example.scoreapp.domain.csvfix.AiFill.parseReply("[{\"i\":0,\"composers\":\"Bach\"}]", F).items.size)
        assertEquals(
            "Bach",
            com.example.scoreapp.domain.csvfix.AiFill.parseReply("[{\"i\":0,\"composers\":\"Bach\"}]", F)
                .items[0].values["composers"],
        )
    }

    @Test
    fun `aiParseReply 去 markdown 围栏与前后废话`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        assertEquals(
            1,
            com.example.scoreapp.domain.csvfix.AiFill.parseReply("```json\n[{\"i\":0,\"composers\":\"Bach\"}]\n```", F).items.size,
        )
        assertEquals(
            1,
            com.example.scoreapp.domain.csvfix.AiFill.parseReply("Sure!\n[{\"i\":0,\"composers\":\"Bach\"}]\ndone", F).items.size,
        )
    }

    @Test
    fun `aiParseReply 剥信封`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        assertEquals(
            1,
            com.example.scoreapp.domain.csvfix.AiFill.parseReply("{\"result\":[{\"i\":0,\"composers\":\"Bach\"}]}", F).items.size,
        )
        assertEquals(
            1,
            com.example.scoreapp.domain.csvfix.AiFill
                .parseReply("{\"data\":{\"items\":[{\"i\":0,\"composers\":\"Bach\"}]}}", F).items.size,
        )
    }

    @Test
    fun `aiParseReply 认中文键名与字符串行号`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        assertEquals(
            "Bach",
            com.example.scoreapp.domain.csvfix.AiFill.parseReply("[{\"i\":0,\"作曲家\":\"Bach\"}]", F)
                .items[0].values["composers"],
        )
        assertEquals(
            2,
            com.example.scoreapp.domain.csvfix.AiFill.parseReply("[{\"index\":\"2\",\"composers\":\"Bach\"}]", F).items[0].index,
        )
    }

    @Test
    fun `aiParseReply 认不出时给错误`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        assertEquals(true, com.example.scoreapp.domain.csvfix.AiFill.parseReply("I cannot help with that.", F).error != null)
        assertEquals(true, com.example.scoreapp.domain.csvfix.AiFill.parseReply("", F).error != null)
        assertEquals(true, com.example.scoreapp.domain.csvfix.AiFill.parseReply(null, F).error != null)
    }

    // ==================================================== 9b. 推理模型混排

    @Test
    fun `推理模型混排抓最后的数组`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        val reasoning = listOf(
            "Let me analyze each entry carefully.",
            "Example shape: [{\"i\":0,\"note\":\"think\"}]",
            "Final answer:",
            "[{\"i\":0,\"composers\":\"Bach\"},{\"i\":1,\"composers\":\"Mozart\"}]",
        ).joinToString("\n")
        val rr = com.example.scoreapp.domain.csvfix.AiFill.parseReply(reasoning, F)
        assertEquals(2, rr.items.size, "示例数组没有有效行号，应该被筛掉")
        assertEquals("Bach", rr.items[0].values["composers"])
        assertEquals("Mozart", rr.items[1].values["composers"])
    }

    @Test
    fun `纯思考无数组时给错误`() {
        val F = com.example.scoreapp.domain.csvfix.AiFill.AI_FIELDS
        val rr = com.example.scoreapp.domain.csvfix.AiFill
            .parseReply("Let me think about this. The first one already has a composer.", F)
        assertEquals(true, rr.error != null)
        assertEquals(0, rr.items.size)
    }

    // ------------------------------------------------ 单条生成（顶部 AI 条那条路）
    //
    // 这一段原先**零覆盖**：parseOneReply / buildOneMessage 从没被测过。
    // 而真机上双子座 flash-lite 的怪脾气全是这条路上的，值得锁死。

    @Test
    fun `单条生成认不出时回空数组就是没认出`() {
        // 提示词明确要求「认不出就回 []」。它必须走「没认出这首曲子」那条提示，
        // **不能**被当成解析失败 —— 否则用户看到的是「没从回答里解析出可用内容」，
        // 完全猜不到其实是模型老实说不知道。
        val F = com.example.scoreapp.domain.csvfix.AiFill.ONE_FIELDS
        val pr = com.example.scoreapp.domain.csvfix.AiFill.parseReply("[]", F)
        // 解析本身不报错：空数组是**合法回答**，不是解析失败。
        // 这个区分是必须的 —— 压成一个 null 之后，界面会把
        // 「模型老实说不知道」显示成「没从回答里解析出可用内容」，
        // 用户于是去改接口地址、换模型，白折腾。
        assertEquals(null, pr.error, "空数组是合法回答，不该算解析失败")
        assertEquals(0, pr.items.size)

        val one = com.example.scoreapp.domain.csvfix.AiFill.parseOneReply("[]")
        assertEquals(true, one != null, "空数组要能返回结果对象，不是 null")
        assertEquals(true, one!!.isBlank, "空结果 → 界面显示「没认出这首曲子」")
    }

    @Test
    fun `单条生成只给部分字段时不全判为没认出`() {
        // 认得出但只确定两三项（真实情况：知道曲名和乐器，调性拿不准）。
        // 这不是「没认出」，界面该把这几个值照实铺出来。
        val one = com.example.scoreapp.domain.csvfix.AiFill.parseOneReply(
            """[{"i":0,"title":"月光奏鸣曲","composer":"","instr":"Piano","genre":"","key":""}]"""
        )
        assertEquals(true, one != null)
        assertEquals(false, one!!.isBlank)
        assertEquals("月光奏鸣曲", one.title)
        assertEquals("Piano", one.instrument)
        assertEquals("", one.key, "空字段就该是空串，不是 null")
    }

    @Test
    fun `单条生成只填曲名的形态要能被识别出来`() {
        // **这条是反过来的**：原先这里写的是「认不出时可以只回曲名其余留空」，
        // 还断言 `isBlank == false` —— 等于把这个形态**祝福**成了合法回答。
        // 真机上 gemini-3.8-flash 就照着它干：输入「您花开的样子 合唱」，
        // 回了一个只填曲名的对象，界面渲染成「识别完成」+ 孤零零一行曲名，
        // 用户完全分不清是「认出来了但信息少」还是「根本没认出来」。
        //
        // 现在这个形态要被【识别出来】并单独标记（`titleOnly`），
        // 界面据此说「只认出曲名」而不是「识别完成」。
        val one = com.example.scoreapp.domain.csvfix.AiFill.parseOneReply(
            """[{"i":0,"title":"您花开的样子","composer":"","instr":"","genre":"","key":""}]"""
        )
        assertEquals(true, one != null)
        // 它不是「全空」——曲名确实有值
        assertEquals(false, one!!.isBlank)
        // 但它是「只有曲名」，必须被单独标出来
        assertEquals(true, one.titleOnly, "四项全空只剩曲名，要能被识别成 titleOnly")
        assertEquals("您花开的样子", one.title)
    }

    @Test
    fun `曲名之外还有别的值时不算只有曲名`() {
        // 反例：只要曲名以外还有任何一项有值，就不是 titleOnly ——
        // 那说明模型确实认出了这首曲子，只是信息不全，该照实铺出来。
        val one = com.example.scoreapp.domain.csvfix.AiFill.parseOneReply(
            """[{"i":0,"title":"茉莉花","composer":"","instr":"声乐","genre":"","key":""}]"""
        )
        assertEquals(true, one != null)
        assertEquals(false, one!!.titleOnly, "乐器有值就不算「只认出曲名」")
        assertEquals("声乐", one.instrument)
    }

    @Test
    fun `只有曲名的结果不算 isBlank`() {
        // 这两个状态必须分开，别混：
        // - isBlank  = 全空 → 「没认出这首曲子」
        // - titleOnly = 只剩曲名 → 「只认出曲名」
        // 混成一个的话，只剩曲名会被报成「没认出」，
        // 用户拿到的那行曲名（虽然信息量低）也被一并丢掉。
        val one = com.example.scoreapp.domain.csvfix.AiFill.parseOneReply(
            """[{"i":0,"title":"七月的草原","composer":"","instr":"","genre":"","key":""}]"""
        )
        assertEquals(false, one!!.isBlank)
        assertEquals(true, one.titleOnly)
    }

    @Test
    fun `提示词明确禁止只填曲名的那种形态`() {
        // 第十五轮修「不许省略」时只堵了「认得/不认得」两条岔路，
        // **漏了中间那条**：模型回了对象、只填曲名。3.8 Flash 走了这条。
        // 这条测试就是钉住它 —— 提示词必须把这个形态点名禁掉，
        // 否则下次改提示词又会无声地把它放回来。
        val p = com.example.scoreapp.domain.csvfix.AiFill.DEFAULT_PROMPT
        assertEquals(
            true,
            p.contains("只填曲名"),
            "必须点名禁止「只填曲名、其余四项留空」这个形态",
        )
        assertEquals(
            true,
            p.contains("曲名**不算**") || p.contains("曲名不算"),
            "必须说清「曲名不算认得这首曲子的证据」——那行字是用户自己敲的",
        )
    }

    @Test
    fun `单条生成的提示词要求认不出就回空数组`() {
        // 这条约束是踩坑换来的：曾写过「五个键一个都不能少」，
        // 它跟「不确定就填空」打架，小模型凑不出五个键就整条放弃，
        // 还从曲名抄一个调性交差。**看起来有用的噪音比明说不知道危险。**
        val p = com.example.scoreapp.domain.csvfix.AiFill.DEFAULT_PROMPT
        assertEquals(true, p.contains("空数组"), "认不出必须能直接回 []")
        assertEquals(true, p.contains("不认得"), "要显式区分认得/不认得两种情形")
    }

    @Test
    fun `单条生成答非所问才算解析失败`() {
        // 与上一条相对：模型吐了一段散文、压根没有 JSON，这才返回 null，
        // 界面该说「没从回答里解析出可用内容」。两种情形不能并成一种。
        val one = com.example.scoreapp.domain.csvfix.AiFill
            .parseOneReply("Let me think about this piece. It might be a folk song from Yunnan.")
        assertEquals(null, one)
    }

    @Test
    fun `单条生成的消息只有一个用户回合`() {
        val msgs = com.example.scoreapp.domain.csvfix.AiFill.buildOneMessage("月光奏鸣曲")
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("user", msgs[1].role)
        assertEquals("曲目：月光奏鸣曲", msgs[1].content)
    }
}
