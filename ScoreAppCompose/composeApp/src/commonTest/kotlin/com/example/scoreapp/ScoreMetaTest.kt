package com.example.scoreapp

import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.formatDate
import com.example.scoreapp.domain.setMembers
import com.example.scoreapp.domain.setsOfScore
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 详情页元数据（添加时间）与谱单归属的纯逻辑测试。
 *
 * 这两块原先都埋在 UI 层里：`formatDate` 写在 `DetailScreen.kt` 却无人调用，
 * 归属反查则干脆没有——「分类归属」面板复制的其实还是作曲家/曲目类型/乐器。
 * 下沉到 domain 后即可直接断言，这里同时锁住行为。
 */
class ScoreMetaTest {

    // ---------------------------------------------------------------- 添加时间

    @Test
    fun `时间戳格式化为 yyyy-MM-dd HH:mm`() {
        // 1970-01-02 00:00 UTC（1 天），验证日期进位与补零
        assertEquals("1970-01-02 00:00", formatDate(86_400_000L))
    }

    @Test
    fun `非正时间戳回退为占位符`() {
        assertEquals("—", formatDate(0L))
        assertEquals("—", formatDate(-1L))
    }

    @Test
    fun `月日时分补零`() {
        // 2021-01-02 03:04 UTC = 1609556640000 ms
        assertEquals("2021-01-02 03:04", formatDate(1_609_556_640_000L))
    }

    @Test
    fun `样例乐谱均带可展示的添加时间`() {
        assertTrue(SampleLibrary.scores.all { it.dateAdded > 0L })
    }

    // ---------------------------------------------------------------- 谱单成员解析

    @Test
    fun `按缩略图种子解析谱单成员`() {
        val scores = listOf(
            score(id = 1, seed = 37),
            score(id = 2, seed = 11),
            score(id = 3, seed = 999),
        )
        val set = ScoreSet(name = "S", desc = "", seeds = listOf(37, 11))
        assertEquals(listOf(1L, 2L), setMembers(set, scores).map { it.id })
    }

    @Test
    fun `种子缺失时不误命中同号 id`() {
        // 种子是 37 这类小值，而 id 从 1001 起；用 id 兜底是永远命中不了的死分支
        val scores = listOf(score(id = 37, seed = 999), score(id = 1001, seed = 37))
        val set = ScoreSet(name = "S", desc = "", seeds = listOf(37))
        assertEquals(listOf(1001L), setMembers(set, scores).map { it.id })
    }

    @Test
    fun `样例谱单种子全部可解析`() {
        assertTrue(
            SampleLibrary.sets.all { setMembers(it, SampleLibrary.scores).isNotEmpty() },
            "每个谱单都应能解析出至少一位成员",
        )
    }

    // ---------------------------------------------------------------- 归属反查

    @Test
    fun `反查乐谱所属谱单`() {
        val target = SampleLibrary.scores.first { it.thumbSeed == 37 }
        val sets = setsOfScore(target, SampleLibrary.sets, SampleLibrary.scores)
        assertEquals(listOf("独奏会备选曲目"), sets.map { it.name })
    }

    @Test
    fun `未入谱单的乐谱反查为空`() {
        val orphan = SampleLibrary.scores.firstOrNull { s ->
            SampleLibrary.sets.none { it.seeds.contains(s.thumbSeed) }
        }
        assertTrue(orphan != null, "样例数据里应存在不属于任何谱单的乐谱")
        assertTrue(setsOfScore(orphan!!, SampleLibrary.sets, SampleLibrary.scores).isEmpty())
    }

    @Test
    fun `归属反查自洽`() {
        // 若某乐谱属于某谱单，则在该谱单的成员里必须能找回它
        SampleLibrary.scores.forEach { s ->
            setsOfScore(s, SampleLibrary.sets, SampleLibrary.scores).forEach { set ->
                assertTrue(
                    setMembers(set, SampleLibrary.scores).any { it.id == s.id },
                    "「${s.title}」声称属于「${set.name}」，但该谱单成员中找不到它",
                )
            }
        }
    }

    private fun score(id: Long, seed: Int) = Score(
        id = id,
        title = "t$id",
        composer = "c",
        type = "奏鸣曲",
        instrument = "钢琴",
        period = "古典",
        level = "高级",
        source = "本地导入",
        thumbSeed = seed,
    )
}
