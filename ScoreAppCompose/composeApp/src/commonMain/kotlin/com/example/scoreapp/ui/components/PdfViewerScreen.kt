package com.example.scoreapp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.ReaderBarText
import com.example.scoreapp.ui.theme.Tokens
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏 PDF 阅读器。
 *
 * 结构与原应用一致：顶栏（返回 / 标题 + 状态副标题 / 页数徽标）+ 正文四态。
 * 正文用 `HorizontalPager` 横向翻页，页面上叠加捏合缩放（1×–4×）与平移。
 */
@Composable
fun PdfViewerScreen(path: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val doc = remember(path) { PdfDocState(context.applicationContext, path) }

    // 打开文档，并在离开时释放 renderer 与文件描述符。
    // 不释放的话，反复开关阅读器会持续占用 fd，几百次之后打开就会失败。
    DisposableEffect(doc) {
        doc.open()
        onDispose { doc.close() }
    }

    val readyCount = (doc.phase as? ReaderPhase.Ready)?.pageCount

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.BgPage),
    ) {
        ReaderBar(
            title = title,
            // 文案规则抽到 domain/ReaderBarText，便于用普通单测锁住
            // 「四种状态互不重复」「空文档不说读取中」这两条不变量
            subtitle = ReaderBarText.subtitle(
                pageCount = readyCount,
                failed = doc.phase is ReaderPhase.Failed,
            ),
            badge = ReaderBarText.badgeText(readyCount),
            onBack = onBack,
        )

        when (val phase = doc.phase) {
            ReaderPhase.Loading -> LoadingBody()
            is ReaderPhase.Failed -> StateBox(phase.message)
            ReaderPhase.Empty -> StateBox("这份 PDF 没有任何页面")
            is ReaderPhase.Ready -> PageBody(doc, phase.pageCount)
        }
    }
}

/**
 * 顶栏。
 *
 * 副标题与页数徽标的分工在 [ReaderBarText] 里定义：
 * 副标题是**叙述**（「共 12 页」），徽标是**数字**（「12 页」），
 * 且徽标只在页数确实大于 0 时出现。
 *
 * 原应用此处无论何种情况都在副标题写「共 N 页」、右侧再写一次「N 页」，
 * 同一信息说两遍；且 `pageCount == 0` 时副标题回落到「读取中…」，
 * 与正文的「这份 PDF 没有任何页面」直接冲突。
 */
@Composable
private fun ReaderBar(
    title: String,
    subtitle: String,
    badge: String?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIcon(AppIcons.ArrowBack, "返回", onBack)
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Tokens.Text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = subtitle,
            fontSize = 11.5.sp,
            color = Tokens.Text3,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(end = 10.dp),
        )
        if (badge != null) {
            PageBadge(badge)
        }
    }
}

/** 页数徽标 */
@Composable
private fun PageBadge(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = Tokens.Surface) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = Tokens.Text2,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Tokens.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Tokens.Text1,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Tokens.Text2)
    }
}

/** 错误 / 空文档的中性提示 */
@Composable
private fun StateBox(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = Tokens.Text2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/**
 * 正文：横向翻页 + 页内缩放。
 *
 * 渲染宽度取 `clamp(toPx(可用宽), 700, 1200)`：下限 700 保证位图不被拉糊，
 * 上限 1200 防止在平板上按屏宽渲染出过大的位图（内存与耗时都不划算）。
 */
@Composable
private fun ColumnScope.PageBody(doc: PdfDocState, pageCount: Int) {
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .weight(1f),
    ) {
        val targetWidth = with(density) { maxWidth.toPx() }.toInt().coerceIn(700, 1200)

        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                PdfPage(doc, index, targetWidth)
            }
            // 悬浮页码，只在多于 1 页时出现——单页文档标「1 / 1」是噪音
            if (pageCount > 1) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Tokens.Text1.copy(alpha = 0.72f),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / $pageCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/**
 * 单页。位图在 IO 线程渲染，未就绪时显示转圈。
 *
 * 缩放夹在 1×–4×（下限 1 让用户总能退回完整页面，上限 4 是清晰度与内存的取舍点）；
 * 位移只在放大后累加，缩回 1× 时归零——否则缩小后页面会停在一个偏移位置，
 * 看起来像是「跑偏了」而不是「回到原处」。
 *
 * 手势按缩放状态分两套，这是「在乐谱上拖不动页」的根因所在：
 * `detectTransformGestures` 一旦过了 touch slop 就 **无条件消费** 指针事件，
 * 单指拖动也被吃掉，外层 `HorizontalPager` 再也收不到滑动；
 * 手势又只挂在 Image 上，于是只有图像之外的空白区能翻页。
 *  · 1×（未放大）：只认双指捏合，单指拖动一律放行 → 交给 Pager 翻页
 *  · >1×（已放大）：接管全部手势，平移页面；此时不该翻页，否则边拖边跳页
 */
@Composable
private fun PdfPage(doc: PdfDocState, index: Int, targetWidth: Int) {
    var scale by remember(index) { mutableStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }

    // PdfDocState.renderPage 返回 android.graphics.Bitmap，这里在 IO 线程顺带转成
    // ImageBitmap，避免把一次跨类型转换留在组合阶段
    val frame by produceState<ImageBitmap?>(initialValue = null, doc, index, targetWidth) {
        value = withContext(Dispatchers.IO) {
            doc.renderPage(index, targetWidth)?.asImageBitmap()
        }
    }

    // key 里带上「是否已放大」：切换缩放状态的那一刻重建手势节点，两套逻辑不打架
    val zoomed = scale > 1.001f
    val gestures = Modifier.pointerInput(index, zoomed) {
        if (zoomed) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
        } else {
            detectPinchZoom { zoom ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                offset = Offset.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize().then(gestures), contentAlignment = Alignment.Center) {
        val current = frame
        if (current == null) {
            // 位图未就绪，或这一页渲染失败——两者都表现为等待状态
            CircularProgressIndicator(color = Tokens.Text2)
        } else {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

/**
 * 只认双指捏合，单指拖动**不消费**。
 *
 * 与 `detectTransformGestures` 的唯一区别就在这里：后者一旦越过 touch slop
 * 就把所有指针变化都 `consume()` 掉，外层 Pager 因此收不到滑动。
 * 未放大时页面本来也不需要平移，单指拖动的语义就是翻页，理应放行。
 */
private suspend fun PointerInputScope.detectPinchZoom(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        val touchSlop = viewConfiguration.touchSlop
        var zoom = 1f
        var pastSlop = false
        awaitFirstDown()
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            val downCount = event.changes.count { it.pressed }
            if (!canceled && downCount >= 2) {
                zoom *= event.calculateZoom()
                if (!pastSlop) {
                    // 捏合的位移量要乘上两指间距才与 touch slop 同量纲
                    val span = event.calculateCentroidSize(useCurrent = false)
                    pastSlop = abs(1f - zoom) * span > touchSlop
                }
                if (pastSlop) {
                    // 传增量：调用方自己乘进当前 scale，这里随即归 1，避免重复累计
                    onZoom(zoom)
                    zoom = 1f
                    event.changes.forEach { if (it.pressed) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}
