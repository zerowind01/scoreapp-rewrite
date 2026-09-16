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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 */
@Composable
fun ScoreCard(
    score: Score,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
                CardBody(score, onEdit, showMore = false, modifier = Modifier.fillMaxWidth())
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
                CardBody(score, onEdit, showMore = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CardBody(
    score: Score,
    onEdit: () -> Unit,
    showMore: Boolean,
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
            )
            if (showMore) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.MoreVert,
                        contentDescription = "编辑",
                        tint = Tokens.Text2,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}
