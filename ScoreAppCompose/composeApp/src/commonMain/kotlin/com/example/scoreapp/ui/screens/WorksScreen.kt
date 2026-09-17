package com.example.scoreapp.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.AvatarPalette
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.EmptyState
import com.example.scoreapp.ui.components.IconBtn
import com.example.scoreapp.ui.components.ScoreCard
import com.example.scoreapp.ui.theme.Tokens

/**
 * 某位作曲家的作品页（[com.example.scoreapp.model.Screen.Works]）。
 *
 * 从作曲家索引点进来，只展示这位作曲家的乐谱，按标题升序。
 * 顶部的头像配色与索引列表保持一致，让「同一个人同一种颜色」贯穿全应用。
 */
@Composable
fun WorksScreen(
    state: ScoreAppState,
    composer: String,
    bottomPadding: Dp,
) {
    val works = state.worksOf(composer)

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 固定标题「作品」。作曲家名在下面的大字块里，不在这里重复
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBtn(
                icon = AppIcons.ArrowBack,
                contentDescription = "返回",
                onClick = { state.back() },
            )
            Text(
                text = "作品",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Tokens.Text1,
            )
        }

        // 作曲家抬头：头像 + 简称 + 全名 + 作品计数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(AvatarPalette.colorFor(composer)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ComposerNames.initialOf(composer),
                    color = Tokens.AccentFg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text(
                    text = ComposerNames.shortName(composer),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                    color = Tokens.Text1,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = composer,
                    fontSize = 13.sp,
                    color = Tokens.Text3,
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "${works.size} 首作品",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tokens.Text2,
                )
            }
        }

        if (works.isEmpty()) {
            EmptyState(
                icon = AppIcons.MusicNote,
                title = "暂无作品",
                message = "",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(works, key = { it.id }) { score ->
                ScoreCard(
                    score = score,
                    onOpen = { state.openDetail(score) },
                    onEdit = { state.openEditor(score) },
                    onView = { state.openPdf(score) },
                    onShare = { state.share(score) },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}
