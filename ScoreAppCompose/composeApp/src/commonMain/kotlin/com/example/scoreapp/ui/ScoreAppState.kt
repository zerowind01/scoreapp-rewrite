package com.example.scoreapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.scoreapp.data.SampleLibrary
import com.example.scoreapp.domain.ComposerNames
import com.example.scoreapp.domain.LibraryQuery
import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.FixStore
import com.example.scoreapp.domain.csvfix.FixStoreHolder
import com.example.scoreapp.domain.csvfix.LibraryAdapter
import com.example.scoreapp.domain.csvfix.SearchFallback
import com.example.scoreapp.domain.netdisk.Netdisk
import com.example.scoreapp.domain.netdisk.NetdiskCached
import com.example.scoreapp.domain.netdisk.NetdiskConfig
import com.example.scoreapp.domain.netdisk.NetdiskEntry
import com.example.scoreapp.domain.csvfix.MiniJson
import com.example.scoreapp.domain.library.ImportedScore
import com.example.scoreapp.domain.library.LocalStore
import com.example.scoreapp.domain.library.applyLocalMeta
import com.example.scoreapp.domain.library.saveLocalMeta
import com.example.scoreapp.domain.library.scoreKeyOf
import com.example.scoreapp.domain.library.toScore
import com.example.scoreapp.domain.netdisk.NetLibItem
import com.example.scoreapp.domain.netdisk.NetOpenRequest
import com.example.scoreapp.domain.netdisk.NetLibStore
import com.example.scoreapp.domain.netdisk.NetLibrary
import com.example.scoreapp.domain.netdisk.NetSyncState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.scoreapp.domain.netdisk.RemoteDir
import com.example.scoreapp.domain.netdisk.SYNC_THROTTLE_MS
import com.example.scoreapp.domain.formatBytes
import com.example.scoreapp.model.FilterDim
import com.example.scoreapp.model.FilterState
import com.example.scoreapp.model.RootTab
import com.example.scoreapp.model.Score
import com.example.scoreapp.model.ScoreSet
import com.example.scoreapp.model.Screen
import com.example.scoreapp.model.SortMode
import com.example.scoreapp.util.AiConfig
import com.example.scoreapp.util.FileBridge
import com.example.scoreapp.util.StorageUsage
import com.example.scoreapp.util.createAiBridge
import com.example.scoreapp.util.createSearchBridge
import com.example.scoreapp.util.createNetdiskBridge
import com.example.scoreapp.util.nowMillis
import com.example.scoreapp.util.nowStamp
import com.example.scoreapp.util.toShareInfo

/** 当前展开的底部弹层 */
enum class SheetKind { None, Filter, Import, Edit, Sort, SetDetail, More }

/** 乐谱库页内的两个子页签 */
enum class LibraryTab(val label: String) { Local("本机"), Netdisk("网盘"), Sets("合集") }

/**
 * 导入弹层里用户点了哪一条，等待系统选择器返回。
 *
 * 选择器是异步的：点击只负责发起请求并记下意图，真正的落库发生在
 * `MainActivity` 的 launcher 回调里（拿到 uri 之后）。用枚举而不是布尔值，
 * 是因为两条通道拿到 uri 后要做的事完全不同，必须能分辨。
 */
