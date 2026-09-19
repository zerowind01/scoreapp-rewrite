package com.example.scoreapp.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出与读取文本文件（forScore CSV 专用）。
 *
 * 与 [ShareUtil] 的分工：`ShareUtil` 处理乐谱 PDF 的分享，
 * 这里处理「校对工具」的两个文件动作 —— 读进一份 CSV、导出一份 CSV。
 */
internal object CsvFileUtil {

    private const val AUTHORITY = "com.example.scoreapp.fileprovider"
    private const val FLAG_GRANT_READ = 1
    private const val FLAG_NEW_TASK = 268435456

    /** 导出目录：与乐谱同一个 `filesDir` 下，但单独一层，免得混进存储占用统计 */
    private const val EXPORT_DIR = "csv"

    /**
     * 读一个 `content://` 文本文件。
     *
     * 用 `contentResolver.openInputStream` 而不是 `File(uri.path)`：
     * 后者对 `content://` 根本不成立（分享出来的文件没有真实路径）。
     * 读不到一律 null，调用方据此提示。
     */
    fun readText(ctx: Context, uri: String): String? = try {
        ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 把文本写成一份 CSV 落到应用目录，再用分享面板交出去。
     *
     * 为什么走分享而不是 `ACTION_CREATE_DOCUMENT`：后者需要一个 Activity
     * 回调来接收用户选定的位置，而文件桥持有的是 `applicationContext`，
     * 拿不到那个回调。分享面板里用户同样可以选「保存到文件」落到任意位置，
     * 还额外多了发微信/邮件这些用法。
     *
     * 写进 `filesDir/csv/` 而不是 `filesDir/scores/`：后者是乐谱存储目录，
     * 「我的 → 导入与存储」会统计它的大小，混进 CSV 会让那个数字对不上。
     */
    fun shareText(ctx: Context, text: String, fileName: String): Boolean = try {
        val dir = File(ctx.filesDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, sanitize(fileName))
        file.writeText(text, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra("android.intent.extra.SUBJECT", fileName)
            addFlags(FLAG_GRANT_READ)
            // 与 ShareUtil 同理：只给 flag 时部分接收方拿不到读权限
            clipData = ClipData.newUri(ctx.contentResolver, fileName, uri)
        }
        ctx.startActivity(Intent.createChooser(intent, "导出校对结果").apply { addFlags(FLAG_NEW_TASK) })
        true
    } catch (_: Throwable) {
        false
    }

    /** 文件名里不能出现的字符换成下划线 */
    fun sanitize(name: String): String {
        val base = name.trim().ifBlank { "scores" }
        return Regex("[\\\\/:*?\"<>|]").replace(base, "_")
    }

    /**
     * 把文本放进系统剪贴板（AI「手动粘贴」那条路的入口）。
     *
     * 用 `ClipboardManager.setPrimaryClip` —— API 11+ 即有，
     * 工程 minSdk 远高于此，不需要为旧版本留分支。
     */
    fun copyText(ctx: Context, text: String): Boolean = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ScoreApp", text))
        true
    } catch (_: Throwable) {
        false
    }
}
