package com.example.scoreapp.domain.csvfix

/**
 * 生成一行的改动建议。
 *
 * 对应网页版的 `propose()`。**只产出建议，不改原数据** —— 是否采纳由用户在界面上逐条决定。
 */
object FixProposer {

    /**
     * 对一行生成建议。
     *
     * @param row 原始数据
     * @param switches 规则开关
     * @param isBookmark 该行是否是合集子条目（带起止页）。是则**永不改标题** ——
     *   合集里每个子条目各自有意义的标题，清洗规则会把「Grade 4」这类误伤。
     */
    fun propose(
        row: FixRow,
        switches: RuleSwitches = RuleSwitches(),
        isBookmark: Boolean = false,
    ): FixProposal {
        val changes = mutableMapOf<String, MutableList<String>>()
        val notes = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        fun record(field: String, ruleIds: List<String>) {
            val list = changes.getOrPut(field) { mutableListOf() }
            ruleIds.forEach { if (!list.contains(it)) list.add(it) }
        }

        // ---------------- 标题 ----------------
        var newTitle: String? = null
        if (!isBookmark) {
            var t = row.title
            val applied = mutableListOf<String>()
            FixRules.TITLE_RULES.forEach { rule ->
                if (!switches.allows(rule.id)) return@forEach
                val next = rule.run(t)
                if (next != t) {
                    t = next
                    applied.add(rule.id)
                }
            }
            if (t != row.title) {
                newTitle = t
                record("title", applied)
            }
        }

        // ---------------- 作曲家 ----------------
        val split = FixRules.splitArrangers(row.composer)
        val keptComposers = mutableListOf<String>()

        split.composers.forEach { c ->
            // 软件名 / 通用词整格清空，且记进 dropped 供提示回指
            if (switches.junk && FixRules.isJunk(c)) {
                dropped.add(c)
                return@forEach
            }
            val norm = FixRules.normalizeName(c)
            // 关掉拼写规则时保留原写法，只做拆分与垃圾清理
            keptComposers.add(if (switches.spell) norm.value else c)
        }
        val composerText = keptComposers.joinToString(", ")
        // 只有真的和原值不同才算改动，否则会凭空报「改了」骗用户点一遍
        val composerResult = composerText.takeIf { it != row.composer }
        if (composerResult != null) record("comp", listOf("junk", "spell", "split"))

        // ---------------- 编配者拆出去 ----------------
        var newTag: String? = null
        if (switches.split && split.arrangers.isNotEmpty()) {
            val existing = row.tag.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotEmpty() }
            val merged = (existing + split.arrangers).distinct().filter { it.isNotEmpty() }
            newTag = merged.joinToString(", ")
            record("tag", listOf("split"))
        }

        // ---------------- 类型：原值为空才补 ----------------
        var newGenre: String? = null
        if (switches.type && row.genre.isBlank()) {
            val inferred = FixRules.inferType(newTitle ?: row.title, row.fileName)
            if (inferred.isNotEmpty()) {
                newGenre = inferred
                record("genre", listOf("type"))
            }
        }

        // ---------------- 只提示、不改 ----------------
        split.composers.forEach { c ->
            if (FixRules.isSuspicious(c)) {
                notes.add("作曲家「$c」看着像残缺值，没敢动")
            }
        }
        dropped.forEach { c ->
            if (FixRules.isOrg(c)) {
                notes.add("「$c」是机构/出版社，清掉了；英皇考级这类来源信息可填到来源列")
            }
        }

        return FixProposal(
            title = newTitle,
            composer = composerResult?.takeIf { composerText != row.composer },
            tag = newTag,
            genre = newGenre,
            changes = changes,
            notes = notes,
            dropped = dropped,
        )
    }

    /** 该行是否是合集子条目：起止页任一非空 */
    fun isBookmarkRow(row: FixRow): Boolean =
        row.startPage.isNotBlank() || row.endPage.isNotBlank()
}
