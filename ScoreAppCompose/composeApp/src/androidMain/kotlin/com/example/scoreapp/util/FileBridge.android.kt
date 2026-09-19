package com.example.scoreapp.util

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.example.scoreapp.model.Score

/**
 * 文件能力桥的 Android 实现。
 *
 * 它自己不做事，只做两件接线工作：
 *  1. 决定「谁去执行」——导入交给 [ImportUtil]，分享交给 [ShareUtil]；
 *  2. 把 Android 的 `Uri` / `Context` 挡在接口之外，
 *     使 `commonMain` 只看到字符串与纯数据结果。
 *
 * 保持这一层薄是有意的：真正的业务规则都在上面两个 object 里，
 * 它们不依赖 Compose，可以被普通单元测试直接覆盖。
 */
internal class AndroidFileBridge(private val context: Context) : FileBridge {

    override fun imagesToPdf(uris: List<String>): ImportOutcome =
        ImportUtil.imagesToPdf(context, uris)

    override fun pdfTitle(uri: String): String =
        ImportUtil.pdfTitle(context, android.net.Uri.parse(uri))

    override fun copyPdfToLocal(uri: String, title: String): String? =
        ImportUtil.copyPdfToLocal(context, android.net.Uri.parse(uri), title)

    override fun readTextFile(uri: String): String? = CsvFileUtil.readText(context, uri)

    override fun shareText(text: String, fileName: String): Boolean =
        CsvFileUtil.shareText(context, text, fileName)

    override fun copyText(text: String): Boolean = CsvFileUtil.copyText(context, text)

    override fun pdfPageCount(path: String): Int = ImportUtil.pdfPageCount(path)

    override fun deleteLocal(path: String) = ImportUtil.deleteLocal(path)

    override fun share(info: ScoreShareInfo): ShareOutcome = ShareUtil.share(context, info)

    override fun installBundledScores(scores: List<Score>): List<Score> =
        PdfAssets.resolveAll(scores, PdfAssets.installMissing(context))

    override fun storageUsage(): StorageUsage {
        // 与 ImportUtil 的落盘目录同源（filesDir/scores/），遍历求和
        val dir = java.io.File(context.filesDir, "scores")
        var bytes = 0L
        var files = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                files++
                bytes += f.length()
            }
        }
        return StorageUsage(bytes, files)
    }

    override fun coverCacheBytes(): Long = CoverRenderer.cacheBytes().toLong()

    override fun clearCoverCache(): Long = CoverRenderer.evictAll().toLong()
}

/**
 * 需要一个 `Context` 才能构造，因此不能像 `nowMillis()` 那样无参。
 * 由 `MainActivity` 在 `setContent` 之前注入到 `ScoreAppState.bridge`。
 *
 * 这里用 `applicationContext`：文件操作与分享都跑在 Activity 生命周期之外
 * （导入在协程里做、分享意图会在系统侧流转），持有 Activity 引用会泄漏。
 */
private var appContext: Context? = null

/** 在 `MainActivity.onCreate` 里调用一次 */
fun installFileBridge(context: Context) {
    appContext = context.applicationContext
}

actual fun createFileBridge(): FileBridge {
    val ctx = appContext
        ?: error("FileBridge 未初始化：请在 MainActivity.onCreate 里调用 installFileBridge(context)")
    return AndroidFileBridge(ctx)
}

actual fun loadCoverBitmap(path: String): ImageBitmap? {
    val ctx = appContext ?: return null
    return CoverRenderer.load(ctx, path)
}

actual val supportsCoverRender: Boolean = true
