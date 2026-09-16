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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.LibraryTab
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.EmptyState
import com.example.scoreapp.ui.components.IconBtn
import com.example.scoreapp.ui.components.ScoreCard
import com.example.scoreapp.ui.components.ScoreSetCard
import com.example.scoreapp.ui.theme.Tokens

/**
 * 「乐谱库」页：应用的主界面。
 *
 * 结构自上而下为：标题页签 → 搜索框（可折叠）→ 分组切换 → 生效筛选摘要 → 分组内容。
 * 列表与网格共用同一份分组数据，只切换排布方式。
 */
@Composable
fun ManageScreen(
    state: ScoreAppState,
    bottomPadding: Dp,
) {
    Column(Modifier.fillMaxSize()) {
        LibraryTopBar(state)

        if (state.searchOpen) {
            SearchField(
                value = state.query,
                placeholder = "搜索曲名、作曲家、类型、乐器…",
                onValueChange = state::updateQuery,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when (state.libraryTab) {
            LibraryTab.Scores -> ScoresTab(state, bottomPadding)
            LibraryTab.Sets -> SetsTab(state, bottomPadding)
        }
    }
}

// ---------------------------------------------------------------- 顶部栏

@Composable
private fun LibraryTopBar(state: ScoreAppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LibraryTab.entries.forEach { tab ->
                Text(
                    text = tab.label,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                    color = if (state.libraryTab == tab) Tokens.Text1 else Tokens.TabInactive,
                    modifier = Modifier.clickable { state.libraryTab = tab },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn(
                icon = if (state.searchOpen) AppIcons.Close else AppIcons.Search,
                contentDescription = "搜索",
                active = state.searchOpen,
                onClick = { state.toggleSearch() },
            )
            IconBtn(
                icon = AppIcons.Filter,
                contentDescription = "筛选与分组",
                active = !state.filters.isEmpty,
                onClick = { state.openFilterSheet() },
            )
            IconBtn(
                icon = if (state.grid) AppIcons.ListView else AppIcons.Grid,
                contentDescription = "切换视图",
                active = state.grid,
                onClick = { state.toggleGrid() },
            )
            IconBtn(
                icon = AppIcons.Sort,
                contentDescription = "排序",
                onClick = { state.openSort() },
            )
        }
    }
}

@Composable
internal fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Surface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = AppIcons.Search,
            contentDescription = null,
            tint = Tokens.Text3,
            modifier = Modifier.size(17.dp),
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 15.sp, color = Tokens.Text3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = Tokens.Text1),
                cursorBrush = SolidColor(Tokens.Text1),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Tokens.Surface3)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = "清除",
                    tint = Tokens.Text2,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 乐谱库子页

@Composable
private fun ScoresTab(state: ScoreAppState, bottomPadding: Dp) {
    val scores = state.visibleScores

    Column(Modifier.fillMaxSize()) {
        GroupChipRow(state)
        if (!state.filters.isEmpty) ActiveFilterRow(state)
        Spacer(Modifier.height(4.dp))

        if (scores.isEmpty()) {
            EmptyState(
                icon = AppIcons.Book,
                title = "没有匹配的乐谱",
                message = "换个关键词或首字母试试",
            )
            return@Column
        }

        val groups = LibraryQuery.group(scores, state.groupBy)

        if (state.grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                groups.forEach { group ->
                    item(key = "head-${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                        GroupHead(group.label, group.items.size)
                    }
                    items(group.items, key = { it.id }) { score ->
                        ScoreCard(
                            score = score,
                            onOpen = { state.openDetail(score) },
                            onEdit = { state.openEditor(score) },
                            compact = true,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                groups.forEach { group ->
                    item(key = "head-${group.key}") { GroupHead(group.label, group.items.size) }
                    items(group.items, key = { it.id }) { score ->
                        ScoreCard(
                            score = score,
                            onOpen = { state.openDetail(score) },
                            onEdit = { state.openEditor(score) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHead(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Tokens.Text1)
        Text("$count 首", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tokens.Text3)
    }
}

@Composable
private fun GroupChipRow(state: ScoreAppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterDim.entries.forEach { dim ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (state.groupBy == dim) Tokens.Accent else Tokens.Surface)
                    .clickable { state.groupBy = dim }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Layers,
                    contentDescription = null,
                    tint = if (state.groupBy == dim) Tokens.AccentFg else Tokens.Text2,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = dim.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.groupBy == dim) Tokens.AccentFg else Tokens.Text2,
                )
            }
        }
    }
}

/** 生效中的筛选条件摘要，可逐条移除 */
@Composable
private fun ActiveFilterRow(state: ScoreAppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FilterDim.entries.forEach { dim ->
            state.filters.valuesOf(dim).forEach { value ->
                ActiveFilterChip(
                    text = if (dim == FilterDim.Composer) ComposerNames.shortName(value) else value,
                    onRemove = { state.toggleFilter(dim, value) },
                )
            }
        }
        ActiveFilterChip(text = "清空", onRemove = { state.clearFilters() })
    }
}

@Composable
private fun ActiveFilterChip(text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Tokens.Surface)
            .padding(start = 11.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tokens.Text1)
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Tokens.Surface3)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Close,
                contentDescription = null,
                tint = Tokens.Text2,
                modifier = Modifier.size(9.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 合架子页

@Composable
private fun SetsTab(state: ScoreAppState, bottomPadding: Dp) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottomPadding + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        items(state.sets) { set ->
            ScoreSetCard(
                set = set,
                members = membersOf(state, set.seeds),
                onOpen = { state.openSet(set) },
            )
        }
    }
}

/** 按缩略图种子解析谱单成员；种子缺失时退化为取前若干首，保证卡片不空 */
internal fun membersOf(state: ScoreAppState, seeds: List<Int>): List<Score> {
    val resolved = seeds.mapNotNull { seed ->
        state.allScores.firstOrNull { it.thumbSeed == seed }
    }
    return resolved.ifEmpty { state.allScores.take(seeds.size.coerceAtLeast(1)) }
}
