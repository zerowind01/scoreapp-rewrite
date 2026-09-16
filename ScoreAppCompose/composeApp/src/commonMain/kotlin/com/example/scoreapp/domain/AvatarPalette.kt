package com.example.scoreapp.domain

import androidx.compose.ui.graphics.Color

/**
 * 头像配色：由姓名稳定推导出一个色相，保证同一个人每次渲染颜色一致。
 * 做法是先在一组预设色相上做哈希取模，再统一到固定的饱和度/明度，
 * 使整屏头像的视觉重量保持均匀。
 */
object AvatarPalette {

    private val HUES = listOf(
        "#b0552f", "#3a6ea5", "#6b4fa0", "#2f7d5b", "#a8412f",
        "#4a5a8a", "#8a6a24", "#7a3f5c", "#3f6b6b", "#5c5c66",
    )

    private const val SATURATION = 0.46f
    private const val LIGHTNESS = 0.46f

    /** Java 风格 31 进制哈希，保持与原实现一致的取值分布 */
    private fun hash(text: String): Int {
        var h = 0
        for (ch in text) h = h * 31 + ch.code
        return h
    }

    fun colorFor(name: String): Color {
        if (name.isEmpty()) return Color.hsl(0f, SATURATION, LIGHTNESS)
        val hex = HUES[Math.floorMod(hash(name), HUES.size)]
        return Color.hsl(hueOf(hex), SATURATION, LIGHTNESS)
    }

    /** 解析 `#rrggbb` 并求 HSL 色相（0..360） */
    private fun hueOf(hex: String): Float {
        val v = hex.removePrefix("#").toInt(16)
        val r = ((v shr 16) and 0xFF) / 255f
        val g = ((v shr 8) and 0xFF) / 255f
        val b = (v and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0f) return 0f
        val hue = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        } * 60f
        return (hue + 360f) % 360f
    }
}
