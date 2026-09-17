package com.example.scoreapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.example.scoreapp.model.Score
import com.example.scoreapp.ui.theme.Tokens
import kotlin.math.max
import kotlin.math.min

/**
 * 乐谱缩略图。
 *
 * 不依赖任何图片资源：按 [Score.thumbSeed] 生成确定性随机数，
 * 在 Canvas 上直接绘制「雕版乐谱页」或「唱片封面」两种形态。
 * 同一份乐谱在任何设备、任何尺寸下都会得到同一张图。
 */
@Composable
fun ScoreThumb(
    score: Score,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (score.thumbKind == Score.THUMB_COVER) {
                drawCover(score, measurer)
            } else {
                drawEngrave(score, dense)
            }
            // 纸面内描边
            drawRect(
                color = Tokens.Ink.copy(alpha = 0.07f),
                size = Size(size.width, size.height),
                style = Stroke(width = 1f),
            )
        }
    }
}

/**
 * 确定性伪随机数发生器（mulberry32）。
 * 相比 `Random(seed)` 的好处是纯整数运算，跨平台结果完全一致。
 */
private class SeededRandom(seed: Int) {
    private var state: Int = seed

    fun next(): Float {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
}

/**
 * 绘制雕版乐谱页：若干谱表系统，每系统 5 条谱线 + 符头 + 符干 + 小节线。
 *
 * @param dense 网格视图下的密集模式：不画抬头，上下留白更小
 */
private fun DrawScope.drawEngrave(score: Score, dense: Boolean) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    drawRect(color = Tokens.Paper, size = Size(w, h))

    val random = SeededRandom(score.thumbSeed * 2654435761.toInt())
    val rows = (score.thumbRows.coerceIn(1, 8)).toFloat()
    val cols = score.thumbCols.coerceIn(1, 3)

    val pad = max(4f, w * 0.07f)
    val innerW = w - pad * 2f
    val topPad = h * if (dense) 0.09f else 0.13f
    val botPad = h * 0.07f
    val sysGap = (h - topPad - botPad) / rows

    // 纸张抬头横线
    if (!dense) {
        drawRect(
            color = Tokens.Ink.copy(alpha = 0.20f),
            topLeft = Offset(w * 0.30f, h * 0.055f),
            size = Size(w * 0.40f, max(1.6f, h * 0.008f)),
        )
    }

    val ink = Tokens.Ink.copy(alpha = 0.80f)
    val staffColor = Tokens.Ink.copy(alpha = 0.26f)
    val barColor = Tokens.Ink.copy(alpha = 0.42f)
    val lineW = max(0.5f, h * 0.0026f)

    for (row in 0 until rows.toInt()) {
        val sysTop = topPad + row * sysGap
        val staffH = min(sysGap * 0.40f, h * 0.075f)
        val lineGap = staffH / 4f

        // 5 条谱线
        for (i in 0..4) {
            val y = sysTop + i * lineGap
            drawLine(
                color = staffColor,
                start = Offset(pad, y),
                end = Offset(w - pad, y),
                strokeWidth = lineW,
            )
        }

        // 音符
        val noteCount = 5 + (random.next() * 7f).toInt()
        val cellW = innerW / noteCount
        for (n in 0 until noteCount) {
            // 随机留白，避免机械感
            if (random.next() < 0.14f) continue

            val cx = pad + cellW * (n + 0.5f)
            val step = (random.next() * 5f).toInt()
            val cy = sysTop + step * lineGap
            val headW = max(1.5f, cellW * 0.30f)
            val headH = headW * 0.68f

            // 符头：略微倾斜的椭圆
            rotate(degrees = -18f, pivot = Offset(cx, cy)) {
                drawOval(
                    color = ink,
                    topLeft = Offset(cx - headW, cy - headH),
                    size = Size(headW * 2f, headH * 2f),
                )
            }

            // 符干
            val stemUp = random.next() > 0.45f
            val stemW = max(0.6f, w * 0.008f)
            if (stemUp) {
                drawLine(
                    color = ink,
                    start = Offset(cx + headW * 0.9f, cy),
                    end = Offset(cx + headW * 0.9f, cy - staffH * 0.92f),
                    strokeWidth = stemW,
                )
            } else {
                drawLine(
                    color = ink,
                    start = Offset(cx - headW * 0.9f, cy),
                    end = Offset(cx - headW * 0.9f, cy + staffH * 0.92f),
                    strokeWidth = stemW,
                )
            }

            // 小节线
            if ((n + 1) % cols == 0 && n < noteCount - 1) {
                val bx = pad + cellW * (n + 1)
                drawLine(
                    color = barColor,
                    start = Offset(bx, sysTop - lineGap * 0.5f),
                    end = Offset(bx, sysTop + staffH + lineGap * 0.5f),
                    strokeWidth = max(0.6f, w * 0.007f),
                )
            }
        }
    }
}

