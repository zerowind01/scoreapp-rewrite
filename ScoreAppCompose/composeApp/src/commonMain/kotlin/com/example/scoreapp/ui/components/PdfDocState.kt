package com.example.scoreapp.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max

/**
 * 阅读器侧「文档打开了没有」的四态。
 *
 * 原应用用三个彼此独立的变量（`renderer` / `pageCount` / `error`）拼装状态，
 * 于是产生了一个自相矛盾的组合：`pageCount == 0` 且 `error == null` 且
 * `renderer != null` 时，顶栏按 `pageCount > 0` 判据吐出「读取中…」，
 * 而正文同时显示「这份 PDF 没有任何页面」——顶栏说还在读、正文说读完了。
 *
 * 把四态显式建模后，这种组合在类型上就不存在了：
 * `Loading` 不是「页数为 0」，`Empty` 也不是「还在加载」。
 */
internal sealed interface ReaderPhase {
    /** 还没拿到结果 */
    data object Loading : ReaderPhase

    /** 打不开：文件不存在、损坏、不受支持 */
    data class Failed(val message: String) : ReaderPhase

    /** 打开了，但这份 PDF 一页都没有 */
    data object Empty : ReaderPhase

    /** 正常可读 */
    data class Ready(val pageCount: Int) : ReaderPhase
}

/**
 * 阅读器的 PDF 文档状态。
 *
 * 位图按页序缓存，上限取运行时最大堆的 1/8（`LruCache` 自己的口径），
 * 对「一份文档的若干页」这个规模够用；页数再多也是同一份文档内的局部性，
 * 不值得再叠一层人为预算（封面那侧的固定 12 MB 是另一回事，见 `CoverRenderer`）。
 */
internal class PdfDocState(private val ctx: Context, private val path: String) {

    private companion object {
        const val TAG = "PdfDocState"
        const val MODE_READ_ONLY = 268435456

        /** `Runtime.maxMemory() / 8`：与 `LruCache` 的惯用取值一致 */
        fun heapBudget(): Int = (Runtime.getRuntime().maxMemory() / 8).toInt()
    }

    var phase: ReaderPhase by mutableStateOf(ReaderPhase.Loading)
        private set

    private var renderer: PdfRenderer? by mutableStateOf(null)
    private var pfd: ParcelFileDescriptor? = null
    private val lock = Any()

    private val cache = object : android.util.LruCache<Int, Bitmap>(heapBudget()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** 打开文档。失败不抛出，把原因写进 [phase] */
    fun open() {
        try {
            val descriptor: ParcelFileDescriptor = if (path.startsWith("content://")) {
                ctx.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    ?: throw IOException("无法读取所选文件")
            } else {
                val file = File(path)
                if (!file.exists()) throw FileNotFoundException("文件不存在：$path")
                ParcelFileDescriptor.open(file, MODE_READ_ONLY)
            }

            pfd = descriptor
            val opened = PdfRenderer(descriptor)
            renderer = opened
            val count = opened.pageCount
            Log.i(TAG, "PDF 打开成功 pages=$count path=$path")
            phase = if (count > 0) ReaderPhase.Ready(count) else ReaderPhase.Empty
        } catch (t: Throwable) {
            Log.e(TAG, "PDF 打开失败", t)
            phase = ReaderPhase.Failed("无法打开这份乐谱：${t.message ?: "文件损坏或不受支持"}")
        }
    }

    /**
     * 渲染某一页到目标宽度。
     *
     * 返回 null 有三种情况，调用方一视同仁（页面区域显示转圈）：
     * 文档未就绪、页序越界、渲染抛异常。
     */
    fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        cache.get(index)?.let { return it }
        return try {
            synchronized(lock) {
                val opened = renderer ?: return null
                val count = (phase as? ReaderPhase.Ready)?.pageCount ?: return null
                if (index < 0 || index >= count) return null

                opened.openPage(index).use { page ->
                    val scale = targetWidth / page.width.toFloat()
                    val bitmap = Bitmap.createBitmap(
                        max((page.width * scale).toInt(), 1),
                        max((page.height * scale).toInt(), 1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(-1)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(index, bitmap)
                    bitmap
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "渲染第 ${index + 1} 页失败", t)
            null
        }
    }

    fun close() {
        runCatching { synchronized(lock) { renderer?.close() } }
        renderer = null
        cache.evictAll()
        runCatching { pfd?.close() }
        pfd = null
    }
}
