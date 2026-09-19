package com.example.scoreapp

import com.example.scoreapp.domain.csvfix.ColumnMap
import com.example.scoreapp.domain.csvfix.CsvAdapter
import com.example.scoreapp.domain.csvfix.FixProposer
import com.example.scoreapp.domain.csvfix.FixRow
import com.example.scoreapp.domain.csvfix.LibraryAdapter
import com.example.scoreapp.domain.csvfix.RuleSwitches
import com.example.scoreapp.domain.csvfix.parseCsv
import com.example.scoreapp.domain.csvfix.toCsv
import com.example.scoreapp.model.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 适配器测试：把「外部 CSV」与「App 曲库」两条来源都对齐到同一套规则。
 *
 * 这一层最关键的断言只有一条：**`Filename` 逐字符保真**。
 * forScore 导入时拿它当关联主键，改一个字节这份乐谱就匹配不上，
 * 而用户看到的现象是「导入成功了但什么都没变」——极难排查。
 */
class CsvAdapterTest {

    private val header = listOf(
        "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)", "Composers",
        "乐曲类型", "编配", "标签", "Reference", "声部", "困难度", "Minutes", "Seconds", "keysf", "keymi",
    )
    private val col = ColumnMap.of(header)

    // ------------------------------------------------------------ CSV → FixRow

    @Test
    fun `CSV 行映射到 FixRow`() {
        val cells = listOf(
            "七月的草原G.pdf", "七月的草原G", "", "", "具本哲 配伴奏, 曹火星",
            "", "", "", "", "0", "0", "", "", "-3", "0",
        )
        val r = CsvAdapter.toRow(cells, col)
        assertEquals("七月的草原G.pdf", r.fileName)
        assertEquals("七月的草原G", r.title)
        assertEquals("具本哲 配伴奏, 曹火星", r.composer)
        assertEquals(-3, r.keysf)
        assertEquals(0, r.keymi)
    }

    @Test
    fun `Filename 不 trim 前后空格`() {
        // 主键就是要拿去匹配的，任何「顺手清理」都可能把能匹配的变成匹配不上的
        val cells = listOf("  x.pdf  ", "X", "", "", "", "", "", "", "", "0", "0", "", "", "", "")
        assertEquals("  x.pdf  ", CsvAdapter.toRow(cells, col).fileName)
    }

    @Test
    fun `缺列与越界给空值不崩`() {
        val r = CsvAdapter.toRow(listOf("a.pdf"), col)
        assertEquals("a.pdf", r.fileName)
        assertEquals("", r.title)
        assertEquals(null, r.keysf)
        assertEquals(null, r.keymi)
    }

    @Test
    fun `非法调性数字给 null 而不是 0`() {
        // 给 0 会静默变成「C 大调」——一个用户从没设过的调
        val cells = listOf("a.pdf", "A", "", "", "", "", "", "", "", "0", "0", "", "", "abc", "xyz")
        val r = CsvAdapter.toRow(cells, col)
        assertEquals(null, r.keysf)
        assertEquals(null, r.keymi)
    }

    // ------------------------------------------------------------ 回写单元格

    @Test
    fun `一个字不改时导出与导入内容一致`() {
        // 一个字都没改时，导出必须与导入内容一致（不新增引号、不改行数）。
        // 注意比的是**解析后的内容**而不是原始字节：toCsv 一律用 CRLF，
        // 而输入可能是 LF，那是刻意的规范（forScore 自己的导出也用 CRLF）。
        val src = "Filename,Title,Composers\n\"a,b.pdf\",X,Beethoven\n"
        val p = parseCsv(src)
        val c = ColumnMap.of(p.header)
        val rows = CsvAdapter.toRows(p.header, p.rows, c)
        val out = rows.mapIndexed { i, _ -> CsvAdapter.toCells(p.header, p.rows[i], c, null, emptySet()) }
        val again = parseCsv(toCsv(p.header, out))
        assertEquals(p.header, again.header)
        assertEquals(p.rows, again.rows, "内容与引号包裹都该原样保留")
    }

