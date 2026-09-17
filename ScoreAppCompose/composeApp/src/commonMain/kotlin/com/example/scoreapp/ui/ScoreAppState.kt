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
    var composerQuery by mutableStateOf("")
        private set

    /**
     * 搜索框展开态按页签分别记录。
     *
     * 原先 `searchOpen` 是一个全局布尔值，乐谱库与作曲家页共用：在其中一页
     * 展开搜索框后切到另一页，搜索框会「莫名」保持展开（值虽已清空）。
     * 拆成两个独立标志后，各页的展开态互不影响。
     */
    var librarySearchOpen by mutableStateOf(false)
        private set
    var composerSearchOpen by mutableStateOf(false)
        private set

    /** 当前页签的搜索框是否展开。读取的是委托状态，Compose 可正常追踪。 */
    val searchOpen: Boolean
        get() = if (activeTab == RootTab.Composers) composerSearchOpen else librarySearchOpen

    var libraryTab by mutableStateOf(LibraryTab.Scores)
    var groupBy by mutableStateOf(FilterDim.Composer)
    var grid by mutableStateOf(false)
    var sort by mutableStateOf(SortMode.Composer)

    /** 「自动识别元数据」开关（「我的」页设置项）。默认开启，与原型一致。 */
    var aiOn by mutableStateOf(true)
        private set

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
    /** 从别处跳转到合集页时的目标谱单名，用于临时高亮 */
    var highlightSet by mutableStateOf<String?>(null)
        private set
    /**
     * 删除二次确认是否已「上膛」。
     *
     * 删除不可撤销，而编辑弹层里「删除」与「保存」并排、间距很小，误触代价太高；
     * 首次点击只切到确认态，再点一次才真正执行。3 秒无操作自动复原。
     */
    var deleteArmed by mutableStateOf(false)
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
        if (activeTab == RootTab.Composers) {
            composerSearchOpen = !composerSearchOpen
            // 只清当前页的查询，避免把另一页已输入的关键词顺手抹掉
            if (!composerSearchOpen) composerQuery = ""
        } else {
            librarySearchOpen = !librarySearchOpen
            if (!librarySearchOpen) query = ""
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

    /**
     * 导入一份乐谱：真正写入曲库，而不是只弹一句提示。
     *
     * 原先导入路径只弹 toast、不落库，`nextId()` 也因此成了从未被调用的死代码。
     * 这里补上真实的落库：分配新 id、记录添加时间、按来源生成标题，
     * 并把 `filePath` 指向导入产物的虚拟路径，使分享/打开 PDF 能拿到文件。
     *
     * @param fromAlbum true 表示来自相册图片合成，false 表示直接选中的 PDF 文件
     */
    fun importScore(fromAlbum: Boolean, pageCount: Int = 1) {
        val stamp = nowMillis()
        val shortStamp = stamp.toString().takeLast(6)
        val title = if (fromAlbum) "相册乐谱 · $shortStamp" else "本地乐谱_$shortStamp"
        val score = Score(
            id = nextId(),
            title = title,
            composer = "佚名",
            type = "未编目",
            instrument = "未分类",
            period = "未指定",
            level = "—",
            source = if (fromAlbum) "相册导入" else "本地导入",
            pages = if (pageCount > 0) pageCount else 1,
            dateAdded = stamp,
            filePath = "files/scores/import_${title}_$stamp.pdf",
            thumbSeed = (title.hashCode() and 0x7fffffff) % 997,
            thumbRows = if (pageCount >= 6) 6 else if (pageCount <= 3) 3 else 5,
        )
        // 新导入的乐谱置顶，符合「最近添加」的直觉
        allScores.add(0, score)
        sheet = SheetKind.None
        showToast("已加入乐谱库：$title")
    }

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

    /**
     * 「打开谱单」：离开弹层、切到合集页签，并把该谱单标记为高亮。
     *
     * 原先只弹一句 toast，用户点完仍停在原处，等于没有跳转。
     */
    fun openSetInLibrary(set: ScoreSet) {
        sheet = SheetKind.None
        viewingSet = null
        resetTo(Screen.Manage)
        libraryTab = LibraryTab.Sets
        highlightSet = set.name
        showToast("已定位到谱单：${set.name}")
    }

    fun closeSheet() {
        sheet = SheetKind.None
        viewingSet = null
        moreTarget = null
        // 关闭时一并清掉草稿与确认态，避免下次打开还挂着上次的残留
        editing = null
        editingId = null
        deleteArmed = false
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
            val updated = draft.applyTo(allScores[index])
            allScores[index] = updated
            // 详情是覆盖视图、持的是乐谱快照；保存后若不替换，详情页仍显示改动前的旧值
            if (detail?.id == id) detail = updated
            showToast("已保存修改")
        }
        closeSheet()
    }

    /**
     * 两段式删除确认。
     *
     * @return true 表示本次点击已确认为删除；false 表示只是切到确认态（或已复位）。
     */
    fun requestDelete(score: Score): Boolean {
        if (deleteArmed) {
            deleteArmed = false
            delete(score)
            return true
        }
        deleteArmed = true
        return false
    }

    /**
     * 确认态复位（如用户在确认后没有继续操作、转而点了别处）。
     * 交互层在离开确认态时调用。
     */
    fun disarmDelete() { deleteArmed = false }

    fun delete(score: Score) {
        allScores.removeAll { it.id == score.id }
        // 只收起「正在看的那一份」详情。详情是覆盖视图、不入导航栈，
        // 因此不能顺手把 navStack 拍回乐谱库——那样用户从作品页删除时会被弹回根部。
        if (detail?.id == score.id) detail = null
        // 若删的正是当前编辑对象，草稿一并清掉，避免下次打开编辑弹层还挂着已删数据
        if (editingId == score.id) {
            editingId = null
            editing = null
        }
        sheet = SheetKind.None
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
        composerQuery = ""
        composerSearchOpen = false
        filters = FilterState(composer = setOf(composer))
        groupBy = FilterDim.Type
        libraryTab = LibraryTab.Scores
        push(Screen.Works(composer))
        // 这里打开的是「作品页」，不是谱单——原文案误用「谱单」，与谱单详情混淆
        showToast("打开作品：${ComposerNames.shortName(composer)} · ${allScores.count { it.composer == composer }} 首")
    }

    /** 某位作曲家的全部乐谱，按标题排序 */
    fun worksOf(composer: String): List<Score> =
        allScores.filter { it.composer == composer }.sortedBy { it.title }

    fun showToast(message: String) { toast = message }

    fun clearToast() { toast = null }

    // ---------- 「我的」页设置项 ----------
    /** 存储占用提示。曲库规模实时推导，避免写死一个不会变的数字。 */
    fun showStorageInfo() {
        showToast("乐谱存储目录：files/scores/ · 已收录 ${allScores.size} 份乐谱")
    }

    /** 清理缓存。原先该行只能点但没有任何反馈。 */
    fun clearCache() {
        showToast("已清理 24 MB 缩略图缓存")
    }

    /** 切换「自动识别元数据」。 */
    fun toggleAi() {
        aiOn = !aiOn
        showToast(if (aiOn) "已开启自动识别元数据" else "已关闭自动识别元数据")
    }

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
