package com.example.scoreapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌。
 *
 * 整体是「浅色纸面」风格：页面底色比卡片略深，靠一层极淡的阴影拉开层次，
 * 而不是用边框。强调色直接取正文墨色，让交互元素在纸上显得克制。
 */
object Tokens {

    // 背景与容器
    val BgPage = Color(0xFFF4F4F6)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF1F1F4)
    val Surface3 = Color(0xFFE9E9EC)

    // 文字
    val Text1 = Color(0xFF111113)
    val Text2 = Color(0xFF6C6C73)
    val Text3 = Color(0xFFA5A5AD)
    val Line = Color(0xFFECECEF)

    // 强调
    val Accent = Color(0xFF111113)
    val AccentFg = Color(0xFFFFFFFF)
    val TabInactive = Color(0xFF9D9DA5)

    // 标签
    val TagTypeFg = Color(0xFF1D5C8A)
    val TagTypeBg = Color(0xFFE6F1F9)
    val TagInstFg = Color(0xFF5B3D8A)
    val TagInstBg = Color(0xFFEFE9FA)
    val TagAiFg = Color(0xFF0A72C4)
    val TagAiBg = Color(0xFFE7F2FC)

    // 状态
    val PillLive = Color(0xFF0D7A4A)
    val LinkBlue = Color(0xFF0A72C4)
    val DangerBg = Color(0xFFFDECEC)
    val DangerFg = Color(0xFFD23A3A)

    // 圆角
    val RadiusCard = 18.dp
    val RadiusThumb = 8.dp
    val RadiusChip = 11.dp

    // 纸面
    val Paper = Color(0xFFFBF9F4)
    val Ink = Color(0xFF181614)
}