    @Test
    fun `采纳标题建议后写回`() {
        val p = parseCsv("Filename,Title\nx.pdf,七月的草原G\n")
        val c = ColumnMap.of(p.header)
        val row = CsvAdapter.toRow(p.rows[0], c)
        val prop = FixProposer.propose(row, RuleSwitches())
        val cells = CsvAdapter.toCells(p.header, p.rows[0], c, prop, setOf("title"))
        assertEquals("七月的草原", cells[1])
        assertEquals("x.pdf", cells[0], "Filename 不该被碰")
    }

    @Test
    fun `没采纳的字段保持原值`() {
        val p = parseCsv("Filename,Title,Composers\nx.pdf,七月的草原G,Beethoven\n")
        val c = ColumnMap.of(p.header)
        val row = CsvAdapter.toRow(p.rows[0], c)
        val prop = FixProposer.propose(row, RuleSwitches())
        // take 里只有 title，作曲家即使有建议也不该写进去
        val cells = CsvAdapter.toCells(p.header, p.rows[0], c, prop, setOf("title"))
        assertEquals("Beethoven", cells[2])
    }

    @Test
    fun `不碰规则范围外的列`() {
        // 声部 / 困难度 / Minutes / Seconds 都是原样保留，一个字节都不动
        val p = parseCsv("Filename,Title,Reference,声部,困难度,Minutes,Seconds\nx.pdf,T,R,3,5,12,30\n")
        val c = ColumnMap.of(p.header)
        val cells = CsvAdapter.toCells(p.header, p.rows[0], c, null, emptySet())
        assertEquals("3", cells[3])
        assertEquals("5", cells[4])
        assertEquals("12", cells[5])
        assertEquals("30", cells[6])
    }

    @Test
    fun `调性两列一起写`() {
        // 只写 keysf 不写 keymi 会得到一个「有调号但不知大小调」的半残值
        val p = parseCsv("Filename,Title,keysf,keymi\nx.pdf,T,,\n")
        val c = ColumnMap.of(p.header)
        val prop = FixProposer.propose(CsvAdapter.toRow(p.rows[0], c), RuleSwitches())
            .copy(keysf = -4, keymi = 1)
        val cells = CsvAdapter.toCells(p.header, p.rows[0], c, prop, setOf("keysf"))
        assertEquals("-4", cells[2])
        assertEquals("1", cells[3])
    }

    @Test
    fun `短行按表头补齐`() {
        // CSV 里行尾空列常被省略，回写时不能越界
        val h = listOf("Filename", "Title", "Composers")
        val c = ColumnMap.of(h)
        val cells = CsvAdapter.toCells(h, listOf("x.pdf"), c, null, emptySet())
        assertEquals(3, cells.size)
        assertEquals("", cells[2])
    }

    // ------------------------------------------------------- 曲库 → FixRow

    private fun score(
        id: Long = 1,
        title: String = "T",
        composer: String = "C",
        type: String = "Sonata",
        source: String = "本地导入",
        filePath: String? = null,
        assetPdf: String? = null,
        keysf: Int? = null,
        keymi: Int? = null,
    ) = Score(
        id = id, title = title, composer = composer, type = type,
        instrument = "钢琴", period = "古典", level = "高级", source = source,
        filePath = filePath, assetPdf = assetPdf, keysf = keysf, keymi = keymi,
    )

    @Test
    fun `取文件名时剥掉目录`() {
        // 曲库存的是绝对路径，而 forScore 只认裸文件名
        val s = score(filePath = "/data/user/0/com.example/files/scores/x.pdf")
        assertEquals("x.pdf", LibraryAdapter.fileNameOf(s))
    }

    @Test
    fun `没有 filePath 时退到 assetPdf`() {
        val s = score(assetPdf = "moonlight_op27_no2.pdf")
        assertEquals("moonlight_op27_no2.pdf", LibraryAdapter.fileNameOf(s))
    }

    @Test
    fun `两者都空时给空串`() {
        assertEquals("", LibraryAdapter.fileNameOf(score()))
    }

