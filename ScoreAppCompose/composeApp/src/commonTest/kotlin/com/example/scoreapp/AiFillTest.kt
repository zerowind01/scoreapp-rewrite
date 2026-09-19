package com.example.scoreapp

import com.example.scoreapp.domain.csvfix.AiApplyOptions
import com.example.scoreapp.domain.csvfix.AiField
import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.AiItem
import com.example.scoreapp.domain.csvfix.AiParseResult
import com.example.scoreapp.domain.csvfix.ColumnMap
import com.example.scoreapp.domain.csvfix.FixProposal
import com.example.scoreapp.domain.csvfix.FixProposer
import com.example.scoreapp.domain.csvfix.FixRow
import com.example.scoreapp.domain.csvfix.RuleSwitches
import com.example.scoreapp.domain.csvfix.ScoreKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AI 落地的测试：把解析结果写成建议（**不动原数据**，采纳由用户勾选）。
 *
 * 两条容易写错的业务线在这里锁住：
 * 1. **两段式覆盖** —— `allowOverwrite` 只是「允许提建议」，真正的采纳仍需用户逐条点。
 *    所以打开开关后 [FixProposal.overwrite] 与原值并存，而不是直接盖掉。
 * 2. **调性两列联动** —— 调名先经 [ScoreKey.parse] 校验，认不出就记进
 *    [FixProposal.keyRejected] 让用户看到「已忽略」，绝不硬写。
 */
class AiFillTest {

    private val header = listOf(
        "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)", "Composers",
        "乐曲类型", "编配", "标签", "Reference", "声部", "困难度", "Minutes", "Seconds", "keysf", "keymi",
    )
    private val col = ColumnMap.of(header)
    private val compField = listOf(AiField("composers", ColumnMap.COMP, "作曲家"))
    private val keyField = listOf(AiField("key", ColumnMap.KEY_KEYSF, "调性", AiField.KIND_KEY))

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

    /** 便利：造「一行 + 它的建议」并按给定结果落地 */
    private fun land(
        r: FixRow,
        results: Any?,
        fields: List<AiField> = compField,
        allowOverwrite: Boolean = false,
    ): Pair<Int, FixProposal> {
        val p = FixProposer.propose(r, RuleSwitches())
        val list = mutableListOf(p)
        val n = AiFill.apply(list, listOf(r), col, results, fields, allowOverwrite)
        return n to list[0]
    }

    // ============================================== 10. 空值补 / 忽略 / 幂等

