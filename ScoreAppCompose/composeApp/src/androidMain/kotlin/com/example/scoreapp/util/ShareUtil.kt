package com.example.scoreapp.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.scoreapp.domain.ShareInfo
import com.example.scoreapp.domain.ShareSummary
import java.io.File

/**
 * 系统分享。
 *
 * 两条分支，取决于这份乐谱是否真的有文件：
 *  - 有 → `ACTION_SEND` + `application/pdf` + `EXTRA_STREAM` 附件，走 FileProvider 授权；
 *  - 无 → `ACTION_SEND` + `text/plain`，只分享曲谱信息。
 *
 * 无文件时**不降级成空附件**：把 `EXTRA_STREAM` 留空会让部分接收方拿到一个坏附件，
 * 不如干脆只发文本，语义清楚。
 */
internal object ShareUtil {

    /** 与 `AndroidManifest.xml` 里 FileProvider 的 authority 必须逐字一致 */
    private const val AUTHORITY = "com.example.scoreapp.fileprovider"

    private const val SUBJECT = "android.intent.extra.SUBJECT"
    private const val TEXT = "android.intent.extra.TEXT"

    private const val FLAG_GRANT_READ = 1
    private const val FLAG_NEW_TASK = 268435456

    /**
     * 分享附件的文件名。
     *
     * 用乐谱标题而不是本地磁盘名——接收方看到的是「《月光》Op.27 No.2.pdf」，
     * 而不是「import_xxx_1737....pdf」。标题里不能出现的字符换成下划线。
     */
    fun shareName(info: ScoreShareInfo): String {
        val base = info.title.trim().ifBlank { "乐谱" }
        return Regex("[\\\\/:*?\"<>|]").replace(base, "_") + ".pdf"
    }

    /**
     * 曲谱信息摘要（纯文本分享用的正文）。
     *
     * 拼接规则已下沉到 `domain/ShareSummary`，因为它是纯字符串加工、
     * 不该为了可测而依赖 Android。这里只做一次字段搬运。
     */
    fun summary(info: ScoreShareInfo): String = ShareSummary.of(
        ShareInfo(
            title = info.title,
            composer = info.composer,
            type = info.type,
            instrument = info.instrument,
            period = info.period,
            level = info.level,
            pages = info.pages,
            source = info.source,
        ),
    )

    /** 构造分享意图。返回 null 表示这份乐谱既没有文件、也不该发纯文本（当前不会发生） */
    fun buildIntent(ctx: Context, info: ScoreShareInfo): Intent? {
        val file = info.filePath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            // 空文件同样算「没有文件」：FileProvider 会抛 IllegalArgumentException
            ?.takeIf { it.exists() && it.length() > 0 }

        if (file == null) {
            return Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(SUBJECT, info.title)
                putExtra(TEXT, summary(info))
            }
        }

        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(SUBJECT, info.title)
            putExtra(TEXT, summary(info))
            addFlags(FLAG_GRANT_READ)
            // ClipData 与 FLAG_GRANT_READ 必须同时给：只给 flag 时，
            // 部分接收方（尤其通过 chooser 转发的）拿不到读权限，附件会读取失败
            clipData = ClipData.newUri(ctx.contentResolver, shareName(info), uri)
        }
    }

    fun share(ctx: Context, info: ScoreShareInfo): ShareOutcome {
        val intent = buildIntent(ctx, info)
        val hadFile = intent?.type == "application/pdf"
        if (intent == null) return ShareOutcome(dispatched = false, hadFile = false)

        return try {
            ctx.startActivity(Intent.createChooser(intent, "分享乐谱").apply { addFlags(FLAG_NEW_TASK) })
            ShareOutcome(dispatched = true, hadFile = hadFile)
        } catch (_: Throwable) {
            // 设备上没有任何能处理该类型的目标应用
            ShareOutcome(dispatched = false, hadFile = hadFile)
        }
    }
}
