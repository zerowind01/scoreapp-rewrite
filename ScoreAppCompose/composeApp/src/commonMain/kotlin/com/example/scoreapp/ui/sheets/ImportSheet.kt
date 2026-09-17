package com.example.scoreapp.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.SheetGhostButton
import com.example.scoreapp.ui.components.SheetScaffold
import com.example.scoreapp.ui.theme.Tokens

/**
 * 「导入乐谱」弹层。
 *
 * 两条路径：
 *  1. 相册图片 → 按 A4 逐页排版合成一份 PDF（图片版乐谱的常见来源）；
 *  2. 直接选择已有的 PDF 文件。
 */
@Composable
fun ImportSheet(state: ScoreAppState, onDismiss: () -> Unit) {
    SheetScaffold(
        title = "导入乐谱",
        onDismiss = onDismiss,
        footer = {
            SheetGhostButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(4.dp))
        ImportRow(
            icon = AppIcons.Photo,
            title = "从相册选择图片",
            subtitle = "多选图片，自动合并为一份 PDF 乐谱",
            onClick = {
                // 真实落库：按页数生成一份新乐谱并写入曲库
                state.importScore(fromAlbum = true, pageCount = 2)
                onDismiss()
            },
        )
        ImportRow(
            icon = AppIcons.FileDoc,
            title = "选择 PDF 文件",
            subtitle = "直接导入已有 PDF 乐谱",
            onClick = {
                state.importScore(fromAlbum = false)
                onDismiss()
            },
        )
        Text(
            text = "图片导入会按 A4（595 × 842 pt）逐页居中排版，最长边压到 1400 px 后写入 " +
                "files/scores/import_<标题>_<时间戳>.pdf，并在数据库中登记页数。",
            fontSize = 11.5.sp,
            color = Tokens.Text3,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ImportRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 11.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Tokens.Surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Tokens.Surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Tokens.Text1, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Tokens.Text1)
                Text(subtitle, fontSize = 12.sp, color = Tokens.Text3, lineHeight = 17.sp)
            }
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = Tokens.Text3,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
