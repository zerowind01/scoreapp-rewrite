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
        "a#" to "c#",
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

        // ---- 第一步：判大小调 ----
        //
        // 两个来源：中文/英文的「小调」字样（上面已判），以及缩写尾缀 `m`。
        // 尾缀 `m` 既不是音名也不是记号，摘掉它后面的字符定位才干净。
        //
        // **只有真的靠尾缀 m 判成小调时才摘最后一个字符**。中文那一路（`d小调`）
        // 的 core 只剩一个 `d`，若照着 `minor==true` 无条件 dropLast，`d` 就没了，
        // 整个调直接解析失败 —— 这是必须分开记录 suffixMinor 的原因。
        val suffixMinor = !minor && core.length > 1 &&
            Regex("m$", RegexOption.IGNORE_CASE).containsMatchIn(core)
        if (suffixMinor) minor = true
        val body = if (suffixMinor) core.dropLast(1) else core
        if (body.isEmpty()) return null

        // ---- 第二步：找出音名，并判定升降记号 ----
        //
        // `b` 在本项目里身兼两职（降号 / 音名 B），所以含 b 的串要按**记法**分辨：
        //   记法 A（前缀）：`b` 是降号，紧跟着一个音名字母 —— `be`(降E) `bb`(降B) `bf`(降F)
        //   记法 B（后缀）：音名字母在前，`b` 或 `#` 在后 —— `eb`(降E) `c#`(升C) `ab`(降A)
        // 判据只有一条：**第一个字符是 `b` 且第二个字符也是音名字母**，则按记法 A。
        // 两个分支取音名的位置不同：
        //   记法 A 取**第二个**字符（`be` → e）；
        //   记法 B 取**第一个**音名字母（`eb` → e）。
        // 早期版本在记法 B 里取「最后一个音名字母」，于是 `eb` 取到记号那个 b，
        // 读成「降B」—— `eb`(=降E, -3) 与 `bb`(=降B, -2) 只差一个号，
        // 单看数字不容易发现，必须靠往返测试才暴露。
        val asciiFlatPrefix =
            body.length >= 2 && body[0].lowercaseChar() == 'b' &&
                body[1].lowercaseChar() in 'a'..'g'

        val letter: Char
        val flatSign: Boolean
        val sharpSign: Boolean
        if (asciiFlatPrefix) {
            letter = body[1].lowercaseChar()
            flatSign = true
            sharpSign = body.length > 2 && body[2] == '#'
        } else {
            val li = body.indexOfFirst { it in 'A'..'G' || it in 'a'..'g' }
            if (li < 0) return null
            letter = body[li].lowercaseChar()
            flatSign = (li + 1 < body.length && body[li + 1].lowercaseChar() == 'b')
            sharpSign = (li + 1 < body.length && body[li + 1] == '#')
        }

        // 升号也可能写成中文「升C」或符号 `C♯`；降号同理
        val sharp = sharpSign || core.contains("升") || core.contains("♯")
        val flat = flatSign || core.contains("降") || core.contains("♭")

        val root = when {
            sharp -> "$letter#"
            flat -> "${letter}b"
            else -> "$letter"
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
     * 编码 → 缩写写法（`c#m` / `bB` / `F#` / `am`）。
     *
     * 与 [label] 的「降E大调」并存，两者服务于不同场合：
     * [label] 给**用户读**（详情页那一行「调性：降E大调」），
     * 本函数给**AI 与紧凑界面** —— Jackson 定的写法照标准记谱惯例：
     * 升号在字母后（`C#`、`c#m`）、降号在字母前（`bB`、`bbm`）、
     * 大调字母大写（`C` / `bB` / `F#`）、小调字母小写 + 末尾 `m`（`am` / `c#m` / `bbm`）。
     *
     * 用缩写而不是中文名的实际好处：它能被 [parse] 原样吃回来，
     * 于是「AI 写缩写 → 解析成 keysf/keymi」这条链路是**无损闭环**的，
     * 中间不需要任何中文→编码的翻译表。
     */
    fun shortLabel(keysf: Int?, keymi: Int?): String {
        if (keysf == null) return ""
        val mi = keymi ?: 0
        val root = shortRoot(keysf, mi) ?: return ""
        // 小调：根音小写 + 尾缀 m（`am` / `c#m` / `bbm`）
        if (mi == 1) return "${root}m"
        // 大调：音名字母大写，但**前缀降号保持小写** —— 照 Jackson 定的 `bB`。
        // 无脑 `uppercase()` 会得到 `BB`/`BE`/`BC`，虽然 [parse] 照样能吃回来
        // （它认的是音名字母），但那不是他要的写法，给 AI 看也更容易被读错。
        // 长度必须 ≥ 2：单独一个 `b` 是 **B 大调**，要变成 `B` 而不是保持小写。
        return if (root.length >= 2 && root[0] == 'b') {
            "b" + root.substring(1).uppercase()
        } else {
            root.uppercase()
        }
    }

    /**
     * 从一行里读缩写调性，供 AI 输入与紧凑显示。
     *
     * 与 [textOf] 的关系同 [shortLabel] 与 [label]：一个紧凑、一个可读。
     */
    fun shortOf(row: FixRow, columns: ColumnMap): String {
        val sf = row.keysf ?: return ""
        return shortLabel(sf, row.keymi)
    }

    /**
     * (升降号数, 大小调) → 缩写根音。表里全小写，大调整体 [String.uppercase] 即可。
     *
     * 之所以敢整体大写：降号写作**前缀** `b`（`bb`、`be`），大写后是 `BB` / `BE`，
     * 看着别扭但**语义不变** —— [parse] 只认「音名字母」，前缀那个 `B` 会被丢掉。
     * 反过来用「只大写音名字母」的写法会让 `bc`（降C）变成 `Bc`，
     * 而 `Bc` 里的 `B` 又会被 `[A-Ga-g]` 当成音名抓走，反而更糟。
     */
    private fun shortRoot(keysf: Int, keymi: Int): String? {
        val i = keysf + 7
        if (i !in 0..14) return null
        return if (keymi == 1) MINOR_SHORT[i] else MAJOR_SHORT[i]
    }

    /**
     * keysf + 7 为下标：0 = 降C/降A … 7 = C/A … 14 = 升C/升A。全小写存储。
     *
     * **降号一律用前缀写法**（`bc` 而不是 `cb`），与 Jackson 定的 `bB` 一致；
     * 升号则固定后缀（`f#`）。两种记号朝向不同是记谱惯例，[parse] 两种都吃。
     *
     * 这张表必须与 [NAMES] 的 `*,0` 行逐项对齐（C♭大调 / G♭大调 / … / C♯大调）。
     * 早期版本误按小调顺序抄了一遍，于是 `keysf=-1` 被标成 F♭（实为 **F 大调**）、
     * `keysf=-4` 被标成 B♭（实为 **A♭大调**）—— 单看某个调名对不出来，
     * 只有「编码→缩写→解析→编码」的往返测试才暴露。
     */
    private val MAJOR_SHORT: List<String> = listOf(
        "bc", "bg", "bd", "ba", "be", "bb", "f", "c", "g", "d", "a", "e", "b", "f#", "c#",
    )

    /**
     * 小调根音。[NAMES] 的 `*,1` 行：降A小调 / 降E小调 / 降B小调 / F小调 / C小调 …
     * 注意**不是**同名大调：A 小调是 `a`（不是 `c`），只是两者调号相同。
     */
    private val MINOR_SHORT: List<String> = listOf(
        "ba", "be", "bb", "f", "c", "g", "d", "a", "e", "b", "f#", "c#", "g#", "d#", "a#",
    )

    /** 表长（测试断言用） */
    val shortCount: Int get() = MAJOR_SHORT.size

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
