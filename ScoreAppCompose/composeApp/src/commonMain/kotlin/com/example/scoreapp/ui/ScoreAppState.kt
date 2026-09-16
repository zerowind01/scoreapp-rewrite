package com.example.scoreapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.FilterState
import com.example.scoreapp.model.RootTab
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet
import com.example.scoreapp.model.Screen
import com.example.scoreapp.model.SortMode
import com.example.scoreapp.util.nowMillis

/** 当前展开的底部弹层 */
enum class SheetKind { None, Filter, Import, Edit, Sort, SetDetail, More }

/** 乐谱库页内的两个子页签 */
enum class LibraryTab(val label: String) { Scores("乐谱库"), Sets("合集") }

/**
 * 应用状态中枢。
 *
 * 所有可观察状态集中在这里，界面层只读状态、调动作，不自行推导。
 * 派生数据（可见列表、分组、分面计数）一律即时计算，避免出现
 * 「状态已改但派生缓存未失效」的不一致。
 */
class ScoreAppState {

    // ---------- 数据 ----------
    val allScores: SnapshotStateList<Score> = mutableStateListOf<Score>().apply {
        addAll(SampleLibrary.scores)
    }
    val sets: List<ScoreSet> = SampleLibrary.sets

    // ---------- 导航 ----------
    var navStack by mutableStateOf<List<Screen>>(listOf(Screen.Manage))
        private set

    val current: Screen get() = navStack.last()

    val activeTab: RootTab
        get() = when (current) {
            is Screen.Composers -> RootTab.Composers
            // 作曲家作品页挂在「作曲家」页签下，返回后回到索引列表
            is Screen.Works -> RootTab.Composers
            is Screen.Me -> RootTab.Me
            else -> RootTab.Library
        }

    fun selectTab(tab: RootTab) = resetTo(tab.screen)

    fun push(screen: Screen) {
        navStack = navStack + screen
    }

    fun resetTo(screen: Screen) {
        navStack = listOf(screen)
    }

    /** 返回上一级；栈底时返回 false，交给系统处理返回键 */
    fun back(): Boolean {
        if (navStack.size <= 1) return false
        navStack = navStack.dropLast(1)
        return true
    }

    // ---------- 查询与视图 ----------
    var query by mutableStateOf("")
        private set
    var searchOpen by mutableStateOf(false)
        private set
    var composerQuery by mutableStateOf("")
        private set

    var libraryTab by mutableStateOf(LibraryTab.Scores)
    var groupBy by mutableStateOf(FilterDim.Composer)
    var grid by mutableStateOf(false)
    var sort by mutableStateOf(SortMode.Composer)

    var filters by mutableStateOf(FilterState.EMPTY)
        private set

    // ---------- 筛选弹层的暂存态 ----------
    var pending by mutableStateOf(FilterState.EMPTY)
        private set
    var pendingGroup by mutableStateOf(FilterDim.Composer)
        private set

    // ---------- 弹层与提示 ----------
    var sheet by mutableStateOf(SheetKind.None)
        private set
    var editing by mutableStateOf<ScoreDraft?>(null)
        private set
    var editingId by mutableStateOf<Long?>(null)
        private set
    var viewingSet by mutableStateOf<ScoreSet?>(null)
        private set
    /** 「更多」弹层的作用对象 */
    var moreTarget by mutableStateOf<Score?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    // ---------- 派生数据 ----------
    val visibleScores: List<Score>
        get() = LibraryQuery.sorted(
            LibraryQuery.visible(allScores, query, filters),
            sort,
        )

    val composerEntries get() = LibraryQuery.composerEntries(allScores)

    val composerSections
        get() = LibraryQuery.byInitial(
            composerEntries.filter { ComposerNames.matches(it.full, composerQuery) },
        )

    // ---------- 动作 ----------
    fun updateQuery(value: String) { query = value }

    fun updateComposerQuery(value: String) { composerQuery = value }

    fun toggleSearch() {
        searchOpen = !searchOpen
        if (!searchOpen) {
            query = ""
            composerQuery = ""
        }
    }

    fun toggleGrid() { grid = !grid }

    fun selectSort(mode: SortMode) {
        sort = mode
        sheet = SheetKind.None
    }

    fun toggleFilter(dim: FilterDim, value: String) {
        filters = filters.toggle(dim, value)
    }

    fun clearFilters() { filters = FilterState.EMPTY }

    // ---------- 筛选弹层 ----------
    fun openFilterSheet() {
        pending = filters
        pendingGroup = groupBy
        sheet = SheetKind.Filter
    }

    fun togglePending(dim: FilterDim, value: String) { pending = pending.toggle(dim, value) }

    fun clearPending(dim: FilterDim) { pending = pending.clear(dim) }

    fun selectPendingGroup(dim: FilterDim) { pendingGroup = dim }

    fun resetPending() {
        pending = FilterState.EMPTY
        pendingGroup = FilterDim.Composer
    }

    fun applyPending() {
        filters = pending
        groupBy = pendingGroup
        sheet = SheetKind.None
        showToast("已筛选出 ${pendingResultCount} 首乐谱")
    }

    fun facetCount(dim: FilterDim, value: String): Int =
        LibraryQuery.facetCount(allScores, query, pending, dim, value)

    val pendingResultCount: Int
        get() = LibraryQuery.pendingCount(allScores, query, pending)

    // ---------- 详情与编辑 ----------
    /** 打开乐谱详情。详情是一层覆盖视图，不压入导航栈，返回键优先关它 */
    fun openDetail(score: Score) { detail = score }

