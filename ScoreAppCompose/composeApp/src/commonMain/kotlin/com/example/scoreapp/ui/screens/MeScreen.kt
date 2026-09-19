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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.AvatarPalette
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.domain.formatBytes
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.ui.PickKind
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.components.SectionHead
import com.example.scoreapp.ui.theme.Tokens

/**
 * 「我的」页：曲库概览与设置入口。
 *
 * 统计数字全部由当前曲库实时推导，不做缓存，避免出现
 * 「删了乐谱但计数没变」的不一致。
 */
@Composable
fun MeScreen(state: ScoreAppState, bottomPadding: Dp) {
    val scoreCount = state.allScores.size
    val setCount = state.sets.size
    val composerCount = LibraryQuery.distinctCount(state.allScores, FilterDim.Composer)
    val typeCount = LibraryQuery.distinctCount(state.allScores, FilterDim.Type)
    val instrumentCount = LibraryQuery.distinctCount(state.allScores, FilterDim.Instrument)

    // 进页现算一次存储占用与缓存量；从别的页签切回来也会重算，
    // 保证「导入了一份乐谱再回我的页」数字立刻跟上。
    // 原应用两行写死「128 MB / 24 MB」，属于它自己的瑕疵（第 4 处）。
    LaunchedEffect(Unit) { state.refreshStorage() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 20.dp),
    ) {
        item {
            // 顶部标题
            Text(
                text = "我的",
                fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                color = Tokens.Text1,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            )
        }

        item {
            // 个人资料
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AvatarPalette.colorFor("乐谱收藏家")),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("谱", color = Tokens.AccentFg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(
                        text = "乐谱收藏家",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        color = Tokens.Text1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "已连续练习 12 天 · 累计 46 小时",
                        fontSize = 12.5.sp,
                        color = Tokens.Text3,
                    )
                }
            }
        }

        item {
            // 三张统计卡
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                StatCard(scoreCount.toString(), "乐谱", Modifier.weight(1f))
                StatCard(setCount.toString(), "谱单", Modifier.weight(1f))
                StatCard(composerCount.toString(), "作曲家", Modifier.weight(1f))
            }
        }

        item { SectionHead("元数据") }
        item {
            // 这三行原先挂在 `onClick = {}` 上——可点、有涟漪，但什么都不发生。
            // 既然展示的是各维度的取值统计，点击就跳到乐谱库并按该维度筛选：
            // 「作曲家」进全局作曲家视图，另两行取当前曲库里第一个取值作为切入点。
            ListPanel {
                // 三行都是**纯展示**（Jackson 2026-09-18 拍板）：不再跳转。
                // 原先「乐器」会直接跳到乐谱库并筛掉一个最高频取值（钢琴），
                // 用户看到「N 种乐器」却只看到其中一种，语义对不上。
                ListItem(
                    icon = AppIcons.Person,
                    label = "作曲家",
                    value = "$composerCount 位",
                )
                ListItem(
                    icon = AppIcons.Book,
                    label = "曲目类型",
                    value = "$typeCount 类",
                )
                ListItem(
                    icon = AppIcons.MusicNote,
                    label = "乐器",
                    value = "$instrumentCount 种",
                )
            }
        }

        item { SectionHead("标签工具") }
        item {
            ListPanel {
                ListItem(
                    icon = AppIcons.FileDoc,
                    label = "校对 forScore 标签",
                    // 副标题写清来源与出口，用户不用点进去猜这是干什么的
                    value = "选 CSV 文件",
                    showArrow = true,
                    onClick = { state.beginPick(PickKind.Csv) },
                )
                ListItem(
                    icon = AppIcons.Cleaning,
                    label = "校对本机曲库",
                    value = "${state.allScores.size} 首",
                    showArrow = true,
                    onClick = { state.openFixFromLibrary() },
                )
            }
        }

        item { SectionHead("设置") }
        item {
            ListPanel {
                ListItem(
                    icon = AppIcons.Download,
                    label = "导入与存储",
                    // 真实占用。还没统计到时显示「…」而不是假数；占位符与 fmtBytes 的「—」区分开
                    value = state.storageUsage?.let { "本地 ${formatBytes(it.bytes)}" } ?: "…",
                    showArrow = true,
                    onClick = { state.showStorageInfo() },
                )
                ListItem(
                    icon = AppIcons.Sort,
                    label = "自动识别元数据",
                    value = if (state.aiOn) "已开启" else "已关闭",
                    valueColor = if (state.aiOn) Tokens.PillLive else Tokens.Text3,
                    onClick = { state.toggleAi() },
                )
                ListItem(
                    icon = AppIcons.Cleaning,
                    label = "清理缓存",
                    // 真实缓存量。0 时显示「—」（与 formatBytes 的空值占位同口径），
                    // 点下去要么报真实释放量、要么说缓存为空，不再报写死的 24 MB
                    value = formatBytes(state.cacheBytes),
                    showArrow = true,
                    onClick = {
                        state.clearCache()
                        // 清完立刻重算，标签归零而不是等下次进页
                        state.refreshStorage()
                    },
                )
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Tokens.Surface,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                color = Tokens.Text1,
            )
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Tokens.Text3)
        }
    }
}

@Composable
private fun ListPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(Tokens.RadiusCard),
        color = Tokens.Surface,
        shadowElevation = 1.dp,
    ) {
        Column { content() }
    }
}

/** 「未传回调」的哨兵值：用于区分纯展示行与可点行（引用相等性判断） */
private val defaultOnClick: () -> Unit = {}

@Composable
private fun ListItem(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = Tokens.Text3,
    showArrow: Boolean = false,
    // 默认空：元数据统计等纯展示行不传即可；可点行显式传
    onClick: () -> Unit = defaultOnClick,
) {
    Column {
        HairlineDivider(Modifier.padding(start = 50.dp))
        // 纯展示行（未传 onClick）不挂 clickable：挂着空回调会出现
        // 「有涟漪、点了没反应」的假交互
        val rowModifier = if (onClick !== defaultOnClick) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Tokens.Text1, modifier = Modifier.size(19.dp))
            }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tokens.Text1, modifier = Modifier.weight(1f))
            Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
            if (showArrow) {
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
