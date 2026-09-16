package com.example.scoreapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.AvatarPalette
import com.example.scoreapp.domain.ComposerEntry
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.ui.ScoreAppState
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.EmptyState
import com.example.scoreapp.ui.components.IconBtn
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * 「作曲家」页。
 *
 * 按索引字母分节，右侧固定 A-Z 快速跳转条。
 * 跳转通过 LazyListState 定位到对应分节的锚点下标实现。
 */
@Composable
fun ComposersScreen(state: ScoreAppState, bottomPadding: Dp) {
    val sections = state.composerSections
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 每个分节在列表中的下标：节标题 1 项 + 该节成员若干项
    val anchors = remember(sections) {
        buildList {
            var index = 0
            sections.forEach { (letter, entries) ->
                add(letter to index)
                index += 1 + entries.size
            }
        }.toMap()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "作曲家",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                    color = Tokens.Text1,
                )
                IconBtn(
                    icon = if (state.searchOpen) AppIcons.Close else AppIcons.Search,
                    contentDescription = "搜索",
                    active = state.searchOpen,
                    onClick = { state.toggleSearch() },
                )
            }

            if (state.searchOpen) {
                SearchField(
                    value = state.composerQuery,
                    placeholder = "搜索作曲家（中文 / 拼音）",
                    onValueChange = state::updateComposerQuery,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (sections.isEmpty()) {
                EmptyState(
                    icon = AppIcons.Person,
                    title = "没有匹配的作曲家",
                    message = "换个关键词或首字母试试",
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // end 留出右侧 A-Z 索引条的宽度，避免长名字被索引条压住
                contentPadding = PaddingValues(end = 22.dp, bottom = bottomPadding + 16.dp),
            ) {
                sections.forEach { (letter, entries) ->
                    item(key = "letter-$letter") { LetterHead(letter, entries.size) }
                    items(entries.size, key = { i -> entries[i].full }) { i ->
                        ComposerRow(
                            entry = entries[i],
                            onClick = { state.openComposerWorks(entries[i].full) },
                        )
                    }
                }
            }
        }

        // 右侧 A-Z 索引条
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .clip(CircleShape)
                .background(Tokens.Surface2)
                .padding(horizontal = 3.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            anchors.keys.forEach { letter ->
                Text(
                    text = letter,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Tokens.Text3,
                    modifier = Modifier.clickable {
                        anchors[letter]?.let { target ->
                            scope.launch { listState.scrollToItem(target) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LetterHead(letter: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = letter,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Tokens.Text3,
            letterSpacing = 0.6.sp,
        )
        Text(
            text = "$count 位",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.Text3,
        )
    }
}

@Composable
private fun ComposerRow(entry: ComposerEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AvatarPalette.colorFor(entry.full)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.initial,
                color = Tokens.AccentFg,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = ComposerNames.shortName(entry.full),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = Tokens.Text1,
            )
            Text(
                text = entry.full,
                fontSize = 12.5.sp,
                color = Tokens.Text3,
            )
        }

        // 作品数徽标
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = Tokens.Surface2,
        ) {
            Text(
                text = entry.scores.size.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Tokens.Text2,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}
