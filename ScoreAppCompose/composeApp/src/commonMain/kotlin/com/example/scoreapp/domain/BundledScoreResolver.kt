package com.example.scoreapp.domain

import com.example.scoreapp.model.Score

/**
 * 内置乐谱的路径解析。
 *
 * 反编译产物里这段逻辑长在 `PdfAssets` 里，而 `PdfAssets` 要 `Context`（读 assets、写 filesDir），
 * 于是「拷文件」和「算路径」这两件事被粘在一起，只能上设备才能验证。
 *
 * 这里把**纯判定**部分抽出来：给定「已安装的文件名集合」，算出每份乐谱该指哪个文件。
 * 留在 `commonMain` 的好处是它可以用普通单元测试覆盖——而这段判定恰恰是出过错的地方
 * （`assetPdf` 只是文件名，直接当路径用会解析到进程工作目录）。
 *
 * 平台侧只负责提供那份「已安装映射」，不做任何决策。
 */
object BundledScoreResolver {

    /**
     * 给一份乐谱挑出应该指向的已安装文件名。
     *
     * 两条分支，对应原应用 `pickSource`：
     *  - **精确命中**：`assetPdf` 在已安装列表里 → 用它，这是正常路径；
     *  - **唯一候选兜底**：没命中、但已安装的只有一个 → 用它。原应用留这条是为了
     *    资源被改名（如 `xxx_v2.pdf`）后乐谱仍能用上，代价是多份内置乐谱时会失灵。
     *
     * 其余一律返回 null，由调用方降级——**不在这一层造路径**，
     * 猜出来的路径打开必失败，不如让上层明确走「没有文件」分支。
     */
    fun pickSource(score: Score, installed: Collection<String>): String? {
        val wanted = score.assetPdf?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        installed.firstOrNull { it == wanted }?.let { return it }
        return installed.singleOrNull()
    }

    /**
     * 把「已安装文件名 → 绝对路径」的映射套到整个曲库上。
     *
     * 只补 `filePath` **为空**的乐谱：
     *  - 本地导入的产物已经带绝对路径，内置资源不该盖掉它（用户导入的版本更新）；
     *  - 没有 `assetPdf` 的乐谱本就不依赖内置资源，原样返回。
     *
     * 空映射时**整体原样返回**（连复制一遍都不做）——这是「仓库里没有内置 PDF」的
     * 正常状态，不是错误。
     */
    fun resolveAll(scores: List<Score>, installed: Map<String, String>): List<Score> {
        if (installed.isEmpty()) return scores
        val names = installed.keys
        return scores.map { score ->
            if (!score.filePath.isNullOrBlank()) return@map score
            val name = pickSource(score, names) ?: return@map score
            val path = installed[name] ?: return@map score
            score.copy(filePath = path)
        }
    }
}
