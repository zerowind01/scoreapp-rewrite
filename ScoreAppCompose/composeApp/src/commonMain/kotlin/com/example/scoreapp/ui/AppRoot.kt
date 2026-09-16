package com.example.scoreapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.RootTab
import com.example.scoreapp.model.Screen
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.HairlineDivider
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
            Scaffold(
                containerColor = Tokens.BgPage,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = { BottomNav(state) },
                floatingActionButton = {
                    // 仅在主列表页提供「导入」入口
                    if (state.current is Screen.Manage && state.detail == null) {
                        ImportFab { state.openImport() }
                    }
                },
            ) { innerPadding ->
                val bottomPad = innerPadding.calculateBottomPadding()

                val detail = state.detail
                // 先取到局部变量再做类型判断：current 是带自定义 getter 的属性，
                // 直接写 state.current is X 无法触发智能转换。
                val current = state.current
                // targetSdk 35 起系统强制 edge-to-edge，内容会顶到状态栏下方，
                // 因此这里显式让出状态栏高度；底部导航栏高度由 BottomNav 自行处理。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    when {
                        detail != null -> DetailScreen(state, detail, bottomPad)

                        current is Screen.Works -> WorksScreen(state, current.composer, bottomPad)
                        current is Screen.Manage -> ManageScreen(state, bottomPad)
                        current is Screen.Composers -> ComposersScreen(state, bottomPad)
                        else -> MeScreen(state, bottomPad)
                    }
                }
            }

            SheetHost(state)
            ToastHost(state)
        }
    }
}

// ---------------------------------------------------------------- 底部导航

@Composable
private fun BottomNav(state: ScoreAppState) {
    Column {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.Surface.copy(alpha = 0.94f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 7.dp),
        ) {
            RootTab.entries.forEach { tab ->
                val selected = state.activeTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { state.selectTab(tab) },
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
}

@Composable
private fun ImportFab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
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
