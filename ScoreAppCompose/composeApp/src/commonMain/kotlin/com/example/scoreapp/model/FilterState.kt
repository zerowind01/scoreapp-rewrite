package com.example.scoreapp.model

/** 可参与筛选的三个维度 */
enum class FilterDim(val key: String, val label: String) {
    Composer("composer", "按作曲家"),
    Type("type", "按曲目类型"),
    Instrument("instrument", "按乐器"),
}

/**
 * 筛选条件。语义：
 *  - 同一维度内的多个取值为「或」；
 *  - 不同维度之间为「且」；
 *  - 空集合表示该维度不参与约束。
 */
data class FilterState(
    val composer: Set<String> = emptySet(),
    val type: Set<String> = emptySet(),
    val instrument: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = composer.isEmpty() && type.isEmpty() && instrument.isEmpty()

    /** 已选条件的总条数，用于角标与「清空」可见性 */
    val selectionCount: Int
        get() = composer.size + type.size + instrument.size

    fun valuesOf(dim: FilterDim): Set<String> = when (dim) {
        FilterDim.Composer -> composer
        FilterDim.Type -> type
        FilterDim.Instrument -> instrument
    }

    /** 切换某个取值：已选则移除，未选则加入 */
    fun toggle(dim: FilterDim, value: String): FilterState {
        val next = valuesOf(dim).toMutableSet()
        if (!next.add(value)) next.remove(value)
        return withDim(dim, next)
    }

    fun clear(dim: FilterDim): FilterState = withDim(dim, emptySet())

    private fun withDim(dim: FilterDim, values: Set<String>): FilterState = when (dim) {
        FilterDim.Composer -> copy(composer = values)
        FilterDim.Type -> copy(type = values)
        FilterDim.Instrument -> copy(instrument = values)
    }

    companion object {
        val EMPTY = FilterState()
    }
}

/** 排序方式 */
enum class SortMode(val key: String, val label: String) {
    Composer("composer", "按作曲家"),
    Title("title", "按标题"),
    DateAdded("dateAdded", "最近添加"),
    Pages("pages", "页数从多到少"),
}