    @Test
    fun `空值直接补`() {
        val (n, p) = land(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to ""),
            listOf(mapOf("i" to "0", "composers" to "Bach")),
        )
        assertEquals(1, n)
        assertEquals("Bach", p.composer)
        assertTrue(p.changes["comp"].orEmpty().contains("ai"))
    }

    @Test
    fun `越界行号忽略`() {
        val (n, _) = land(
            row(ColumnMap.FILE to "z.pdf", ColumnMap.TITLE to "Z"),
            listOf(mapOf("i" to "99", "composers" to "X")),
        )
        assertEquals(0, n)
    }

    @Test
    fun `重复应用值不变且不堆标记`() {
        val r = row(ColumnMap.FILE to "y.pdf", ColumnMap.TITLE to "Y")
        val p = FixProposer.propose(r, RuleSwitches())
        val list = mutableListOf(p)
        val results = listOf(mapOf("i" to "0", "composers" to "Bach"))
        AiFill.apply(list, listOf(r), col, results, compField)
        val first = list[0].composer
        val firstLen = list[0].changes["comp"].orEmpty().size
        AiFill.apply(list, listOf(r), col, results, compField)
        assertEquals(first, list[0].composer)
        assertEquals(firstLen, list[0].changes["comp"].orEmpty().size, "同一来源不该重复堆")
        // 注意：这时原数据仍为空，所以第二次仍会「补空」，但不会变成覆盖
        assertFalse(list[0].overwrite.containsKey(ColumnMap.COMP))
    }

    @Test
    fun `已有值默认不动`() {
        val (n, _) = land(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to "金承志"),
            listOf(mapOf("i" to "0", "composers" to "Bach")),
        )
        assertEquals(0, n)
    }

    @Test
    fun `开了覆盖才动`() {
        val (n, _) = land(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to "金承志"),
            listOf(mapOf("i" to "0", "composers" to "Bach")),
            allowOverwrite = true,
        )
        assertEquals(1, n)
    }

    @Test
    fun `三种传入形状都能吃`() {
        val shapes: List<Pair<String, Any?>> = listOf(
            "裸数组" to listOf(mapOf("i" to "0", "composers" to "Bach")),
            "values 形态" to listOf(AiItem(0, mapOf("composers" to "Bach"))),
            "包装形态" to AiParseResult(listOf(AiItem(0, mapOf("composers" to "Bach"))), null),
        )
        shapes.forEach { (name, shape) ->
            val (n, p) = land(
                row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to "金承志"),
                shape,
                allowOverwrite = true,
            )
            assertEquals(1, n, name)
            assertEquals("Bach", p.composer, name)
        }
    }

    // ================================================= 10b. 调性编码

    @Test
    fun `KEY_SIGS 表长 30`() {
        assertEquals(30, ScoreKey.signatureCount)
    }

    @Test
    fun `认全部大调`() {
        listOf(
            "C大调" to 0, "G大调" to 1, "D大调" to 2, "A大调" to 3, "E大调" to 4,
            "B大调" to 5, "F大调" to -1, "降B大调" to -2, "降E大调" to -3, "降A大调" to -4,
            "降D大调" to -5, "降G大调" to -6, "降C大调" to -7, "F#大调" to 6, "C#大调" to 7,
        ).forEach { (name, sf) ->
            val p = ScoreKey.parse(name)
            assertEquals(sf, p?.keysf, name)
            assertEquals(0, p?.keymi, name)
        }
    }

    @Test
    fun `认全部小调 调号走关系大调`() {
        // 最容易错的一步：小调的调号等于它上方小三度那个大调的调号。
        // A 小调是 C 大调（0 个），不是 A 大调（3 个）。
        listOf(
            "A小调" to 0, "E小调" to 1, "B小调" to 2, "F#小调" to 3, "C#小调" to 4,
            "G#小调" to 5, "D小调" to -1, "G小调" to -2, "C小调" to -3, "F小调" to -4,
            "降B小调" to -5, "降E小调" to -6, "降A小调" to -7,
        ).forEach { (name, sf) ->
            val p = ScoreKey.parse(name)
            assertEquals(sf, p?.keysf, name)
            assertEquals(1, p?.keymi, name)
        }
    }

    @Test
    fun `认各种等价写法`() {
        assertEquals(0, ScoreKey.parse("a minor")?.keysf)
        assertEquals(1, ScoreKey.parse("a minor")?.keymi)
        // Am：结尾的 m 必须在小写归一化之前认出来，否则会被当 A 大调
        assertEquals(0, ScoreKey.parse("Am")?.keysf)
        assertEquals(1, ScoreKey.parse("Am")?.keymi)
        assertEquals(-1, ScoreKey.parse("Dm")?.keysf)
        assertEquals(-1, ScoreKey.parse("d minor")?.keysf)
        assertEquals(-4, ScoreKey.parse("f minor")?.keysf)
        assertEquals(-5, ScoreKey.parse("bb minor")?.keysf)
        // 降号四种写法都要落到同一个调
        assertEquals(-3, ScoreKey.parse("E♭大调")?.keysf)
        assertEquals(-3, ScoreKey.parse("Eb major")?.keysf)
        assertEquals(-2, ScoreKey.parse("Bb major")?.keysf)
        assertEquals(6, ScoreKey.parse("F# major")?.keysf)
        assertEquals(4, ScoreKey.parse("c#m")?.keysf)
    }

    @Test
    fun `认不出的写法一律 null`() {
        assertEquals(null, ScoreKey.parse("很抒情"))
        assertEquals(null, ScoreKey.parse(""))
        assertEquals(null, ScoreKey.parse(null))
    }

    @Test
    fun `编码转文本`() {
        assertEquals("C大调", ScoreKey.label(0, 0))
        assertEquals("D小调", ScoreKey.label(-1, 1))
        assertEquals("F小调", ScoreKey.label(-4, 1))
        assertEquals("E♭小调", ScoreKey.label(-6, 1))
        assertEquals("A♯小调", ScoreKey.label(7, 1))
    }

    @Test
    fun `从行里读调性`() {
        assertEquals("D小调", ScoreKey.textOf(row(ColumnMap.KEY_KEYSF to "-1", ColumnMap.KEY_KEYMI to "1"), col))
        assertEquals("", ScoreKey.textOf(row(), col))
        // keysf 有值而 keymi 空着是常见情况，把大调当默认比拒绝显示更有用
        assertEquals("C大调", ScoreKey.textOf(row(ColumnMap.KEY_KEYSF to "0"), col))
    }

    // ================================ 11. AI 建议改已有值（由用户决定留哪个）

    @Test
    fun `11a 默认不给已有值提建议`() {
        val r = row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to "贝多芬")
        val (n, _) = land(r, listOf(mapOf("i" to "0", "composers" to "Beethoven")))
        assertEquals(0, n)
        assertEquals("贝多芬", r.composer, "原数据不该被碰")
    }

    @Test
    fun `11b 开建议则产生建议并记下原值`() {
        val r = row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to "贝多芬")
        val (n, p) = land(r, listOf(mapOf("i" to "0", "composers" to "Beethoven")), allowOverwrite = true)
        assertEquals(1, n)
        assertEquals("贝多芬", p.overwrite[ColumnMap.COMP])
        assertEquals("Beethoven", p.composer)
        // 关键：这是「建议」，原数据本身不动 —— 采纳由用户勾选
        assertEquals("贝多芬", r.composer)
    }

    @Test
    fun `11c 值相同不出建议`() {
        val (n, _) = land(
            row(ColumnMap.FILE to "a.pdf", ColumnMap.TITLE to "A", ColumnMap.COMP to "贝多芬"),
            listOf(mapOf("i" to "0", "composers" to "贝多芬")),
            allowOverwrite = true,
        )
        assertEquals(0, n)
    }

    @Test
    fun `11d 空值两种模式都补且不算覆盖`() {
        val (n, p) = land(
            row(ColumnMap.FILE to "b.pdf", ColumnMap.TITLE to "B", ColumnMap.COMP to ""),
            listOf(mapOf("i" to "0", "composers" to "Mozart")),
        )
        assertEquals(1, n)
        assertEquals("Mozart", p.composer)
        assertFalse(p.overwrite.containsKey(ColumnMap.COMP))
    }

    @Test
    fun `11e 调性覆盖`() {
        val kr = row(ColumnMap.FILE to "c.pdf", ColumnMap.TITLE to "C", ColumnMap.KEY_KEYSF to "-1", ColumnMap.KEY_KEYMI to "0")
        val (n, p) = land(
            kr,
            listOf(mapOf("i" to "0", "key" to "d小调")),
            fields = keyField,
            allowOverwrite = true,
        )
        assertEquals(1, n)
        assertEquals(-1, p.keysf)
        assertEquals(1, p.keymi)
        assertTrue(p.keyOverride, "原有调性被替换，UI 要显示「覆盖」且默认不勾选")
    }

    @Test
    fun `11f 调性认不出就不改并记下原文`() {
        val kr = row(ColumnMap.FILE to "c.pdf", ColumnMap.TITLE to "C", ColumnMap.KEY_KEYSF to "-1", ColumnMap.KEY_KEYMI to "0")
        val (n, p) = land(
            kr,
            listOf(mapOf("i" to "0", "key" to "很抒情")),
            fields = keyField,
            allowOverwrite = true,
        )
        assertEquals(0, n)
        assertEquals("很抒情", p.keyRejected)
    }

    @Test
    fun `11g 空调性补上`() {
        val kr = row(ColumnMap.FILE to "d.pdf", ColumnMap.TITLE to "D")
        val (n, p) = land(kr, listOf(mapOf("i" to "0", "key" to "降E小调")), fields = keyField)
        assertEquals(1, n)
        assertEquals(-6, p.keysf)
        assertEquals(1, p.keymi)
    }

    @Test
    fun `11h 只对有差异的字段提议`() {
        val r = row(
            ColumnMap.FILE to "e.pdf", ColumnMap.TITLE to "E",
            ColumnMap.COMP to "贝多芬", ColumnMap.GENRE to "Sonata",
        )
        val fields = listOf(
            AiField("composers", ColumnMap.COMP, "作曲家"),
            AiField("genres", ColumnMap.GENRE, "乐曲类型"),
        )
        val (n, p) = land(
            r,
            listOf(mapOf("i" to "0", "composers" to "贝多芬", "genres" to "奏鸣曲")),
            fields = fields,
            allowOverwrite = true,
        )
        assertEquals(1, n, "同值字段不该计数")
        assertEquals("奏鸣曲", p.genre)
        assertFalse(p.overwrite.containsKey(ColumnMap.COMP), "同值字段不该进覆盖表")
    }

    // ============================================ 配置对象口径（防漏）

    @Test
    fun `AiApplyOptions 默认只补空且跳过书签`() {
        val o = AiApplyOptions()
        assertTrue(o.onlyEmpty)
        assertTrue(o.skipBookmark)
        assertFalse(o.allowOverwrite, "覆盖必须是显式打开的，默认不动用户已填的值")
    }
}
