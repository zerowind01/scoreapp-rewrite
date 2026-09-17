package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.screens.ActionRow
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * 详情页右上角「更多」弹出的次级动作清单。
 *
 * 原应用详情页的常驻操作只有「分享」（强调色主按钮）和封面上的「编辑」，
 * 打开乐谱与删除收在这一层，避免详情页被四五个并列入口塞满。
 */
@Composable
fun MoreSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    val score = state.moreTarget ?: return

    // 确认态若一直挂着，用户下次进来会看到一个陌生的按钮；3 秒无操作自动复原
    LaunchedEffect(state.deleteArmed) {
        if (state.deleteArmed) {
            delay(3000)
            state.disarmDelete()
        }
    }

    SheetScaffold(
        title = "更多",
        onDismiss = onDismiss,
        scrollable = false,
        footer = {
            SheetGhostButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            shape = RoundedCornerShape(Tokens.RadiusCard),
            color = Tokens.Surface,
            shadowElevation = 1.dp,
        ) {
            Column {
                ActionRow(AppIcons.OpenInNew, "打开乐谱", false) {
                    onDismiss()
                    state.openPdf(score)
                }
                ActionRow(AppIcons.Edit, "编辑元数据", false) {
                    onDismiss()
                    state.openEditor(score)
                }
                ActionRow(
                    icon = AppIcons.Delete,
                    // 两段式确认：首点切文案，再点才执行，避免与上方两项贴着误触
                    label = if (state.deleteArmed) "确认删除，不可撤销" else "从乐谱库删除",
                    danger = true,
                ) {
                    if (state.requestDelete(score)) onDismiss()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
