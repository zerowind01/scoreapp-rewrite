package com.example.scoreapp.domain

import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet

/**
 * 乐谱整理的纯逻辑：日期 / 容量格式化与「谱单归属」反查。
 *
 * 这些函数原先散落在 UI 层（`formatDate` 写在 `DetailScreen.kt` 里且无人调用，
 * 归属反查在详情页里靠手写重复字段凑数），既不便于复用也测不到。
 * 统一收拢到 domain，与 `LibraryQuery` 保持同一层级：纯函数、零依赖、可直接断言。
 */

/**
 * 时间戳格式化为 `yyyy-MM-dd HH:mm`。commonMain 无法用 java.time，故手工拼装。
 *
 * @param offsetSeconds 相对 UTC 的偏移（秒）。默认取设备本地时区在**该时刻**的偏移
 *   （见 [localUtcOffsetSeconds]）——原实现一律按 UTC 解释，在 UTC+8 下会早 8 小时。
 *   显式传入可让测试不依赖运行机器的时区。
 */
fun formatDate(timestamp: Long, offsetSeconds: Int = localUtcOffsetSeconds(timestamp)): String {
    if (timestamp <= 0L) return "—"
    // 先加偏移再拆天：偏移可能把时间推到前一天或后一天
    val totalSeconds = timestamp / 1000 + offsetSeconds
    // 向下取整的除法 / 取模：epoch 之前的时间戳不能让「当天秒数」变成负数
    val days = if (totalSeconds >= 0) totalSeconds / 86400 else (totalSeconds - 86399) / 86400
    val secondsOfDay = totalSeconds - days * 86400
    val (year, month, day) = civilFromDays(days)
    val hour = secondsOfDay / 3600
    val minute = (secondsOfDay % 3600) / 60
    return buildString {
        append(year.toString().padStart(4, '0')); append('-')
        append(month.toString().padStart(2, '0')); append('-')
        append(day.toString().padStart(2, '0')); append(' ')
        append(hour.toString().padStart(2, '0')); append(':')
        append(minute.toString().padStart(2, '0'))
    }
}

/**
 * 由「1970-01-01 起的天数」反解公历年月日（Howard Hinnant 的 civil_from_days 算法）。
 * 纯整数运算，不依赖任何平台日期库。
 */
internal fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

/**
 * 字节数格式化为 `N B` / `N KB` / `N.N MB` 三档。
 *
 * 口径与原型 `fmtBytes` 逐字一致（两边测试互相对照）：
 * 非 &gt;0 一律「—」；不足 1KB 走 B；不足 1MB 走 KB（整数）；
 * 再往上 MB 保留一位小数。原应用在「我的」页写死「128 MB / 24 MB」，
 * 改为真实值后，两端的显示规则必须完全相同才不会各说各话。
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024) return "${bytes / 1024} KB"
    // 与 JS 的 toFixed(1) 同为四舍五入；截断会让 0.87 MB 在两端差成 0.8 / 0.9
    val mb = kotlin.math.round(bytes / 1024.0 / 1024.0 * 10) / 10
    return "$mb MB"
}

/**
 * 把谱单的 [ScoreSet.seeds]（缩略图种子）解析成实际的乐谱列表。
 *
 * 谱单引用的是 `thumbSeed` 而非 id：样例数据重建后 id 会变，种子不会。
 * 原先详情页/谱单页各写一份 `find { it.thumbSeed == seed || it.id == seed }`，
 * 其中 `it.id == seed` 分支永远命中不了（种子是 37 这类小值，id 从 1001 起），
 * 属死代码；这里去掉，并统一到一个实现。
 */
fun setMembers(set: ScoreSet, scores: List<Score>): List<Score> =
    set.seeds.mapNotNull { seed -> scores.find { it.thumbSeed == seed } }

/**
 * 反查某份乐谱属于哪些谱单。
 *
 * 详情页的「分类归属」问的就是这件事——原先该面板渲染的是作曲家/曲目类型/乐器，
 * 与「元数据」面板前 3 行一字不差，抄一遍元数据并没有回答「归属」。
 */
fun setsOfScore(score: Score, sets: List<ScoreSet>, scores: List<Score>): List<ScoreSet> =
    sets.filter { set -> setMembers(set, scores).any { it.id == score.id } }
