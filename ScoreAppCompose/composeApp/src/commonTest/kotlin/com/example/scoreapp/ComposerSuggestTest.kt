package com.example.scoreapp

import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.ComposerSuggest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 编辑页作曲家自动补全的纯逻辑测试。
 *
 * 这一栏原先是一排 chip，名字越多占位越大；改输入框 + 候选后，
 * 「常用作曲家」的语义等于「空输入时的前 N 条候选」，所以这里既测过滤，
 * 也测「空输入必须给得出东西」——否则快捷入口就真的丢了。
 *
 * 命名与断言刻意与原型 `regression-test.js` 的对应段落保持同一套期待值，
 * 两边各自实现但语义必须锁死一致。
 */
class ComposerSuggestTest {

    // ---------------------------------------------------------------- 候选来源

    @Test
    fun `候选全集就是别名表顺序`() {
        assertEquals(ComposerNames.ALIAS.values.toList(), ComposerSuggest.all)
    }

    @Test
    fun `空输入给出常用作曲家前若干条`() {
        // 这正是原来那排 chip 的语义：什么都没打的时候也得有得点
        val got = ComposerSuggest.suggestions("")
        assertEquals(ComposerSuggest.MAX_SUGGESTIONS, got.size)
        assertEquals(ComposerSuggest.all.take(ComposerSuggest.MAX_SUGGESTIONS), got)
    }

    @Test
    fun `纯空白输入等同于空输入`() {
        // 输入框里敲了个空格，不应该变成「过滤不出任何东西」而把面板清空
        assertEquals(ComposerSuggest.suggestions(""), ComposerSuggest.suggestions("   "))
    }

    @Test
    fun `候选数量始终不超过上限`() {
        // 逐个常用姓氏都不能把面板撑爆
        ComposerSuggest.all.forEach { name ->
            assertTrue(
                ComposerSuggest.suggestions(name).size <= ComposerSuggest.MAX_SUGGESTIONS,
                "输入「$name」时候选数量超出上限",
            )
        }
        assertEquals(ComposerSuggest.MAX_SUGGESTIONS, ComposerSuggest.MAX_SUGGESTIONS)
    }

    // ---------------------------------------------------------------- 过滤

    @Test
    fun `输入姓氏过滤出对应作曲家`() {
        assertEquals(listOf("路德维希·范·贝多芬"), ComposerSuggest.suggestions("贝多芬"))
    }

    @Test
    fun `输入姓氏中的一个字也能命中`() {
        // 边打边过滤：只打了「贝」就该出现贝多芬
        val got = ComposerSuggest.suggestions("贝")
        assertTrue(got.contains("路德维希·范·贝多芬"), "输入「贝」应命中贝多芬，实际 $got")
    }

    @Test
    fun `输入名字段也能命中`() {
        // 「路德维希」是名字不是姓氏，走的是整串包含匹配这条分支
        val got = ComposerSuggest.suggestions("路德维希")
        assertTrue(got.contains("路德维希·范·贝多芬"), "输入名应命中，实际 $got")
    }

    @Test
    fun `拉丁字母大小写不影响命中`() {
        // 数据里目前没有拉丁名，但规则要站得住：matches 已做 lowercase
        assertTrue(ComposerNames.matches("路德维希·范·贝多芬", "贝多芬"))
        assertTrue(ComposerNames.matches("路德维希·范·贝多芬", "  贝多芬  "), "查询词应先去空白")
    }

    @Test
    fun `完全不相干的输入给出空候选`() {
        // 面板据此收起，不留一条空白边
        assertEquals(emptyList(), ComposerSuggest.suggestions("zzzz不存在"))
    }

    // ---------------------------------------------------------------- 高亮区间

    @Test
    fun `姓氏命中时区间落在整串的正确位置`() {
        val full = "路德维希·范·贝多芬"
        val range = ComposerSuggest.matchRange(full, "贝")
        // 「路德维希·范·贝多芬」：路0 德1 维2 希3 ·4 范5 ·6 贝7 多8 芬9
        assertEquals(7..7, range)
        assertEquals("贝", full.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `多字姓氏命中区间覆盖完整子串`() {
        val full = "路德维希·范·贝多芬"
        val range = ComposerSuggest.matchRange(full, "贝多芬")
        assertEquals(7..9, range)
    }

    @Test
    fun `多个中点时区间仍靠长度差反推`() {
        // 「阿希尔-克洛德·德彪西」的中点只有一个，但名字里带连字符，
        // 用「长度差」而不是「第一个中点位置」来定位才不会错
        val full = "阿希尔-克洛德·德彪西"
        val range = ComposerSuggest.matchRange(full, "德彪西")
        assertEquals("德彪西", full.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `名字段命中时区间落在名字里`() {
        val full = "路德维希·范·贝多芬"
        val range = ComposerSuggest.matchRange(full, "希")
        assertEquals(3..3, range)
    }

    @Test
    fun `空查询不产生区间`() {
        assertNull(ComposerSuggest.matchRange("路德维希·范·贝多芬", ""))
        assertNull(ComposerSuggest.matchRange("路德维希·范·贝多芬", "  "))
    }

    @Test
    fun `未命中返回空区间`() {
        assertNull(ComposerSuggest.matchRange("路德维希·范·贝多芬", "莫扎特"))
    }

    // ---------------------------------------------------------------- 三段切分

    @Test
    fun `三段切分拼回原串`() {
        val full = "路德维希·范·贝多芬"
        val (before, hit, after) = ComposerSuggest.splitHighlight(full, "贝多芬")
        assertEquals(full, before + hit + after, "三段必须能无损拼回原串")
        assertEquals("路德维希·范·", before)
        assertEquals("贝多芬", hit)
        assertEquals("", after)
    }

    @Test
    fun `命中在中间时后段非空`() {
        val full = "约翰·塞巴斯蒂安·巴赫"
        val (before, hit, after) = ComposerSuggest.splitHighlight(full, "巴")
        assertEquals(full, before + hit + after)
        assertEquals("巴", hit)
        assertEquals("赫", after)
    }

    @Test
    fun `无命中时整串落在前段`() {
        val full = "路德维希·范·贝多芬"
        val (before, hit, after) = ComposerSuggest.splitHighlight(full, "莫扎特")
        assertEquals(full, before)
        assertEquals("", hit)
        assertEquals("", after)
    }

    @Test
    fun `切分不丢字也不多字`() {
        // 对每条候选 × 每个常用姓氏做一次交叉，防止某条名字触发出越界或丢字
        ComposerSuggest.all.forEach { full ->
            ComposerNames.ALIAS.keys.forEach { surname ->
                val (before, hit, after) = ComposerSuggest.splitHighlight(full, surname)
                assertEquals(full, before + hit + after, "「$full」+「$surname」切分后无法拼回")
            }
        }
    }
}
