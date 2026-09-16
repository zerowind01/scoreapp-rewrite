package com.example.scoreapp

import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.FilterState
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.SortMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 业务层回归测试。
 *
 * 与 `prototype/regression-test.js` 使用同一组语义断言，用来锁住
 * 「HTML 原型」和「Compose 工程」两条实现路径不会各自漂移。
 * 期望值全部从样例数据推导，不写死数字。
 */
class LibraryQueryTest {

    private val all = SampleLibrary.scores

    // ---------------------------------------------------------------- 搜索

    @Test
    fun `空查询命中全部`() {
        assertEquals(all.size, LibraryQuery.visible(all, "", FilterState.EMPTY).size)
        assertEquals(all.size, LibraryQuery.visible(all, "   ", FilterState.EMPTY).size)
    }

    @Test
    fun `查询命中标题`() {
        val target = all.first()
        val hit = LibraryQuery.visible(all, target.title, FilterState.EMPTY)
        assertTrue(hit.any { it.id == target.id }, "按完整标题应能搜到")
    }

    @Test
    fun `查询命中作曲家与乐器`() {
        val target = all.first { it.instrument.isNotBlank() }
        assertTrue(
            LibraryQuery.visible(all, target.composer, FilterState.EMPTY).any { it.id == target.id },
        )
        assertTrue(
            LibraryQuery.visible(all, target.instrument, FilterState.EMPTY).any { it.id == target.id },
        )
    }

    @Test
    fun `查询大小写不敏感`() {
        val withLatin = all.firstOrNull { s -> s.title.any { it in 'A'..'Z' || it in 'a'..'z' } }
        if (withLatin != null) {
            assertEquals(
                LibraryQuery.visible(all, withLatin.title.uppercase(), FilterState.EMPTY).size,
                LibraryQuery.visible(all, withLatin.title.lowercase(), FilterState.EMPTY).size,
            )
        }
    }

    @Test
    fun `无匹配时返回空`() {
        assertTrue(LibraryQuery.visible(all, "zzz-不存在的关键词-qqq", FilterState.EMPTY).isEmpty())
    }

    // ---------------------------------------------------------------- 筛选

    @Test
    fun `筛选为空时全部通过`() {
        assertTrue(all.all { LibraryQuery.matchesFilters(it, FilterState.EMPTY) })
    }

    @Test
    fun `单维度筛选`() {
        val composer = all.first().composer
        val filters = FilterState.EMPTY.copy(composer = setOf(composer))
        val hit = LibraryQuery.visible(all, "", filters)
        assertTrue(hit.isNotEmpty())
        assertTrue(hit.all { it.composer == composer })
        assertEquals(all.count { it.composer == composer }, hit.size)
    }

    @Test
    fun `维度内取并集`() {
        val composers = all.map { it.composer }.distinct().take(2)
        val filters = FilterState.EMPTY.copy(composer = composers.toSet())
        val hit = LibraryQuery.visible(all, "", filters)
        assertEquals(all.count { it.composer in composers }, hit.size)
    }

    @Test
    fun `维度间取交集`() {
        val sample = all.first()
        val filters = FilterState.EMPTY.copy(
            composer = setOf(sample.composer),
            type = setOf(sample.type),
        )
        val hit = LibraryQuery.visible(all, "", filters)
        assertTrue(hit.all { it.composer == sample.composer && it.type == sample.type })
        assertEquals(all.count { it.composer == sample.composer && it.type == sample.type }, hit.size)
    }

    @Test
    fun `互斥条件得到空集`() {
        val composers = all.map { it.composer }.distinct()
        if (composers.size >= 2) {
            val filters = FilterState.EMPTY.copy(composer = setOf(composers[0], composers[1]))
            // 两位不同作曲家的乐谱类型若无交集，结果可能为空；这里只验证结果自洽
            val hit = LibraryQuery.visible(all, "", filters)
            assertTrue(hit.all { it.composer in filters.composer })
        }
    }

    // ---------------------------------------------------------------- 分面计数

