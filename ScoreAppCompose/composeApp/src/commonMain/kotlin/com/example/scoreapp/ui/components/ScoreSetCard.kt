package com.example.scoreapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet
import com.example.scoreapp.ui.theme.Tokens

/**
 * 谱单卡片。左侧用几张缩略图错位叠放成「一叠乐谱」的意象，
 * 越靠前的卡片越大、越不透明。
 */
@Composable
fun ScoreSetCard(
    set: ScoreSet,
    members: List<Score>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
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
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetStack(members)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(set.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tokens.Text1)
                Text(
                    text = set.desc,
                    fontSize = 12.sp,
                    color = Tokens.Text2,
                    lineHeight = 18.sp,
                )
                Text(
                    text = "${members.size} 首乐谱",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Tokens.Text3,
                    modifier = Modifier.padding(top = 3.dp),
                )
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

/** 叠放的缩略图。最多展示 3 张，依次向右下偏移并缩小 */
@Composable
private fun SetStack(members: List<Score>) {
    Box(Modifier.size(width = 86.dp, height = 104.dp)) {
        members.take(3).forEachIndexed { index, score ->
            val offset = (index * 5).dp
            val scale = 1f - index * 0.045f
            Box(
                modifier = Modifier
                    .padding(start = offset, top = offset)
                    .size(width = 78.dp, height = 98.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        alpha = 1f - index * 0.18f
                    }
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                ScoreThumb(score, dense = true, modifier = Modifier.fillMaxWidth().height(98.dp))
            }
        }
    }
}
