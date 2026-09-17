package com.example.scoreapp

import com.example.scoreapp.domain.BundledScoreResolver
import com.example.scoreapp.model.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 内置乐谱路径解析的回归测试。
 *
 * 锁住的是一条**曾经被漏掉**的能力：`assetPdf` 只是一个文件名
 * （`moonlight_op27_no2.pdf`），直接当 `filePath` 用会以进程工作目录为基准解析，
 * 设备上必然不存在——封面渲染不出来、「打开乐谱」直接提示没有文件。
 *
 * 原应用靠 `PdfAssets.installMissing` 把 assets 里的 PDF 拷到 `filesDir/scores/`
 * 再换掉路径。这里覆盖的是「拷完之后怎么算」，拷文件那半需要设备，属于演示边界。
 */
class BundledScoreResolverTest {

    private fun score(
        id: Long = 1,
        title: String = "月光",
        filePath: String? = null,
        assetPdf: String? = null,
    ) = Score(
        id = id,
        title = title,
        composer = "贝多芬",
        type = "奏鸣曲",
        instrument = "钢琴",
        period = "古典",
        level = "高级",
        source = "内置曲库",
        filePath = filePath,
        assetPdf = assetPdf,
    )

    private val installed = mapOf(
        "moonlight_op27_no2.pdf" to "/data/user/0/com.example.scoreapp/files/scores/moonlight_op27_no2.pdf",
        "nocturne_op9_no2.pdf" to "/data/user/0/com.example.scoreapp/files/scores/nocturne_op9_no2.pdf",
    )

    // ------------------------------------------------------------ pickSource

    @Test
    fun `精确命中优先于唯一候选`() {
        // 已安装两份时，唯一候选兜底不生效，必须靠精确匹配——
        // 否则两份内置乐谱会同时指向同一个文件。
        val s = score(assetPdf = "nocturne_op9_no2.pdf")
        assertEquals("nocturne_op9_no2.pdf", BundledScoreResolver.pickSource(s, installed.keys))
    }

    @Test
    fun `名字对不上但只有一个候选时兜底`() {
        // 原应用留这条是为了资源被改名（xxx → xxx_v2）后乐谱仍能用上
        val s = score(assetPdf = "moonlight_op27_no2_v2.pdf")
        assertEquals(
            "moonlight_op27_no2.pdf",
            BundledScoreResolver.pickSource(s, listOf("moonlight_op27_no2.pdf")),
        )
    }

    @Test
    fun `多份候选且都不匹配时返回 null 而不是猜`() {
        // 猜出来的路径打开必失败。返回 null 让上层明确走「没有文件」分支，
        // 用户看到的是「这份乐谱还没有 PDF 文件」，而不是一个空白阅读器。
        val s = score(assetPdf = "unknown.pdf")
        assertNull(BundledScoreResolver.pickSource(s, installed.keys))
    }

    @Test
    fun `没有 assetPdf 时不解析`() {
        assertNull(BundledScoreResolver.pickSource(score(), installed.keys))
        assertNull(BundledScoreResolver.pickSource(score(assetPdf = ""), installed.keys))
        assertNull(BundledScoreResolver.pickSource(score(assetPdf = "   "), installed.keys))
    }

    @Test
    fun `没有任何已安装文件时不做兜底`() {
        // 空集合的 singleOrNull() 是 null，不能变成「随便给个值」
        assertNull(BundledScoreResolver.pickSource(score(assetPdf = "a.pdf"), emptyList()))
    }

    // ------------------------------------------------------------ resolveAll

    @Test
    fun `内置乐谱被补成绝对路径`() {
        val scores = listOf(score(id = 1, assetPdf = "moonlight_op27_no2.pdf"))
        val out = BundledScoreResolver.resolveAll(scores, installed)
        assertEquals(installed["moonlight_op27_no2.pdf"], out[0].filePath)
        assertTrue(out[0].hasPdf)
    }

    @Test
    fun `已有 filePath 的乐谱不被覆盖`() {
        // 本地导入的产物必须胜过内置资源，否则用户导入过一次后
        // 会被内置版本盖回去，改的元数据跟着一起丢。
        val mine = "/data/user/0/com.example.scoreapp/files/scores/import_1735.pdf"
        val scores = listOf(score(id = 1, filePath = mine, assetPdf = "moonlight_op27_no2.pdf"))
        val out = BundledScoreResolver.resolveAll(scores, installed)
        assertEquals(mine, out[0].filePath, "导入产物不应被内置资源覆盖")
    }

    @Test
    fun `没有 assetPdf 的乐谱原样保留`() {
        val scores = listOf(score(id = 1), score(id = 2, filePath = "/x.pdf"))
        val out = BundledScoreResolver.resolveAll(scores, installed)
        assertEquals(scores, out)
    }

    @Test
    fun `空安装映射时整体原样返回`() {
        // 本仓库不把第三方版权乐谱纳入版本控制，新克隆下来就是这种状态：
        // 这是**正常状态**而不是错误，不能因此崩、也不该改动任何乐谱。
        val scores = listOf(
            score(id = 1, assetPdf = "moonlight_op27_no2.pdf"),
            score(id = 2),
        )
        val out = BundledScoreResolver.resolveAll(scores, emptyMap())
        assertTrue(out === scores, "空映射应原样返回同一个列表，不做无谓复制")
        assertNull(out[0].filePath, "缺内置资源时不该凭空造路径")
    }

    @Test
    fun `只解析指得到的乐谱，其余保持无文件`() {
        // 混合场景：一份能对上、一份对不上。对不上的保持 filePath 为 null，
        // 封面退化为程序化绘制，其余功能不受影响。
        val scores = listOf(
            score(id = 1, assetPdf = "moonlight_op27_no2.pdf"),
            score(id = 2, assetPdf = "ghost.pdf"),
        )
        val out = BundledScoreResolver.resolveAll(scores, installed)
        assertEquals(installed["moonlight_op27_no2.pdf"], out[0].filePath)
        assertNull(out[1].filePath)
    }

    @Test
    fun `解析不改变乐谱的其他字段`() {
        val s = score(id = 42, title = "月光", assetPdf = "moonlight_op27_no2.pdf")
        val out = BundledScoreResolver.resolveAll(listOf(s), installed)[0]
        assertEquals(s.copy(filePath = out.filePath), out, "只应改 filePath")
        assertEquals(s.id, out.id)
        assertEquals(s.title, out.title)
        assertEquals(s.assetPdf, out.assetPdf)
    }
}
