package com.example.scoreapp.domain

/**
 * 作曲家姓名的展示与索引规则。
 *
 * 中文姓名统一采用「名·姓」或「姓」两种写法，以中点 `·` 分隔；
 * 列表展示时只取姓氏，索引则优先走人工整理的字母表，未命中时退化为首字符。
 */
object ComposerNames {

    /**
     * 姓氏 → 全名。
     *
     * 前 17 条（含 `佚名` / `合集`）还原自原应用的别名表；
     * 其后的条目是为演示数据集补充的，原应用只预置了 1 首乐谱，
     * 不覆盖这些作曲家。补齐的目的是让 A–Z 索引条上不会出现汉字。
     */
    val ALIAS: Map<String, String> = linkedMapOf(
        "巴赫" to "约翰·塞巴斯蒂安·巴赫",
        "贝多芬" to "路德维希·范·贝多芬",
        "肖邦" to "弗雷德里克·弗朗索瓦·肖邦",
        "莫扎特" to "沃尔夫冈·阿玛多伊斯·莫扎特",
        "德彪西" to "阿希尔-克洛德·德彪西",
        "门德尔松" to "费利克斯·门德尔松",
        "帕格尼尼" to "尼科罗·帕格尼尼",
        "舒伯特" to "弗朗茨·舒伯特",
        "勃拉姆斯" to "约翰内斯·勃拉姆斯",
        "柴可夫斯基" to "彼得·伊里奇·柴可夫斯基",
        "李斯特" to "弗朗茨·李斯特",
        "海顿" to "约瑟夫·海顿",
        "维瓦尔第" to "安东尼奥·维瓦尔第",
        "拉赫玛尼诺夫" to "谢尔盖·拉赫玛尼诺夫",
        "车尔尼" to "卡尔·车尔尼",
        "佚名" to "佚名",
        "合集" to "合集",
        // ---- 以下为演示数据集补充 ----
        "舒曼" to "罗伯特·舒曼",
        "穆索尔斯基" to "莫杰斯特·穆索尔斯基",
        "帕赫贝尔" to "约翰·帕赫贝尔",
        "克莱门蒂" to "穆齐奥·克莱门蒂",
        "斯特拉文斯基" to "伊戈尔·斯特拉文斯基",
        "比才" to "乔治·比才",
    )

    /** 姓氏 → 索引字母。人工映射可避免多音字与音译带来的排序歧义 */
    private val SURNAME_INITIAL: Map<String, String> = mapOf(
        "巴赫" to "B", "贝多芬" to "B", "肖邦" to "X", "莫扎特" to "M",
        "德彪西" to "D", "门德尔松" to "M", "帕格尼尼" to "P", "舒伯特" to "S",
        "勃拉姆斯" to "B", "柴可夫斯基" to "C", "李斯特" to "L", "海顿" to "H",
        "维瓦尔第" to "W", "拉赫玛尼诺夫" to "L", "车尔尼" to "C",
        "佚名" to "Y", "合集" to "H",
        // ---- 以下为演示数据集补充 ----
        "舒曼" to "S", "穆索尔斯基" to "M", "帕赫贝尔" to "P",
        "克莱门蒂" to "K", "斯特拉文斯基" to "S", "比才" to "B",
    )

    /** 取姓氏：按中点切分后取最后一段；无中点则原样返回 */
    fun shortName(full: String): String {
        if (full.isEmpty()) return full
        val parts = full.split('·')
        return if (parts.size > 1) parts.last() else full
    }

    /**
     * 索引字母：先查人工表；未命中时取姓氏首字符。
     * 注意汉字同样视为字母（与 [Char.isLetter] 一致），只有真正的
     * 非字母开头（数字、符号）才归入 `#`。
     */
    fun initialOf(full: String): String {
        val short = shortName(full)
        SURNAME_INITIAL[short]?.let { return it }
        val first = short.firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    /** 是否命中搜索词（同时匹配全名与姓氏） */
    fun matches(full: String, query: String): Boolean {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return true
        return full.lowercase().contains(key) || shortName(full).lowercase().contains(key)
    }
}