/** 绘制唱片封面型缩略图（thumbKind == "cover"） */
private fun DrawScope.drawCover(score: Score, measurer: TextMeasurer) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val c1 = parseHex(score.coverC1) ?: Color(0xFF243B4A)
    val c2 = parseHex(score.coverC2) ?: Color(0xFF5B7F96)
    drawRect(brush = Brush.linearGradient(colors = listOf(c1, c2), start = Offset.Zero, end = Offset(w, h)))

    // 版式参数全部来自 Score 模型，而非写死的百分比。
    // 这些字段（coverTx/coverTy/coverTSize/coverTGap/coverSubX/coverSubY…）此前在
    // 两套实现里都只定义、从不读取，导致样张里调好的排版完全没生效。
    // 坐标语义：样张数据中 coverTx=15 与 coverSubX=15 并存、subY=120 已接近画布底边
    // （参考系 100），可见是**左对齐**语义——15 指左边距而非中心点。
    val tx = score.coverTx
    val ty = score.coverTy
    val tGap = score.coverTGap
    val subX = score.coverSubX
    // 样张 subY=120 超出 100 的参考画布（换算后会落到画布下方被裁掉），夹到参考系内
    val subY = min(score.coverSubY, 96f)
    val kx = w / 100f
    val ky = h / 100f
    val textColor = parseHex(score.coverTColor) ?: Color.White
    val baseSize = max(9f, w * (score.coverTSize / 100f) * 0.62f)
    val left = tx * kx
    // 左边界 + 右侧安全边距；超出可用宽度时缩字并截断，避免长标题溢出画布
    val usable = max(10f, w - left * 2f)

    /** 装饰同心弧；coverDeco = "none" 时跳过 */
    if (score.coverDeco != "none") {
        val arcStroke = Stroke(width = max(1f, w * 0.012f))
        for (i in 0..2) {
            val radius = w * (0.18f + i * 0.11f)
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = 194f,
                sweepAngle = 151f,
                useCenter = false,
                topLeft = Offset(w * 0.5f - radius, h * 0.46f - radius),
                size = Size(radius * 2f, radius * 2f),
                style = arcStroke,
            )
        }
    }

    /**
     * 以左边界为锚点绘制单行文本：必要时缩字号（最多缩到 68%），
     * 仍超宽则按省略号截断。截断依赖平台单行省略能力，无法逐字符试探。
     */
    fun drawAligned(
        text: String?,
        x: Float,
        y: Float,
        wantSize: Float,
        weight: FontWeight,
        alpha: Float = 1f,
        alignRight: Boolean = false,
    ) {
        if (text.isNullOrBlank()) return
        // 先按目标字号量一次，超宽则缩到可用宽度上限
        var size = wantSize
        var layout = measurer.measure(
            text = text,
            style = TextStyle(
                color = textColor.copy(alpha = alpha),
                fontSize = size.toSp(),
                fontWeight = weight,
            ),
            maxLines = 1,
        )
        if (layout.size.width > usable && size > wantSize * 0.68f) {
            size = max(wantSize * 0.68f, size * (usable / layout.size.width))
            layout = measurer.measure(
                text = text,
                style = TextStyle(
                    color = textColor.copy(alpha = alpha),
                    fontSize = size.toSp(),
                    fontWeight = weight,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = usable.toInt().coerceAtLeast(1)),
            )
        }
        // y 传入的是基线位置，转为文本块顶边；右对齐时从右边界反推左起点
        val xPos = if (alignRight) w - x - layout.size.width else x
        drawText(textLayoutResult = layout, topLeft = Offset(xPos, y - layout.size.height * 0.78f))
    }

    // 封面下部的色带
    if (!score.coverBarText.isNullOrBlank()) {
        drawRect(
            color = Color.Black.copy(alpha = 0.34f),
            topLeft = Offset(0f, h * 0.80f),
            size = Size(w, h * 0.075f),
        )
    }

    drawAligned(score.coverTitle, left, ty * ky, baseSize, FontWeight.Bold)
    drawAligned(score.coverEn, left, ty * ky + tGap * ky + baseSize * 0.32f, baseSize * 0.42f, FontWeight.Medium, alpha = 0.72f)
    if (!score.coverBarText.isNullOrBlank()) {
        // 色带满宽，不适用文本块的 left/usable，按画布居中并限制在 90% 宽内
        drawAligned(
            text = score.coverBarText,
            x = 0f,
            y = h * 0.80f + h * 0.052f,
            wantSize = baseSize * 0.40f,
            weight = FontWeight.SemiBold,
        )
    }
    // 底部说明：coverFooter 为真时贴右边（作为版式页脚），否则与文本块左对齐
    drawAligned(
        text = score.coverSub,
        x = if (score.coverFooter) left else subX * kx,
        y = subY * ky,
        wantSize = baseSize * 0.40f,
        weight = FontWeight.Medium,
        alpha = 0.80f,
        alignRight = score.coverFooter,
    )
}

/** 解析 `#rrggbb`；非法输入返回 null 由调用方兜底 */
private fun parseHex(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}
