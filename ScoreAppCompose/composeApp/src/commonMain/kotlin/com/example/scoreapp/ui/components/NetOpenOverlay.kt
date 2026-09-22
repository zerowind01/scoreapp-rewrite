package com.example.scoreapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scoreapp.domain.netdisk.Netdisk

/**
 * 网盘谱子的下载浮层。**首页网盘页与网盘浏览页共用同一份** ——
 * 两处都是「下载完直接进阅读器」，反馈必须长得一样，否则用户会以为是两个功能。
 *
 * 四态（文案全在 `Netdisk.progressLabel`，可单测）：
 *  1. 下载中：进度条 + 百分比 + 已收/总量；
 *  2. 字节收完（[progress] >= 100）：说「已下载，正在打开…」——
 *     **不能倒回去说「正在下载…」**，那看着像卡死（1.22 真机就是这么被误会的）；
 *  3. 服务端没给总长度（[progress] < 0）：只说「正在下载… + 已收 x MB」，
 *     **不摆一根永远不动的条**；
 *  4. 失败：人话原因（红字）+ 技术细节（灰字，[detail]）；细节必须显示 ——
 *     分类码只会说「网络不通」，「请求没发出去」和「服务器拒了」翻出来是同一句话。
 *     只有失败态给「重试」。
 */
@Composable
fun NetOpenOverlay(
    title: String,
    progress: Int,
    error: String?,
    detail: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    done: Long = 0L,
    total: Long = 0L,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD92B2B2F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 30.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFFA9A9B2),
                    textAlign = TextAlign.Center,
                )
            }
            if (error != null) {
                Text(
                    "下载失败：$error",
                    fontSize = 11.5.sp,
                    color = Color(0xFFFF9A9A),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp),
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        fontSize = 10.5.sp,
                        color = Color(0xFF8E8E97),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 30.dp),
                    )
                }
            } else {
                // 有总长度才画条。progress < 0 = 服务端没给 Content-Length，
                // 那就只报已收多少，不摆一根永远不动的条。
                // **别用 LinearProgressIndicator**：它的 progress 参数在不同
                // Material3 版本签名不一样，这里只要一根条
                if (progress >= 0) {
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF43434A)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                // progress 可能 >100（服务端少报了长度），画的时候夹回 1
                                .fillMaxWidth(progress.coerceIn(0, 100) / 100f)
                                .background(Color.White),
                        )
                    }
                }
                Text(
                    Netdisk.progressLabel(progress, done, total),
                    fontSize = 11.5.sp,
                    color = Color(0xFFA9A9B2),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error != null) {
                    OverlayKey("重试", onRetry)
                }
                OverlayKey("关闭", onDismiss)
            }
        }
    }
}

@Composable
private fun OverlayKey(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF43434A))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
