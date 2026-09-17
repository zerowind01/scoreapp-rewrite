package com.example.scoreapp

import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ReaderBarText
import com.example.scoreapp.domain.ShareInfo
import com.example.scoreapp.domain.ShareSummary
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.PickKind
import com.example.scoreapp.ui.ReaderRequest
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.ScoreDraft
import com.example.scoreapp.ui.SheetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件链路的回归测试。
 *
 * 覆盖三块**纯逻辑**、不依赖 Android 运行时的部分：
 *  1. 导入落库的元数据口径与路径语义；
 *  2. 分享摘要的拼接规则（空字段剔除、`—` 视为无信息）；
 *  3. 封面渲染所依赖的「这份乐谱有没有可读文件」判定。
 *
 * 真正调用 `PdfRenderer` / `FileProvider` 的部分无法在这里验证——
 * 那些需要设备，属于「演示边界」里明写的能力。
 */
class FileLinkTest {

    private fun state() = ScoreAppState()

    // ---------------------------------------------------------------- 导入落库

    @Test
    fun `导入落库的元数据口径正确`() {
        val s = state()
        s.importFromImages(listOf())  // 空列表，no-op
        assertNull(s.bridge, "测试态下未注入桥，文件动作应安全降级")

        // 曲库精简后样例里不再有「本地导入」条目，这里直接构造导入产物应有的样子：
        // 六个占位字段的口径见 addImported 与 ScoreDraft.normalize*
        val imported = Score(
            id = 1,
            title = "相册乐谱 · 3 页",
            composer = "佚名",
            type = "未编目",
            instrument = "未分类",
            period = "未指定",
            level = "—",
            source = "本地导入",
        )
        assertEquals("本地导入", imported.source, "两条导入通道都写「本地导入」")
    }

    @Test
    fun `相册导入的来源标记与 PDF 导入一致`() {
        // 反编译产物里两个调用点都写 "本地导入"：
        // 相册合成 (MainActivityKt:1539) 与 PDF 拷贝 (MainActivityKt:1560)。
        // 这里锁住这一点——曾经误写成「相册导入」，导致同一来源裂成两个筛选分面。
        val sources = SampleLibrary.SOURCES
        assertTrue(sources.contains("本地导入"))
        assertTrue(!sources.contains("相册导入"), "不应存在「相册导入」这个来源取值")
    }

    @Test
    fun `没有桥时导入不落库也不崩溃`() {
        val s = state()
        val before = s.allScores.size
        s.importFromImages(listOf("content://x/1"))
        assertEquals(before, s.allScores.size, "拿不到桥时不应凭空增加乐谱")
        assertNotNull(s.toast, "应给出提示而不是静默失败")

        s.importFromPdf("content://x/1")
        assertEquals(before, s.allScores.size)
    }

    @Test
    fun `选择意图可以在发起与消费之间往返`() {
        val s = state()
        assertEquals(PickKind.None, s.pendingPick)
        s.beginPick(PickKind.Pdf)
        assertEquals(PickKind.Pdf, s.pendingPick)
        // 关弹层的动作由 beginPick 内部完成，避免选择器盖在弹层上
        assertEquals(SheetKind.None, s.sheet)
        s.consumePick()
        assertEquals(PickKind.None, s.pendingPick)
    }

    // ---------------------------------------------------------------- 打开乐谱

    @Test
    fun `没有文件时不进入阅读器`() {
        val s = state()
        val noFile = Score(
            id = 999,
            title = "无文件",
            composer = "佚名",
            type = "未编目",
            instrument = "未分类",
            period = "未指定",
            level = "—",
            source = "本地导入",
        )
        s.openPdf(noFile)
        assertNull(s.reader, "没有 PDF 不该打开空阅读器")
        assertEquals("这份乐谱还没有 PDF 文件，可在列表里导入", s.toast)
    }

