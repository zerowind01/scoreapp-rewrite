package com.example.scoreapp.model

/** 可参与筛选的三个维度 */
enum class FilterDim(val key: String, val label: String) {
    Composer("composer", "按作曲家"),
    Type("type", "按曲目类型"),
    Instrument("instrument", "按乐器");

    /**
     * 维度名（去掉「按」前缀后的名词形式）。
     *
     * [label] 是给筛选控件用的动宾短语（「按作曲家」），拼进提示语会变成
     * 「已筛选：按作曲家 · 巴赫」这种别扭说法，故单列一个名词形式。
     */
    val noun: String
        get() = when (this) {
            Composer -> "作曲家"
            Type -> "曲目类型"
            Instrument -> "乐器"
        }
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

        /** 构造「只按某个维度的一个取值」筛选的状态 */
        fun forValue(dim: FilterDim, value: String): FilterState = when (dim) {
            FilterDim.Composer -> FilterState(composer = setOf(value))
            FilterDim.Type -> FilterState(type = setOf(value))
            FilterDim.Instrument -> FilterState(instrument = setOf(value))
        }
    }
}

/** 排序方式 */
enum class SortMode(val key: String, val label: String) {
    Composer("composer", "按作曲家"),
    Title("title", "按标题"),
    DateAdded("dateAdded", "最近添加"),
    Pages("pages", "页数从多到少"),
}