    @Test
    fun `反斜杠分隔符也认`() {
        // Windows 上跑测试时路径可能是反斜杠
        assertEquals("x.pdf", LibraryAdapter.baseName("C:\\a\\b\\x.pdf"))
    }

    @Test
    fun `曲库字段映射到 forScore 列`() {
        val s = score(
            title = "《四季》Op.8", composer = "维瓦尔第", type = "协奏曲",
            source = "Bärenreiter 版", filePath = "/d/x.pdf", keysf = 1, keymi = 0,
        )
        val r = LibraryAdapter.toFixRow(s)
        assertEquals("x.pdf", r.fileName)
        assertEquals("《四季》Op.8", r.title)
        assertEquals("维瓦尔第", r.composer)
        assertEquals("协奏曲", r.genre, "曲库的 type 对应 forScore 的 Genres")
        assertEquals("Bärenreiter 版", r.reference)
        assertEquals(1, r.keysf)
        assertEquals(0, r.keymi)
    }

    // ------------------------------------------------------- 写回 Score

    @Test
    fun `写回只动规则管得到的字段`() {
        val s = score(title = "七月的草原G", composer = "贝多芬", type = "Sonata")
        val r = LibraryAdapter.toFixRow(s)
        val prop = FixProposer.propose(r, RuleSwitches())
        val next = LibraryAdapter.applyTo(s, prop, setOf("title"))
        assertEquals("七月的草原", next.title)
        // 乐器 / 时期 / 难度 / id 一概不动 —— 那些是曲库自己的语义
        assertEquals("钢琴", next.instrument)
        assertEquals("古典", next.period)
        assertEquals("高级", next.level)
        assertEquals(1L, next.id)
    }

    @Test
    fun `写回调性两列`() {
        val s = score(keysf = 0, keymi = 0)
        val prop = FixProposer.propose(LibraryAdapter.toFixRow(s), RuleSwitches())
            .copy(keysf = -6, keymi = 1)
        val next = LibraryAdapter.applyTo(s, prop, setOf("keysf"))
        assertEquals(-6, next.keysf)
        assertEquals(1, next.keymi)
    }

    @Test
    fun `标题为 null 时保持原值`() {
        // 合集子条目的 proposal.title 是 null（规则刻意不改它），
        // 这时不能把标题写成空串
        val s = score(title = "原样")
        val prop = FixProposer.propose(LibraryAdapter.toFixRow(s), RuleSwitches())
        val next = LibraryAdapter.applyTo(s, prop, setOf("title"))
        assertEquals("原样", next.title)
    }

    // ------------------------------------------------- 往返：曲库 → CSV → 曲库

    @Test
    fun `曲库导出的 CSV 能被重新解析回同样的调性`() {
        val s = score(filePath = "/d/x.pdf", keysf = -4, keymi = 1)
        val r = LibraryAdapter.toFixRow(s)
        val cells = CsvAdapter.toCells(header, List(header.size) { "" }, col, null, emptySet())
        // 手工按 FixRow 填一份（模拟导出）
        val filled = cells.toMutableList().also {
            it[0] = r.fileName; it[1] = r.title; it[4] = r.composer
            it[13] = r.keysf.toString(); it[14] = r.keymi.toString()
        }
        val back = CsvAdapter.toRow(filled, col)
        assertEquals(r.keysf, back.keysf)
        assertEquals(r.keymi, back.keymi)
        assertEquals("F小调", com.example.scoreapp.domain.csvfix.ScoreKey.textOf(back, col))
    }

    @Test
    fun `Filename 是整个链路的稳定性锚点`() {
        val raw = "《萱草花》乐谱（五线谱带伴奏）.pdf"
        val s = score(filePath = "/data/x/$raw")
        val r = LibraryAdapter.toFixRow(s)
        val cells = CsvAdapter.toCells(header, List(header.size) { "" }, col, null, emptySet())
        assertTrue(r.fileName == raw, "文件名必须逐字符保真，得到 ${r.fileName}")
        assertEquals(raw, cells[0].ifEmpty { raw }, "Filename 列不该被清洗")
    }
}
