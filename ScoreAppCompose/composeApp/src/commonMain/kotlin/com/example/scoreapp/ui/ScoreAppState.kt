package com.example.scoreapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.domain.formatBytes
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.FilterState
import com.example.scoreapp.model.RootTab
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet
import com.example.scoreapp.model.Screen
import com.example.scoreapp.model.SortMode
import com.example.scoreapp.util.FileBridge
import com.example.scoreapp.util.StorageUsage
import com.example.scoreapp.util.nowMillis
import com.example.scoreapp.util.toShareInfo

/** 当前展开的底部弹层 */
enum class SheetKind { None, Filter, Import, Edit, Sort, SetDetail, More }

/** 乐谱库页内的两个子页签 */
enum class LibraryTab(val label: String) { Scores("乐谱库"), Sets("合集") }

/**
 * 导入弹层里用户点了哪一条，等待系统选择器返回。
 *
 * 选择器是异步的：点击只负责发起请求并记下意图，真正的落库发生在
 * `MainActivity` 的 launcher 回调里（拿到 uri 之后）。用枚举而不是布尔值，
 * 是因为两条通道拿到 uri 后要做的事完全不同，必须能分辨。
 */
enum class PickKind { None, Images, Pdf }

/**
 * 打开阅读器的请求。
 *
 * 阅读器是一层覆盖视图，不入导航栈；`key` 用来区分「打开了另一份乐谱」——
 * 复用同一个 key 时 Compose 不会重建，`PdfDocState` 就还握着上一份文档。
 */
data class ReaderRequest(val key: Long, val path: String, val title: String)

/**
 * 应用状态中枢。
 *
 * 所有可观察状态集中在这里，界面层只读状态、调动作，不自行推导。
 * 派生数据（可见列表、分组、分面计数）一律即时计算，避免出现
 * 「状态已改但派生缓存未失效」的不一致。
 */
class ScoreAppState {

    // ---------- 平台能力 ----------
    /**
     * 文件能力桥。由 `MainActivity` 在 `setContent` 之前注入。
     *
     * 为 null 时（例如在普通单元测试里直接构造本类）所有文件相关动作只弹提示、
     * 不落库——这样业务层测试不必依赖 Android 运行时，而真机上行为完整。
     */
    var bridge: FileBridge? = null

    // ---------- 数据 ----------
    val allScores: SnapshotStateList<Score> = mutableStateListOf<Score>().apply {
        addAll(SampleLibrary.scores)
    }
    val sets: List<ScoreSet> = SampleLibrary.sets

    /**
     * 解析随包分发的内置乐谱。
     *
     * 启动时必须调用一次：`SampleLibrary` 里的样例乐谱只声明了 `assetPdf`（一个**文件名**），
     * 而 `File("moonlight_op27_no2.pdf")` 会被解析到进程工作目录下，设备上必然不存在。
     * 由 [FileBridge.installBundledScores] 把 assets 里的 PDF 拷到 `filesDir/scores/`，
     * 再把绝对路径写回 `filePath`——之后封面渲染、打开乐谱、分享三条路径才真的走得通。
     *
     * 两处刻意的设计：
     *  - **幂等且可重复调用**（重建、返回前台都可再跑一次），已安装的文件不会重写；
     *  - 没有内置资源时**静默保持原样**，不提示、不报错。本仓库不把第三方版权乐谱
     *    纳入版本控制，缺失是正常状态，此时封面退化为程序化绘制，功能不缺、只是少了预览图。
     *
     * @return 实际解析出绝对路径的乐谱数量，便于日志与测试断言
     */
    fun installBundledScores(): Int {
        val bridge = bridge ?: return 0
        val resolved = bridge.installBundledScores(allScores.toList())
        var changed = 0
        for (i in resolved.indices) {
            val next = resolved[i]
            if (next != allScores[i]) {
                allScores[i] = next
                changed++
            }
        }
        // 详情是覆盖视图、持的是乐谱快照，若正开着内置乐谱的详情，一并换成新对象
        detail?.let { open -> resolved.firstOrNull { it.id == open.id }?.let { detail = it } }
        return changed
    }

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

    /**
     * 待处理的导入选择意图。
     *
     * 界面层观察到它不为 [PickKind.None] 时拉起对应的系统选择器，
     * 随后由 launch 回调调 [consumePick] 复位。这样「想选什么」这件事
     * 留在状态中枢里，界面层只负责转发，不必自己记一份平行的意图。
     */
    var pendingPick by mutableStateOf(PickKind.None)
        private set

    /** 阅读器覆盖视图的请求；为 null 表示没打开 */
    var reader by mutableStateOf<ReaderRequest?>(null)
        private set

