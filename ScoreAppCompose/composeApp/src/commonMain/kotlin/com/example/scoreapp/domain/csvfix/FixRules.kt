package com.example.scoreapp.domain.csvfix

/**
 * forScore 标签清洗规则。
 *
 * 规则集逐条对齐网页版 `tools/forscore-csv-fixer.html`，纯函数、零依赖、可单测。
 * 所有规则只作用于「文字标签」字段，**绝不碰 [FixRow.fileName]**（关联主键）
 * 与合集行的起止页。
 */

/** 一条标题清洗规则 */
data class TitleRule(
    val id: String,
    val name: String,
    val desc: String,
    val run: (String) -> String,
)

/** 拆分后的作曲家 / 编配者 */
data class ComposerSplit(
    val composers: List<String>,
    val arrangers: List<String>,
)

object FixRules {

    // ------------------------------------------------------------------ 词表

    /**
     * 作曲家拼写规范化表。
     *
     * 键是小写、压缩过空格的写法。前 4 条 Chopin 的变体来自真实数据里
     * `Federick Chopin`（错拼）出现 13 次这类情况。
     */
    val SPELLING: Map<String, String> = mapOf(
        "federick chopin" to "Frédéric Chopin",
        "frederic chopin" to "Frédéric Chopin",
        "frederick chopin" to "Frédéric Chopin",
        "chopin" to "Frédéric Chopin",
        "wolfgang mozart" to "Wolfgang Amadeus Mozart",
        "mozart" to "Wolfgang Amadeus Mozart",
        "robert alexander schumann" to "Robert Schumann",
        "schumann" to "Robert Schumann",
        "camille saint-saë" to "Camille Saint-Saëns",
        "camille saint-saens" to "Camille Saint-Saëns",
        "saint-saëns" to "Camille Saint-Saëns",
        "saint-saens" to "Camille Saint-Saëns",
        "peter tchaikovsky" to "Pyotr Ilyich Tchaikovsky",
        "tchaikovsky" to "Pyotr Ilyich Tchaikovsky",
        "pyotr tchaikovsky" to "Pyotr Ilyich Tchaikovsky",
        "p.i. tchaikovsky" to "Pyotr Ilyich Tchaikovsky",
        "j.s. bach" to "Johann Sebastian Bach",
        "bach" to "Johann Sebastian Bach",
        "fredrich burgmuller" to "Friedrich Burgmüller",
        "burgmuller" to "Friedrich Burgmüller",
        "beethoven" to "Ludwig van Beethoven",
        "ludwig beethoven" to "Ludwig van Beethoven",
    )

    /** 明确的软件名 / 通用词：不是人名，整格清空 */
    val JUNK: Set<String> = setOf(
        "wpsscan", "wp scan", "camscanner", "cam scanner", "camscan", "administrator", "admin",
        "wps", "wps office", "hymusic", "shinelon", "abrsm", "arbsm", "microsoft word",
        "scanner", "user", "unknown", "pdf", "scan", "wp", "wpdf",
    )

    /** 出版社 / 机构：不是作曲家，清掉并提示可以挪到来源列 */
    val ORG: Set<String> = setOf(
        "abrsm", "广东省教育考试院", "henle", "g. henle verlag", "wiener urtext edition",
        "kalmus", "national edition", "z-library", "imslp",
    )

    /** 判断是不是「编配者」而非作曲家 */
    private val ARRANGER_RE = Regex("(编合唱|编配|编伴奏|配伴奏|编曲|改编|填词|作词|整理|移调|配和声)")

