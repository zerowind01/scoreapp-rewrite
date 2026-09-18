package com.example.scoreapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 导入能力的实现：相册图片 → A4 PDF，以及把外部 PDF 收进本地目录。
 *
 * 排版口径全部来自原应用：A4 纸面 595 × 842 pt，图片等比缩放后居中；
 * 解码阶段先探边界、再按最大边 1400 px 采样，格式固定 RGB_565
 * （乐谱是黑白线条图，565 足够且省一半内存）。
 *
 * 失败分支的返回文案与原应用逐字一致，是产品语义的一部分，不要改写。
 */
internal object ImportUtil {

    private const val TAG = "ImportUtil"

    /** A4 纸面（PostScript point） */
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    /** 单张图片解码后的最长边上限 */
    private const val MAX_EDGE = 1400

    /** 导入产物统一落在 `filesDir/scores/`，与 `file_paths.xml` 的 `files-path` 对应 */
    private const val ASSET_DIR = "scores"

    /** `ParcelFileDescriptor.MODE_READ_ONLY`。源码里是裸字面量 268435456，这里保留常数名以免误读 */
    private const val MODE_READ_ONLY = 268435456

    /**
     * 把多张相册图片合成为一份 PDF。
     *
     * 单张图片失败不中断整体：跳过并计数，最后在 [ImportOutcome.error] 里告知跳过了几张。
     * 全部失败、产物无法解析这两种情况才算彻底失败，并删除半成品文件，不留垃圾。
     */
    fun imagesToPdf(context: Context, uris: List<String>): ImportOutcome {
        if (uris.isEmpty()) {
            return ImportOutcome(null, 0, 0, "没有选择任何图片")
        }

        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val outFile = File(dir, "import_${System.currentTimeMillis()}.pdf")

        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        try {
            var written = 0
            var skipped = 0
            // 第一张解码失败的原因，用于全灭时给出一句可行动的提示
            var firstFailureCause: String? = null

            uris.forEach { raw ->
                val uri = Uri.parse(raw)
                val outcome = decodeSampled(context, uri, MAX_EDGE)
                val bitmap = outcome.bitmap
                if (bitmap == null) {
                    skipped++
                    if (firstFailureCause == null) firstFailureCause = outcome.reason
                    Log.w(TAG, "图片解码失败，跳过：$uri（${outcome.reason}）")
                    return@forEach
                }
                try {
                    // 等比缩放到刚好放进 A4，再算居中偏移
                    val scale = min(PAGE_W / bitmap.width.toFloat(), PAGE_H / bitmap.height.toFloat())
                    val drawW = max((bitmap.width * scale).toInt(), 1)
                    val drawH = max((bitmap.height * scale).toInt(), 1)
                    val left = (PAGE_W - drawW) / 2f
                    val top = (PAGE_H - drawH) / 2f

                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, written + 1).create(),
                    )
                    page.canvas.drawBitmap(
                        bitmap,
                        null as Rect?,
                        RectF(left, top, left + drawW, top + drawH),
                        paint,
                    )
                    document.finishPage(page)
                    written++
                } catch (t: Throwable) {
                    skipped++
                    Log.e(TAG, "写入第 ${written + 1} 页失败", t)
                } finally {
                    bitmap.recycle()
                }
            }

            if (written == 0) {
                outFile.delete()
                // 全灭是最难排查的一类：把第一张的原因带进返回文案。
                // 真机上报「所有照片都读不出」时，用户看到的这句话
                // 加上 logcat 里 decodeSampled 打的明细，足以区分
                // 「权限/流打不开」「格式不支持」「尺寸读不出」三种情况。
                val hint = firstFailureCause?.let { "（$it）" } ?: ""
                return ImportOutcome(null, 0, uris.size, "所选 ${uris.size} 张图片都无法读取$hint")
            }

            FileOutputStream(outFile).use { document.writeTo(it) }
            document.close()

            val path = outFile.absolutePath
            val pages = pdfPageCount(path)
            if (pages <= 0) {
                // 写出来了却解析不了，说明产物是坏的——宁可不要，也不能入库一个打不开的文件
                Log.e(TAG, "生成的 PDF 无法被解析，已丢弃")
                outFile.delete()
                return ImportOutcome(null, 0, uris.size, "生成的 PDF 无法解析")
            }

