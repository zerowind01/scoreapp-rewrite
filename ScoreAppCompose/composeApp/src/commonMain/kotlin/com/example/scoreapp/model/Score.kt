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
) {
    /** 是否已关联可打开的 PDF 文件 */
    val hasPdf: Boolean
        get() = !filePath.isNullOrBlank() || !assetPdf.isNullOrBlank()

    /** 展示用的文件名（优先本地导入文件） */
    val displayFile: String?
        get() = filePath?.takeIf { it.isNotBlank() } ?: assetPdf?.takeIf { it.isNotBlank() }

    companion object {
        const val THUMB_ENGRAVE = "engrave"
        const val THUMB_COVER = "cover"
    }
}
