package com.example.scoreapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.csvfix.FixProposal
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.theme.Tokens

/**
 * forScore 标签校对页。
 *
 * 为什么是整页而不是弹层：这张表列多（标题/作曲家/类型/编配/标签/来源/调性）、
 * 行可能几百条，弹层的高度和宽度都撑不开。整页还能让顶栏常驻，
 * 让「有多少条改动、采纳了几条」随时可见。
 *
 * 页面结构刻意保持三段：
 * 1. 顶栏（返回 + 标题 + 来源标签）
 * 2. 概览条（总数 / 有改动 / 已采纳 + 全选全不选）
 * 3. 逐行表格（勾选框 + 原值 → 新值 + 覆盖徽标）
 *
 * 底部动作区按来源分叉：CSV 来源的出口是「导出文件」，
 * 曲库来源的出口是「写回曲库」—— 见 [FixSession.exportCsv] 的注释。
 */
@Composable
fun FixScreen(state: ScoreAppState) {
    val session = state.fixSession

    // 会话被关掉（例如导出后返回）时不要崩，直接什么都不画。
    // 正常情况下 navStack 会同步弹栈，这里只是兜底。
    if (session == null) {
        Box(Modifier.fillMaxSize().background(Tokens.BgPage))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.BgPage)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        FixTopBar(session, onBack = { state.closeFix() })
        HairlineDivider()
        FixSummaryBar(session, state)
        HairlineDivider()

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(session.entries.size) { index ->
                val e = session.entries[index]
                FixRowCard(
                    entry = e,
                    isOverwrite = session.isOverwrite(e),
                    onToggle = { session.toggleRow(index) },
                )
                HairlineDivider()
            }
        }

        FixFooter(session, state)
    }
}

// ---------------------------------------------------------------- 顶栏

@Composable
private fun FixTopBar(session: FixSession, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.ArrowBack,
                contentDescription = "返回",
                tint = Tokens.Text1,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(Modifier.weight(1f)) {
            Text("标签校对", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Tokens.Text1)
            Text(session.source.label, fontSize = 12.sp, color = Tokens.Text3)
        }

        // 来源徽标：CSV 与曲库两条路的出口不同，先用颜色区分开，
        // 免得用户导出之后才发现打开的是另一个来源。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Tokens.RadiusChip))
                .background(Tokens.Surface3)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                if (session.source == FixSource.Csv) "改完导出，导回 iPad" else "改完写回本机曲库",
                fontSize = 11.sp,
                color = Tokens.Text2,
            )
        }
    }
}

// ---------------------------------------------------------------- 概览条

@Composable
private fun FixSummaryBar(session: FixSession, state: ScoreAppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "共 ${session.total} 条，${session.changedCount} 条有建议",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text1,
            )
            Text(
                buildString {
                    append("已采纳 ${session.takenCount} 条")
                    if (session.overwriteCount > 0) append("　覆盖 ${session.overwriteCount} 条需逐条确认")
                },
                fontSize = 12.sp,
                color = if (session.overwriteCount > 0) Tokens.TabInactive else Tokens.Text3,
            )
        }

        FixTextButton(
            label = if (session.takenCount == session.changedCount && session.changedCount > 0) "全不选" else "全选",
            enabled = session.changedCount > 0,
        ) {
            val allTaken = session.changedCount > 0 && session.takenCount == session.changedCount
            session.setAllTaken(!allTaken)
        }

        FixTextButton(label = "AI 补全") { state.openFixAi() }
    }
}

/** 概览条上的小文字按钮 */
@Composable
private fun FixTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(Tokens.RadiusChip))
            .background(if (enabled) Tokens.Surface3 else Tokens.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Tokens.Text1 else Tokens.Text3,
        )
    }
}

// ---------------------------------------------------------------- 逐行

/**
 * 一行建议。
 *
 * 视觉上分两块：左边勾选框，右边乐谱名与逐字段的「原值 → 新值」。
 * 没有改动的行整体降饱和（文字用 Text3），扫一眼就能跳过。
 */