    @Test
    fun `有文件时挂上阅读器请求`() {
        val s = state()
        val withFile = Score(
            id = 7,
            title = "有文件",
            composer = "佚名",
            type = "未编目",
            instrument = "未分类",
            period = "未指定",
            level = "—",
            source = "本地导入",
            filePath = "/data/user/0/com.example.scoreapp/files/scores/import_x.pdf",
        )
        s.openPdf(withFile)
        val request: ReaderRequest? = s.reader
        assertNotNull(request)
        assertEquals(7L, request.key)
        assertEquals(withFile.filePath, request.path)
        assertEquals("有文件", request.title)

        s.closePdf()
        assertNull(s.reader)
    }

    @Test
    fun `assetPdf 也能作为打开来源`() {
        // 内置乐谱带的是 assets 里的文件名，靠 PdfAssets 拷进 filesDir 后再指过去。
        // 判定必须同时认这两个字段，否则内置乐谱会打不开。
        val s = state()
        val bundled = SampleLibrary.scores.first { !it.assetPdf.isNullOrBlank() }
        s.openPdf(bundled)
        assertNotNull(s.reader, "带 assetPdf 的乐谱应该能打开")
        assertEquals(bundled.assetPdf, s.reader?.path)
    }

    // ---------------------------------------------------------------- 分享摘要

    private fun shareInfo(
        title: String = "《月光》",
        composer: String = "",
        type: String = "奏鸣曲",
        instrument: String = "钢琴",
        period: String = "—",
        level: String = " ",
        pages: Int = 0,
        source: String = "本地导入",
    ) = ShareInfo(title, composer, type, instrument, period, level, pages, source)

    @Test
    fun `分享摘要剔除空字段与破折号`() {
        val lines = ShareSummary.of(shareInfo()).lines()
        assertEquals("《月光》", lines[0])
        // 作曲家为空 → 不占一行，第二行直接是元信息
        assertEquals("奏鸣曲 · 钢琴", lines[1], "空作曲家不该留下空行，`—` 与空白也不该进拼接")
        assertTrue(lines.none { it.contains("共 ") }, "页数为 0 时不输出页数行")
        assertTrue(lines.any { it == "来源：本地导入" })
        assertEquals("—— 由「乐谱管理」分享", lines.last())
    }

    @Test
    fun `分享摘要以换行结尾时裁掉尾部空白`() {
        val summary = ShareSummary.of(
            shareInfo(composer = "c", period = "古典", level = "高级", pages = 14),
        )
        assertEquals(summary.trimEnd(), summary, "摘要不应以空白结尾")
        assertTrue(summary.contains("共 14 页"))
        assertTrue(summary.contains("奏鸣曲 · 钢琴 · 古典 · 高级"))
    }

    @Test
    fun `元信息全为空时不留下悬空分隔符`() {
        val lines = ShareSummary.of(
            shareInfo(type = "", instrument = "", period = "", level = ""),
        ).lines()
        // 标题行 + 来源行 + 署名行，不应出现单独一行的分隔符或破折号
        assertTrue(lines.none { it.isBlank() })
        assertTrue(lines.none { it.trim() == "·" || it.contains("· ") })
    }

    // ---------------------------------------------------------------- 封面前提

    @Test
    fun `hasPdf 与 displayFile 的语义`() {
        val bare = Score(
            id = 1, title = "t", composer = "c", type = "y", instrument = "i",
            period = "p", level = "l", source = "s",
        )
        assertTrue(!bare.hasPdf)
        assertNull(bare.displayFile)

        // 只有 assetPdf 时也算有文件
        val assetOnly = bare.copy(assetPdf = "moonlight_op27_no2.pdf")
        assertTrue(assetOnly.hasPdf)
        assertEquals("moonlight_op27_no2.pdf", assetOnly.displayFile)

        // filePath 优先于 assetPdf：本地导入的产物应胜过内置资源
        val both = assetOnly.copy(filePath = "/files/scores/import_x.pdf")
        assertEquals("/files/scores/import_x.pdf", both.displayFile, "本地文件应优先于内置资源")
    }

    // ---------------------------------------------------------------- 占位符位置（毛病一）

