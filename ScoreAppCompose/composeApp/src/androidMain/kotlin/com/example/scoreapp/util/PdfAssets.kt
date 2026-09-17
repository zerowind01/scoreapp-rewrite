package com.example.scoreapp.util

import android.content.Context
import android.util.Log
import com.example.scoreapp.domain.BundledScoreResolver
import com.example.scoreapp.model.Score
import java.io.File

/**
 * 随包分发的内置乐谱 PDF 的**安装**。
 *
 * 原应用的 `data/PdfAssets.kt`：启动时把 `assets/scores/` 下的 PDF 拷到
 * `filesDir/scores/`，再把乐谱的 `assetPdf` 换成那条绝对路径。
 * 不这么做的话 `assetPdf` 只是**一个文件名**，`File(path)` 会以进程工作目录为基准解析，
 * 在设备上必然不存在——「打开乐谱」和「封面渲染」就都成了死路。
 *
 * 拆成三步，职责分明：
 *  1. [installMissing] 拷文件，返回 `文件名 → 绝对路径` 的映射；
 *  2. [resolveAll] 把映射套到曲库上，产出「已解析」的乐谱列表；
 *  3. [isInstalled] 供启动期做幂等判断，避免每次冷启动都重写一遍文件。
 *
 * 拷完的文件是真实存在的，因此 [resolveAll] 之后 `filePath` 指哪打哪，
 * 分享与阅读器不需要再各自猜路径。
 */
internal object PdfAssets {

    private const val TAG = "PdfAssets"

    /** 资源目录名。与 `ImportUtil.ASSET_DIR` 是同一个目录，一份内置一份导入，共存不冲突。 */
    const val ASSET_DIR = "scores"

    /**
     * 把 assets 里缺的文件补齐到 `filesDir/scores/`。
     *
     * 返回 `文件名 → 绝对路径`。已存在且长度大于 0 的文件不会重写——
     * 这与原应用一致：安装是幂等的，重复启动不应反复覆盖用户可能已替换过的文件。
     *
     * **没有任何内置 PDF 时返回空映射而不是抛异常。** 本仓库刻意不把第三方版权乐谱
     * 纳入版本控制（见仓库根 `.gitignore`），缺失是正常状态：此时内置乐谱退化为
     * 「没有文件」，封面走程序化绘制，页面不崩。这条降级路径有测试锁住。
     */
    fun installMissing(context: Context): Map<String, String> {
        val dir = File(context.filesDir, ASSET_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建目录：${dir.absolutePath}")
            return emptyMap()
        }

        val names = try {
            context.assets.list(ASSET_DIR)?.toList().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "列出 assets/$ASSET_DIR 失败：${t.message}")
            emptyList()
        }
        if (names.isEmpty()) {
            Log.i(TAG, "assets/$ASSET_DIR 为空，跳过内置乐谱安装（封面将走程序化绘制）")
            return emptyMap()
        }

        val installed = LinkedHashMap<String, String>()
        for (name in names) {
            if (!name.endsWith(".pdf", ignoreCase = true)) continue
            val target = File(dir, name)
            if (target.exists() && target.length() > 0L) {
                installed[name] = target.absolutePath
                continue
            }
            try {
                val source = context.assets.open("$ASSET_DIR/$name")
                source.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() > 0L) {
                    installed[name] = target.absolutePath
                } else {
                    Log.w(TAG, "拷出的文件为空：$name")
                    target.delete()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "安装 assets PDF 失败：$name", t)
                target.delete()
            }
        }
        Log.i(TAG, "内置乐谱安装完成，共 ${installed.size} 份")
        return installed
    }

    /**
     * 把 `assetPdf` 解析成已安装的绝对路径。
     *
     * 规则本身住在 `commonMain` 的 [BundledScoreResolver.pickSource]——
     * 那段判定可以用普通单元测试覆盖，这里只做转发，不重复实现一遍。
     */
    fun pickSource(score: Score, installed: Collection<String>): String? =
        BundledScoreResolver.pickSource(score, installed)

    /**
     * 把曲库整体解析一遍：凡是带 `assetPdf` 的乐谱，都把 `filePath` 补成绝对路径。
     *
     * 已带 `filePath` 的**不动**——本地导入的产物必须胜过内置资源，
     * 否则用户导入过的乐谱会被内置版本盖掉。
     */
    fun resolveAll(scores: List<Score>, installed: Map<String, String>): List<Score> =
        BundledScoreResolver.resolveAll(scores, installed)
}
