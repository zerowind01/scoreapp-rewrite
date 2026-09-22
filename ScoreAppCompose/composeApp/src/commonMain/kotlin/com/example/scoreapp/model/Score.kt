package com.example.scoreapp.model

/**
 * 一份乐谱的完整元数据。
 *
 * 除常规书目字段外，还携带两组「渲染描述」字段，使列表缩略图无需依赖
 * 真实图片文件即可稳定绘制：
 *  - [thumbKind] / [thumbSeed] / [thumbCols] / [thumbRows] 决定程序化缩略图的形态；
 *  - `cover*` 系列字段描述「唱片封面」型缩略图的排版参数。
 */
data class Score(
    val id: Long = 0L,
    val title: String,
    val composer: String,
    val type: String,
    val instrument: String,
    val period: String,
    val level: String,
    val source: String,
    val pages: Int = 0,
    val dateAdded: Long = 0L,
    val filePath: String? = null,
    val assetPdf: String? = null,
    val isAi: Boolean = false,
    val thumbKind: String = THUMB_ENGRAVE,
    val thumbSeed: Int = 0,
    val thumbCols: Int = 1,
    val thumbRows: Int = 5,
    val coverTitle: String? = null,
    val coverEn: String? = null,
    val coverTColor: String? = null,
    val coverDeco: String? = null,
    val coverFooter: Boolean = false,
    val coverBarText: String? = null,
    val coverSub: String? = null,
    val coverC1: String? = null,
    val coverC2: String? = null,
    val coverTx: Float = 15f,
    val coverTy: Float = 24f,
    val coverTSize: Float = 13f,
    val coverTGap: Float = 15f,
    val coverSubX: Float = 15f,
    val coverSubY: Float = 120f,
    /**
     * 调性，映射到 forScore 的 `keysf` / `keymi` 两列：
     * [keysf] 是 −7..7 的升降号个数（负数 = 降号），[keymi] 是 0 大调 / 1 小调。
     *
     * 为什么用两个 Int 而不是一个字符串：forScore 的 CSV 就长这样，
     * 用同一套表示可以让「曲库 ↔ CSV」的互转不需要任何解析；
     * 给用户看的名字由 [keyText] 现算（见 `ScoreKey.label`）。
     */
    val keysf: Int? = null,
    val keymi: Int? = null,
    /**
     * **网盘入库条目的远端地址**；本机谱子一律为 null。
     *
     * 有它才需要「先下载再打开」，没有就是本机文件直接开。
     * 放在最后一位是为了不打乱任何一处按位置传参的构造调用。
     */
    val remotePath: String? = null,
) {
    /** 是否已关联可打开的 PDF 文件 */
    val hasPdf: Boolean
        get() = !filePath.isNullOrBlank() || !assetPdf.isNullOrBlank()

    /** 是否网盘条目。首页靠它把「本机」与「网盘」分成两个标签页 */
    val isNet: Boolean
        get() = !remotePath.isNullOrBlank()

    /** 展示用的文件名（优先本地导入文件） */
    val displayFile: String?
        get() = filePath?.takeIf { it.isNotBlank() } ?: assetPdf?.takeIf { it.isNotBlank() }

    /**
     * 可读调性名（如「降B大调」「A小调」）；没设置调性返回空串。
     *
     * **刻意不在这个文件里引 `ScoreKey`**：model 层保持零依赖，
     * 显示层要名字时自己调 `ScoreKey.label(score.keysf, score.keymi)`。
     * 这里只提供「有没有调性」这个判断，够 UI 决定要不要显示整行用了。
     */
    val hasKey: Boolean
        get() = keysf != null

    companion object {
        const val THUMB_ENGRAVE = "engrave"
        const val THUMB_COVER = "cover"
    }
}
