package com.example.scoreapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.model.RootTab
import com.example.scoreapp.model.Screen
import com.example.scoreapp.ui.components.AppIcons
import com.example.scoreapp.ui.components.PdfViewerScreen
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
 * 负责整体骨架：底部导航、悬浮按钮、弹层分发、阅读器覆盖层与轻提示；
 * 具体页面内容由各 Screen 自行渲染。
 *
 * @param onPickImages 请求系统相册多选；由平台侧注入（Android 才能拉起选择器）
 * @param onPickPdf 请求系统文件选择器单选 PDF
 */
@Composable
fun AppRoot(
    state: ScoreAppState,
    onPickImages: () -> Unit = {},
    onPickPdf: () -> Unit = {},
) {
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

            // 导入意图 → 拉起系统选择器。放在 effect 里而不是点击回调里，
            // 是因为选择器必须等弹层收起之后再拉起：先关弹层再 launch，
            // 否则某些机型上会把选择器压在弹层底下。
            LaunchedEffect(state.pendingPick) {
                when (state.pendingPick) {
                    PickKind.Images -> onPickImages()
                    PickKind.Pdf -> onPickPdf()
                    PickKind.None -> Unit
                }
            }

            SheetHost(state)
            ReaderHost(state)
            ToastHost(state)
        }
    }
}

/**
 * 阅读器覆盖层。
 *
 * 单独一层而不是并入 `SheetHost`：阅读器是全屏视图，不是底部弹层，
 * 两者的进出场与返回键语义都不一样。
 */
@Composable
private fun ReaderHost(state: ScoreAppState) {
    val request = state.reader ?: return
    // key 随打开对象变化：换一份乐谱必须重建文档状态，否则会读到上一份的内容
    key(request.key) {
        PdfViewerScreen(
            path = request.path,
            title = request.title,
            onBack = { state.closePdf() },
        )
    }
}

/**
 * 悬浮导台需要为内容预留的底部空间（dp）。
 *
 * 组成：导台距底 12 + 高度 52 + 与内容的呼吸间距 28。
 * 导台压薄后所需避让空间同步收敛（原为 96）。
 * 各页面把它加到自己的 `contentPadding.bottom`，避免末条内容被玻璃导台压住。
 */
private val BottomNavClearance = 92.dp

/** 导台高度（dp）：内容 44 + 上下内边距各 4，比常规底栏更"薄"。 */
private val BottomNavHeight = 54.dp

/**
 * 选中项焦点气泡的尺寸（dp）。
 *
 * 刻意做成"略微拉宽"的椭圆而非正圆：宽比高多 4dp，在玻璃导台上包裹感更松弛。
 * 必须同时给出宽高——只给一边会让 [CircleShape] 渲染成正圆或椭圆，无法控形。
 */
private val BottomNavBubbleWidth = 50.dp
private val BottomNavBubbleHeight = 46.dp

// ---------------------------------------------------------------- 底部导航

@Composable
private fun BottomNav(state: ScoreAppState, modifier: Modifier = Modifier) {
    // 悬浮胶囊玻璃导台：四周留空、两端半圆收口、半透明 + 高斯模糊，
    // 让底下的列表内容透出来但仍保持可读（对应原型的 backdrop-filter: blur）。
    // 圆角取高度一半（26dp），两端才会得到真正的半圆而非"圆角矩形"。
    val pillShape = RoundedCornerShape(BottomNavHeight / 2)
    val tabs = RootTab.entries
    val index = tabs.indexOf(state.activeTab).coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(BottomNavHeight)
            .clip(pillShape)
            .glassSurface()
            .border(1.dp, Color.White.copy(alpha = 0.72f), pillShape)
            .padding(horizontal = 4.dp),
    ) {
        // 页签是等宽排列的，所以「第 index 个的中心」可以直接从可用宽算出来：
        //   单签宽 = (可用宽 - 间距×(n-1)) / n
        //   中心   = index × (单签宽 + 间距) + 单签宽 / 2
        // 用份额（Float）做动画而不是 dp，避免首帧拿到 0 宽时算出一个错误的偏移量。
        val step = maxWidth / tabs.size
        // 直接动画 Dp：offset 的 lambda 版本要自己把 Dp 转像素，
        // 而 offset(x = Dp) 把换算留给框架，省一层容易写错的单位处理
        val bubbleX by animateDpAsState(
            targetValue = step * (index + 0.5f) - BottomNavBubbleWidth / 2,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "navBubble",
        )
        // 选中项：白色气泡浮在玻璃之上，对应参考图里那颗高亮圆底。
        // 整条导台只有这一颗，切页签时靠 offset 滑过去，而不是旧位置消失、新位置重画。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = bubbleX)
                .size(width = BottomNavBubbleWidth, height = BottomNavBubbleHeight)
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f)),
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { tab ->
                val selected = state.activeTab == tab
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // indication = null：默认的 ripple 在被裁进矩形边界后
                        // 看起来就是一块长方形高亮，观感脏。选中反馈交给白色气泡。
                        .clickable(interactionSource = interaction, indication = null) {
                            state.selectTab(tab)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = when (tab) {
                                RootTab.Library -> AppIcons.Book
                                RootTab.Composers -> AppIcons.Person
                                RootTab.Me -> AppIcons.AccountCircle
                            },
                            contentDescription = tab.label,
                            tint = if (selected) Tokens.Text1 else Tokens.TabInactive,
                            modifier = Modifier.size(21.dp),
                        )
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Tokens.Text1 else Tokens.TabInactive,
                        )
                    }
                }
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
    // 0.82 而非更「通透」的取值：太透时列表文字会从导台底下穿上来，
    // 与底部图标叠在一起难以阅读（原型已同步调到 .82）
    .background(Tokens.Surface.copy(alpha = 0.82f))

@Composable
private fun ImportFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // 与导台同属一套悬浮体系：右侧对齐、压在导台正上方留 14dp 间隙。
            // 导台压薄并下移后，FAB 的抬升量同步收敛（原为 92dp）。
            .padding(end = 16.dp, bottom = 78.dp)
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