    var detail by mutableStateOf<Score?>(null)
        private set

    fun closeDetail() { detail = null }

    fun openEditor(score: Score?) {
        editingId = score?.id
        editing = ScoreDraft.from(score)
        sheet = SheetKind.Edit
    }

    fun openImport() { sheet = SheetKind.Import }

    fun openSort() { sheet = SheetKind.Sort }

    /** 详情页右上角「更多」：承接打开乐谱 / 删除这两个次级动作 */
    fun openMore(score: Score) {
        moreTarget = score
        sheet = SheetKind.More
    }

    fun openSet(set: ScoreSet) {
        viewingSet = set
        sheet = SheetKind.SetDetail
    }

    fun closeSheet() {
        sheet = SheetKind.None
        viewingSet = null
        moreTarget = null
    }

    /**
     * 提交编辑草稿。
     *
     * 原应用的编辑弹层只服务于「修改已有乐谱」——新增走导入乐谱流程，
     * 因此这里不处理新建分支。
     */
    fun saveDraft() {
        val draft = editing ?: return
        val id = editingId ?: return
        if (draft.title.isBlank()) {
            showToast("请填写标题")
            return
        }
        val index = allScores.indexOfFirst { it.id == id }
        if (index >= 0) {
            allScores[index] = draft.applyTo(allScores[index])
            showToast("已保存修改")
        }
        closeSheet()
    }

    fun delete(score: Score) {
        allScores.removeAll { it.id == score.id }
        if (detail?.id == score.id) {
            detail = null
            navStack = listOf(Screen.Manage)
        }
        showToast("已删除「${score.title}」")
    }

    fun share(score: Score) {
        showToast(
            if (score.hasPdf) "已分享 PDF：${score.displayFile}"
            else "这份乐谱还没有 PDF 文件，已分享曲谱信息",
        )
    }

    fun openPdf(score: Score) {
        showToast(
            if (score.hasPdf) "打开乐谱：${score.displayFile}"
            else "这份乐谱还没有 PDF 文件，可在列表里导入",
        )
    }

    /**
     * 打开某位作曲家的作品页。
     *
     * 同时把乐谱库的筛选条件重置为「只看这位作曲家」，
     * 这样用户从作品页返回乐谱库时，看到的是同一个上下文，不会突然变回全量列表。
     */
    fun openComposerWorks(composer: String) {
        query = ""
        searchOpen = false
        filters = FilterState(composer = setOf(composer))
        groupBy = FilterDim.Type
        libraryTab = LibraryTab.Scores
        push(Screen.Works(composer))
        showToast("打开谱单：${ComposerNames.shortName(composer)} · ${allScores.count { it.composer == composer }} 首")
    }

    /** 某位作曲家的全部乐谱，按标题排序 */
    fun worksOf(composer: String): List<Score> =
        allScores.filter { it.composer == composer }.sortedBy { it.title }

    fun showToast(message: String) { toast = message }

    fun clearToast() { toast = null }

    private fun nextId(): Long = (allScores.maxOfOrNull { it.id } ?: 0L) + 1
}

/**
 * 编辑表单的草稿对象，与 [Score] 解耦，便于「取消」时不污染原对象。
 *
 * 各字段用 [mutableStateOf] 委托，使输入框能随键入即时刷新；
 * 若用普通 `var`，Compose 无法感知变化，文本会「卡住」不更新。
 */
class ScoreDraft(
    title: String,
    composer: String,
    type: String,
    instrument: String,
    period: String,
    level: String,
    source: String,
    pages: String,
) {
    var title by mutableStateOf(title)
    var composer by mutableStateOf(composer)
    var type by mutableStateOf(type)
    var instrument by mutableStateOf(instrument)
    var period by mutableStateOf(period)
    var level by mutableStateOf(level)
    var source by mutableStateOf(source)
    var pages by mutableStateOf(pages)

    fun applyTo(original: Score): Score = original.copy(
        title = title.trim(),
        composer = normalizeComposer(composer),
        type = normalizeType(type),
        instrument = normalizeInstrument(instrument),
        period = normalizePeriod(period),
        level = normalizeLevel(level),
        source = normalizeSource(source),
        pages = pages.toIntOrNull() ?: original.pages,
    )

    companion object {
        fun from(score: Score?): ScoreDraft = ScoreDraft(
            title = score?.title.orEmpty(),
            composer = score?.composer.orEmpty(),
            type = score?.type ?: SampleLibrary.TYPES.first(),
            instrument = score?.instrument ?: SampleLibrary.INSTRUMENTS.first(),
            period = score?.period ?: SampleLibrary.PERIODS.first(),
            level = score?.level ?: "中级",
            source = score?.source ?: "本地导入",
            pages = (score?.pages ?: 1).toString(),
        )

        // ---------- 保存时的字段归一化 ----------
        // 原应用在保存回调里对每个空字段填入固定的占位取值，
        // 保证筛选分面不会因为「空串」裂成额外的桶。
        // composer 原应用不做兜底，这里补「佚名」是为了避免作曲家索引出现空行。

        internal fun normalizeComposer(v: String) = v.trim().ifBlank { "佚名" }
        internal fun normalizeType(v: String) = v.trim().ifBlank { "未编目" }
        internal fun normalizeInstrument(v: String) = v.trim().ifBlank { "未分类" }
        internal fun normalizePeriod(v: String) = v.trim().ifBlank { "未指定" }
        internal fun normalizeLevel(v: String) = v.trim().ifBlank { "—" }
        internal fun normalizeSource(v: String) = v.trim().ifBlank { "本地导入" }
    }
}
