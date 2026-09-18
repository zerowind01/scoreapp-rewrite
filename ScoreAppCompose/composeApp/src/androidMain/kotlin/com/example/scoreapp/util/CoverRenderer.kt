package com.example.scoreapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 列表封面用的「PDF 首页渲染」。
 *
 * 与阅读器（`PdfDocState`）共用 `PdfRenderer`，但**缓存策略刻意不同**：
 *  - 这里按 `路径 → 位图` 缓存，上限 12 MB，`sizeOf` 手算 `宽 × 4 × 高`
 *    （按 ARGB 逐像素估，而非 `Bitmap.byteCount`）；
 *  - 阅读器按 `页序 → 位图` 缓存，上限取 `Runtime.maxMemory() / 8`。
 *
 * 两处口径不一致是原应用本来的样子，且各有道理：封面数量多、单张固定 640 px 宽，
 * 用一个明确的字节预算更可控；阅读器一份文档内页数有限、单页更大，按堆大小浮动更稳。
 * 统一它们会改变内存行为，不是重构该顺手做的事。
 *
 * 注意 `LruCache` **没有**无参构造，上限必须显式给——两处都给了，
 * 只是取值口径不同（固定 12 MB vs 堆的 1/8）。
 */
internal object CoverRenderer {

    private const val TAG = "CoverRenderer"

    /** 封面渲染目标宽度（px）。只约束渲染精度，显示时由 Image 的 Fit 再缩到卡片尺寸 */
    private const val THUMB_WIDTH = 640

    /** 放大上限。原图比目标宽还小时不强行拉大，否则小页会糊成马赛克 */
    private const val MAX_UPSCALE = 1.4f

    private const val MODE_READ_ONLY = 268435456

    /** 缓存上限 12582912 B = 12 MB */
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * 4 * value.height
    }

    /**
     * 取封面：先查缓存，未命中则渲染首页。
     *
     * 这里没有额外状态字段——原应用在组件里维护了一个 `state`（0 未加载 / 1 成功 / 2 失败）
     * 来驱动「预览加载失败」提示条。本工程让 [loadCoverBitmap] 直接返回 `ImageBitmap?`，
     * 由 Compose 侧用 `produceState` 承载「还没结果」这第三种状态，
     * 组件不必自己编号，语义也更直白。
     */
    fun load(ctx: Context, path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val bitmap = renderFirstPage(ctx, path)
        if (bitmap != null) cache.put(path, bitmap)
        return bitmap
    }

    /** 缓存键与文件一一对应；重新导入会生成新文件名，因此旧条目自然失效，无需手工清 */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    /** 当前缓存占用（字节），口径即 sizeOf：宽×4×高。「我的 → 清理缓存」的标签值 */
    fun cacheBytes(): Int = cache.size()

    /** 清空并返回释放的字节数。0 表示本来就空，调用方据此如实提示 */
    fun evictAll(): Int {
        val freed = cache.size()
        cache.evictAll()
        return freed
    }

    /**
     * 渲染 PDF 第 0 页。
     *
     * 入口先判断这是不是 PDF：`.pdf` 后缀，或 ContentResolver 报的类型含 `pdf`。
     * 都不是则当作普通位图解码——这条分支是给「相册导入的图片」留的，
     * 它们的封面就是图片本身，不需要走 PdfRenderer。
     */
    private fun renderFirstPage(ctx: Context, path: String): ImageBitmap? = runCatching {
        val uri = Uri.parse(path)

        val isPdf = path.contains(".pdf", ignoreCase = true) ||
            runCatching {
                ctx.contentResolver.getType(uri)?.contains("pdf", ignoreCase = true) == true
            }.getOrDefault(false)

        if (!isPdf) {
            val stream: InputStream = ctx.contentResolver.openInputStream(uri) ?: return null
            return stream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }

        val pfd: ParcelFileDescriptor = if (path.startsWith("content://")) {
            ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        } else {
            ParcelFileDescriptor.open(File(path), MODE_READ_ONLY)
        }

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount <= 0) return null

                renderer.openPage(0).use { page ->
                    // 只缩不放：目标 640 px 宽，但最多放大到 1.4 倍
                    val scale = min(THUMB_WIDTH / page.width.toFloat(), MAX_UPSCALE)
                    val bitmap = Bitmap.createBitmap(
                        max((page.width * scale).toInt(), 1),
                        max((page.height * scale).toInt(), 1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // 先铺白：PDF 页面本身没有底色，不铺的话透明区域在深色卡片上会发灰
                    bitmap.eraseColor(-1)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        }
    }.getOrElse {
        // 封面是附加信息，渲染失败不该冒泡打断列表；返回 null 让调用方叠提示条即可
        android.util.Log.w(TAG, "封面渲染失败：${it.message}")
        null
    }
}
