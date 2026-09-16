package com.example.scoreapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.components.IconBtn
import com.example.scoreapp.ui.components.ScoreThumb
import com.example.scoreapp.ui.theme.Tokens

/**
 * 乐谱详情页：封面 + 完整元数据 + 操作入口。
 */
@Composable
fun DetailScreen(
    state: ScoreAppState,
    score: Score,
    bottomPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
    ) {
        item {
            // 顶栏：返回 / 更多（原应用此处不重复标题，标题只在封面区出现一次）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconBtn(
                    icon = AppIcons.ArrowBack,
                    contentDescription = "返回",
                    onClick = { state.closeDetail() },
                )
                IconBtn(
                    icon = AppIcons.MoreVert,
                    contentDescription = "更多",
                    onClick = { state.openMore(score) },
                )
            }
        }

        item {
            // 封面区
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(Tokens.RadiusCard),
                color = Tokens.Surface,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box {
                        ScoreThumb(
                            score = score,
                            modifier = Modifier
                                .size(width = 96.dp, height = 124.dp)
                                .clip(RoundedCornerShape(Tokens.RadiusThumb)),
                        )
                        // 编辑入口贴在封面右上角，不占用正文空间
                        IconBtn(
                            icon = AppIcons.Edit,
                            contentDescription = "编辑",
                            onClick = { state.openEditor(score) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                            size = 28,
                            iconSize = 15,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = score.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp,
                            color = Tokens.Text1,
                        )
                        Text(score.composer, fontSize = 12.5.sp, color = Tokens.Text2)
                        Text(
                            text = metaLine(score),
                            fontSize = 12.5.sp,
                            color = Tokens.Text3,
                            lineHeight = 21.sp,
                        )
                    }
                }
            }
        }

        item {
            // 强调色分享按钮（原应用把分享做成主操作，而不是塞进「更多」菜单）
            Surface(
                onClick = { state.share(score) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                shape = RoundedCornerShape(15.dp),
                color = Tokens.Accent,
                contentColor = Tokens.AccentFg,
            ) {
                Row(
                    modifier = Modifier.height(50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("分享", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item { SectionHead("元数据") }
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(Tokens.RadiusCard),
                color = Tokens.Surface,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    KV("作曲家", score.composer)
                    KV("曲目类型", score.type)
                    KV("乐器", score.instrument)
                    KV("时期 / 风格", score.period)
                    KV("难度", score.level)
                    KV("来源", score.source)
                    KV("页数", if (score.pages > 0) "${score.pages} 页" else "—")
                }
            }
        }

        item { SectionHead("分类归属") }
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(Tokens.RadiusCard),
                color = Tokens.Surface,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    KV("作曲家", score.composer)
                    KV("曲目类型", score.type)
                    KV("乐器", score.instrument)
                }
            }
        }

    }
}

/**
 * 详情页头部的元信息行：`曲目类型 · 乐器 · 时期 / 风格 · 难度`。
 *
 * 空白字段会被剔除（原应用对 `listOf(...)` 做 `isBlank` 过滤后再
 * `joinToString(" · ")`），因此缺字段时不会留下悬空的分隔符。
 */
internal fun metaLine(score: Score): String =
    listOf(score.type, score.instrument, score.period, score.level)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

@Composable
private fun SectionHead(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Tokens.Text2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun KV(key: String, value: String) {
    Column {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(key, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tokens.Text2)
            Text(
                text = value,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** 面板内的一行动作项。「更多」弹层复用同一个实现，保持视觉一致。 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val color = if (danger) Tokens.DangerFg else Tokens.Text1
    Column {
        HairlineDivider(Modifier.padding(start = 50.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
            }
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.weight(1f),
            )
            if (!danger) {
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = Tokens.Text3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 时间戳格式化为 `yyyy-MM-dd HH:mm`。commonMain 无法用 java.time，故手工拼装 */
internal fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    val totalSeconds = timestamp / 1000
    val days = totalSeconds / 86400
    val secondsOfDay = totalSeconds % 86400
    val (year, month, day) = civilFromDays(days)
    val hour = secondsOfDay / 3600
    val minute = (secondsOfDay % 3600) / 60
    return buildString {
        append(year.toString().padStart(4, '0')); append('-')
        append(month.toString().padStart(2, '0')); append('-')
        append(day.toString().padStart(2, '0')); append(' ')
        append(hour.toString().padStart(2, '0')); append(':')
        append(minute.toString().padStart(2, '0'))
    }
}

/**
 * 由「1970-01-01 起的天数」反解公历年月日（Howard Hinnant 的 civil_from_days 算法）。
 * 纯整数运算，不依赖任何平台日期库。
 */
private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}
