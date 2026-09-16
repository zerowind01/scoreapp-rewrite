package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.SortMode
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.ChoiceChip
import com.example.scoreapp.ui.components.FacetChip
import com.example.scoreapp.ui.components.HairlineDivider
import com.example.scoreapp.ui.components.SectionTitle
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetPrimaryButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.theme.Tokens

/**
 * 「筛选与分组」弹层。
 *
 * 交互要点：所有改动先写入 [ScoreAppState.pending] 暂存态，
 * 只有点「查看 N 个结果」才提交。因此每个分面标签上显示的是
 * 「在当前暂存条件下的命中数」，用户可以先试再定。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    SheetScaffold(
        title = "筛选与分组",
        onDismiss = onDismiss,
        footer = {
            SheetGhostButton(
                label = "重置",
                onClick = { state.resetPending() },
                modifier = Modifier.width(92.dp),
            )
            SheetPrimaryButton(
                label = "查看 ${state.pendingResultCount} 个结果",
                onClick = { state.applyPending() },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        FilterDim.entries.forEachIndexed { index, dim ->
            if (index > 0) {
                Spacer(Modifier.height(14.dp))
                HairlineDivider()
            }
            SectionTitle(
                title = dim.label,
                actionLabel = if (state.pending.valuesOf(dim).isEmpty()) null else "清空",
                onAction = { state.clearPending(dim) },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 只列出确实出现过的取值，避免用户选到空结果
                val values = LibraryQuery.facetValues(state.allScores, dim)

                values.forEach { value ->
                    val selected = value in state.pending.valuesOf(dim)
                    val count = state.facetCount(dim, value)
                    FacetChip(
                        label = if (dim == FilterDim.Composer) ComposerNames.shortName(value) else value,
                        selected = selected,
                        count = count,
                        enabled = selected || count > 0,
                        onClick = { state.togglePending(dim, value) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HairlineDivider()
        SectionTitle(title = "分组方式", subtitle = "决定列表如何归类")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterDim.entries.forEach { dim ->
                ChoiceChip(
                    label = dim.label,
                    selected = state.pendingGroup == dim,
                    onClick = { state.selectPendingGroup(dim) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 「排序方式」弹层 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    SheetScaffold(
        title = "排序方式",
        onDismiss = onDismiss,
        footer = {
            SheetPrimaryButton(
                label = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortMode.entries.forEach { mode ->
                ChoiceChip(
                    label = mode.label,
                    selected = state.sort == mode,
                    onClick = { state.selectSort(mode) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
