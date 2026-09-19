package com.example.scoreapp.domain.csvfix

/**
 * CSV 校对工具的中枢数据结构。
 *
 * 设计要点：**规则只认 [FixRow]**，不认进来的原始形态。
 * 这样「修 forScore 导出的 CSV」与「修 App 自己的曲库」可以共用同一套规则，
 * 两边各自写一个适配器把数据转成 [FixRow] / 转回去即可（见 CsvAdapter / LibraryAdapter）。
 *
 * 字段口径对照 forScore 的 15 列：
 * `Filename, Title, Start Page, End Page, Composers, Genres, Tags, Labels,
 *  Reference, Rating, Difficulty, Minutes, Seconds, keysf, keymi`
 *
 * 注意 [fileName] 是**关联主键**：forScore 导入时按它匹配、且对全小写名有
 * capitalize 行为，所以从解析到导出必须逐字符保真，任何清洗规则都不得碰它。
 */
data class FixRow(
    /** forScore 的 Filename 列，逐字符保真。App 内曲库场景填 score.filePath 的文件名 */
    val fileName: String = "",
    val title: String = "",
    val composer: String = "",
    /** forScore 的 Genres（他的表头叫「乐曲类型」） */
    val genre: String = "",
    /** forScore 的 Tags（他的表头叫「编配」） */
    val tag: String = "",
    /** forScore 的 Labels（他的表头叫「标签」） */
    val label: String = "",
    val reference: String = "",
    /** 合集子条目的起始页；非空说明这是 bookmark 行，标题永不改 */
    val startPage: String = "",
    val endPage: String = "",
    /** -7..7，升降号个数；null = 未设置 */
    val keysf: Int? = null,
    /** 0 大调 / 1 小调；null = 未设置 */
    val keymi: Int? = null,
)

/** 一行的改动建议。对应网页版 `propose()` 的返回值。 */
data class FixProposal(
    val title: String? = null,
    val composer: String? = null,
    val tag: String? = null,
    val label: String? = null,
    val genre: String? = null,
    val reference: String? = null,
    /** 触发了哪些规则，键为字段名（title/comp/tag/genre/label/ref），值为规则 id 列表 */
    val changes: Map<String, List<String>> = emptyMap(),
    /** 只提示、不修改的说明（残缺值、机构名等） */
    val notes: List<String> = emptyList(),
    /** 被整格清空的垃圾值，用于提示里回指 */
    val dropped: List<String> = emptyList(),
    /** AI 给的调性（已解析成编码） */
    val keysf: Int? = null,
    val keymi: Int? = null,
    /** 该调性是覆盖了原有值（UI 显示「覆盖」徽标、默认不勾选） */
    val keyOverride: Boolean = false,
    /** AI 给的调名认不出来时的原文，UI 上如实告诉用户「已忽略」 */
    val keyRejected: String? = null,
    /** 被 AI 建议替换的原有值，键为字段名。UI 显示「留：原值」供用户对比 */
    val overwrite: Map<String, String> = emptyMap(),
) {
    /** 是否产生了任何字段级改动 */
    val hasChange: Boolean
        get() = title != null || composer != null || tag != null ||
            label != null || genre != null || reference != null || keysf != null
}

/** 一个字段的标识：给 AI 用 [key]，内部列代号 [col]，中文列名 [cn] */
data class AiField(
    val key: String,
    val col: String,
    val cn: String,
    /** 非 null 表示这是特殊编码字段（目前只有调性） */
    val kind: String? = null,
) {
    companion object {
        const val KIND_KEY = "key"
    }
}

/** AI 解析出来的单条结果：行号 + 「字段键 → 值」 */
data class AiItem(
    val index: Int,
    val values: Map<String, String>,
)

/** [parseAiReply] 的返回：解析出的条目与错误文案 */
data class AiParseResult(
    val items: List<AiItem> = emptyList(),
    val error: String? = null,
)

/** AI 落地时的开关 */
data class AiApplyOptions(
    /** 只补空字段（false = 已填的也问） */
    val onlyEmpty: Boolean = true,
    /** 跳过合集 bookmark 行 */
    val skipBookmark: Boolean = true,
    /** 已有值也允许 AI 提建议（建议本身默认不采纳，由用户逐条决定） */
    val allowOverwrite: Boolean = false,
)
