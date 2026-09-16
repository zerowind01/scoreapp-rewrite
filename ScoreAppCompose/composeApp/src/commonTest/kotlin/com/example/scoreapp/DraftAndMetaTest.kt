package com.example.scoreapp

import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.ScoreDraft
import com.example.scoreapp.ui.screens.metaLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 保存路径与详情页派生文案的回归测试。
 *
 * 这两块逻辑在反编译产物里都能读到确切实现，属于「必须对齐」的部分：
 * 字段归一化决定了筛选分面的粒度，元信息行决定了详情页头部的呈现。
 */
class DraftAndMetaTest {

    // ---------------------------------------------------------------- 字段归一化

    private fun draft(
        title: String = "x",
        composer: String = "",
        type: String = "",
        instrument: String = "",
        period: String = "",
        level: String = "",
        source: String = "",
        pages: String = "1",
    ) = ScoreDraft(
        title = title,
        composer = composer,
        type = type,
        instrument = instrument,
        period = period,
        level = level,
        source = source,
        pages = pages,
    )

    private val base = SampleLibrary.scores.first()

    @Test
    fun `空字段按原应用规则填入占位值`() {
        val out = draft(title = "  标题  ").applyTo(base)
        assertEquals("标题", out.title, "标题应去空格")
        assertEquals("佚名", out.composer)
        assertEquals("未编目", out.type)
        assertEquals("未分类", out.instrument)
        assertEquals("未指定", out.period)
        assertEquals("—", out.level)
        assertEquals("本地导入", out.source)
    }

    @Test
    fun `已有取值不被覆盖`() {
        val out = draft(
            title = "x",
            composer = "贝多芬",
            type = "奏鸣曲",
            instrument = "钢琴",
            period = "古典",
            level = "高级",
            source = "Henle 原版",
            pages = "14",
        ).applyTo(base)
        assertEquals("贝多芬", out.composer)
        assertEquals("奏鸣曲", out.type)
        assertEquals("钢琴", out.instrument)
        assertEquals("古典", out.period)
        assertEquals("高级", out.level)
        assertEquals("Henle 原版", out.source)
        assertEquals(14, out.pages)
    }

    @Test
    fun `页数非法时保留原值`() {
        val out = draft(title = "x", pages = "不是数字").applyTo(base)
        assertEquals(base.pages, out.pages)
        assertEquals(base.pages, draft(title = "x", pages = "").applyTo(base).pages)
    }

    @Test
    fun `归一化不改动无关字段`() {
        val out = draft(title = "新标题").applyTo(base)
        assertEquals(base.id, out.id)
        assertEquals(base.thumbSeed, out.thumbSeed)
        assertEquals(base.assetPdf, out.assetPdf)
        assertEquals(base.dateAdded, out.dateAdded)
    }

    // ---------------------------------------------------------------- 详情页元信息行

    @Test
    fun `元信息行按固定顺序拼接`() {
        val s = SampleLibrary.scores.first()
        assertEquals(
            listOf(s.type, s.instrument, s.period, s.level).joinToString(" · "),
            metaLine(s),
        )
    }

    @Test
    fun `元信息行剔除空白字段`() {
        val s = Score(
            id = 1,
            title = "t",
            composer = "c",
            type = "奏鸣曲",
            instrument = "",
            period = "古典",
            level = "  ",
            source = "本地导入",
        )
        assertEquals("奏鸣曲 · 古典", metaLine(s))
    }

    @Test
    fun `全部字段为空时元信息行为空串`() {
        val s = Score(
            id = 1,
            title = "t",
            composer = "c",
            type = "",
            instrument = "",
            period = "",
            level = "",
            source = "",
        )
        assertEquals("", metaLine(s))
    }
}