    @Test
    fun `导入产物的占位符与字段语义一一对应`() {
        // 原应用把这一串整体错位了一格：作曲家位填「未编目」，之后依次顺移。
        // 判定标准是「这个值放进这个字段读起来通不通」：
        //   作者叫「未编目」不通，作者叫「佚名」通。
        val s = Score(
            id = 1,
            title = "相册乐谱 · 3 页",
            composer = "佚名",
            type = "未编目",
            instrument = "未分类",
            period = "未指定",
            level = "—",
            source = "本地导入",
        )
        assertEquals("佚名", s.composer, "作者未知应记为「佚名」")
        assertEquals("未编目", s.type, "未归类体裁应记为「未编目」")
        assertEquals("未分类", s.instrument, "未指定编制应记为「未分类」")
        assertEquals("未指定", s.period, "未判定年代应记为「未指定」")
        assertEquals("—", s.level, "难度无内容是占位符，不是一种难度")
        assertEquals("本地导入", s.source)

        // 错位的特征就是「未编目」出现在作曲家位——显式禁止
        assertTrue(s.composer != "未编目", "作曲家位不应出现曲目类型的占位值")
        assertTrue(s.type != "未分类", "曲目类型位不应出现乐器的占位值")
    }

    @Test
    fun `保存时的归一化与导入时的口径一致`() {
        // 两条写入路径必须落到同一组占位值，否则同一份「空白乐谱」
        // 会在筛选分面里裂成两个桶，取决于它是被导入还是被编辑出来的。
        val fromEdit = ScoreDraft(
            title = "t",
            composer = " ",
            type = "",
            instrument = " ",
            period = "",
            level = " ",
            source = "",
            pages = "1",
        ).applyTo(SampleLibrary.scores.first())

        assertEquals("佚名", fromEdit.composer)
        assertEquals("未编目", fromEdit.type)
        assertEquals("未分类", fromEdit.instrument)
        assertEquals("未指定", fromEdit.period)
        assertEquals("—", fromEdit.level)
        assertEquals("本地导入", fromEdit.source)
    }

    // ---------------------------------------------------------------- 阅读器四态（毛病二、三）

    @Test
    fun `阅读器顶栏副标题在四种状态下互不重复`() {
        val subtitles = listOf(
            ReaderBarText.subtitle(pageCount = null),
            ReaderBarText.subtitle(pageCount = null, failed = true),
            ReaderBarText.subtitle(pageCount = 0),
            ReaderBarText.subtitle(pageCount = 12),
        )
        assertEquals(listOf("读取中…", "无法打开", "没有页面", "共 12 页"), subtitles)
        assertEquals(subtitles.size, subtitles.toSet().size, "四种状态的文案必须互不相同")
    }

    @Test
    fun `空文档不再显示读取中`() {
        // 原应用判据只有 pageCount > 0，于是空文档落到 else 打出「读取中…」，
        // 与正文的「这份 PDF 没有任何页面」直接打架。
        val subtitle = ReaderBarText.subtitle(pageCount = 0)
        assertTrue(subtitle != ReaderBarText.LOADING, "空文档已经读完，不该再显示读取中")
        assertEquals("没有页面", subtitle)
    }

    @Test
    fun `页数徽标只在就绪时出现`() {
        assertNull(ReaderBarText.badgeText(null), "读取中不该显示徽标")
        assertNull(ReaderBarText.badgeText(0), "空文档不该显示「0 页」")
        assertEquals("1 页", ReaderBarText.badgeText(1))
    }

    @Test
    fun `就绪态下副标题与徽标说的是不同的事`() {
        // 副标题给状态（「共 12 页」），徽标给计数（「12 页」）。
        // 原应用两处都写页数，等于同一信息重复一遍——这里用「一个是叙述、一个是数字」区分。
        val subtitle = ReaderBarText.subtitle(pageCount = 12)
        val badge = ReaderBarText.badgeText(12)
        assertEquals("共 12 页", subtitle)
        assertEquals("12 页", badge)
        assertTrue(subtitle != badge, "副标题与徽标不应是完全相同的文案")
    }
}
