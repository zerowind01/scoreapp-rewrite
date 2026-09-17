package com.example.scoreapp.domain

/**
 * 阅读器顶栏的文案规则。
 *
 * 从 `ui/components/PdfViewerScreen.kt` 里抽出来，原因是这段「什么状态说什么话」的
 * 判断有明确的正确性标准，值得被测试锁住；而它本身是纯函数，不该为了可测而依赖 Android。
 *
 * 三种相互矛盾的表现都在原应用里出现过，这个文件就是为了让它们不再发生：
 *  1. 副标题与页数徽标说同一件事（`共 N 页` + `N 页`）；
 *  2. 空文档的副标题回落成「读取中…」，与正文的「没有任何页面」冲突；
 *  3. 未就绪时也显示徽标，出现「0 页」这种没有信息量的显示。
 */
object ReaderBarText {

    /** 读取中 */
    const val LOADING = "读取中…"
    /** 打不开 */
    const val FAILED = "无法打开"
    /** 打开了但没有页 */
    const val EMPTY = "没有页面"

    /**
     * 顶栏副标题。
     *
     * @param pageCount 已就绪时的页数；未就绪传 null，空文档传 0
     * @param failed 是否打开失败
     */
    fun subtitle(pageCount: Int?, failed: Boolean = false): String = when {
        failed -> FAILED
        pageCount == null -> LOADING
        pageCount == 0 -> EMPTY
        else -> "共 $pageCount 页"
    }

    /**
     * 页数徽标文案。页数未知或为 0 时返回 null，表示不显示徽标。
     *
     * 与副标题的分工：副标题是**叙述**（「共 12 页」），徽标是**数字**（「12 页」）。
     * 两者刻意不完全相同——原应用写成一模一样的文本，等于把同一句话印了两遍。
     */
    fun badgeText(pageCount: Int?): String? =
        if (pageCount != null && pageCount > 0) "$pageCount 页" else null
}
