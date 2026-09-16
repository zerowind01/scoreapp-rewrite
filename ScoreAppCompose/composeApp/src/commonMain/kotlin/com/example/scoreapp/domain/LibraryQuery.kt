package com.example.scoreapp.domain

import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.FilterState
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.SortMode

/** 一个分组及其成员 */
data class ScoreGroup(val key: String, val label: String, val items: List<Score>)

/** 分面取值及其在当前约束下的命中数 */
data class FacetValue(val value: String, val count: Int)

/** 一位作曲家及其名下作品 */
data class ComposerEntry(
    val full: String,
    val short: String,
    val initial: String,
    val scores: List<Score>,
)

/**
 * 乐谱库的查询与统计逻辑。
 *
 * 这里全部是纯函数：输入快照、输出结果，不持有任何状态。
 * 界面层只负责把状态喂进来并渲染返回值，便于单独验证。
 */
object LibraryQuery {

    /** 关键词搜索：命中标题、作曲家、体裁、乐器、时期、来源任一字段 */
    fun matchesQuery(score: Score, query: String): Boolean {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return true
        return listOf(
            score.title, score.composer, score.type,
            score.instrument, score.period, score.source,
        ).any { it.lowercase().contains(key) }
    }

    /** 单条记录是否满足筛选条件（维度内取并集，维度间取交集） */
    fun matchesFilters(score: Score, filters: FilterState): Boolean {
        if (filters.composer.isNotEmpty() && score.composer !in filters.composer) return false
        if (filters.type.isNotEmpty() && score.type !in filters.type) return false
        if (filters.instrument.isNotEmpty() && score.instrument !in filters.instrument) return false
        return true
    }

    /** 可见乐谱 = 搜索 ∩ 筛选 */
    fun visible(all: List<Score>, query: String, filters: FilterState): List<Score> =
        all.filter { matchesQuery(it, query) && matchesFilters(it, filters) }

    /** 排序 */
    fun sorted(list: List<Score>, mode: SortMode): List<Score> = when (mode) {
        SortMode.Composer -> list.sortedWith(compareBy({ it.composer }, { it.title }))
        SortMode.Title -> list.sortedBy { it.title }
        SortMode.DateAdded -> list.sortedByDescending { it.dateAdded }
        SortMode.Pages -> list.sortedByDescending { it.pages }
    }

    /**
     * 分组：组内数量降序，数量相同按组名升序。
     * 作曲家维度展示姓氏。
     */
    fun group(list: List<Score>, dim: FilterDim): List<ScoreGroup> {
        val buckets = LinkedHashMap<String, MutableList<Score>>()
        for (score in list) {
            val key = valueOf(score, dim)
            buckets.getOrPut(key) { mutableListOf() }.add(score)
        }
        return buckets.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<Score>>> { it.value.size }
                .thenBy { it.key })
            .map { (key, items) ->
                ScoreGroup(
                    key = key,
                    label = if (dim == FilterDim.Composer) ComposerNames.shortName(key) else key,
                    items = items,
                )
            }
    }

    /**
     * 分面计数：在「其它维度」的约束下，某个取值的命中数。
     *
     * 与 [dim] 同维度的已选条件不参与计算 —— 否则多选后同维度的
     * 其它选项会全部归零，用户无法继续叠加选择。
     */
    fun facetCount(
        all: List<Score>,
        query: String,
        pending: FilterState,
        dim: FilterDim,
        value: String,
    ): Int = all.count { score ->
        if (valueOf(score, dim) != value) return@count false
        if (!matchesQuery(score, query)) return@count false
        FilterDim.entries
            .filter { it != dim }
            .all { other ->
                val selected = pending.valuesOf(other)
                selected.isEmpty() || valueOf(score, other) in selected
            }
    }

    /** 待应用条件下的结果总数 */
    fun pendingCount(all: List<Score>, query: String, pending: FilterState): Int =
        all.count { matchesQuery(it, query) && matchesFilters(it, pending) }

    /** 某维度下出现过的全部取值，按出现次数降序 */
    fun facetValues(all: List<Score>, dim: FilterDim): List<String> =
        all.groupingBy { valueOf(it, dim) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** 去重统计：作曲家数 / 体裁数 / 乐器数 */
    fun distinctCount(all: List<Score>, dim: FilterDim): Int =
        all.map { valueOf(it, dim) }.toSet().size

    /** 按作曲家聚合，供「作曲家」页与头像列表使用 */
    fun composerEntries(all: List<Score>): List<ComposerEntry> =
        all.groupBy { it.composer }
            .map { (full, scores) ->
                ComposerEntry(
                    full = full,
                    short = ComposerNames.shortName(full),
                    initial = ComposerNames.initialOf(full),
                    scores = scores,
                )
            }
            .sortedBy { it.full }

    /** 按索引字母二次聚合，用于 A-Z 分节展示 */
    fun byInitial(entries: List<ComposerEntry>): List<Pair<String, List<ComposerEntry>>> =
        entries.groupBy { it.initial }
            .toSortedMap()
            .map { (letter, group) -> letter to group.sortedBy { it.full } }

    fun valueOf(score: Score, dim: FilterDim): String = when (dim) {
        FilterDim.Composer -> score.composer
        FilterDim.Type -> score.type
        FilterDim.Instrument -> score.instrument
    }
}
