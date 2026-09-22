package com.example.scoreapp.ui.components

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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.theme.Tokens

/**
 * 乐谱卡片。
 *
 * @param compact 网格视图下改为纵向排布，缩略图占满整行宽度
 * @param onView 直接打开 PDF；[onShare] 分享；[onEdit] 编辑元数据
 */
@Composable
fun ScoreCard(
    score: Score,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onView: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /**
     * 右下角那枚状态标签（网盘条目用它写「已缓存 / 需下载」）。
     * 本机条目没有这一步，传空串即不显示。
     */
    badge: String = "",
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.RadiusCard))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(Tokens.RadiusCard),
        color = Tokens.Surface,
        shadowElevation = 1.dp,
    ) {
        if (compact) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ScoreThumb(
                    score = score,
                    dense = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(118.dp)
                        .clip(RoundedCornerShape(Tokens.RadiusThumb)),
                )
                CardBody(score, onView, onShare, onEdit, badge, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.padding(11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScoreThumb(
                    score = score,
                    modifier = Modifier
                        .size(width = 74.dp, height = 96.dp)
                        .clip(RoundedCornerShape(Tokens.RadiusThumb)),
                )
                // 横向排布时用 weight 吃掉剩余宽度；若写 fillMaxWidth 会按整行宽度测量而溢出
                CardBody(score, onView, onShare, onEdit, badge, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CardBody(
    score: Score,
    onView: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    badge: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = score.title,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text1,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = score.composer,
            fontSize = 12.sp,
            color = Tokens.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MetaTag(score.type, Tokens.TagTypeFg, Tokens.TagTypeBg)
            MetaTag(score.instrument, Tokens.TagInstFg, Tokens.TagInstBg)
            if (score.isAi) MetaTag("AI 识别", Tokens.TagAiFg, Tokens.TagAiBg)
            if (badge.isNotBlank()) MetaTag(badge, Tokens.LinkBlue, Tokens.TagAiBg)
        }

        Spacer(Modifier.height(1.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${score.pages} 页 · ${score.period} · ${score.level}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            CardActions(onView, onShare, onEdit)
        }
    }
}

/**
 * 列表项右下角的三个快捷入口：查看 / 分享 / 编辑。
 *
 * 原先这里只有一个三点，且只做「编辑」一件事；查看与分享必须先点进详情页。
 * 三点（[AppIcons.MoreHoriz]）语义上是「还有更多」，只挂一个动作是误导。
 */
@Composable
private fun CardActions(onView: () -> Unit, onShare: () -> Unit, onEdit: () -> Unit) {
    // 间距 4dp 而非 2dp：命中区放大到 34dp 后，2dp 太近，手指容易误触相邻按钮
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CardAction(AppIcons.OpenInNew, "查看乐谱", onView)
        CardAction(AppIcons.Share, "分享", onShare)
        CardAction(AppIcons.Edit, "编辑", onEdit)
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            // 34dp 命中区 + 18dp 图标（原为 25/14）。
            // 真机反馈「快捷入口太小」，这两个值一起放大才有效果：
            // 只放大图标会显得挤，只放大命中区则视觉上毫无变化。
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            // 颜色由 Text3 提到 Text2：放大后仍用最浅的一档会显得发灰、像不可用
            tint = Tokens.Text2,
            modifier = Modifier.size(18.dp),
        )
    }
}
