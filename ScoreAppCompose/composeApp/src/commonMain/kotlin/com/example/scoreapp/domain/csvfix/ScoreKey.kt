package com.example.scoreapp.domain.csvfix

/**
 * 乐谱调性：映射到 forScore 的 `keysf` / `keymi` 两列。
 *
 * 官方编码（forscore.co 的元数据页）：`keysf` = −7..7 的**升降号个数**
 * （负数 = 降号），`keymi` = 0 大调 / 1 小调。两者合起来唯一确定一个调，共 30 种组合。
 *
 * 解析调名时最容易错的一步是**小调**：小调的调号等于它上方小三度那个大调
 * （关系大调）的调号 —— A 小调是 C 大调（0 个），不是 A 大调（3 个）。
 * 历史实现踩过这个坑，测试里专门锁住了。
 */
object ScoreKey {

    /** `"sf,mi"` → 调名的完整表，30 项 */
    private val NAMES: Map<String, String> = mapOf(
        "-7,0" to "C♭大调", "-7,1" to "A♭小调",
        "-6,0" to "G♭大调", "-6,1" to "E♭小调",
        "-5,0" to "D♭大调", "-5,1" to "B♭小调",
        "-4,0" to "A♭大调", "-4,1" to "F小调",
        "-3,0" to "E♭大调", "-3,1" to "C小调",
        "-2,0" to "B♭大调", "-2,1" to "G小调",
        "-1,0" to "F大调", "-1,1" to "D小调",
        "0,0" to "C大调", "0,1" to "A小调",
        "1,0" to "G大调", "1,1" to "E小调",
        "2,0" to "D大调", "2,1" to "B小调",
        "3,0" to "A大调", "3,1" to "F♯小调",
        "4,0" to "E大调", "4,1" to "C♯小调",
        "5,0" to "B大调", "5,1" to "G♯小调",
        "6,0" to "F♯大调", "6,1" to "D♯小调",
        "7,0" to "C♯大调", "7,1" to "A♯小调",
    )

    /** 调名主体（如 `eb`）→ 大调的调号数。表里只存大调，小调走关系小调换算 */
    private val MAJOR_SIGNATURE: Map<String, Int> = mapOf(
        "c" to 0, "g" to 1, "d" to 2, "a" to 3, "e" to 4, "b" to 5, "f#" to 6, "c#" to 7,
        "f" to -1, "bb" to -2, "eb" to -3, "ab" to -4, "db" to -5, "gb" to -6, "cb" to -7,
    )

    /**
     * 小调根音 → 其关系大调根音（上方小三度）。
     *
     * 调号规则：小调的调号 = 关系大调的调号。
     * 例：a → c（0）；d → f（−1）；eb → gb（−6）。
     */
    private val RELATIVE_MAJOR: Map<String, String> = mapOf(
        "a" to "c", "e" to "g", "b" to "d", "f#" to "a", "c#" to "e", "g#" to "b", "d#" to "f#",
        "d" to "f", "g" to "bb", "c" to "eb", "f" to "ab", "bb" to "db", "eb" to "gb", "ab" to "cb",
    )

    /** 表长（测试断言用） */
    val signatureCount: Int get() = NAMES.size

    /**
     * 编码 → 可读调名。值不合法或缺失返回空串。
     *
     * [mi] 为 null 时按大调处理 —— keysf 有值而 keymi 空着是常见情况，
     * 此时把大调当默认比拒绝显示更有用。
     */
    fun label(keysf: Int?, keymi: Int?): String {
        if (keysf == null) return ""
        val key = "$keysf,${keymi ?: 0}"
        return NAMES[key] ?: ""
    }

    /**
     * 把「降B大调」「Eb major」「Am」「d小调」这类写法解析成 (keysf, keymi)。
     * **认不出来一律返回 null**，不猜测、不硬凑 —— 宁可让用户看到「已忽略」。
     */
    fun parse(name: String?): KeySignature? {
        val t = name?.trim().orEmpty()
        if (t.isEmpty()) return null

        var minor = Regex("小调|minor|\\bmin\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)
        val major = Regex("大调|major", RegexOption.IGNORE_CASE).containsMatchIn(t)

        // 抽调名主体：去掉「大调/小调/major/minor/调」等修饰
        val core = t
            .replace(Regex("大调|小调|major|minor|调", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\s()（）]"), "")
            .trim()
        if (core.isEmpty()) return null

        // 结尾的 m 表示小调（Am、Em、c#m、bbm 这类），必须先把后缀认出来再取根音，
        // 否则「Am」会被当成 A 大调。长度 > 1 是为了不把单字母（如「b」）误判。
        if (!minor && Regex("m$", RegexOption.IGNORE_CASE).containsMatchIn(core) && core.length > 1) {
            minor = true
        }

        // 降号有三种写法：中文「降E」、符号「E♭」、字母前置「bE」或后置「Bb」
        var flat = core.contains("降") || core.contains("♭")
        var sharp = core.contains("升") || core.contains("♯")
        val letter = Regex("[A-Ga-g]").find(core)?.value ?: return null
        val lower = letter.lowercase()

        // 归一化成 MAJOR_SIGNATURE / RELATIVE_MAJOR 用的键：降号一律「字母 + b」后缀。
        // 于是「降E」「E♭」「bE」「Eb」四种写法都落到 'eb'。
        // 注意正则要避开字母本身的大小写：用 RegexOption.IGNORE_CASE 统一处理。
        val preFlat = Regex("^b(?=$letter)", RegexOption.IGNORE_CASE).containsMatchIn(core) // bE
        val postFlat = Regex("${letter}b", RegexOption.IGNORE_CASE).containsMatchIn(core) // Eb
        if (!flat && (preFlat || postFlat)) flat = true
        if (!sharp && Regex("${letter}#", RegexOption.IGNORE_CASE).containsMatchIn(core)) sharp = true

        val root = when {
            sharp -> "${lower}#"
            flat -> "${lower}b"
            else -> "$lower"
        }

        return if (minor) {
            // 小调：转成关系大调再查号数
            val majorRoot = RELATIVE_MAJOR[root] ?: return null
            val sf = MAJOR_SIGNATURE[majorRoot] ?: return null
            KeySignature(sf, 1)
        } else {
            val sf = MAJOR_SIGNATURE[root] ?: return null
            KeySignature(sf, 0)
        }
    }

    /**
     * 从一行里读出调性（用于显示与 AI 输入）。
     * keysf 为空即视为「没有调性」；keymi 为空按大调。
     */
    fun textOf(row: FixRow, columns: ColumnMap): String {
        if (columns.keysf == null || columns.keymi == null) return ""
        val sf = row.keysf ?: return ""
        return label(sf, row.keymi)
    }
}

/** 调号：[sharpsFlats] 为 −7..7 的升降号个数（负 = 降号），[isMinor] 是否小调 */
data class KeySignature(val sharpsFlats: Int, val isMinor: Int) {
    /** 0 大调 / 1 小调，直接可用于 forScore 的 keymi 列 */
    val keymi: Int get() = isMinor
    /** 直接可用于 forScore 的 keysf 列 */
    val keysf: Int get() = sharpsFlats
}