    /**
     * 曲目类型推断表。
     *
     * **顺序有意义**：`小奏鸣曲` 必须排在 `奏鸣曲` 前面，否则会被后者的规则先抢走。
     */
    private val TYPE_RULES: List<Pair<Regex, String>> = listOf(
        Regex("小奏鸣曲|sonatina", RegexOption.IGNORE_CASE) to "Sonatina",
        Regex("奏鸣曲|sonata", RegexOption.IGNORE_CASE) to "Sonata",
        Regex("协奏曲|concerto", RegexOption.IGNORE_CASE) to "Concerto",
        Regex("练习曲|étude|etude|op\\.?\\s*(299|599|849|740|636)|基本功", RegexOption.IGNORE_CASE)
            to "基本功练习",
        Regex("圆舞曲|waltz|华尔兹", RegexOption.IGNORE_CASE) to "Waltz",
        Regex("小夜曲|serenade", RegexOption.IGNORE_CASE) to "Serenade",
        Regex("夜曲|nocturne", RegexOption.IGNORE_CASE) to "Nocturne",
        Regex("合唱|混声|童声|女声|男声|s\\.?a\\.?t\\.?b", RegexOption.IGNORE_CASE) to "合唱",
        Regex("考级|grade\\s*\\d|abrsm", RegexOption.IGNORE_CASE) to "英皇考级曲目",
        Regex("教材|教程|基础教程", RegexOption.IGNORE_CASE) to "教材",
        Regex("民歌|folksong", RegexOption.IGNORE_CASE) to "Chinese Folksong",
        Regex("前奏曲|prelude", RegexOption.IGNORE_CASE) to "Prelude",
        Regex("幻想曲|fantas", RegexOption.IGNORE_CASE) to "Fantasia",
        Regex("进行曲|march", RegexOption.IGNORE_CASE) to "March",
    )

    // ------------------------------------------------------------------ 标题规则

