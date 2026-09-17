package com.example.scoreapp.domain

/**
 * 分享正文（纯文本）的拼接规则。
 *
 * 单独放在 domain 层而不是跟着 `ShareUtil` 留在 androidMain，有两个原因：
 *  1. 它**没有任何平台依赖**——只是字符串加工，却决定了用户在微信里看到什么；
 *  2. 放在这里就能被 `commonTest` 直接覆盖，不必为了测一个拼串而跑设备。
 *
 * 规则逐条对齐反编译产物：
 *  - 标题后必换行；
 *  - 作曲家为空则整行不出现（不是留一个空行）；
 *  - 类型 / 乐器 / 时期 / 难度四项过滤掉空串与 `—` 后用 ` · ` 连接，
 *    全被过滤掉时不输出该行，避免出现悬空的分隔符或孤零零一个破折号；
 *  - 页数为 0 时不输出页数行；
 *  - 来源为空或 `—` 时不输出来源行；
 *  - 固定以署名结尾，整体裁掉尾部空白。
 */
object ShareSummary {

    private const val PLACEHOLDER = "—"

    fun of(info: ShareInfo): String = buildString {
        append(info.title)
        append('\n')

        if (info.composer.isNotBlank()) {
            append(info.composer)
            append('\n')
        }

        val meta = listOf(info.type, info.instrument, info.period, info.level)
            .filter { it.isNotBlank() && it != PLACEHOLDER }
        if (meta.isNotEmpty()) {
            append(meta.joinToString(" · "))
            append('\n')
        }

        if (info.pages > 0) {
            append("共 ${info.pages} 页")
            append('\n')
        }

        if (info.source.isNotBlank() && info.source != PLACEHOLDER) {
            append("来源：${info.source}")
            append('\n')
        }

        append("—— 由「乐谱管理」分享")
    }.trimEnd()
}

/**
 * 生成分享摘要所需的字段。
 *
 * 与 `ScoreShareInfo` 字段相同但**不含 filePath**：这个对象只服务于「正文怎么写」，
 * 而有没有附件是另一件事。收窄到这里，摘要逻辑就不会被文件状态的细节污染。
 */
class ShareInfo(
    val title: String,
    val composer: String,
    val type: String,
    val instrument: String,
    val period: String,
    val level: String,
    val pages: Int,
    val source: String,
)
