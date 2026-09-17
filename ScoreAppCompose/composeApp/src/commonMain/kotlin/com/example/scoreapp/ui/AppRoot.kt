package com.example.scoreapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.RootTab
import com.example.scoreapp.model.Screen
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.screens.ComposersScreen
import com.example.scoreapp.ui.screens.DetailScreen
import com.example.scoreapp.ui.screens.ManageScreen
import com.example.scoreapp.ui.screens.MeScreen
import com.example.scoreapp.ui.screens.WorksScreen
import com.example.scoreapp.ui.screens.membersOf
import com.example.scoreapp.ui.sheets.EditSheet
import com.example.scoreapp.ui.sheets.FilterSheet
import com.example.scoreapp.ui.sheets.ImportSheet
import com.example.scoreapp.ui.sheets.MoreSheet
import com.example.scoreapp.ui.sheets.SetDetailSheet
import com.example.scoreapp.ui.sheets.SortSheet
import com.example.scoreapp.ui.theme.ScoreAppTheme
import com.example.scoreapp.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * 应用根组件。
 *
 * 负责整体骨架：底部导航、悬浮按钮、弹层分发与轻提示；
 * 具体页面内容由各 Screen 自行渲染。
 */
@Composable
fun AppRoot(state: ScoreAppState) {
    ScoreAppTheme {
        Box(Modifier.fillMaxSize().background(Tokens.BgPage)) {
            // 底部导航不再是 Scaffold 的 bottomBar（那会贴底通栏并占去内容高度），
            // 改为浮在内容之上的圆角玻璃导台，因此这里用扁平 Box 自管布局。
            Scaffold(
                containerColor = Tokens.BgPage,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { innerPadding ->
                val detail = state.detail
                // 先取到局部变量再做类型判断：current 是带自定义 getter 的属性，
                // 直接写 state.current is X 无法触发智能转换。
                val current = state.current
                // 导台脱离文档流后不再挤压内容，需由页面自行预留出它的高度；
                // 这里算一次统一传给各页面做底部内边距。
                val navClearance = BottomNavClearance + innerPadding.calculateBottomPadding()
                // targetSdk 35 起系统强制 edge-to-edge，内容会顶到状态栏下方，
                // 因此这里显式让出状态栏高度。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    when {
                        // 详情页占满整屏、不显示悬浮导台，故无需为其预留底部空间
                        detail != null -> DetailScreen(state, detail, 0.dp)

                        current is Screen.Works -> WorksScreen(state, current.composer, navClearance)
                        current is Screen.Manage -> ManageScreen(state, navClearance)
                        current is Screen.Composers -> ComposersScreen(state, navClearance)
                        else -> MeScreen(state, navClearance)
                    }
                }
            }

            // 悬浮导台：仅在主界面（非详情页）显示，与原型一致
            if (state.detail == null) {
                BottomNav(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
                // 仅在主列表页提供「导入」入口，位置压在导台右上方
                if (state.current is Screen.Manage) {
                    ImportFab(
                        onClick = { state.openImport() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    )
                }
            }

            SheetHost(state)
            ToastHost(state)
        }
    }
}

/**
 * 悬浮导台需要为内容预留的底部空间（dp）。
 *
 * 组成：导台距底 14 + 内容高度约 52 + 与内容的呼吸间距 30。
 * 各页面把它加到自己的 `contentPadding.bottom`，避免末条内容被玻璃导台压住。
 */
private val BottomNavClearance = 96.dp

// ---------------------------------------------------------------- 底部导航

@Composable
private fun BottomNav(state: ScoreAppState, modifier: Modifier = Modifier) {
    // 悬浮玻璃导台：四周留空、圆角、半透明 + 高斯模糊，
    // 让底下的列表内容透出来但仍保持可读（对应原型的 backdrop-filter: blur）。
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .glassSurface()
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RootTab.entries.forEach { tab ->
            val selected = state.activeTab == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    // 选中项加一层淡内底，使玻璃导台上也有明确的位置锚点
                    .background(if (selected) Tokens.Text1.copy(alpha = 0.06f) else Color.Transparent)
                    .clickable { state.selectTab(tab) }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = when (tab) {
                        RootTab.Library -> AppIcons.Book
                        RootTab.Composers -> AppIcons.Person
                        RootTab.Me -> AppIcons.AccountCircle
                    },
                    contentDescription = tab.label,
                    tint = if (selected) Tokens.Text1 else Tokens.TabInactive,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = tab.label,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Tokens.Text1 else Tokens.TabInactive,
                )
            }
        }
    }
}

/**
 * 玻璃拟态（glassmorphism）。
 *
 * Compose 没有现成的 `backdrop-filter`，用 [Modifier.blur] 只能模糊自身而不是
 * 背后内容；因此这里改用「高透明度的浅色底」，配合导台外层的半透明堆叠，
 * 让底下内容隐约透出，达到等价的玻璃观感，且在各平台渲染开销可控。
 */
private fun Modifier.glassSurface(): Modifier = this
    .background(Tokens.Surface.copy(alpha = 0.62f))

@Composable
private fun ImportFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // 与导台同属一套悬浮体系：右侧对齐、压在导台正上方留 12dp 间隙
            .padding(end = 18.dp, bottom = 92.dp)
            .size(54.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Tokens.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Add,
            contentDescription = "导入乐谱",
            tint = Tokens.AccentFg,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ---------------------------------------------------------------- 弹层分发

@Composable
private fun SheetHost(state: ScoreAppState) {
    val dismiss: () -> Unit = { state.closeSheet() }
    when (state.sheet) {
        SheetKind.Filter -> FilterSheet(state, dismiss)
        SheetKind.Edit -> EditSheet(state, dismiss)
        SheetKind.Import -> ImportSheet(state, dismiss)
        SheetKind.Sort -> SortSheet(state, dismiss)
        SheetKind.More -> MoreSheet(state, dismiss)
        SheetKind.SetDetail -> {
            val set = state.viewingSet
            if (set != null) {
                SetDetailSheet(
                    state = state,
                    set = set,
                    members = membersOf(state, set.seeds),
                    onDismiss = dismiss,
                )
            }
        }
        SheetKind.None -> Unit
    }
}

// ---------------------------------------------------------------- 轻提示

@Composable
private fun ToastHost(state: ScoreAppState) {
    val message = state.toast

    LaunchedEffect(message) {
        if (message != null) {
            delay(2200)
            state.clearToast()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                text = message.orEmpty(),
                color = Tokens.AccentFg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(bottom = 96.dp)
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Tokens.Accent.copy(alpha = 0.93f))
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}