enum class PickKind { None, Images, Pdf, Csv }

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

    var libraryTab by mutableStateOf(LibraryTab.Local)
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

    // ---------- forScore 标签校对 ----------

    /**
     * 当前打开的校对会话；null 表示没在核对。
     *
     * 它是一个**有始有终的子任务**，所以单独成对象（见 [FixSession]）：
     * 关掉校对页就整体丢弃，不给曲库状态留残渣。
     */
    var fixSession by mutableStateOf<FixSession?>(null)
        private set

    /** 校对页是否正开着（它是整页覆盖视图，不占弹层位） */
    val fixOpen: Boolean get() = fixSession != null

    /**
     * AI 设置。**随校对存档落盘**（见 [FixStore.ai]），不是只存内存。
     *
     * 早先这里写的是「刻意只存在内存里、每次启动重填」——那个取舍在
     * **逐条翻阅**的模型出现之后就不成立了：一屏一条、几百条要过，
     * 用户随时可能点「生成」，每次冷启动都重输地址 + 密钥 + 模型等于把这个
     * 功能废掉一半。现在改成明文落盘（`filesDir/fix-store.json` 的 `ai` 段），
     * 并在设置页界面上明确告知用户。
     *
     * 启动时由 [loadAiConfig] 从存档读一次；改设置时由 [saveAiConfig] 写回。
     */
    var aiConfig by mutableStateOf(AiConfig())
        private set

    /**
     * 逐条校对的**跨会话存档**。
     *
     * 它读写的实现来自 [FileBridge]（`filesDir/fix-store.json`），而逻辑全在
     * [FixStoreHolder] 里 —— 这样「翻到哪一条、采纳了哪些字段」这套逻辑
     * 可以在 commonTest 里脱离 Android 运行时验证。
     *
     * 桥还没注入时（单元测试、或 App 启动的极早期）退化成纯内存存档：
     * 功能不缺，只是关掉 App 就没了。
     */
    private val fixStoreHolder = FixStoreHolder(
        read = { bridge?.readFixStore() },
        write = { json -> bridge?.writeFixStore(json) ?: false },
    )

    /**
     * 从存档里读一次 AI 设置。
     *
     * 由 `MainActivity` 在启动时调用一次（读盘是阻塞 I/O，不该写在构造器里）。
     * 读不到（首次安装、老存档没有 `ai` 段、文件损坏）时保持默认值 ——
     * 那时「未配置」，用户去设置页填一次即可。
     */
    fun loadAiConfig() {
        aiConfig = fixStoreHolder.load().ai
    }

    /** 打开 AI 设置页 */
    fun openAiSetup() {
        push(Screen.AiSetup)
    }

    /** 关闭 AI 设置页 */
    fun closeAiSetup() {
        if (navStack.lastOrNull() == Screen.AiSetup && navStack.size > 1) back()
    }

    /**
     * 保存 AI 设置。
     *
     * **写进 [FixStore] 而不是单开一个文件**：那份存档本来就要读一次
     * （开校对会话时），顺手把它读出来、改掉 `ai` 段、再整体写回去，
     * 复用已有的桥通道与 JSON 编解码，不新增任何 I/O 路径。
     *
     * 注意这里**先 load 再改再 update**：直接用内存里那份 [fixStoreHolder.value]
     * 会丢掉「用户上次翻到第几条」——那个值只在开校对会话时才被写进 holder，
     * 而用户完全可能先开设置页、再去开校对页。
     */
    fun saveAiConfig(config: AiConfig) {
        val merged = fixStoreHolder.load().copy(ai = config)
        val ok = fixStoreHolder.update(merged)
        aiConfig = config
        showToast(if (ok) "已保存" else "保存失败，配置只在本次运行有效")
        closeAiSetup()
    }

    /** 开一次校对会话，来源为 forScore CSV 文本 */
    fun openFixFromCsv(text: String) {
        if (text.isBlank()) {
            showToast("这个文件是空的")
            return
        }
        val store = fixStoreHolder.load()
        val session = FixSession.fromCsv(text, store = store)
        if (session.total == 0) {
            showToast("CSV 里没有数据行")
            return
        }
        // 换了批次（换了一份 CSV、条数对不上）就从头翻，但采纳记录保留 ——
        // 用户可能只是重新导出了同一批数据，那些决定不该丢
        val batch = FixStore.batchKeyOf("csv", session.rawRows)
        if (store.batchKey.isNotEmpty() && store.batchKey != batch && store.cursor >= session.total) {
            session.goTo(0)
        }
        // 把接口设置交给会话 —— 不注入的话，用户在设置页填的地址与密钥
        // 在校对页根本不会被用到（生成时仍拿默认地址和空密钥去请求）
        session.aiConfig = aiConfig
        fixSession = session
        push(Screen.Fix)
    }

    /** 开一次校对会话，来源为 App 自己的曲库 */
    fun openFixFromLibrary() {
        if (allScores.isEmpty()) {
            showToast("曲库是空的")
            return
        }
        val store = fixStoreHolder.load()
        val session = FixSession.fromLibrary(allScores.toList(), store = store)
        val batch = FixStore.batchKeyOf("lib", session.rawRows)
        if (store.batchKey.isNotEmpty() && store.batchKey != batch && store.cursor >= session.total) {
            session.goTo(0)
        }
        session.aiConfig = aiConfig
        fixSession = session
        push(Screen.Fix)
    }

    /**
     * 关掉校对页。
     *
     * 关之前**必须落盘**：跨会话存档的意义就是「用户随时可以退出」，
     * 而 Android 上的退出不一定是走返回键（可能是切后台后被系统回收），
     * 所以不能只在导出时才写。
     */
    fun closeFix() {
        fixSession?.let { persistFixStore(it) }
        fixSession = null
        // 退掉校对页；只有当它确实在栈顶时才弹（栈底不可弹，否则会空栈）
        if (navStack.lastOrNull() == Screen.Fix && navStack.size > 1) back()
    }

    /**
     * 把当前会话的存档写下去。
     *
     * 写之前**把 `ai` 段并回去**：会话手里的 `store` 是从 holder 读出来的那份，
     * 其中 `ai` 是当时的值；如果用户中途去设置页改了配置，直接用会话的 store
     * 覆盖会把新配置改回旧的。这里以 holder 里的 `ai` 为准。
     */
    fun persistFixStore(session: FixSession) {
        fixStoreHolder.update(session.store.copy(ai = aiConfig))
    }

    /** 清空存档（顶栏那三个工具里的一个） */
    fun clearFixStore() {
        val session = fixSession
        // 桥不可用时也要清掉内存里那份，否则用户点了没反应
        bridge?.clearFixStore()
        fixStoreHolder.clear()
        session?.resetStore()
        showToast("已清空存档")
    }

    /**
     * 单条生成：用户在顶部 AI 条里写一个曲名，让模型补出这一条的五个字段。
     *
     * 与旧版「整表补全」的关系：那条路（挑出所有待补行 → 一次问全表 → 落地）
     * 在逐条校对的模型下已经没有位置了 —— 用户眼前只有一条，问全表没有意义。
     * 保留的 [AiFill.parseReply] 等纯逻辑不变，只是调用点换成了单条。
     *
     * 结果**只进 `aiInjected`**（要用户点「填入本条」），不直接改建议表：
     * AI 不知道自己在改哪一行，直接落地会把数据改烂（见 [AiFill.looksRelated]）。
     *
     * 用的配置是**会话手上那一份**（`session.aiConfig`），不是 `state.aiConfig`：
     * 会话可能比设置更早建立，而配置在开机时就已经从存档注入进会话了。
     * 两处读同一个值反而会让人以为它们是两个独立开关。
     */
    suspend fun runAiOne(prompt: String) {
        val session = fixSession ?: return
        // 搜索兜底进行中不接受叠加的生成请求：两轮模型调用正悬着，
        // 再点生成会把状态搅在一起（原型同一条规则）
        if (session.aiSearching) return
        val q = prompt.trim()
        if (q.isEmpty()) {
            session.aiError = "先写点什么"
            return
        }
        val config = session.aiConfig
        if (!config.ready) {
            session.aiError = "还没填接口地址或密钥，去「我的 → AI 设置」里填一下"
            return
        }
        session.aiBusy = true
        // 兜底的「搜过 / 来源 / 说明」属于**上一遍结果**，必须先清掉 ——
        // 否则新结果天生顶着「已联网搜过」的标签，来源还是上一条的
        session.clearAiSearchState()
        session.lastAiQuery = q
        session.aiError = null
        session.aiStatus = null
        // 第一遍调用真的走通了吗（接口层成功，解析成败不论）——
        // 接口层就失败了（密钥错 / 断网）就不兜底：那是配置问题，不是认不出
        var apiOk = false
        try {
            val reply = createAiBridge().chat(AiFill.buildOneMessage(q), config)
            if (!reply.ok) {
                session.aiError = reply.error ?: "调用失败"
            } else {
                apiOk = true
                val one = AiFill.parseOneReply(reply.text)
                if (one == null) {
                    // 只有**真的读不懂**才走到这：模型答非所问、吐了一整段散文。
                    session.aiError = "没从回答里解析出可用内容"
                    session.aiUnknown = true
                } else {
                    // 解析干净但一条都没有（提示词允许的 `[]`）与「五个值全空」是同一种结果：
                    // 模型老实说它认不出这首曲子。这时**不能**报「解析失败」——
                    // 用户会以为程序坏了、去改接口地址，白折腾一轮。
                    // 交给 landAiResult 置 aiUnknown，界面显示「没认出这首曲子」就好。
                    session.landAiResult(one)
                    if (one.isBlank) session.aiError = null
                }
            }
        } catch (e: Exception) {
            session.aiError = "调用出错：${e.message ?: e.javaClass.simpleName}"
        } finally {
            session.aiBusy = false
        }
        // 联网兜底：第一遍没认出（整条空 / 只回曲名 / 回了个读不懂的）→
        // 自动搜一轮网页资料、带着资料再问一遍。搜索词用**点生成那会儿的字**。
        if (apiOk && config.searchReady &&
            SearchFallback.eligible(session.aiResult, session.aiUnknown)
        ) {
            runSearchFallback(session, q)
        }
    }

    /** 把当前的 AI 结果复制成一段文字（「复制」那个小按钮） */
    fun copyAiResult() {
        val session = fixSession ?: return
        val r = session.aiResult ?: return
        val bridge = bridge ?: run {
            showToast("剪贴板不可用")
            return
        }
        val text = listOf(
            "曲名：" + r.title,
            "作曲家：" + r.composer,
            "乐器：" + r.instrument,
            "乐曲类型：" + r.genre,
            "调性：" + r.key,
        ).joinToString("\n")
        session.aiStatus = if (bridge.copyText(text)) "已复制" else "复制失败"
    }

    /**
     * 联网兜底的**执行体**：搜一轮资料 → 带着资料把模型再问一遍。
     *
     * 规格（原型 `fix-stepper-demo.html` 的联网段是权威文本）：
     * - 只搜**一轮**：二问仍不认得就明说，不三问；
     * - 搜不到老实说没找到，绝不靠曲名硬编；
     * - 二问的结果**照样过闸门**（looksRelated / titleOnly / 勾选），
     *   联网不是通行证；
     * - 认出来了必须亮**来源**（域名，最多 3 条）。
     *
     * ## 翻页作废
     *
     * 搜索 + 二问是两次真正的网络往返，期间用户完全可能翻页。
     * 回来时核对行号：变了就说明这份资料是给**上一条**查的，
     * 整个兜底状态原样清掉 —— 绝不能把 A 条搜回来的东西落到 B 条头上
     * （`clearAiSearchState` 顺带清掉搜索中的提示，界面立刻干净）。
     * 这条在纯逻辑层管不着协程，只能在这里落实，所以它是**私有**的：
     * 入口只有 [runAiOne]（自动）与 [runAiSearchFallback]（手动）两个。
     */
    private suspend fun runSearchFallback(session: FixSession, prompt: String) {
        if (session.aiSearching) return
        val query = SearchFallback.buildQuery(prompt, session.currentRow?.fileName)
        val at = session.cursor
        // 第一遍可能留下「没解析出内容」的红字 —— 既然要再试一轮，先摘掉，
        // 不然搜索中那行提示和旧红字挤在一起，用户分不清哪句是最新的
        session.aiError = null
        session.aiSearching = true
        session.aiSearched = true
        try {
            val reply = createSearchBridge().search(query, session.aiConfig.searchKey)
            if (!reply.ok) {
                session.aiSearchNote = "搜索失败：${reply.error ?: "原因不明"}"
                return
            }
            val hits = SearchFallback.parseTavily(reply.json)
            if (hits.isEmpty()) {
                // 老实说没找到。aiSearchNote 留空，界面用默认措辞「资料里没有可靠信息」
                return
            }
            val second = createAiBridge().chat(
                SearchFallback.buildSecondMessages(query, hits),
                session.aiConfig,
            )
            if (!second.ok) {
                session.aiSearchNote = "资料抓到了，但第二次识别没成功：${second.error ?: "原因不明"}"
                return
            }
            val outcome = SearchFallback.adjudicate(second.text)
            if (outcome.result != null) {
                session.landAiResult(outcome.result)
                session.aiViaSearch = true
                session.aiSources = SearchFallback.domainsOf(hits)
            }
            session.aiSearchNote = outcome.note
        } catch (e: Exception) {
            session.aiSearchNote = "搜索出错：${e.message ?: e.javaClass.simpleName}"
        } finally {
            if (fixSession !== session || session.cursor != at) {
                session.clearAiSearchState()
            } else {
                session.aiSearching = false
            }
        }
    }

    /**
     * 「联网搜一次」手动键：黄条上那个。
     *
     * 与自动兜底走同一条路（[runSearchFallback]），差别只有两处：
     * - 密钥没配时给一句**能行动的**提示（自动兜底在 searchReady 为 false 时
     *   干脆不触发，所以永远轮不到报这句错）；
     * - 搜索词优先用 [FixSession.lastAiQuery]（点生成那会儿的字），
     *   没生成过才退回现在框里的 —— 两次之间用户可能已经改了框，
     *   拿改过的字去搜，资料对不上这条，结果还是落不到点上。
     */
    suspend fun runAiSearchFallback() {
        val session = fixSession ?: return
        if (session.aiSearching) return
        if (session.aiConfig.searchKey.isBlank()) {
            session.aiError = "联网兜底还没配好：去「我的 → AI 设置」填搜索密钥（Tavily）"
            return
        }
        val q = session.lastAiQuery.ifBlank { session.aiPrompt }
        if (q.isBlank()) {
            session.aiError = "先写点什么"
            return
        }
        runSearchFallback(session, q)
    }

    /**
     * 联网兜底总开关（AI 条上的「联网」徽章）。
     *
     * 改的是**中枢这份**配置，会话那份跟着同步 —— 会话的 aiConfig 是注入的拷贝，
     * 不同步的话，开关在 AI 条上按了、兜底照样按旧值跑。
     *
     * 立刻落盘而不是等关页：开关是全局偏好，而且关校对页的那条路
     * （[persistFixStore]）会用中枢的 aiConfig 覆盖存档 ——
     * 开关状态本身不会丢，但用户点完就杀掉 App 的场景下，
     * 存档里的进度与开关是否一致就说不清了，宁可当场写一次。
     */
    fun toggleSearchFallback() {
        aiConfig = aiConfig.copy(searchOn = !aiConfig.searchOn)
        val session = fixSession
        if (session != null) {
            session.aiConfig = aiConfig
            persistFixStore(session)
        } else {
            fixStoreHolder.update(fixStoreHolder.load().copy(ai = aiConfig))
        }
    }

    /**
     * 把采纳的改动写回曲库。
     *
     * 只对 [FixSource.Library] 有意义 —— CSV 来源的出口是「导出文件」，
     * 由用户自己导回 iPad，App 这边不该擅自改曲库。
     *
     * 逐条走 [FixSession.takenFields]，它已经把 AI 注入的值合并进来了
     * （只走规则建议的话，界面上明明用 AI 改过、写回曲库却没变）。
     *
     * @return 实际改动的乐谱数
     */
    fun applyFixToLibrary(): Int {
        val session = fixSession ?: return 0
        if (session.source != FixSource.Library) return 0
        var changed = 0
        session.rawRows.indices.forEach { i ->
            val score = session.scores.getOrNull(i) ?: return@forEach
            val fields = session.takenFields(i)
            if (fields.isEmpty()) return@forEach
            val next = LibraryAdapter.applyTo(score, session.proposalOf(i), fields)
            if (next == score) return@forEach
            val index = allScores.indexOfFirst { it.id == score.id }
            if (index >= 0) {
                allScores[index] = next
                // 详情是覆盖视图、持的是快照，正开着这条就一并换掉，否则显示旧值
                if (detail?.id == score.id) detail = next
                changed++
            }
        }
        return changed
    }

    /**
     * 落地全部采纳的改动。两个来源的出口不同：
     *
     * - [FixSource.Csv]：生成新 CSV 交系统分享出去，用户自己导回 iPad。
     *   App 不碰他的曲库 —— 那份数据在 iPad 上，App 改了也没用。
     * - [FixSource.Library]：原地更新 [allScores]。
     *
     * 成功后关闭校对页并提示结果。**失败时留在页面上**，
     * 否则用户看不到失败原因就得重新走一遍选文件。
     */
    fun commitFix() {
        val session = fixSession ?: return
        when (session.source) {
            FixSource.Csv -> {
                val bridge = bridge
                if (bridge == null) {
                    showToast("导出能力还没就绪")
                    return
                }
                val text = session.exportCsv()
                val name = "scores-fixed-${nowStamp()}.csv"
                if (bridge.shareText(text, name)) {
                    showToast("已导出，导回 iPad 后即可生效")
                    closeFix()
                } else {
                    showToast("导出失败，没有可用的分享目标")
                }
            }

            FixSource.Library -> {
                val n = applyFixToLibrary()
                showToast(if (n > 0) "已更新 $n 首乐谱" else "没有实际改动")
                closeFix()
            }
        }
    }

    // ---------------------------------------------------------------- AI 调用
    //
    // 旧版的「整表补全」三个入口（buildAiRequest / runAi / applyPastedAi）已经删掉：
    // 逐条校对的模型下，用户眼前只有一条，问全表没有意义。单条生成见 [runAiOne]。
    // AiFill 里的解析、提示词、字段表这些纯逻辑原样保留，只是调用点换了。

    // ---------- 派生数据 ----------
    val visibleScores: List<Score>
        get() = LibraryQuery.sorted(
            LibraryQuery.visible(activePool, query, filters),
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
        LibraryQuery.facetCount(activePool, query, pending, dim, value)

    val pendingResultCount: Int
        get() = LibraryQuery.pendingCount(activePool, query, pending)

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
     * 导入一份 forScore 导出的 CSV，开一次校对会话。
     *
     * 这条路**不落曲库**：CSV 是曲库之外的一份独立数据（来源是 iPad 上的
     * forScore），校对完导出成新文件给用户导回去。把它塞进曲库毫无意义，
     * 两者的 `Filename` 也未必对得上。
     */
    fun importCsvForFix(uri: String) {
        val bridge = bridge ?: run {
            showToast("当前环境不支持导入")
            return
        }
        val text = bridge.readTextFile(uri)
        if (text == null) {
            showToast("读取失败，请确认选的是 CSV 文件")
            return
        }
        openFixFromCsv(text)
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
        imported = imported + ImportedScore(
            title = score.title,
            composer = score.composer,
            type = score.type,
            instrument = score.instrument,
            period = score.period,
            level = score.level,
            source = score.source,
            pages = score.pages,
            dateAdded = score.dateAdded,
            filePath = score.filePath ?: "",
            thumbSeed = score.thumbSeed,
            thumbRows = score.thumbRows,
        )
        persistLibrary()
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
        // 网盘条目：写 netMetas（按远端路径索引），**只存本机，不写回网盘**
        val netIndex = (id - netIdBase).toInt()
        if (netIndex >= 0 && netIndex < netItems.size) {
            val base = netItemToScore(netItems[netIndex], id)
            val applied = draft.applyTo(base)
            netMetas = NetLibrary.saveMetaDraft(netMetas, base.remotePath!!, draft.fieldsOf(applied))
            persistLibrary()
            if (detail?.id == id) detail = netdiskScores.getOrNull(netIndex)
            showToast("已保存 · 只存在本机，不会写回网盘")
            closeSheet()
            return
        }
        val index = allScores.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = draft.applyTo(allScores[index])
            allScores[index] = updated
            localMetas = saveLocalMeta(localMetas, scoreKeyOf(updated), draft.fieldsOf(updated))
            persistLibrary()
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
        // 网盘条目不在手机上：删它等于「让我再也找不到这份谱」，而它还好好躺在网盘里
        if (score.isNet) {
            showToast("网盘乐谱不能在手机里删。" + "取消绑定，或到网盘里删文件")
            sheet = SheetKind.None
            return
        }
        val key = scoreKeyOf(score)
        val path = score.filePath
        if (path != null && imported.any { it.filePath == path }) {
            imported = imported.filterNot { it.filePath == path }
        } else {
            // 内置谱不落盘，只能记「这份被删过」，否则下次启动它又会回来
            localHidden = localHidden + key
        }
        localMetas = localMetas - key
        allScores.removeAll { it.id == score.id }
        persistLibrary()
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
        // 网盘条目要先下载，而下载是 suspend 的 —— 点击回调里起不了协程，
        // 记一次「请求」交给 `AppRoot` 的 effect 去做（与 pendingPick 同一套路）。
        // 每次都换一个新对象：key 变了 effect 才会重跑（连点同一份也算一次新请求）
        val remote = score.remotePath
        if (!remote.isNullOrBlank()) {
            netOpenRequest = NetLibrary.nextOpenRequest(netOpenRequest, remote)
            return
        }
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
        libraryTab = LibraryTab.Local
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
        libraryTab = LibraryTab.Local
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

    // ---------- 乐谱库持久化（library.json） ----------
    //
    // 网盘入库要求「用户改过的元数据跨同步、跨重启都在」；既然要落盘，
    // 本机那一半顺手一起做 —— 否则会出现一件很怪的事：
    // 网盘谱子的修改记得住，本机谱子的记不住。
    //
    // 存的是**改过的字段**（不是整份 Score）：默认值由 SampleLibrary / 远端决定，
    // 只记差异，将来改默认值时老存档不会把旧值钉死。

    /** 网盘入库的条目 */
    var netItems by mutableStateOf<List<NetLibItem>>(emptyList())
        private set

    /** 网盘条目上用户改过的字段（key → 字段）。**跨同步永久保留**，条目消失也不删 */
    var netMetas by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
        private set

    /** 绑定进乐谱库的文件夹。存的是**完整远端地址** */
    var netBound by mutableStateOf<List<String>>(emptyList())
        private set

    /** 上次同步成功的时刻；0 = 从没同步过 */
    var netSyncedAt by mutableStateOf(0L)
        private set

    var netSyncing by mutableStateOf(false)
        private set

    /** 同步失败的原因（人话）；null = 这次没失败。
     *  **失败时列表保持上次的内容**，绝不能把首页清空 */
    var netSyncError by mutableStateOf<String?>(null)
        private set

    /** 本机条目上用户改过的字段 */
    var localMetas by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
        private set

    /** 导入进来的谱子（重启后靠它重建曲库条目） */
    var imported by mutableStateOf<List<ImportedScore>>(emptyList())
        private set

    /** 删掉过的本机条目 key。不记的话重启会复活 */
    var localHidden by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 「打开网盘页里某一份」的请求；null = 没在请求。下载是 suspend 的，
     *  点击回调起不了协程，因此交给 `AppRoot` 的 effect 去消费。
     *
     *  **effect 拿这个对象当 key，所以它绝不能回写这个对象。** 旧写法是
     *  `netPendingOpen = null` 先置空再干活 —— 那正是它自己的 key：Compose 会取消
     *  正在跑的下载协程，而 IO 块不看取消、文件照旧写完，收尾就永远不执行
     *  → 界面停在「已下载，正在打开…」（1.23 真机定位）。 */
    var netOpenRequest by mutableStateOf<NetOpenRequest?>(null)
        private set

    /** 真的正在下载的那一份（给卡片显示「下载中…」）。
     *  **与 [netOpenRequest] 分开**：前者是「请求」，这个是「正在执行」，
     *  否则下载函数一改请求，effect 会被自己再触发一次 —— 无限下载。 */
    var netOpeningKey by mutableStateOf<String?>(null)

    /** 首页网盘页正在下载那一份的名字（浮层标题） */
    var netOpenTitle by mutableStateOf<String?>(null)
        private set

    /** 浮层副标题（体积）。下载时给个量级，比一根条更有实感 */
    var netOpenSubtitle by mutableStateOf<String?>(null)
        private set

    /** 首页网盘页的下载进度 0–100；**-1 = 服务端没给总长度**，显示「正在下载…」 */
    var netOpenProgress by mutableStateOf(-1)
        private set

    /** 已收 / 总字节。浮层拿它显示「2.1 / 5.6 MB」—— 只给百分比看不出「到底在动没有」 */
    var netOpenDone by mutableStateOf(0L)
        private set

    /** 服务端给的总长度；0 = 没给（此时不报百分比） */
    var netOpenTotal by mutableStateOf(0L)
        private set

    /** 首页网盘页下载失败的人话原因；null = 这次没失败 */
    var netOpenError by mutableStateOf<String?>(null)
        private set

    /**
     * 失败的技术细节（`HTTP 404` / `MalformedURLException: no protocol`）。
     * 浮层上灰字显示 —— 分类码只会说「网络不通」，
     * 而「请求没发出去」和「服务器拒了」翻出来是同一句，谁也看不出区别。
     */
    var netOpenDetail by mutableStateOf<String?>(null)
        private set

    /** 网盘条目的 id 从这个基数往上编，与本机条目（从 1 开始）隔开 */
    private val netIdBase: Long get() = 900_000L

    /** 派生：网盘条目 → 曲库条目。**不进 allScores** —— 两个标签页各用各的池子 */
    val netdiskScores: List<Score>
        get() = netItems.mapIndexed { i, item ->
            netItemToScore(NetLibrary.applyMeta(item, netMetas[item.key]), netIdBase + i)
        }

    /** 本机条目：内置样例 + 导入的谱子，**去掉删掉过的** */
    val localScores: List<Score>
        get() = allScores.filter { scoreKeyOf(it) !in localHidden }

    /** 当前标签页用的是哪个池子 */
    private val activePool: List<Score>
        get() = when (libraryTab) {
            LibraryTab.Netdisk -> netdiskScores
            LibraryTab.Sets -> allScores
            LibraryTab.Local -> localScores
        }

    /** 网盘条目 → Score。filePath 只在**已缓存**时才有值（否则要先下载） */
    private fun netItemToScore(item: NetLibItem, id: Long): Score = Score(
        id = id,
        title = item.title,
        composer = item.composer,
        type = item.type,
        instrument = item.instrument,
        period = item.period,
        level = item.level,
        source = item.source,
        pages = 0,
        dateAdded = 0L,
        filePath = netdiskCache[item.key]?.localPath,
        remotePath = item.remotePath,
        thumbSeed = (item.key.hashCode() and 0x7fffffff) % 997,
        thumbRows = 5,
    )

    /** 网盘条目的缓存标记：已缓存 / 下载中 / 需下载。不写清楚用户会以为点了没反应 */
    fun netBadgeOf(score: Score): String {
        val key = score.remotePath ?: return ""
        if (netdiskCache.containsKey(key)) return "已缓存"
        return if (netOpeningKey == key) "下载中…" else "需下载"
    }

    /** 启动时读一次存档。**在 `installBundledScores` 之后调用** */
    fun loadLibraryStore() {
        val obj = runCatching { MiniJson.parse(bridge?.readLibraryJson() ?: "") }.getOrNull()
            as? MiniJson.Value.Obj
        val net = NetLibStore.fromValue(obj?.fields?.get("net"))
        val local = LocalStore.fromValue(obj?.fields?.get("local"))
        netItems = net.items
        netMetas = net.metas
        netBound = net.bound
        netSyncedAt = net.syncedAt
        localMetas = local.metas
        localHidden = local.hidden
        imported = local.imported

        // 本机：先把用户改过的字段盖回去，再把导入的谱子加回来，最后去掉删掉过的
        for (i in allScores.indices) {
            val meta = localMetas[scoreKeyOf(allScores[i])]
            if (meta != null) allScores[i] = applyLocalMeta(allScores[i], meta)
        }
        // id 要**逐个递增**：`map { it.toScore(nextId()) }` 会在 addAll 之前全算完，
        // 而 nextId 取的是当前最大值 —— 那样每一份都会拿到同一个 id
        var next = (allScores.maxOfOrNull { it.id } ?: 0L) + 1
        val restored = local.imported.map { it.toScore(next++) }
        allScores.addAll(0, restored)
        if (localHidden.isNotEmpty()) {
            allScores.removeAll { scoreKeyOf(it) in localHidden }
        }
    }

    /** 落盘。任何一处改动（改元数据 / 导入 / 删除 / 绑定 / 同步）之后都要调一次 */
    private fun persistLibrary() {
        val json = buildString {
            append("{\"v\":1,\"net\":")
            append(NetLibStore(netItems, netMetas, netBound, netSyncedAt).toJson())
            append(",\"local\":")
            append(LocalStore(localMetas, imported, localHidden).toJson())
            append('}')
        }
        bridge?.writeLibraryJson(json)
    }

    /**
     * 同步网盘库：把每个绑定目录**当前层**的 PDF 拉回来。
     *
     * 只做三件事：加新条目、删消失的条目、更新体积 —— 用户改过的元数据不动
     * （语义见 `NetLibrary.syncLibrary`）。失败时**保留上次列表**并说清原因。
     */
    suspend fun syncNetLibrary(force: Boolean) {
        if (netSyncing) return
        if (netBound.isEmpty()) {
            netSyncError = null
            return
        }
        val cfg = netdiskConfig
        if (!cfg.ready) {
            netSyncError = "还没配网盘"
            return
        }
        // 节流：切走再切回来不该每次都拉一次
        if (!force && netSyncedAt > 0L && nowMillis() - netSyncedAt < SYNC_THROTTLE_MS) return

        val boundAt = netBound
        netSyncing = true
        netSyncError = null
        val dirs = ArrayList<RemoteDir>()
        var failure: String? = null
        for (path in boundAt) {
            val reply = createNetdiskBridge().propfind(path, cfg.user, cfg.pass)
            if (!reply.ok) {
                failure = Netdisk.errorText(reply.kind.ifBlank { "none" })
                break
            }
            // path 一律自己拼：服务端给的 href 可能只是绝对路径
            val listed = Netdisk.parsePropfind(reply.xml, path)
                .map { it.copy(path = Netdisk.joinPath(path, it.name)) }
            dirs.add(RemoteDir(path, listed))
        }
        // 同步途中用户改了绑定（或退出了），这一轮结果作废
        if (netBound != boundAt) {
            netSyncing = false
            return
        }
        if (failure != null) {
            netSyncing = false
            netSyncError = failure
            showToast("同步失败：$failure · 现在看到的是上次的列表")
            return
        }
        val res = NetLibrary.syncLibrary(netItems, netMetas, dirs)
        netItems = res.items
        netSyncedAt = nowMillis()
        netSyncError = null
        netSyncing = false
        persistLibrary()
        showToast(
            if (res.added > 0 || res.removed > 0) {
                "已同步 · 新增 ${res.added} 份、移除 ${res.removed} 份"
            } else {
                "已同步 · 没有变化"
            },
        )
    }

    /** 同步条文案。三种态必须都能说清：同步中 / 已同步（多久前）/ 失败（显示多久前的列表） */
    val netSyncText: String
        get() = NetLibrary.syncStatusText(
            NetSyncState(
                busy = netSyncing,
                boundCount = netBound.size,
                boundPaths = netBound,
                error = netSyncError,
                syncedAt = netSyncedAt,
                ageMs = if (netSyncedAt > 0L) nowMillis() - netSyncedAt else 0L,
            ),
        )

    /** 浏览页当前目录是否已绑定 */
    fun netdiskCurrentBound(): Boolean = NetLibrary.isBound(netBound, netdiskUrl(netdiskCwd))

    /** 绑定 / 解绑网盘浏览页当前所在的文件夹 */
    fun toggleBindCurrent() {
        toggleBind(netdiskUrl(netdiskCwd))
    }

    fun toggleBind(path: String) {
        netBound = NetLibrary.toggleBound(netBound, path)
        persistLibrary()
        showToast(
            if (NetLibrary.isBound(netBound, path)) {
                "已绑定 · 回首页「网盘」页同步一次"
            } else {
                "已解绑，库里的条目会在下次同步时移除"
            },
        )
    }

    /**
     * 打开一份网盘乐谱：命中缓存直接开，否则下载 → 入缓存 → 开。
     * 缓存**只留最近 1 份**（打开新的，上一份连文件一起删）。
     *
     * 失败时**故意留着** [netOpeningKey]：浮层要显示是哪一份失败、并给「重试」。
     * 一声不响地退回列表，用户只会以为自己点错了。
     */
    suspend fun openNetdiskLibraryItem(key: String) {
        val item = netItems.firstOrNull { it.key == key }
        if (item == null) {
            // 静默 return 就是「点了没反应」——必须说一句
            showToast("这份谱子不在库里了，回网盘页同步一次")
            return
        }
        // 同一份真的在下载就别重入；但**失败态必须允许重试**，否则「重试」会被自己挡死。
        // 判据要带上 key：用户在下一份谱子时不该被上一份挡掉，那样只会卡着不动
        if (netOpeningKey == key && netOpenError == null) return
        if (!netdiskConfig.ready) {
            netOpeningKey = null
            netOpenTitle = null
            showToast("先去「我的 → 网盘」填地址、账号和口令")
            return
        }
        netOpeningKey = key
        netOpenTitle = item.title
        netOpenSubtitle = Netdisk.formatSize(item.size).ifBlank { null }
        netOpenProgress = -1
        netOpenDone = 0L
        netOpenTotal = 0L
        netOpenError = null
        netOpenDetail = null
        val hit = netdiskCache[key]
        if (hit != null) {
            netdiskCache = Netdisk.evictCache(netdiskCache, key)
            netOpeningKey = null
            netOpenTitle = null
            showToast("来自缓存，没有重新下载")
            openNetdiskPdf(hit.localPath, item.title)
            return
        }
        val dir = bridge?.netdiskCacheDir()
        if (dir == null) {
            netOpeningKey = null
            netOpenTitle = null
            showToast("网盘缓存目录不可用")
            return
        }
        val dest = "$dir/${Netdisk.cacheFileName(item.remotePath)}"
        // 服务端给的 href 不带 host（见 Netdisk.absoluteUrl 的注释）：
        // 少了这一步请求根本发不出去，每份谱子都会报「网络不通」
        val reply = createNetdiskBridge().download(
            url = Netdisk.absoluteUrl(netdiskConfig.addr, item.remotePath),
            user = netdiskConfig.user,
            pass = netdiskConfig.pass,
            destPath = dest,
            onProgress = { pct, done, total ->
                netOpenProgress = pct
                netOpenDone = done
                netOpenTotal = total
            },
        )
        // 收尾一律**不可取消**：走到这里字节已经落盘（进度都到 100 了），
        // 剩下的只是记账与开阅读器。被取消就整段跳过的话，界面会永远停在
        // 「已下载，正在打开…」—— 既不报错、也没得等（1.23 真机）
        withContext(NonCancellable) {
            if (!reply.ok) {
                // 只有还是当前这一份才去写错误，否则会抢别人的浮层
                if (netOpeningKey == key) {
                    netOpenError = Netdisk.errorText(reply.kind.ifBlank { "none" })
                    netOpenDetail = reply.detail.ifBlank { null }
                }
                return@withContext
            }
            // 文件确实躺在缓存目录里了：记账无条件做，否则下次点同一份还要重下
            val kept = Netdisk.evictCache(netdiskCache, key)
            netdiskCache.forEach { (k, v) -> if (k != key) bridge?.deleteLocal(v.localPath) }
            netdiskCache =
                kept + (key to NetdiskCached(item.title, item.size, item.remotePath, dest))
            // 但浮层和阅读器只归「当前这一份」：用户关掉了浮层、或已经换成别的谱子，
            // 就不该被这一份抢走
            if (netOpeningKey != key) return@withContext
            netOpeningKey = null
            netOpenTitle = null
            showToast("已下载到缓存 · ${Netdisk.formatSize(item.size)} · 看完就丢")
            openNetdiskPdf(dest, item.title)
        }
    }

    /** 重试首页网盘页那一份失败的下载（走与首次点击同一个 effect） */
    fun retryNetOpen() {
        val key = netOpeningKey ?: return
        netOpenError = null
        netOpenDetail = null
        netOpenRequest = NetLibrary.nextOpenRequest(netOpenRequest, key)
    }

    /** 关掉首页网盘页的下载浮层 */
    fun dismissNetOpen() {
        netOpeningKey = null
        netOpenTitle = null
        netOpenSubtitle = null
        netOpenError = null
        netOpenDetail = null
        netOpenProgress = -1
        netOpenDone = 0L
        netOpenTotal = 0L
    }

    // ---------- 网盘（AList / WebDAV） ----------
    //
    // 语义写在 domain/netdisk/Netdisk.kt 的文件头：只认标准 WebDAV、
    // 只在线打开不入库、缓存只留最近 1 份。这里只放状态与动作，
    // 判断（哪些能列、路径怎么拼、错误怎么翻人话）一律在纯逻辑层。

    /** 连接配置。明文落 `filesDir/netdisk.json`，与 [AiConfig] 同一套取舍 */
    var netdiskConfig by mutableStateOf(NetdiskConfig())
        private set

    /** 设置面板是否盖在列表上 */
    var netdiskSetupOpen by mutableStateOf(false)
        private set

    /** 当前目录。**相对根**的路径（`/钢琴/拜厄`）；根目录是空串 */
    var netdiskCwd by mutableStateOf("")
        private set

    /** 当前目录列出来的东西。**含非 PDF** —— 界面自己挑，[Netdisk.skippedCount] 要数它们 */
    var netdiskEntries by mutableStateOf<List<NetdiskEntry>>(emptyList())
        private set

    var netdiskLoading by mutableStateOf(false)
        private set

    /** 列目录失败的分类码（交给 [Netdisk.errorText] 翻人话）；null = 这次没失败 */
    var netdiskError by mutableStateOf<String?>(null)
        private set

    /** 缓存。**只留最近 1 份** */
    var netdiskCache by mutableStateOf<Map<String, NetdiskCached>>(emptyMap())
        private set

    /** 正在下载的那一份；null = 没在下载 */
    var netdiskOpening by mutableStateOf<NetdiskEntry?>(null)
        private set

    /** 下载进度 0–100；**-1 表示服务端没给总长度**，界面显示「正在下载…」而不是一根不动的条 */
    var netdiskProgress by mutableStateOf(-1)
        private set

    /** 与 [netOpenDone] 同理：浮层要能显示「2.1 / 5.6 MB」 */
    var netdiskDone by mutableStateOf(0L)
        private set

    /** 服务端给的总长度；0 = 没给 */
    var netdiskTotal by mutableStateOf(0L)
        private set

    /** 打开失败时给人看的一句话 */
    var netdiskOpenError by mutableStateOf<String?>(null)
        private set

    /** 打开失败的技术细节（HTTP 码 / 异常名），浮层灰字显示 */
    var netdiskOpenDetail by mutableStateOf<String?>(null)
        private set

    /** 「重试 / 测试连接」用的自增键：目录没变也要能重新发一次请求 */
    var netdiskTick by mutableStateOf(0)
        private set

    /** 网盘谱子没有库内 id，用它给阅读器生成互不相同的 key */
    private var readerKeySeq = 1_000_000L

    /** 从磁盘读一次网盘配置。启动时由 `MainActivity` 调一次 */
    fun loadNetdiskConfig() {
        netdiskConfig = NetdiskConfig.fromJson(bridge?.readNetdiskConfig())
    }

    fun openNetdisk() {
        push(Screen.Netdisk)
        // 没配过就直接把设置面板摊开 —— 一个空白列表对用户没有任何信息量
        netdiskSetupOpen = !netdiskConfig.ready
        netdiskTick++
    }

    fun closeNetdisk() {
        netdiskOpening = null
        netdiskOpenError = null
        netdiskSetupOpen = false
        if (navStack.lastOrNull() == Screen.Netdisk && navStack.size > 1) back()
    }

    fun openNetdiskSetup() { netdiskSetupOpen = true }

    fun closeNetdiskSetup() { netdiskSetupOpen = false }

    fun saveNetdiskConfig(config: NetdiskConfig) {
        val ok = bridge?.writeNetdiskConfig(NetdiskConfig.toJson(config)) ?: false
        netdiskConfig = config
        netdiskSetupOpen = false
        // 换了网盘就是换了地方，原来的层级没有意义
        netdiskCwd = ""
        netdiskTick++
        showToast(if (ok) "已保存" else "保存失败，配置只在本次运行有效")
    }

    fun clearNetdiskConfig() {
        netdiskConfig = NetdiskConfig()
        netdiskCwd = ""
        netdiskEntries = emptyList()
        netdiskError = null
        bridge?.writeNetdiskConfig(NetdiskConfig.toJson(NetdiskConfig()))
        showToast("已清空")
    }

    /**
     * 列当前目录。
     *
     * 由界面用 `LaunchedEffect(netdiskCwd, netdiskTick)` 驱动：
     * 换目录自然重跑，原地重试靠 [netdiskTick]。
     */
    suspend fun loadNetdiskDir() {
        val cfg = netdiskConfig
        if (!cfg.ready) {
            netdiskEntries = emptyList()
            netdiskError = null
            netdiskLoading = false
            return
        }
        val at = netdiskCwd
        val url = netdiskUrl(at)
        netdiskLoading = true
        netdiskError = null
        val reply = createNetdiskBridge().propfind(url, cfg.user, cfg.pass)
        // 请求悬着的时候用户可能已经翻页或退出，别把旧结果盖回新的目录上
        if (netdiskCwd != at || netdiskConfig != cfg) {
            netdiskLoading = false
            return
        }
        netdiskLoading = false
        if (!reply.ok) {
            netdiskEntries = emptyList()
            netdiskError = reply.kind.ifBlank { "none" }
            return
        }
        val listed = Netdisk.parsePropfind(reply.xml, url)
        // path 一律自己拼：服务端给的 href 可能只是绝对路径（`/dav/x.pdf`），
        // 拿它当地址去发请求必然失败
        netdiskEntries = listed.map { it.copy(path = Netdisk.joinPath(url, it.name)) }
        netdiskError = null
    }

    /** 顶栏「测试连接」：只探根目录，不动当前目录 */
    suspend fun testNetdisk() {
        val cfg = netdiskConfig
        if (!cfg.ready) {
            netdiskSetupOpen = true
            return
        }
        val url = netdiskUrl("")
        val reply = createNetdiskBridge().propfind(url, cfg.user, cfg.pass)
        if (reply.ok) {
            val n = Netdisk.parsePropfind(reply.xml, url).size
            netdiskError = null
            showToast("连上了 · $n 项")
        } else {
            val kind = reply.kind.ifBlank { "none" }
            netdiskError = kind
            showToast(Netdisk.errorText(kind))
        }
    }

    fun netdiskGo(rel: String) {
        if (netdiskLoading) return
        netdiskCwd = rel
    }

    /** 上一级。**已经在根就返回 false**（调用方据此决定是退出本页还是不动） */
    fun netdiskUp(): Boolean {
        val root = Netdisk.normPath(netdiskConfig.addr)
        val up = Netdisk.parentPath(netdiskUrl(netdiskCwd), root) ?: return false
        netdiskCwd = netdiskRelOf(up, root)
        return true
    }

    fun netdiskRetry() { netdiskTick++ }

    /**
     * 打开一份网盘谱子：命中缓存直接开，否则下载 → 入缓存 → 淘汰旧的 → 开。
     * 谱子**不进乐谱库**，看完就丢（缓存只留最近 1 份）。
     */
    suspend fun netdiskOpen(entry: NetdiskEntry) {
        // 失败时 netdiskOpening 是**故意留着**的（浮层要显示是哪一份失败了），
        // 所以这里只在「真的正在下载」时才拒绝重入 —— 否则「重试」会被自己挡死
        if (netdiskOpening != null && netdiskOpenError == null) return
        if (!netdiskConfig.ready) {
            showToast("先填网盘地址、账号和口令")
            return
        }
        val key = Netdisk.cacheKeyOf(entry.path)
        val hit = netdiskCache[key]
        if (hit != null) {
            netdiskCache = Netdisk.evictCache(netdiskCache, key)
            showToast("来自缓存，没有重新下载")
            openNetdiskPdf(hit.localPath, hit.name)
            return
        }
        val dir = bridge?.netdiskCacheDir()
        if (dir == null) {
            showToast("网盘缓存目录不可用")
            return
        }
        val dest = "$dir/${Netdisk.cacheFileName(entry.path)}"
        netdiskOpening = entry
        netdiskProgress = -1
        netdiskDone = 0L
        netdiskTotal = 0L
        netdiskOpenError = null
        val reply = createNetdiskBridge().download(
            url = Netdisk.absoluteUrl(netdiskConfig.addr, entry.path),
            user = netdiskConfig.user,
            pass = netdiskConfig.pass,
            destPath = dest,
            onProgress = { pct, done, total ->
                netdiskProgress = pct
                netdiskDone = done
                netdiskTotal = total
            },
        )
        if (!reply.ok) {
            // 留着 netdiskOpening，界面才知道是哪一份失败了
            netdiskOpenError = Netdisk.errorText(reply.kind.ifBlank { "none" })
            netdiskOpenDetail = reply.detail.ifBlank { null }
            return
        }
        // 下载途中用户关掉了浮层（或退出了本页）就别再开
        if (netdiskOpening == null) return
        // 只留最近 1 份：打开新的，上一份连文件一起删掉
        val kept = Netdisk.evictCache(netdiskCache, key)
        netdiskCache.forEach { (k, v) -> if (k != key) bridge?.deleteLocal(v.localPath) }
        netdiskCache = kept + (key to NetdiskCached(entry.name, entry.size, entry.path, dest))
        netdiskOpening = null
        // 直接进阅读器，没有界面能写这句话，所以走 Toast ——
        // 「只留最近 1 份、看完就丢」是用户该知道的事
        showToast("已下载到缓存 · ${Netdisk.formatSize(entry.size)} · 看完就丢")
        openNetdiskPdf(dest, entry.name)
    }

    /** 关掉下载浮层（失败时的「关闭」） */
    fun netdiskDismissOpen() {
        netdiskOpening = null
        netdiskOpenError = null
        netdiskProgress = -1
        netdiskDone = 0L
        netdiskTotal = 0L
    }

    /** 当前目录对应的完整远端地址 */
    private fun netdiskUrl(rel: String): String {
        var url = Netdisk.normPath(netdiskConfig.addr)
        for (seg in rel.split('/')) {
            if (seg.isNotBlank()) url = Netdisk.joinPath(url, seg)
        }
        return url
    }

    /** 把完整地址减掉根，还原成相对路径（面包屑与「上一级」用） */
    private fun netdiskRelOf(abs: String, root: String): String {
        val r = Netdisk.normPath(root)
        val s = Netdisk.normPath(abs)
        return if (r.isNotEmpty() && s.startsWith(r)) s.substring(r.length) else s
    }

    private fun openNetdiskPdf(path: String, title: String) {
        // 每次打开都换 key：换一份谱子必须重建文档状态，否则会读到上一份的内容
        reader = ReaderRequest(key = ++readerKeySeq, path = path, title = title)
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

    /** 规范化之后取 6 个可改字段。存的是**规范化后的值**，重启后界面显示才一致 */
    fun fieldsOf(applied: Score): Map<String, String> = mapOf(
        "title" to applied.title,
        "composer" to applied.composer,
        "instrument" to applied.instrument,
        "type" to applied.type,
        "period" to applied.period,
        "level" to applied.level,
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