    /**
     * 乐谱存储目录的真实占用；null 表示还没统计过。
     *
     * 由「我的」页进入时刷新一次（见 [refreshStorage]），标签与点击提示同口径。
     * 原应用这里写死「本地 128 MB」永不变化，属于它自己的瑕疵（第 4 处）。
     */
    var storageUsage by mutableStateOf<StorageUsage?>(null)
        private set

    /** 封面缓存当前占用（字节）。清空后由 [clearCache] 同步归零 */
    var cacheBytes by mutableStateOf(0L)
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
    /**
     * 打开详情页。
     *
     * 顺带收起任何已打开的弹层：详情页是全屏视图，
     * 而弹层渲染在它之上，不关掉会出现「详情被谱单弹层盖住」。
     */
    fun openDetail(score: Score) {
        sheet = SheetKind.None
        detail = score
    }

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
     * 发起导入：记下意图，关上弹层，等系统选择器返回。
     *
     * 这里**不再自己伪造一份乐谱**。原先的实现直接按当前时间戳编一个标题就落库，
     * 结果是「库里多了一条，文件系统里什么都没有」——分享和打开都会失败。
     * 现在只有真拿到 uri、真写出文件之后才落库，见 [importFromImages] / [importFromPdf]。
     */
    fun beginPick(kind: PickKind) {
        pendingPick = kind
        sheet = SheetKind.None
    }

    /** 选择器返回后复位意图 */
    fun consumePick() { pendingPick = PickKind.None }

    /**
     * 相册图片 → 合成 A4 PDF → 落库。
     *
     * 跳过数不为 0 时，成功提示后面追加一句括号说明（与原应用一致）：
     * 用户选了 5 张、只进来 3 页，必须让他知道另外 2 张去哪了。
     */
    fun importFromImages(uris: List<String>) {
        if (uris.isEmpty()) return
        val bridge = bridge ?: run {
            showToast("当前环境不支持导入")
            return
        }

        val result = bridge.imagesToPdf(uris)
        val path = result.path
        if (path == null) {
            showToast(result.error ?: "PDF 生成失败")
            return
        }

        addImported(
            title = "相册乐谱 · ${result.pages} 页",
            pages = result.pages,
            filePath = path,
        )

        var message = "已生成 PDF 并加入乐谱库：${result.pages} 页"
        result.error?.let { message += "（$it）" }
        showToast(message)
    }

    /**
     * 选中 PDF → 拷进本地目录 → 落库。
     *
     * 拷贝而不是直接引用 `content://`：外部 uri 的读权限只在本次会话有效，
     * 不拷进来的话，下次启动这份乐谱就打不开了。
     */
    fun importFromPdf(uri: String) {
        val bridge = bridge ?: run {
            showToast("当前环境不支持导入")
            return
        }

        val title = bridge.pdfTitle(uri)
        val local = bridge.copyPdfToLocal(uri, title)
        if (local == null) {
            showToast("导入失败，无法读取所选文件")
            return
        }

        addImported(
            title = title,
            pages = bridge.pdfPageCount(local),
            filePath = local,
        )
        showToast("已导入 PDF：$title")
    }

    /**
     * 把导入产物登记进曲库。
     *
     * 元数据沿用反编译产物里那两个调用点的口径：作曲家「佚名」、
     * 类型「未编目」、乐器「未分类」、时期「未指定」、难度「—」、来源「本地导入」。
     *
     * 注意 `filePath` 写的是**真实绝对路径**而不是虚拟串；标题也不再由时间戳拼装
     * （相册路径用页数、PDF 路径用文件名），因此库里看到的信息是可解释的。
     */
    private fun addImported(title: String, pages: Int, filePath: String) {
        val score = Score(
            id = nextId(),
            title = title,
            composer = "佚名",
            type = "未编目",
            instrument = "未分类",
            period = "未指定",
            level = "—",
            source = "本地导入",
            pages = pages,
            dateAdded = nowMillis(),
            // 相册来源没有 assetPdf，PDF 来源也统一走 filePath——
            // 两者语义等价（都是「本地真实文件」），分两处存会带来不一致的风险
            filePath = filePath,
            thumbSeed = (title.hashCode() and 0x7fffffff) % 997,
            thumbRows = if (pages >= 6) 6 else if (pages <= 3) 3 else 5,
        )
        // 新导入的乐谱置顶，符合「最近添加」的直觉
        allScores.add(0, score)
        sheet = SheetKind.None
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
        val bridge = bridge ?: run {
            showToast("当前环境不支持分享")
            return
        }
        val outcome = bridge.share(score.toShareInfo())
        showToast(
            when {
                !outcome.dispatched -> "没有可用的分享方式"
                // 带着附件分享，还是只发出了一段曲谱信息——两者对用户的含义不同
                outcome.hadFile -> "已分享 PDF：${score.displayFile}"
                else -> "这份乐谱还没有 PDF 文件，已分享曲谱信息"
            },
        )
    }

