package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.screens.ActionRow
import com.example.scoreapp.ui.theme.Tokens

/**
 * 详情页右上角「更多」弹出的次级动作清单。
 *
 * 原应用详情页的常驻操作只有「分享」（强调色主按钮）和封面上的「编辑」，
 * 打开乐谱与删除收在这一层，避免详情页被四五个并列入口塞满。
 */
@Composable
fun MoreSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    val score = state.moreTarget ?: return

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
                ActionRow(AppIcons.Delete, "从乐谱库删除", true) {
                    onDismiss()
                    state.delete(score)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