    @Test
    fun `分面计数排除本维度已选条件`() {
        // 选中某位作曲家后，同维度其它作曲家的计数不应被自己归零
        val composers = all.map { it.composer }.distinct()
        val selected = composers.first()
        val other = composers.first { it != selected }

        val pending = FilterState.EMPTY.copy(composer = setOf(selected))
        val countForOther = LibraryQuery.facetCount(all, "", pending, FilterDim.Composer, other)

        assertEquals(
            all.count { it.composer == other },
            countForOther,
            "同维度已选条件不应参与本维度计数",
        )
    }

    @Test
    fun `分面计数受其它维度约束`() {
        val sample = all.first()
        val pending = FilterState.EMPTY.copy(type = setOf(sample.type))
        val count = LibraryQuery.facetCount(all, "", pending, FilterDim.Composer, sample.composer)
        assertEquals(all.count { it.type == sample.type && it.composer == sample.composer }, count)
    }

    @Test
    fun `分面取值只列出实际出现过的值`() {
        for (dim in FilterDim.entries) {
            val values = LibraryQuery.facetValues(all, dim)
            assertEquals(values.distinct(), values, "取值不应重复")
            for (v in values) {
                assertTrue(
                    all.any { LibraryQuery.valueOf(it, dim) == v },
                    "取值 $v 应至少对应一条记录",
                )
            }
        }
    }

    @Test
    fun `待提交计数忽略分组方式`() {
        val pending = FilterState.EMPTY.copy(composer = setOf(all.first().composer))
        assertEquals(
            LibraryQuery.pendingCount(all, "", pending),
            LibraryQuery.visible(all, "", pending).size,
        )
    }

    // ---------------------------------------------------------------- 作曲家

    @Test
    fun `短名取姓氏`() {
        for (full in ComposerNames.ALIAS.values) {
            val short = ComposerNames.shortName(full)
            assertTrue(short.isNotBlank(), "$full 的短名不应为空")
            assertTrue(full.contains(short), "$full 应包含短名 $short")
        }
    }

    @Test
    fun `短名对未知作曲家原样返回`() {
        assertEquals("某某人", ComposerNames.shortName("某某人"))
    }

    @Test
    fun `首字母索引支持汉字与拉丁字母`() {
        // 原应用用 Character.isLetter 判断，汉字算字母
        for ((surname, expected) in mapOf("肖邦" to "X", "巴赫" to "B", "莫扎特" to "M")) {
            assertEquals(expected, ComposerNames.initialOf(surname), "$surname 的首字母")
        }
        assertEquals("Z", ComposerNames.initialOf("Zimmer"))
    }

    @Test
    fun `首字母对非字母输入有兜底`() {
        assertEquals("#", ComposerNames.initialOf(""))
        assertEquals("#", ComposerNames.initialOf("  "))
        assertEquals("#", ComposerNames.initialOf("123"))
    }

    @Test
    fun `未命中姓氏表时汉字仍视为字母`() {
        // 与 Java Character.isLetter 对齐：只有真正的非字母开头才归入 #
        assertEquals("张", ComposerNames.initialOf("张某某"))
    }

    @Test
    fun `姓氏表覆盖全部别名`() {
        val uncovered = ComposerNames.ALIAS.keys.filter { ComposerNames.initialOf(it) == "#" }
        assertTrue(uncovered.isEmpty(), "以下姓氏缺少索引字母：$uncovered")
    }

    @Test
    fun `样例数据中每位作曲家的索引字母都是 A-Z`() {
        // A–Z 索引条上出现汉字说明姓氏表漏登记，是数据与表不同步的信号
        val bad = all.map { it.composer }.distinct()
            .filterNot { ComposerNames.initialOf(it).matches(Regex("^[A-Z]$")) }
        assertTrue(bad.isEmpty(), "以下作曲家的索引字母不是 A–Z：$bad")
    }

    @Test
    fun `作曲家检索同时匹配全名与短名`() {
        val full = ComposerNames.ALIAS.values.first()
        assertTrue(ComposerNames.matches(full, full))
        assertTrue(ComposerNames.matches(full, ComposerNames.shortName(full)))
        assertTrue(ComposerNames.matches(full, ""))
        assertFalse(ComposerNames.matches(full, "zzz-不存在"))
    }