    /**
     * 打开乐谱。
     *
     * 没有文件时不进入阅读器，只提示去导入——空阅读器对用户没有任何信息量。
     * 有文件则挂上覆盖视图，由 `AppRoot` 渲染 [com.example.scoreapp.ui.components.PdfViewerScreen]。
     */
    fun openPdf(score: Score) {
        val path = score.filePath?.takeIf { it.isNotBlank() }
            ?: score.assetPdf?.takeIf { it.isNotBlank() }
        if (path == null) {
            showToast("这份乐谱还没有 PDF 文件，可在列表里导入")
            return
        }
        // 每次打开都换一个 key，确保切换乐谱时阅读器会重建文档状态
        reader = ReaderRequest(key = score.id, path = path, title = score.title)
    }

    fun closePdf() { reader = null }

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

    /**
     * 从「我的」页的元数据统计行跳到乐谱库，并把该维度作为唯一筛选条件。
     *
     * 这几行原本是 `onClick = {}` 的空回调——点了有涟漪、却什么都不发生。
     * 与其删掉可点区域，不如接上语义上最自然的动作：既然展示的是
     * 「N 位作曲家 / N 类曲目类型 / N 种乐器」的统计，点进去就该看到这些
     * 取值构成的列表。这里复用已有的筛选机制，不新造页面。
     *
     * @param dim 目标筛选维度
     * @param value 具体取值；为 null 表示「不带取值」，只跳到库页并清空筛选
     */
    fun jumpToLibrary(dim: FilterDim, value: String? = null) {
        // 先回根，避免从「我的」页 push 后堆栈里留着旧层
        resetTo(Screen.Manage)
        query = ""
        librarySearchOpen = false
        libraryTab = LibraryTab.Scores
        groupBy = dim
        filters = if (value == null) FilterState.EMPTY else FilterState.forValue(dim, value)
        showToast(
            if (value == null) "已打开乐谱库"
            else "已筛选：${dim.noun} · $value",
        )
    }

    fun showToast(message: String) { toast = message }

    fun clearToast() { toast = null }

    // ---------- 「我的」页设置项 ----------

    /**
     * 现算存储占用与缓存量。进「我的」页时调一次；清理后也要调，
     * 让标签与提示永远说同一个数。
     */
    fun refreshStorage() {
        val b = bridge ?: return
        storageUsage = b.storageUsage()
        cacheBytes = b.coverCacheBytes()
    }

    /** 存储占用提示。份数与字节数来自同一次统计，口径不会再打架 */
    fun showStorageInfo() {
        val u = storageUsage
        showToast(
            if (u == null) "乐谱存储目录：files/scores/"
            else "乐谱存储目录：files/scores/ · ${u.files} 个文件 · ${formatBytes(u.bytes)}",
        )
    }

    /**
     * 清理缓存。返回值不再写死：清多少报多少，0 就说 0——
     * 原应用报「已清理 24 MB 缩略图缓存」是写死的假数。
     */
    fun clearCache() {
        val b = bridge
        if (b == null) {
            showToast("缓存为空，无需清理")
            return
        }
        val freed = b.clearCoverCache()
        cacheBytes = 0
        showToast(if (freed > 0) "已清理 ${formatBytes(freed)} 缩略图缓存" else "缓存为空，无需清理")
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
        //
        // 每个字段的兜底取值必须与它的**语义**对应，不能错位：
        //   作曲家 → 佚名      作者未知
        //   曲目类型 → 未编目  还没归类到具体体裁
        //   乐器 → 未分类      还没指定演奏编制
        //   时期 → 未指定      还没判断风格年代
        //   难度 → —           「无」的占位符（不是一种难度）
        //   来源 → 本地导入    从本机文件进来的
        //
        // 原应用的导入回调把这一串整体错位了一格：作曲家位填了「未编目」，
        // 之后每个值依次顺移，最后两位都落成「—」。结果是一份导入进来的乐谱
        // 作者叫「未编目」、体裁叫「未分类」，筛选分面里出现了语义错乱的分组。
        // 这里按语义逐项归位，并由单元测试锁住。

        internal fun normalizeComposer(v: String) = v.trim().ifBlank { "佚名" }
        internal fun normalizeType(v: String) = v.trim().ifBlank { "未编目" }
        internal fun normalizeInstrument(v: String) = v.trim().ifBlank { "未分类" }
        internal fun normalizePeriod(v: String) = v.trim().ifBlank { "未指定" }
        internal fun normalizeLevel(v: String) = v.trim().ifBlank { "—" }
        internal fun normalizeSource(v: String) = v.trim().ifBlank { "本地导入" }
    }
}
