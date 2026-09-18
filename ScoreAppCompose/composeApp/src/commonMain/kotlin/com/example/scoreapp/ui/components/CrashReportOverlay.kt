package com.example.scoreapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.CrashLog
import com.example.scoreapp.ui.theme.Tokens

/**
 * 崩溃恢复浮层。
 *
 * 与 MainActivity 的接线顺序对译原应用：启动时读日志 → 立刻清除 → 非空才弹，
 * 所以一次崩溃只提示一次，重复冷启动不会反复打扰。
 *
 * 版式对译原型 `.crash`：卡片标题（danger 色）+ 落盘路径说明 +
 * 等宽字体日志正文（内部滚动、限高）+ 「复制日志 / 关闭」并排。
 * 堆栈只有等宽 + 不折行才可读，正文用 `white-space:pre` 的等价物逐行展示。
 */
@Composable
fun CrashReportOverlay(
    log: String,
    storageHint: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // 日志只在变化时解析一次；remember 的 key 是日志本身
    val parsed = androidx.compose.runtime.remember(log) { CrashLog.parse(log) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.Surface)
            .padding(14.dp),
    ) {
        Text(
            text = "上次运行发生异常",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Tokens.DangerFg,
        )
        Text(
            text = "日志已保存到：$storageHint",
            fontSize = 11.5.sp,
            color = Tokens.Text3,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 5.dp, bottom = 11.dp),
        )
        Text(
            text = parsed.raw,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
            ),
            color = Tokens.Text2,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Tokens.Surface2)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetGhostButton(
                label = "复制日志",
                onClick = {
                    clipboard.setText(AnnotatedString(parsed.raw))
                },
                modifier = Modifier.weight(1f),
            )
            SheetDangerButton(
                label = "关闭",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