@Composable
private fun FixRowCard(entry: FixEntry, isOverwrite: Boolean, onToggle: () -> Unit) {
    val p = entry.proposal
    val changed = entry.hasChange

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = changed, onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FixCheckBox(checked = changed && entry.taken, enabled = changed)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // fileName 行。两个子项都显式给约束：Text 用 weight(1f)（fill 默认 true，
            // 永远拿得到确定宽度、超长自动折行），徽标固定在右侧。
            // 之前用过 weight(1f, fill = false) 的写法，真机上有把行挤成竖条的风险，
            // 全部换成确定性约束 —— 校对页的每一行内容都可能很长，不容任何歧义布局。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.row.fileName.ifBlank { entry.row.title.ifBlank { "（无文件名）" } },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (changed) Tokens.Text1 else Tokens.Text3,
                    modifier = Modifier.weight(1f),
                )
                if (isOverwrite && changed) {
                    Box(Modifier.padding(start = 6.dp)) { OverwriteBadge() }
                }
            }

            if (!changed) {
                Text("无需改动", fontSize = 12.sp, color = Tokens.Text3)
            } else {
                FixFieldDiff("标题", p.title, p.overwrite["title"], entry.row.title)
                FixFieldDiff("作曲家", p.composer, p.overwrite["comp"], entry.row.composer)
                FixFieldDiff("类型", p.genre, p.overwrite["genre"], entry.row.genre)
                FixFieldDiff("编配", p.tag, p.overwrite["tag"], entry.row.tag)
                FixFieldDiff("标签", p.label, p.overwrite["label"], entry.row.label)
                FixFieldDiff("来源", p.reference, p.overwrite["ref"], entry.row.reference)
                FixKeyDiff(p, entry.row)
            }

            // 规则给的提示（残缺值、机构名等）：只说明、不修改
            p.notes.forEach { note ->
                Text("· $note", fontSize = 11.sp, color = Tokens.Text3)
            }
            // AI 给了调名但认不出来：如实告诉用户已忽略，别装作没看见
            p.keyRejected?.let { raw ->
                Text("· 调性「$raw」认不出来，已忽略", fontSize = 11.sp, color = Tokens.TabInactive)
            }
        }
    }
}

/** 覆盖徽标：这一条会改掉用户已经填过的值 */
@Composable
private fun OverwriteBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tokens.DangerBg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text("覆盖", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Tokens.DangerFg)
    }
}

/**
 * 一个字段的差异展示：**上下两行**，原值在上（灰）、新值在下（黑粗体）。
 *
 * 为什么不画成同一行的「原值 → 新值」：第一版就是同行横排，
 * 真机（Jackson 的 549 行真实 CSV）上直接炸了 —— label、原值、新值三个
 * Text 并排且都没有宽度约束，长标题（Bella siccome un angelo 这种 40+ 字符）
 * 把整行挤成几条一字一行的竖条。教训：**会换行的长文本永远不要和别的
 * Text 同行裸排**，要么上下分行、要么给 weight。两行式还有个附带好处：
 * 原值再长也只在自己那行折行，新值始终贴着 label 下方一眼可见。
 *
 * [next] 为 null 说明这条规则没碰这个字段，不画。
 */
@Composable
private fun FixFieldDiff(label: String, next: String?, overwritten: String?, current: String) {
    if (next == null) return
    val before = overwritten ?: current
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 11.sp,
            color = Tokens.Text3,
            modifier = Modifier
                .width(40.dp)
                .padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            if (before.isNotBlank()) {
                Text(before, fontSize = 12.sp, color = Tokens.Text3)
            }
            Text(
                text = next.ifBlank { "（清空）" },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (next.isBlank()) Tokens.Text3 else Tokens.Text1,
            )
        }
    }
}

/**
 * 调性那一行单独画。
 *
 * 调性是两列编码（keysf + keymi）合成的一个概念，不能按普通字段展示成
 * 「keysf=3」，用户看不懂。这里翻成「A 大调 / f# 小调」这种人话。
 * 布局同样是上下两行 —— 理由见 [FixFieldDiff]。
 */
@Composable
private fun FixKeyDiff(p: FixProposal, row: com.example.scoreapp.domain.csvfix.FixRow) {
    val sf = p.keysf ?: return
    val mi = p.keymi ?: 0
    val label = com.example.scoreapp.domain.csvfix.ScoreKey.label(sf, mi)
    val before = com.example.scoreapp.domain.csvfix.ScoreKey.label(row.keysf, row.keymi)

    Row(Modifier.fillMaxWidth()) {
        Text(
            "调性",
            fontSize = 11.sp,
            color = Tokens.Text3,
            modifier = Modifier
                .width(40.dp)
                .padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            if (row.keysf != null) {
                Text(before, fontSize = 12.sp, color = Tokens.Text3)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tokens.Text1)
                if (p.keyOverride) {
                    Box(Modifier.padding(start = 6.dp)) { OverwriteBadge() }
                }
            }
        }
    }
}

/** 自绘勾选框，与工程其它地方保持一致的方块 + 对勾观感 */
@Composable
private fun FixCheckBox(checked: Boolean, enabled: Boolean) {
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    checked -> Tokens.Accent
                    enabled -> Tokens.Surface3
                    else -> Tokens.Surface2
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tokens.AccentFg)
        }
    }
}

// ---------------------------------------------------------------- 底部动作区

@Composable
private fun FixFooter(session: FixSession, state: ScoreAppState) {
    Column(Modifier.fillMaxWidth()) {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "将写入 ${session.takenCount} 条改动",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (session.takenCount > 0) Tokens.Text1 else Tokens.Text3,
                )
                Text(
                    if (session.source == FixSource.Csv) "文件名列不会被改动" else "只改规则覆盖到的字段",
                    fontSize = 11.sp,
                    color = Tokens.Text3,
                )
            }

            val enabled = session.takenCount > 0
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (enabled) Tokens.Accent else Tokens.Surface3)
                    .clickable(enabled = enabled) { state.commitFix() }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (session.source == FixSource.Csv) "导出 CSV" else "写回曲库",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) Tokens.AccentFg else Tokens.Text3,
                )
            }
        }
    }
}
