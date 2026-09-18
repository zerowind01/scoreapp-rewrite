package com.example.scoreapp.domain

/**
 * 作曲家输入框的自动补全规则。
 *
 * 编辑页原先把常用作曲家摊成一排 chip：名字一多就占掉半屏，
 * 且每加一个名字列表就更长。改成输入框 + 候选列表后，
 * 「常用姓氏」退化为「空输入时的前 6 条候选」，等于把快捷入口收进了输入框。
 *
 * 这里只放纯逻辑（过滤 + 高亮区间），不碰 Compose，
 * 因此可以被普通单元测试直接覆盖，也保证与原型同名函数语义一致。
 */
object ComposerSuggest {

    /** 候选列表最多显示几条。再多会把下半屏盖住，列表内部滚动即可 */
    const val MAX_SUGGESTIONS = 6

    /** 全部候选，按别名表顺序 */
    val all: List<String> = ComposerNames.ALIAS.values.toList()

    /**
     * 按输入过滤候选。
     *
     * 空输入给出前 [MAX_SUGGESTIONS] 条——这正是「常用作曲家」的语义。
     * 有输入时按 [ComposerNames.matches] 过滤，同样截断到上限。
     */
    fun suggestions(query: String): List<String> {
        val key = query.trim()
        val source = if (key.isEmpty()) all else all.filter { ComposerNames.matches(it, key) }
        return source.take(MAX_SUGGESTIONS)
    }

    /**
     * 高亮区间 `[start, end)`，返回的是**整串里的下标**。
     *
     * 姓名形如「路德维希·范·贝多芬」，用户输入的多半是姓氏，因此优先在姓氏段里找；
     * 找到后必须折算回整串下标，否则高亮会标在错误的位置
     * （例如输入「贝」，`路德维希` 里并没有「贝」，但若直接返回姓氏段内的相对下标，
     * 就会把「路」标成命中）。姓氏段找不到才退到整串包含匹配。
     *
     * @return 命中区间；无命中返回 null
     */
    fun matchRange(full: String, query: String): IntRange? {
        val key = query.trim()
        if (key.isEmpty()) return null
        val surname = ComposerNames.shortName(full)
        val inSurname = surname.indexOf(key)
        if (inSurname >= 0) {
            // 姓氏段在整串中的起点。姓名是「…·姓氏」，所以用长度差反推最稳，
            // 不必依赖中点位置（可能有多个中点，如「让-菲利普·拉莫」）。
            val base = full.length - surname.length
            return (base + inSurname) until (base + inSurname + key.length)
        }
        val i = full.indexOf(key)
        return if (i >= 0) i until (i + key.length) else null
    }

    /**
     * 把候选切成「命中前 / 命中 / 命中后」三段，供界面做三段式高亮。
     * 无命中时整串落在 before 里，命中段为空。
     */
    fun splitHighlight(full: String, query: String): Triple<String, String, String> {
        val range = matchRange(full, query)
        if (range == null) return Triple(full, "", "")
        return Triple(
            full.substring(0, range.first),
            full.substring(range.first, range.last + 1),
            full.substring(range.last + 1),
        )
    }
}