            return ImportOutcome(
                path = path,
                pages = pages,
                total = uris.size,
                error = if (skipped > 0) "有 $skipped 张图片无法读取，已跳过" else null,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "PDF 写入失败", t)
            outFile.delete()
            return ImportOutcome(null, 0, uris.size, "PDF 生成失败：${t.message ?: "未知错误"}")
        } finally {
            runCatching { document.close() }
        }
    }

    /**
     * 取选中 PDF 的显示名当标题。
     *
     * `_display_name` 在部分 provider 上查不到，于是退到 `lastPathSegment` 的最后一段，
     * 再退到兜底文案。最后统一剥掉 `.pdf` 后缀（忽略大小写）——否则标题里会拖着一串后缀。
     */
    fun pdfTitle(context: Context, uri: Uri): String {
        val fromCursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex("_display_name")
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

        val name = fromCursor
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "导入乐谱"

        return if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name
    }

    /**
     * 把选中的 PDF 拷进 `filesDir/scores/`。
     *
     * 文件名用标题清洗后截断到 40 字符再加时间戳：标题可能带 `/`、`:` 等非法字符，
     * 直接拼进路径会写出目录穿越或失败。保留汉字、字母数字、`.`、`-`，其余换下划线。
     */
    fun copyPdfToLocal(context: Context, uri: Uri, title: String): String? {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val safe = Regex("[^\\w\\u4e00-\\u9fa5.-]").replace(title, "_").take(40)
        val outFile = File(dir, "import_${safe}_${System.currentTimeMillis()}.pdf")

        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(outFile).use { target -> source.copyTo(target) }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "拷贝 PDF 失败：${e.message}")
            null
        }
    }

    /** 探测页数。任何异常都归为 0，调用方据此判断「这份文件打不开」 */
    fun pdfPageCount(path: String): Int = runCatching {
        if (path.startsWith("content://")) return 0
        ParcelFileDescriptor.open(File(path), MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    }.getOrDefault(0)

    fun deleteLocal(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /**
     * 解码结果：失败时带上原因。
     *
     * 单看 `null` 无法区分「流打不开」与「格式不支持」，
     * 而这两种情况的排查方向完全不同，因此把原因一并带出来。
     */
    private class DecodeOutcome(val bitmap: Bitmap?, val reason: String?)

    /**
     * 按最大边采样解码。
     *
     * 外层三轮降低目标分辨率（extra 取 1 / 2 / 4）：超大图在第一次尝试就可能 OOM，
     * 与其直接失败，不如换更狠的采样比再来一次。
     * 前端 `BitmapFactory` 全流程失败时，用 `ImageDecoder`（API 28+）兜底——
     * 它对 HEIC / AVIF 等新格式支持更好，是原应用为相册图片准备的第二道网。
     */
    private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): DecodeOutcome {
        // 记下最后一次失败原因。真机上报「所有照片都读不出」时，
        // 没有这一句就只能靠猜——是打不开流、还是解不出尺寸、还是配置不支持？
        var lastError: String? = null
        for (extra in intArrayOf(1, 2, 4)) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsStream = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: Exception) {
                    // 权限被拒 / provider 不可达都会在这里
                    lastError = "打不开输入流：${e::class.simpleName}"
                    Log.w(TAG, "$lastError ${e.message} uri=$uri")
                    null
                }
                val realStream = boundsStream ?: return DecodeOutcome(null, lastError)
                realStream.use { BitmapFactory.decodeStream(it, null, options) }

                val width = options.outWidth
                val height = options.outHeight
                if (width <= 0 || height <= 0) {
                    lastError = "读不出图片尺寸"
                    Log.w(TAG, "$lastError（outWidth=$width outHeight=$height）uri=$uri")
                    continue
                }

                options.inSampleSize = computeSample(width, height, maxEdge) * extra
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565

                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                if (decoded != null) return DecodeOutcome(decoded, null)
                lastError = "解码返回空（${width}×${height}, sample=${options.inSampleSize}）"
                Log.w(TAG, "$lastError uri=$uri")
            } catch (_: OutOfMemoryError) {
                lastError = "内存不足，已降级重试仍失败"
                Log.w(TAG, "解码 OOM，降低分辨率重试 extra=$extra")
            } catch (t: Throwable) {
                lastError = "${t::class.simpleName}: ${t.message}"
                Log.w(TAG, "BitmapFactory 解码失败：${t.message}")
            }
        }
        val fallback = fallbackImageDecoder(context, uri, maxEdge)
        if (fallback == null) Log.e(TAG, "该图片彻底无法解码，最后一次原因：$lastError uri=$uri")
        return DecodeOutcome(fallback, if (fallback == null) lastError else null)
    }

    private fun fallbackImageDecoder(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size: Size = info.size
                // 只缩小不放大：原图比目标还小时保持原样，避免插值糊化
                val ratio = min(maxEdge / max(size.width, size.height).toFloat(), 1f)
                decoder.setTargetSize(
                    max((size.width * ratio).toInt(), 1),
                    max((size.height * ratio).toInt(), 1),
                )
            }
        }.onFailure { Log.w(TAG, "ImageDecoder 兜底也失败：${it.message}") }.getOrNull()
    }

    /** 求最小的 2 的幂采样比，使宽高都不超过 [maxEdge] */
    private fun computeSample(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (width / sample > maxEdge || height / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }
}
