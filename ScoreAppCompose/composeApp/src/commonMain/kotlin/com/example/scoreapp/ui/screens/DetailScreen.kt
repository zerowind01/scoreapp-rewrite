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
import com.example.scoreapp.domain.csvfix.ScoreKey
import com.example.scoreapp.domain.formatDate
import com.example.scoreapp.domain.setMembers
import com.example.scoreapp.domain.setsOfScore
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.components.IconBtn
import com.example.scoreapp.ui.components.ScoreThumb
import com.example.scoreapp.ui.components.SectionHead
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
                    icon = AppIcons.MoreHoriz,
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
                    // 预览图上原先叠了一个编辑角标，与「更多 → 编辑元数据」重复，已去掉
                    ScoreThumb(
                        score = score,
                        modifier = Modifier
                            .size(width = 96.dp, height = 124.dp)
                            .clip(RoundedCornerShape(Tokens.RadiusThumb)),
                    )
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
            // 两个主操作并排：读谱是第一动作，分享是次级动作。
            // 原先「分享」独占整宽强调按钮，而打开乐谱被收进「更多」菜单里，主次颠倒了。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    icon = AppIcons.OpenInNew,
                    label = "查看乐谱",
                    primary = true,
                    onClick = { state.openPdf(score) },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    icon = AppIcons.Share,
                    label = "分享",
                    primary = false,
                    onClick = { state.share(score) },
                    modifier = Modifier.weight(1f),
                )
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
                    // 调性是可选字段：没设置的条目**整行不出现**，
                    // 而不是显示一个「—」占位，免得让「没填」看起来像「填了个空值」
                    ScoreKey.label(score.keysf, score.keymi)
                        .takeIf { it.isNotEmpty() }
                        ?.let { KV("调性", it) }
                    KV("乐器", score.instrument)
                    KV("时期 / 风格", score.period)
                    KV("难度", score.level)
                    KV("来源", score.source)
                    KV("页数", if (score.pages > 0) "${score.pages} 页" else "—")
                    KV("添加时间", formatDate(score.dateAdded))
                }
            }
        }

        item { SectionHead("分类归属") }
        item {
            // 真实归属：这份乐谱属于哪些谱单。原先这里渲染的是作曲家/曲目类型/乐器，
            // 与「元数据」面板前 3 行完全重复，没有回答「归属」这个问题。
            val sets = setsOfScore(score, state.sets, state.allScores)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(Tokens.RadiusCard),
                color = Tokens.Surface,
                shadowElevation = 1.dp,
            ) {
                if (sets.isEmpty()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        KV("谱单", "尚未加入任何谱单")
                    }
                } else {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        sets.forEach { set ->
                            SetRow(
                                name = set.name,
                                count = setMembers(set, state.allScores).size,
                                onClick = { state.openSet(set) },
                            )
                        }
                    }
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

/**
 * 详情页主操作按钮：`primary` 为强调色实心，否则为次级灰底。
 * 高度 50dp、圆角 15dp、图标 19dp 与原型 `.actbtn` 一致。
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (primary) Tokens.Accent else Tokens.Surface3,
        contentColor = if (primary) Tokens.AccentFg else Tokens.Text1,
    ) {
        Row(
            modifier = Modifier.height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 「分类归属」里的一行谱单。整行可点，点击后打开该谱单的详情。
 * 复用与「更多」弹层一致的视觉语言（图标 + 标签 + 数值 + 箭头）。
 */
@Composable
private fun SetRow(name: String, count: Int, onClick: () -> Unit) {
    Column {
        HairlineDivider(Modifier.padding(start = 46.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Layers, contentDescription = null, tint = Tokens.Text2, modifier = Modifier.size(17.dp))
            }
            Text(
                text = name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("$count 首", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Tokens.Text2)
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = Tokens.Text3,
                modifier = Modifier.size(16.dp),
            )
        }
    }
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

/** 时间戳格式化已下沉到 domain（`formatDate`），此处仅保留引用以免破坏既有调用点。 */

