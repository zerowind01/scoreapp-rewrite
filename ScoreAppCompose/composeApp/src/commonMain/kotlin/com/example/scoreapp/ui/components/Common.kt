package com.example.scoreapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.ui.theme.Tokens

/** 圆形图标按钮。选中态反色，用于工具栏与详情页操作 */
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Int = 38,
    iconSize: Int = 19,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (active) Tokens.Accent else Tokens.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Tokens.AccentFg else Tokens.Text1,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** 元数据小标签 */
@Composable
fun MetaTag(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 分节标题，可带副标题说明与右侧动作 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 15.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Tokens.Text1)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.5.sp, fontWeight = FontWeight.Normal, color = Tokens.Text3)
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.LinkBlue,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Tokens.Line),
    )
}

/**
 * 分面筛选标签。[count] 为当前约束下的命中数，
 * 命中为 0 且未选中时整体降低不透明度，提示该选项不可达。
 */
@Composable
fun FacetChip(
    label: String,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val fg = if (selected) Tokens.AccentFg else Tokens.Text2
    val bg = if (selected) Tokens.Accent else Tokens.Surface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.RadiusChip))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) fg else fg.copy(alpha = 0.4f),
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.22f) else Tokens.Surface3)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = count.toString(),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else Tokens.Text3,
                )
            }
        }
    }
}

/** 分组方式等纯文字选择项 */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.RadiusChip))
            .background(if (selected) Tokens.Accent else Tokens.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Tokens.AccentFg else Tokens.Text2,
        )
    }
}

/** 空状态占位 */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Tokens.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Tokens.Text3, modifier = Modifier.size(24.dp))
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tokens.Text1)
        Text(message, fontSize = 12.5.sp, color = Tokens.Text3)
    }
}