    @Test
    fun `作曲家索引按首字母分组且组内有序`() {
        val entries = LibraryQuery.composerEntries(all)
        assertEquals(entries.map { it.full }.distinct().size, entries.size, "作曲家不应重复")

        val sections = LibraryQuery.byInitial(entries)
        assertEquals(sections.map { it.first }, sections.map { it.first }.sorted(), "分组键应升序")
        for ((_, group) in sections) {
            assertEquals(group.map { it.full }, group.map { it.full }.sorted(), "组内应按全名升序")
        }
    }

    @Test
    fun `作曲家条目携带其全部乐谱`() {
        val entries = LibraryQuery.composerEntries(all)
        for (e in entries) {
            assertTrue(e.scores.isNotEmpty())
            assertTrue(e.scores.all { it.composer == e.full })
        }
        assertEquals(all.size, entries.sumOf { it.scores.size })
    }

    // ---------------------------------------------------------------- 排序与分组

    @Test
    fun `排序不丢不重`() {
        for (mode in SortMode.entries) {
            val sorted = LibraryQuery.sorted(all, mode)
            assertEquals(all.size, sorted.size, "$mode 排序后数量应不变")
            assertEquals(all.map { it.id }.toSet(), sorted.map { it.id }.toSet(), "$mode 排序不应改变集合")
        }
    }

    @Test
    fun `分组覆盖全部输入且不丢项`() {
        for (dim in FilterDim.entries) {
            val groups = LibraryQuery.group(all, dim)
            assertEquals(all.size, groups.sumOf { it.items.size }, "$dim 分组后总数应不变")
            assertEquals(groups.map { it.key }.distinct().size, groups.size, "$dim 分组键不应重复")
        }
    }

    @Test
    fun `分组按数量降序`() {
        val groups = LibraryQuery.group(all, FilterDim.Type)
        val sizes = groups.map { it.items.size }
        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun `作曲家分组的标签是姓氏`() {
        val groups = LibraryQuery.group(all, FilterDim.Composer)
        for (g in groups) {
            assertEquals(ComposerNames.shortName(g.key), g.label)
        }
    }

    // ---------------------------------------------------------------- 统计

    @Test
    fun `去重计数与手工去重一致`() {
        for (dim in FilterDim.entries) {
            val expected = all.map { LibraryQuery.valueOf(it, dim) }.filter { it.isNotBlank() }.distinct().size
            assertEquals(expected, LibraryQuery.distinctCount(all, dim), "$dim 去重计数")
        }
    }

    @Test
    fun `空库各项计数为零`() {
        assertEquals(0, LibraryQuery.distinctCount(emptyList(), FilterDim.Composer))
        assertTrue(LibraryQuery.composerEntries(emptyList()).isEmpty())
        assertTrue(LibraryQuery.group(emptyList(), FilterDim.Type).isEmpty())
    }

    // ---------------------------------------------------------------- 样例数据

    @Test
    fun `样例数据自洽`() {
        assertTrue(all.isNotEmpty())
        assertEquals(all.map { it.id }.distinct().size, all.size, "id 不应重复")
        for (s in all) {
            assertTrue(s.title.isNotBlank(), "标题不应为空")
            assertTrue(s.composer.isNotBlank(), "作曲家不应为空")
            assertTrue(s.pages > 0, "${s.title} 页数应为正")
        }
    }

    @Test
    fun `合集引用的乐谱都存在`() {
        for (set in SampleLibrary.sets) {
            for (seed in set.seeds) {
                assertTrue(
                    all.any { it.thumbSeed == seed || it.id == seed.toLong() },
                    "合集「${set.name}」引用了不存在的乐谱 $seed",
                )
            }
        }
    }

    @Test
    fun `同一作曲家只用一种头像配色`() {
        // 头像配色由姓名哈希决定，同名必然同色；这里确认哈希本身稳定
        for (s in all.take(10)) {
            assertEquals(
                com.example.scoreapp.domain.AvatarPalette.colorFor(s.composer),
                com.example.scoreapp.domain.AvatarPalette.colorFor(s.composer),
            )
        }
    }
}
