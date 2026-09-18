package com.example.scoreapp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.ui.BottomNavClearance
import com.example.scoreapp.ui.BottomNavHeight
import com.example.scoreapp.ui.sheets.editChipTextSize
import com.example.scoreapp.ui.sheets.editFieldLabelSize
import com.example.scoreapp.ui.sheets.editFieldTextSize
import com.example.scoreapp.ui.sheets.editFieldTopPadding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机反馈催生的尺寸参数回归。
 *
 * 这批数值是 Jackson 拿真机逐条比对、又照参考图定下的，散落在三四个文件里，
 * 靠肉眼 review 很容易在后续改动中被"顺手"调回去。这里把它们抄成断言，
 * 与原型 CSS 的对应数值一一对齐——两边任何一侧漂移都会红。
 *
 * 断言的对象是 `internal val` 常量本身，不是渲染结果，所以不需要截图或 UI 测试。
 */
class LayoutMetricsTest {

    // ---------------------------------------------------------------- 导台

    @Test
    fun `导台高度与内容避让是配套的`() {
        // 避让空间 = 距底 12 + 高度 56 + 呼吸 28
        assertEquals(56.dp, BottomNavHeight, "导台高度应为 56dp（原 54，按参考图加高）")
        assertEquals(96.dp, BottomNavClearance, "避让空间应与 56 高的导台配套（原 92）")
        assertTrue(
            BottomNavClearance > BottomNavHeight,
            "避让空间必须大于导台本身，否则末条内容会被压住",
        )
    }

    @Test
    fun `导台加高后避让同步放大`() {
        // 旧的 54 高配 92 避让；加高 2 之后避让也加了 4，这个差值关系要留住
        assertEquals(40.dp, BottomNavClearance - BottomNavHeight)
    }

    // ---------------------------------------------------------------- 编辑页密度

    @Test
    fun `编辑页表单间距与字号按减密度调过`() {
        assertEquals(16.dp, editFieldTopPadding, "字段上间距应为 16dp（原 11，太挤）")
        assertEquals(13.sp, editFieldLabelSize, "标签字号应为 13sp（原 12，与正文分不出层级）")
        assertEquals(15.sp, editFieldTextSize, "输入框文字应为 15sp（原 14.5）")
        assertEquals(13.sp, editChipTextSize, "chip 字号应为 13sp（原 12）")
    }

    @Test
    fun `标签与正文之间拉开层级`() {
        // 减密度的核心是"标签要明确小一号、但别小到看不清"，
        // 而不是简单地把所有字号一起放大。
        assertTrue(
            editFieldLabelSize < editFieldTextSize,
            "标签应小于正文，否则表单层级糊成一片",
        )
        assertTrue(
            editFieldTextSize.value - editFieldLabelSize.value >= 1.5f,
            "标签与正文字号差小于 1.5sp 时层级不明显",
        )
    }

    @Test
    fun `chip 字号不小于标签字号`() {
        // chip 是可点元素，不能比只读标签还小
        assertTrue(
            editChipTextSize >= editFieldLabelSize,
            "chip 是点选目标，字号不应小于标签",
        )
    }
}
