package com.example.scoreapp.data

import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet

/**
 * 内置样例曲库。
 *
 * 只保留 3 首做演示（Jackson 2026-09-18 拍板）：月光（唯一带 assetPdf） +
 * 夜曲集（钢琴/浪漫） + 四季（小提琴/巴洛克，封面型缩略图）。
 * 3 首覆盖 3 位作曲家 / 3 类体裁 / 2 种乐器 / 3 个时期，
 * 筛选、分组、A-Z 索引与统计仍有内容可演示；其余样例已移除。
 * 《夜曲集》刻意不属于任何谱单，保住「无归属」分支的可测性。
 */
object SampleLibrary {

    /** 固定基准时间，保证每次启动的排序结果稳定可复现 */
    private const val BASE_DATE = 1_767_225_600_000L
    private const val DAY = 86_400_000L

    val scores: List<Score> = buildList {
        fun add(
            title: String,
            composer: String,
            type: String,
            instrument: String,
            period: String,
            level: String,
            source: String,
            pages: Int,
            seed: Int,
            rows: Int,
            assetPdf: String? = null,
            isAi: Boolean = false,
            keysf: Int? = null,
            keymi: Int? = null,
            cover: (Score.() -> Score)? = null,
        ) {
            val id = (size + 1).toLong()
            var score = Score(
                id = id,
                title = title,
                composer = composer,
                type = type,
                instrument = instrument,
                period = period,
                level = level,
                source = source,
                pages = pages,
                dateAdded = BASE_DATE - id * DAY,
                assetPdf = assetPdf,
                isAi = isAi,
                thumbSeed = seed,
                thumbRows = rows,
                thumbCols = 1,
                keysf = keysf,
                keymi = keymi,
            )
            if (cover != null) score = score.cover()
            add(score)
        }

        // 调性只给「整册一个调」的条目填：
        // - 月光：升 c 小调 = 关系大调 E 大调（4 个升号，小调）
        // - 夜曲集 / 四季：一册里多个调，单值会误导，**刻意留空**
        add(
            "《升c小调第十四钢琴奏鸣曲「月光」》Op.27 No.2", "路德维希·范·贝多芬", "奏鸣曲",
            "钢琴", "古典", "高级", "Henle 原版", 14, 37, 5,
            assetPdf = "moonlight_op27_no2.pdf", keysf = 4, keymi = 1,
        )
        add("《夜曲集》", "弗雷德里克·弗朗索瓦·肖邦", "夜曲", "钢琴", "浪漫", "中级", "Henle 原版", 88, 71, 6)
        // 一张封面型缩略图，用于展示 cover 渲染分支（曲库精简后仍需保留这一支）
        add(
            "《四季》Op.8", "安东尼奥·维瓦尔第", "协奏曲",
            "小提琴", "巴洛克", "高级", "Bärenreiter 版", 78, 419, 6,
        ) {
            copy(
                thumbKind = Score.THUMB_COVER,
                coverTitle = "VIVALDI",
                coverEn = "THE FOUR SEASONS · OP.8",
                coverTColor = "#ffffff",
                coverDeco = "arc",
                coverFooter = true,
                coverBarText = "BÄRENREITER URTEXT",
                coverSub = "巴洛克 · 小提琴协奏",
                coverC1 = "#3a2a1c",
                coverC2 = "#8a6a45",
            )
        }
    }

    /** 谱单：用缩略图种子引用乐谱，避免依赖自增 id */
    val sets: List<ScoreSet> = listOf(
        // 原应用预置的唯一谱单，原样保留
        ScoreSet("独奏会备选曲目", "贝多芬奏鸣曲，含慢板乐章与终曲", listOf(37)),
        // 刻意让《夜曲集》不属于任何谱单，保留「无归属」这条分支的可演示性
        ScoreSet("协奏曲精选", "维瓦尔第《四季》，适合作为比赛与音乐会曲目", listOf(419)),
    )

    /** 编辑表单的可选项 */
    val TYPES = listOf("奏鸣曲", "练习曲", "前奏曲", "赋格", "协奏曲", "组曲", "变奏曲", "夜曲", "圆舞曲", "叙事曲", "即兴曲", "交响曲", "室内乐")
    val INSTRUMENTS = listOf("钢琴", "小提琴", "大提琴", "长笛", "单簧管", "管弦乐", "声乐", "室内乐")
    val PERIODS = listOf("巴洛克", "古典", "浪漫", "印象主义", "现代", "未分类")
    val LEVELS = listOf("初级", "中级", "高级", "演奏级")
    val SOURCES = listOf("Henle 原版", "Peters 版", "Bärenreiter 版", "Breitkopf 版", "Durand 版", "本地导入", "未编目")
}