    /**
     * 标题清洗规则，按顺序依次应用。
     *
     * 顺序有讲究：先去括号调号再去书名号，最后才整理空格；
     * 「去掉尾部调号」压在最后，避免它先啃掉「降E」这类整体词后留下孤立汉字。
     */
    val TITLE_RULES: List<TitleRule> = listOf(
        TitleRule("ext", "去掉 .pdf 后缀", "含 _pdf、-pdf 这类重复后缀") { s ->
            // 循环剥：`x_pdf.pdf` 这类要连着去两次
            var cur = s
            var prev: String
            do {
                prev = cur
                cur = cur.replace(Regex("[._\\-\\s]*pdf$", RegexOption.IGNORE_CASE), "")
            } while (cur != prev && cur.isNotEmpty())
            cur
        },
        TitleRule("keyblk", "去掉括号里的调号", "【C】、（G调）、【原调-降A】整块去掉") { s ->
            s.replace(
                Regex("[（(【\\[]\\s*(?:原调\\s*[-－]?\\s*)?[降升]?\\s*[A-G][#b♯♭]?\\s*(?:调)?\\s*[）)】\\]]"),
                "",
            )
        },
        TitleRule("bk", "去掉书名号与方头括号", "《萱草花》→ 萱草花，只去符号，保留里面的字") { s ->
            s.replace(Regex("[《》【】〈〉]"), "")
        },
        TitleRule("num", "去掉开头序号", "1_月半小夜曲 → 月半小夜曲（9.28 这种日期不算序号）") { s ->
            s.replace(Regex("^\\d{1,3}[._、](?![0-9])\\s*"), "")
        },
        TitleRule("scan", "去掉扫描站水印", "(Z-Library) 这类来源标记") { s ->
            s.replace(
                Regex(
                    "[（(\\[]\\s*(z-?lib\\w*|zlibrary|scan\\w*|wp?s\\w*|cam\\s?scan\\w*)\\s*[）)\\]]",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
        },
        TitleRule("tail", "去掉尾部杂串", "「完整乐谱 -」「7页」「-2LJ」这类") { s ->
            var cur = s
            cur = cur.replace(Regex("^完整乐谱\\s*[-－—]\\s*"), "")
            cur = cur.replace(Regex("[-\\s]*\\d+\\s*页.*$"), "")
            // 下载站常见重复标记 (1)（1）
            cur = cur.replace(Regex("[（(]\\s*\\d+\\s*[）)]\\s*$"), "")
            // 只砍「数字开头 + 1~3 个字母」的短尾巴（-2LJ、-3P）；-vol1 这种分卷信息不动
            cur = cur.replace(Regex("[-_]\\d[A-Za-z]{1,3}$"), "")
            cur
        },
        TitleRule(
            "key",
            "去掉尾部调号",
            "七月的草原G → 七月的草原；降E 整体去掉；「D转F转D」这种带「转」的不动",
        ) { s ->
            var cur = s.replace(Regex("[\\s_-]?[降升][A-G][#b♯♭]?$"), "")
            // 带「转」的是转调说明，不是单纯调号，整段保留
            if (cur.contains("转")) return@TitleRule cur
            cur.replace(Regex("([一-龥])([A-G][#b♯♭]?)$"), "$1")
        },
        TitleRule("sp", "整理空格", "合并连续空格、去掉首尾空白") { s ->
            s.replace(Regex("\\s{2,}"), " ").trim()
        },
    )

    // ------------------------------------------------------------------ 判定

    /** 是否是软件名 / 通用词（精确匹配，不是模糊包含） */
    fun isJunk(name: String): Boolean = JUNK.contains(name.trim().lowercase())

    /** 是否是出版社 / 机构 */
    fun isOrg(name: String): Boolean = ORG.contains(name.trim().lowercase())

    /** 是否是「看着就不对」的残缺值：1~3 个拉丁字母，或单个汉字 */
    fun isSuspicious(name: String): Boolean {
        val t = name.trim()
        if (t.isEmpty()) return false
        if (Regex("^[A-Za-z]{1,3}$").matches(t)) return true
        if (Regex("^[一-龥]$").matches(t)) return true
        return false
    }

    /**
     * 把作曲家格子按逗号拆开，再按「是否含编配动词」分成两组。
     *
     * 真实数据里大量出现 `具本哲 配伴奏, 姚峰 编合唱, 曹火星` 这种混填：
     * 信息是对的，只是放错了列。
     */
    fun splitArrangers(raw: String): ComposerSplit {
        val parts = raw.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotEmpty() }
        val composers = mutableListOf<String>()
        val arrangers = mutableListOf<String>()
        parts.forEach { p -> if (ARRANGER_RE.containsMatchIn(p)) arrangers.add(p) else composers.add(p) }
        return ComposerSplit(composers, arrangers)
    }

    /** 单个名字的拼写规范化；[normalized] 与原名不同说明发生了改写 */
    fun normalizeName(name: String): NameNorm {
        val t = name.trim()
        val key = t.lowercase().replace(Regex("\\s+"), " ")
        val hit = SPELLING[key]
        return if (hit != null) NameNorm(hit, "spell") else NameNorm(t, null)
    }

    /**
     * 曲目类型推断。**只在原值为空时调用**，避免把用户手填的值盖掉。
     * 认不出来返回空串（不硬猜）。
     */
    fun inferType(title: String, fileName: String): String {
        val s = "$title $fileName"
        TYPE_RULES.forEach { (re, type) -> if (re.containsMatchIn(s)) return type }
        return ""
    }
}

/** 拼写规范化的结果 */
data class NameNorm(val value: String, val kind: String?)

/** 各规则的开关。缺省全部启用（与网页版一致：只有显式传 false 才关） */
data class RuleSwitches(
    val title: Boolean = true,
    val keyBlock: Boolean = true,
    val bookMark: Boolean = true,
    val number: Boolean = true,
    val scan: Boolean = true,
    val tail: Boolean = true,
    val key: Boolean = true,
    val space: Boolean = true,
    val junk: Boolean = true,
    val spell: Boolean = true,
    val split: Boolean = true,
    val type: Boolean = true,
) {
    /** 按规则 id 查开关；未知 id 视为启用 */
    fun allows(ruleId: String): Boolean = when (ruleId) {
        "ext" -> title
        "keyblk" -> keyBlock
        "bk" -> bookMark
        "num" -> number
        "scan" -> scan
        "tail" -> tail
        "key" -> key
        "sp" -> space
        else -> true
    }
}
