package com.example.scoreapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.scoreapp.domain.csvfix.AiField
import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.ColumnMap
import com.example.scoreapp.domain.csvfix.CsvAdapter
import com.example.scoreapp.domain.csvfix.CsvTable
import com.example.scoreapp.domain.csvfix.FixProposal
import com.example.scoreapp.domain.csvfix.FixProposer
import com.example.scoreapp.domain.csvfix.FixRow
import com.example.scoreapp.domain.csvfix.FixRules
import com.example.scoreapp.domain.csvfix.FixStore
import com.example.scoreapp.domain.csvfix.LibraryAdapter
import com.example.scoreapp.domain.csvfix.RuleSwitches
import com.example.scoreapp.domain.csvfix.ScoreKey
import com.example.scoreapp.domain.csvfix.SearchFallback
import com.example.scoreapp.domain.csvfix.parseCsv
import com.example.scoreapp.domain.csvfix.toCsv
import com.example.scoreapp.model.Score
import com.example.scoreapp.util.AiConfig

/**
 * 校对的**数据来源**。
 *
 * 两条来源的业务规则完全相同，差别只在于「改完之后写回哪里」：
 * - [Csv]：forScore 导出的 CSV，改完导出成新文件给用户导回 iPad；
 * - [Library]：App 自己的曲库，改完直接原地更新 [ScoreAppState.allScores]。
 *
 * 这正是 Jackson 拍板的「两者都要」—— 规则只有一份（[FixProposer] / [AiFill]），
 * 靠 [CsvAdapter] / [LibraryAdapter] 两个适配器接驳。
 */
enum class FixSource(val label: String) {
    Csv("forScore CSV"),
    Library("App 曲库"),
}

/** 界面上一行字段的静态描述：内部代号 + 中文名 */
data class FixFieldSpec(val key: String, val cn: String)

/**
 * 一条**已合并的建议**：规则给的 + AI 给的，供界面直接渲染。
 *
 * 界面不再自己去翻 `FixProposal` 的六七个可空字段，而是拿这一份拍平的视图。
 * 这么切的好处：合并逻辑（AI 覆盖规则、备选政策）只写一次、只测一次，
 * Compose 那边只剩画图。
 */
class FixEntryView(
    val row: FixRow,
    /** 每个字段的**候选新值**（null = 这一条没碰这个字段） */
    val proposed: Map<String, String?>,
    /** 被建议替换掉的原有值，键为字段代号 */
    val overwritten: Map<String, String>,
    /** 规则只提示、不修改的说明 */
    val notes: List<String>,
    /** AI 给的调名认不出来时的原文 */
    val keyRejected: String?,
    /** 这一条有 AI 注入过 */
    val hasAi: Boolean,
    /** AI 给了值但按「不覆盖」政策被挡下的字段：代号 → AI 的值（界面标「备选」） */
    val alternates: Map<String, String>,
    /**
     * 哪些字段的值是**用户自己填**的（界面挂绿「自填」标）。
     *
     * 自填的值是候选值里优先级最高的那一层，但光看值分不出来它从哪来 ——
     * 用户得知道「这条是我亲手写过的，不是 AI 猜的」。
     */
    val selfTyped: Set<String> = emptySet(),
    /**
     * 哪些字段被用户**明确要求清空**（自填里写了空值）。
     *
     * 跟 [selfTyped] 分开：那个是「我填了个值」，这个是「我要求它空」。
     * 界面给后者挂一个**灰「已清空」标**而不是绿「自填」标 ——
     * 用户得能一眼看出「这个空是我自己要的」，不然会以为 AI 又没给值、
     * 手滑再点一次生成把它填回来。
     */
    val blankedByUser: Set<String> = emptySet(),
) {
    fun proposedOf(key: String): String? = proposed[key]

    fun overwrittenOf(key: String): String? = overwritten[key]

    fun alternateOf(key: String): String? = alternates[key]

    /** 这个值是不是用户亲手打的 */
    fun isSelfTyped(key: String): Boolean = key in selfTyped

    /** 这个字段是不是用户明确要求清空的 */
    fun isBlanked(key: String): Boolean = key in blankedByUser

    /**
     * 这一条有任何可采纳的改动吗。
     *
     * **清空也算改动**。只判 `proposed` 的话，一条「只有清空、没有任何新值」的行
     * 会被判成「无需改动」，底下显示「这一条无需改动，直接翻下一条即可」——
     * 而它明明是要写东西出去的（把某格写成空），还可能因此被用户跳过。
     * 原型那边 `hasChange` 一直是这么算的（见 fix-stepper-demo.html），
     * 这里之前漏了，属于两边语义不一致。
     */
    val hasChange: Boolean
        get() = FIELD_ORDER.any { proposed[it] != null } || blankedByUser.isNotEmpty()

    companion object {
        /** 界面上的固定字段序。顺序照原型：曲名 → 作曲家 → 类型 → 编配 → 标签 → 来源 → 调性 */
        val FIELD_ORDER = listOf("title", "comp", "genre", "tag", "label", "ref", "keysf")
    }
}

/**
 * 一次逐条校对会话的全部状态。
 *
 * ## 为什么是「一屏一条」而不是长表
 *
 * 第一版做的是「一屏列出全部 549 行 + 逐行勾选 + 一次导出」。真机上用起来的问题是：
 * 一张几百行的表里，用户根本读不完；规则给出的建议里哪些是真错、哪些是误伤，
 * 必须**逐条对着上下文看**才判断得了。长表把「判断」压缩成了「扫一眼就勾」，
 * 于是覆盖类的误伤会被批量采纳 —— 那是最坏的结果。
 *
 * 换成一条一屏之后，用户看到的永远是「这一份乐谱 + 它的原值与建议」，
 * 判断成本降到最低；代价是必须一条条翻，所以配了：
 *  - **跨会话存档**（见 [FixStore]）：翻到一半退出，下次接着来；
 *  - **跳到下一条未处理**：跳过已经存过的，直接找还没决策的。
 *
 * ## 为什么是「逐字段采纳」而不是「整条采纳」
 *
 * 一条里往往只有一两个字段真的需要改。整条勾选意味着「改了标题就必须连作曲家一起收下」，
 * 用户只能二选一：全部接受，或者全部放弃自己动手。逐字段之后，
 * 每一条的每个字段各自一个勾，且**勾选即写入存档** —— 不需要另一个「保存」动作，
 * 因为逐条翻阅的场景里，用户随时可能退出。
 *
 * ## 采纳粒度与「行级 / 字段级」的取舍
 *
 * 存档粒度是字段级（`Filename → {title, comp}`），而不是行级的布尔值。
 * 行级太粗：用户只采纳了作曲家、把标题留着没动，下次进来看不出他到底决定过什么。
 * 字段级还让「取消采纳」退化成「从集合里删一个元素」，不需要任何额外状态。
 */
class FixSession(
    val source: FixSource,
    val header: List<String>,
    val columns: ColumnMap,
    /** 原始行（[FixRow]）与对应的原始单元格。CSV 来源时是 CSV 行，曲库来源时是空列表 */
    val rawRows: List<FixRow>,
    private val rawCells: List<List<String>>,
    /** 曲库来源时对应的乐谱；CSV 来源全是 null */
    val scores: List<Score?> = emptyList(),
    /** CSV 来源时的表头行原文，导出要原样写回 */
    private val originalHeader: List<String> = header,
    /** 初始存档（从 [FixStore] 读出来） */
    initialStore: FixStore = FixStore.EMPTY,
) {
    /** 规则开关。改任一开关都会重算全部建议（见 [recompute]） */
    var switches by mutableStateOf(RuleSwitches())

    /** 「AI 也在已有值上提建议」—— 默认关闭，用户要显式开 */
    var aiSuggestExisting by mutableStateOf(false)

    /**
     * 本次会话要用的 AI 接口设置。
     *
     * 为什么会话自己持有一份、而不是去读 [ScoreAppState]：本类住在 `ui` 包但
     * **不持有状态中枢的引用**（它连平台桥都够不到），而 AI 调用发生在
     * `ScoreAppState.runAiOne` 里。会话只是一份数据的容器，配置得由外面推进来。
     *
     * 由 `openFixFromCsv` / `openFixFromLibrary` 在构造时从 `state.aiConfig` 注入。
     * 不这样做的话，用户在设置页里填的地址与密钥**在校对页根本不会被用到** ——
     * 生成时仍然拿默认地址和空密钥去请求，是必失败的一条死路。
     */
    var aiConfig by mutableStateOf(AiConfig())

    /** 「跳过合集子条目」—— 那些行的标题是页码片段，清洗规则会误伤 */
    var skipBookmarks by mutableStateOf(true)

    /** 规则产出的基础建议。AI 注入是**叠在它之上**的，不写回这里 */
    private var baseProposals: List<FixProposal> = rawRows.map { propose(it) }

    // ---------------------------------------------------------------- 存档

    /** 跨会话存档：`Filename → 已采纳字段集合` + 上次位置 */
    var store by mutableStateOf(initialStore)
        private set

    /**
     * 当前条的**临时勾选**（还没点「采纳并继续」）。
     *
     * 与 [store] 的关系：`draft` 是「这一条正在编辑中」，`store` 是「已经定下的」。
     * 勾选会**立即写入 store**（逐条翻阅时用户随时可能退出，不该让他再点一次保存），
     * `draft` 只用于「还没离开这一条」这个瞬间的界面状态。
     */
    var draft by mutableStateOf(emptySet<String>())
        private set

    /** AI 结果按条号暂存，[viewOf] 时合并进候选项。翻页即清（结果属于上一条） */
    private val aiInjected = mutableMapOf<Int, Map<String, String>>()

    /** 被用户勾选「提升为正式建议」的 AI 备选字段（key = `"条号|字段代号"`） */
    private val promoted = mutableSetOf<String>()

    /**
     * 用户自己填的值：`条号 → {字段代号 → 值}`。
     *
     * 值的三个来源**优先级**：规则 < AI < **自填**。自填压过一切 ——
     * 包括 AI 给的值：AI 猜错了，用户以前只能「不勾」把它整个丢掉，
     * 现在能直接改成对的。
     *
     * 这里只存**会话内**的那一份（键是条号，换了 CSV 就错位）；
     * 真正的跨会话持久化由 `setTypedValue` 顺手写进 [store] 完成 ——
     * 存档是按 `Filename` 存的，那才是稳的键。
     */
    private val typed = mutableMapOf<Int, MutableMap<String, String>>()

    /** 当前停在第几条 */
    var cursor by mutableStateOf(initialStore.cursor.coerceIn(0, maxOf(0, rawRows.size - 1)))
        private set

    // ---------------------------------------------------------------- 派生

    val total: Int get() = rawRows.size

    /** 已存了多少条 */
    val savedCount: Int get() = store.savedCount

    val currentRow: FixRow? get() = rawRows.getOrNull(cursor)

    val canPrev: Boolean get() = cursor > 0

    val canNext: Boolean get() = cursor < total - 1

    /** AI 结果（当前条）。为 null 表示没生成过 */
    var aiResult by mutableStateOf<AiFill.OneResult?>(null)
        private set

    /**
     * 「没认出这首曲子」——AI 返回了一条全空的结果。
     *
     * **不设 `private set`**：解析不出结果时也要置起它（「模型答非所问」与
     * 「模型说不知道」对用户是同一件事），而那是 `ScoreAppState.runAiOne` 在写。
     */
    var aiUnknown by mutableStateOf(false)

    /** AI 生成中 */
    var aiBusy by mutableStateOf(false)

    /** AI 那行下面的一行状态文字（「已复制」「已填入…」） */
    var aiStatus by mutableStateOf<String?>(null)

    /** AI 调用失败的原因；成功或翻页时清空 */
    var aiError by mutableStateOf<String?>(null)

    // ---------------- 联网兜底的状态（写它的除界面外还有 ScoreAppState.runSearchFallback） ----------------

    /**
     * 正在联网搜索兜底。
     *
     * 此刻 [aiResult] 还是**第一遍的**（没认出 / 只回曲名），界面先不画它 ——
     * 免得先闪一下「没认出」再变成「搜索中」，两个状态打架（原型同一条规则）。
     */
    var aiSearching by mutableStateOf(false)

    /**
     * 当前这条结果**已经试过联网兜底**。
     *
     * 「联网也没认出」与「压根没搜过」必须分开措辞 ——
     * 用户得知道这条路已经试过了，再试意义不大，除非他改了曲名。
     * 手动键的文字（「联网搜一次」/「再搜一次」）也靠它区分。
     */
    var aiSearched by mutableStateOf(false)

    /** 当前结果来自兜底的二问 —— 来源行的显示判据 */
    var aiViaSearch by mutableStateOf(false)

    /** 兜底的补充说明（如「资料对不上」）；null = 界面用默认措辞 */
    var aiSearchNote by mutableStateOf<String?>(null)

    /** 联网查到的来源域名（已去重、最多 3 条）。联网不是通行证，来源亮出来让用户自己把关 */
    var aiSources by mutableStateOf<List<String>>(emptyList())

    /**
     * 最近一次点「生成」用的提示词。
     *
     * 手动「联网搜一次」要用**点生成那会儿的字**，而不是现在框里的 ——
     * 两次之间用户可能已经把框里的字改了；拿改过的字去搜，
     * 搜回来的资料对不上这条，结果还是落不到点上。
     * 不是 state：没有任何界面读它，只是个普通字段。
     */
    var lastAiQuery: String = ""

    /**
     * 用户在 AI 输入框里写的提示词。
     *
     * **不设 `private set`**：输入框直接双向绑定它。包一层 `setAiPrompt()`
     * 会与属性自身生成的 setter 撞 JVM 签名（`setAiPrompt(Ljava/lang/String;)V`）
     * —— 这个坑在项目里踩过一次（见 ScoreAppState 的 aiBusy 段注释）。
     *
     * 框里默认是**当前条的文件名**（见 [prefillPrompt] / 翻页用 [reprefillPrompt]），
     * 翻页时跟着换 —— 这个框要的是「眼前这一条是什么曲子」，留着上一条的提示词
     * 只会诱导用户手滑点「生成」，把上一条的结果填到这一条上。
     * （早期没有预填时这里翻页直接清空，现在改成换成新条目的文件名；
     * 用户自己改过的字仍然会被保住，不会被覆盖。）
     */
    var aiPrompt by mutableStateOf("")

    /**
     * 上一次由预填写入的值（不是用户手写的），用来判断框里的字是谁写的。
     *
     * **必须声明在 `init` 之前。** 它会被 `init { prefillPrompt() }` 赋值，
     * 而 Kotlin 按源码顺序初始化属性 —— 声明在 `init` 后面的话，
     * 那句赋值先发生、随后又被 `= ""` 冲掉，`lastPrefill` 永远是空串，
     * 于是 [reprefillPrompt] 判定「框里的字不是我写的」，翻页再也不换预填。
     * 症状是「翻页后输入框一直停在第一条」——和「守卫挡死」长得一样，
     * 但根因不同（一个是位置在 `init` 后，一个是判断写反）。
     */
    private var lastPrefill: String = ""

    /**
     * 开场预填 AI 输入框。
     *
     * **这个 `init` 必须贴在 `aiPrompt` 声明下面，不能往上挪。**
     * Kotlin 的初始化按**源码书写顺序**执行：写在 `aiPrompt` 之前的 `init`
     * 会在那个属性被赋值前先跑，而 `by mutableStateOf` 的幕后字段此时还是 `null`，
     * 读它会直接 NPE（`Cannot invoke "State.getValue()" because ... is null`）。
     * 曾经把它放在 `cursor` 后面，整个 `FixStepperTest` 45 个用例全在
     * 构造 `FixSession` 时炸掉 —— 症状像「业务全坏了」，根因却是初始化顺序。
     *
     * 为什么要有这个预填：会话一开就该是「可以立刻点生成」的状态，
     * 不该等某一帧渲染时才补上（界面没调就等于没做，这种
     *「靠调用方记得」的约定最容易漏）。
     */
    init {
        prefillPrompt()
    }

    /**
     * AI 结果与当前条**对不上**时的警告。
     *
     * 见 [AiFill.looksRelated]：AI 只看着提示词作答，不知道自己在改哪一行，
     * 用户在第 300 条上敲「月光奏鸣曲」而这一行原本是《萱草花》时，
     * 硬写就把数据改烂了。这里如实标红，并把双方都念出来。
     */
    val aiMismatch: Boolean
        get() {
            val ai = aiResult ?: return false
            if (ai.isBlank) return false
            return !AiFill.looksRelated(currentRow?.title, ai.title)
        }

    /**
     * 清掉上一条的 AI 结果与状态。
     *
     * **不碰 `aiPrompt`** —— 输入框的预填由 [prefillPrompt] 单独负责。
     * 原先这里顺手把 `aiPrompt` 也清成空串（「别让上一条的提示词留在框里，
     * 免得手滑把上一条的结果填到这一条」），那是**没有预填**时的正确做法；
     * 现在框里默认就是当前条的文件名，清空反而把用户自己敲的字也抹了。
     */
    fun clearAiResult() {
        aiResult = null
        aiUnknown = false
        aiStatus = null
        aiError = null
        clearAiSearchState()
    }

    /**
     * 只清联网兜底的状态，不动结果本体。
     *
     * 「重新生成」走这里而不是 [clearAiResult]：生成期间旧结果还挂在界面上
     * （busy 时保留上一遍结果是本页一贯的行为，好让用户对照),
     * 但兜底的「搜过 / 来源 / 说明」是**上一遍结果**的属性，必须先清掉 ——
     * 否则新结果天生顶着「已联网搜过」的标签，来源还是上一条的。
     */
    fun clearAiSearchState() {
        aiSearching = false
        aiSearched = false
        aiViaSearch = false
        aiSearchNote = null
        aiSources = emptyList()
    }

    /**
     * 把 AI 输入框预填成当前条的文件名（去掉 `.pdf` 后缀和书名号）。
     *
     * ## 为什么要预填
     *
     * 这个框本来要求用户**自己敲一遍曲名**，可那行字在屏幕上是现成的 ——
     * 往下两行就是「文件名 · 关联主键，永不被改」那块，`
     * 《您花开的样子》.pdf` 摆在那儿。让用户把已经显示出来的字重打一遍
     * 是纯粹的浪费，549 条就是 549 次。
     *
     * ## 为什么可以预填
     *
     * 用户敲进去的字**只是给模型的线索**，不是数据 —— 真正落到库里的值
     * 要经过提示词、`looksRelated` 闸门和逐字段勾选三道关。
     * 预填错了最坏也就是这次生成没结果，点一下清掉重敲即可。
     *
     * ## 用文件名而不是 `row.title`
     *
     * `row.title` 是**已经清洗过**的（去书名号、拆编曲者、去水印），
     * 拿它当线索等于把规则已经做出来的判断又喂回给模型；
     * 而文件名是用户**自己命名的原始串**，往往带更多信息
     * （`《您花开的花》合唱_pdf` 里的「合唱」就是 `title` 里没有的编制线索）。
     * 这与「纠错工具挑错时看原名」是同一个取向：入口处的原话信息量最大。
     *
     * 用户已经自己在框里敲了字就**不动它** —— 那代表他在改写这条的线索，
     * 翻页回来不该被抹掉。
     *
     * ## 读取顺序有讲究
     *
     * 先把结果算出来（`promptFromFileName` 只依赖 `rawRows` 和参数，永远安全），
     * 再去读 `aiPrompt` 做判断。这个顺序在正常调用点看不出区别，
     * 但构造期**只**能这么写 —— `init` 里读一个还没赋值的 `by mutableStateOf`
     * 属性会直接 NPE（见上面 `aiPrompt` 声明处那个 `init` 的注释）。
     * 不拿 `runCatching` 兜：那会把「谁提前读了它」这种真错也一起吞掉。
     *
     * ## 翻页用 [reprefillPrompt]，不能直接用这个
     *
     * 这个函数的守卫是「有字就不动」。而翻页那一刻框里**正好有上一条的预填**，
     * 直接调它会被守卫挡下 —— 输入框永远停在第一条的文件名上，
     * 用户拿第一条的线索去问第二条，还会以为预填坏了。
     * 翻页走 [reprefillPrompt]：「用户手写的不能覆盖」这条由它按
     * [lastPrefill] 判断，而不是按「框里空不空」。
     */
    fun prefillPrompt() {
        val next = promptFromFileName(currentRow?.fileName)
        if (next.isEmpty()) return
        // 用户手写的优先，不覆盖
        if (aiPrompt.isNotBlank()) return
        aiPrompt = next
        lastPrefill = next
    }

    /**
     * 翻页后的重预填：把上一条的预填换成新条目的，**但用户动过的字保住**。
     *
     * 跟 [prefillPrompt] 的区别是它不靠「框里空不空」判断谁写的 ——
     * 预填之后框里也有字，那个守卫会把自己挡死。
     * 改判「框里的字是不是就是我上次预填进去的那串」（[lastPrefill]）：
     *
     * - 框里**正是**那串（或 [lastPrefill] 还是空、说明从没预填过）→ 没人动过，换成新条目的；
     * - 框里是别的字 → 用户改过，留着；
     * - 框里是**空的**，但 [lastPrefill] 有值 → 用户把它删了。
     *   删空也是「动过」，得留着空框 —— 他删空说明他要自己写，
     *   这时候预填回去等于跟他抢。
     *
     * 三种情况必须都分开。原先只判了「非空且不等于 lastPrefill」，
     * 于是删空那种会掉进「没人动过」的分支被填回来，用户删了两次都删不掉。
     */
    fun reprefillPrompt() {
        val next = promptFromFileName(currentRow?.fileName)
        val untouched = aiPrompt == lastPrefill
        if (!untouched) return
        aiPrompt = next
        lastPrefill = next
    }

    /** `《您花开的样子》.pdf` → `您花开的样子`；取不出东西就返回空串 */
    private fun promptFromFileName(fileName: String?): String =
        // 委托给 SearchFallback.fileQuery：联网兜底的搜索词与这里的预填
        // 必须是**同一条清洗链**，各写一份迟早分叉（那边改了这边没跟）。
        SearchFallback.fileNameQuery(fileName)

    // ---------------------------------------------------------------- 建议合并

    private fun propose(row: FixRow): FixProposal =
        FixProposer.propose(row, switches, skipBookmarks && FixProposer.isBookmarkRow(row))

    /**
     * 合并出当前条（或任意一条）的界面视图。
     *
     * AI 注入的优先级**高于**规则：规则只能做无歧义的清洗（去书名号、拆编配者），
     * 而 AI 是「知道这首曲子是什么」的，它给的曲名/作曲家比规则猜的准。
     *
     * 唯一的例外是**乐曲类型**：如果原值非空（多半是用户自己的分类，如「教学」），
     * AI 给的体裁名（如 `Sonata`）**不当作覆盖** —— 两者语义不冲突，一个是用途、
     * 一个是曲式，硬盖等于用 AI 的分类替换掉用户的分类。此时只把它记为「备选」
     * 交给用户自己决定；用户勾了才提升为正式建议。
     */
    fun viewOf(index: Int): FixEntryView {
        val row = rawRows.getOrNull(index) ?: return emptyView()
        val p = baseProposals.getOrNull(index) ?: FixProposal()
        val proposed = linkedMapOf<String, String?>(
            "title" to p.title,
            "comp" to p.composer,
            "genre" to p.genre,
            "tag" to p.tag,
            "label" to p.label,
            "ref" to p.reference,
        )
        val overwritten = p.overwrite.toMutableMap()
        val alternates = mutableMapOf<String, String>()

        val ai = aiInjected[index]
        if (ai != null) {
            ai.forEach { (k, v) ->
                if (v.isEmpty()) return@forEach
                val code = aiKeyToCode(k) ?: return@forEach
                if (code == "keysf") {
                    // 调性走编码：先解析（认不出就什么都不做，绝不硬写）
                    val sig = ScoreKey.parse(v) ?: return@forEach
                    val cur = ScoreKey.textOf(row, columns)
                    val label = ScoreKey.label(sig.keysf, sig.keymi)
                    if (cur == label) return@forEach
                    // 同上面的理由：逐条 AI 是用户亲手触发，不受批量默认值约束
                    if (cur.isNotEmpty()) overwritten["keysf"] = cur
                    proposed["keysf"] = label
                    return@forEach
                }
                if (code == "genre" && row.genre.isNotBlank()) {
                    // 见上面的「备选政策」
                    if (v == row.genre) return@forEach
                    if (promoted.contains("$index|genre")) {
                        overwritten["genre"] = row.genre
                        proposed["genre"] = v
                    } else {
                        alternates["genre"] = v
                    }
                    return@forEach
                }
                val cur = currentValueOf(row, code)
                if (cur == v) return@forEach
                // AI 压过规则：规则给过值就记为「被覆盖」
                proposed[code]?.let { if (it != v) overwritten.putIfAbsent(code, it) }
                // 原有值非空时记进覆盖表，界面显示「原值 → 新值」供对比。
                //
                // **逐条 AI 不受 `aiSuggestExisting` 约束**：那个开关是给「整表批量补全」
                // 用的默认值（批量跑时不该动用户已填的字段）。而这里用户是在**某一条上
                // 亲手敲了提示词**按的「生成」，这是明确的逐条意图 —— 若还因为
                // 「这一行标题已经有字了」而把结果丢掉，这个功能就永远用不上：
                // 待校对的谱子本来就都有个粗糙的标题，没有哪一行是空的。
                if (cur.isNotBlank()) overwritten[code] = cur
                proposed[code] = v
            }
        }

        // 存档里已采纳的值要**回填**：翻回旧条时屏幕上必须是当时存进去的那个值，
        // 不能显示成重新算出来的建议 —— 否则屏幕上与导出里是两个值。
        //
        // 两种空串要分开：
        // - **v1 老存档迁过来的**（值已丢失）：跳过，让重算的建议值顶上 ——
        //   那时还没有「清空」这个动作，空串只可能来自迁移。
        // - **用户明确清空**的（`typed` 里记着空串）：不能跳过，必须压成空值。
        //   判据用 `typed` 而不是存档本身 —— 存档里的空串分不出是哪种，
        //   而 `typed` 是会话内用户意图的直接记录。
        store.of(row.fileName).forEach { (code, value) ->
            if (value.isEmpty()) {
                // 用户要求清空的字段，别让规则值顶回来
                if (isBlankedByUser(index, code)) {
                    proposed.remove(code)
                    overwritten.remove(code)
                }
                return@forEach
            }
            if (code == "keysf" || proposed.containsKey(code)) proposed[code] = value
        }

        // 用户自填：最后压上去，谁也盖不过它。
        // 这一层必须有 —— 不然 AI 猜错了用户只能整条拒绝，改不了。
        //
        // **空串是「用户要求这个字段空」，不是「没填」** ——
        // `typed` 里键不存在才是后者。所以这里对空串要**显式压成空值**
        // （`remove` 掉，让 proposed 里没有这个键 = 界面与导出都没有值），
        // 而不是跳过。跳过就等于用户的「清空」被 AI/规则的原值顶回来，
        // 那个错值会一直赖在屏幕上。
        val blanked = mutableSetOf<String>()
        val typedHere = typed[index]
        if (typedHere != null) {
            typedHere.forEach { (code, v) ->
                if (v.isEmpty()) {
                    proposed.remove(code)
                    overwritten.remove(code)
                    alternates.remove(code)
                    blanked.add(code)
                    return@forEach
                }
                proposed[code] = v
                alternates.remove(code)   // 自己写了值，「备选」提示就没意义了
            }
        }

        return FixEntryView(
            row = row,
            proposed = proposed,
            overwritten = overwritten,
            notes = p.notes,
            keyRejected = p.keyRejected,
            hasAi = ai != null,
            alternates = alternates,
            selfTyped = typedHere?.keys?.filter { typedHere[it].orEmpty().isNotEmpty() }?.toSet()
                ?: emptySet(),
            blankedByUser = blanked,
        )
    }

    private fun emptyView() = FixEntryView(
        row = FixRow(),
        proposed = emptyMap(),
        overwritten = emptyMap(),
        notes = emptyList(),
        keyRejected = null,
        hasAi = false,
        alternates = emptyMap(),
    )

    private fun currentValueOf(row: FixRow, code: String): String = when (code) {
        "title" -> row.title
        "comp" -> row.composer
        "genre" -> row.genre
        "tag" -> row.tag
        "label" -> row.label
        "ref" -> row.reference
        else -> ""
    }

    /** AI 那边的键名 → 内部字段代号 */
    private fun aiKeyToCode(key: String): String? = when (key) {
        "title" -> "title"
        "composers" -> "comp"
        "tags" -> "tag"
        "genres" -> "genre"
        "key" -> "keysf"
        else -> null
    }

    /**
     * 这一行的某个字段现在算不算「已采纳」。
     *
     * 三个来源的并集：当前条的草稿勾选、历史存档、以及「备选被提升」。
     * 界面据此画勾。
     */
    fun isTaken(index: Int, key: String): Boolean {
        if (index == cursor && draft.contains(key)) return true
        val row = rawRows.getOrNull(index) ?: return false
        return store.took(row.fileName, key)
    }

    /**
     * 把 keys 这些字段**按当前的候选值**写进存档。
     *
     * 值必须现取（[viewOf]），不能退回「规则建议」：候选值可能是 AI 给的，
     * 也可能是用户手打的 —— 那两个来源存档里都没有，重算必然算错。
     * 这正是存档从「记字段名」改成「记字段 → 值」的原因。
     */
    private fun adopt(index: Int, keys: Set<String>) {
        val row = rawRows.getOrNull(index) ?: return
        val view = viewOf(index)
        val map = store.of(row.fileName).toMutableMap()
        keys.forEach { k -> view.proposedOf(k)?.let { map[k] = it } }
        store = if (map.isEmpty()) store.without(row.fileName) else store.with(row.fileName, map)
    }

    // ---------------------------------------------------------------- 自填

    /**
     * **自己填**：写值 → 立刻勾上 → 落存档 → 记进「用过的值」。
     *
     * 为什么立刻落存档（不等「采纳并继续」）：逐条翻阅的场景里用户随时会翻页跑掉，
     * 敲完的字没了比多一步保存更让人火大。
     *
     * 为什么自动勾上：都亲手打字了，不可能是不想要这个值 ——
     * 再让他去点一次勾是纯多余（`setTypedValue` 是明确的意图，跟「勾选」等价）。
     *
     * 空白视为「撤回」：把框清空就是不要这个值了，不能有歧义地存个空串进去。
     */
    fun setTypedValue(index: Int, key: String, value: String) {
        val row = rawRows.getOrNull(index) ?: return
        val v = value.trim()
        if (v.isEmpty()) {
            clearTypedValue(index, key)
            return
        }
        typed.getOrPut(index) { linkedMapOf() }[key] = v
        draft = draft + key
        store = store.rememberUsed(key, v)
        adopt(index, setOf(key))
    }

    /** 撤回自填：值、勾选、存档里的记录一起抹掉 */
    fun clearTypedValue(index: Int, key: String) {
        val row = rawRows.getOrNull(index) ?: return
        typed[index]?.remove(key)
        if (typed[index].isNullOrEmpty()) typed.remove(index)
        draft = draft - key
        promoted.remove("$index|$key")
        store = store.withField(row.fileName, key, null)
    }

    /**
     * **把这一条的这个字段清成空值**（自填的「空值覆盖」）。
     *
     * ## 为什么需要一个跟 [clearTypedValue] 不同的动作
     *
     * 用户报的场景：AI 或规则给了个错值，他想要的不是「改成别的值」，
     * 而是**这个字段就该是空的**。以前做不到 —— 输入框留空会被
     * [setTypedValue] 当成「撤回」，于是原来的错值又冒回来。
     * 用户只能眼睁睁看着一个错值，或者手打一个假值去顶替它。
     *
     * ## 与「撤回自填」的区别（这两个很容易混）
     *
     * - [clearTypedValue]（撤回）：**撤销我填过的东西**，值退回规则/AI 给的原值。
     * - 本方法（清空）：**明确要求它是空的**，于是原值和自填值一起被压掉。
     *
     * 换句话说：撤回是「我没填过」，清空是「我要求它空」。后者更强。
     *
     * ## 实现
     *
     * 自填的值以一个**空串**记进 `typed`（跟「没填过」的 null 区分开），
     * 于是 [viewOf] 的自填那一层会把 `proposed[code]` 压成空串 ——
     * 而空串在界面与导出两边都表现为「没有值」。
     * 存档里也记空串：这是用户**要的结果**，翻回来必须还是空的，
     * 不能被规则值重新填上。
     *
     * 勾选**照旧打上**（跟自填一个道理：都亲手做了决定，不该再点一次勾）。
     */
    fun clearFieldToBlank(index: Int, key: String) {
        val row = rawRows.getOrNull(index) ?: return
        // 空串 = 「我要它空」，与「没填过」（键不存在）是两件事，务必写进去
        typed.getOrPut(index) { linkedMapOf() }[key] = ""
        draft = draft + key
        // 清空是个**结果**、不是「用过的值」，所以不进 rememberUsed
        store = store.withField(row.fileName, key, "")
    }

    /** 这一条这个字段是不是被用户明确要求「清空」的 */
    fun isBlankedByUser(index: Int, key: String): Boolean =
        typed[index]?.get(key) == ""

    /** 这一条这个字段用户自己填的值；没填过给 null */
    fun typedOf(index: Int, key: String): String? =
        typed[index]?.get(key)?.takeIf { it.isNotEmpty() }

    /** 这个字段攒下的「用过的值」（最近用的在前） */
    fun usedValuesOf(key: String): List<String> = store.usedOf(key)

    /**
     * 切换某一字段的采纳。
     *
     * **勾选即落存档**：这是逐条翻阅场景的核心约定，用户不必再去点一次「保存」。
     * 唯一的例外是「备选」字段 —— 界面点它是把 AI 的备选**提升**为正式建议，
     * 于是提议里那个值会变（见 [viewOf]），需要重新渲染，所以由调用方负责刷新。
     */
    fun toggleField(index: Int, key: String) {
        val row = rawRows.getOrNull(index) ?: return
        val taken = isTaken(index, key)
        val pk = "$index|$key"

        if (taken) {
            draft = draft - key
            promoted.remove(pk)
            // 取消采纳也顺手撤掉自填：留着一个「不被采纳的自填值」没有意义，
            // 界面上会变成「勾是空的、值却还在」的怪状态
            typed[index]?.remove(key)
            if (typed[index].isNullOrEmpty()) typed.remove(index)
            store = store.withField(row.fileName, key, null)
        } else {
            draft = draft + key
            // 「勾了一个还没有候选值的字段」＝勾的是备选，把它提升为正式建议
            val view = viewOf(index)
            if (view.proposedOf(key) == null && view.alternateOf(key) != null) {
                promoted.add(pk)
            }
            // 必须先提升、再取值：提升之后 viewOf 才会把备选放进 proposed
            adopt(index, setOf(key))
        }
    }

    /**
     * 采纳并继续：把当前条的草稿落进存档（勾选时已经落过了，这里只做收尾），
     * 清掉草稿与 AI 结果，翻到下一条。
     */
    fun commitAndNext() {
        val row = currentRow
        if (row != null) {
            // draft 为空 = 这一条什么都不采纳，等价于清掉它的存档
            if (draft.isEmpty()) store = store.without(row.fileName) else adopt(cursor, draft)
        }
        draft = emptySet()
        clearAiResult()
        if (canNext) cursor++
        syncCursorToStore()
        reprefillPrompt()
    }

    /** 翻页（不上存草稿，草稿在勾选时已落盘） */
    fun go(delta: Int) {
        val next = (cursor + delta).coerceIn(0, maxOf(0, total - 1))
        if (next == cursor) return
        draft = emptySet()
        cursor = next
        // 直接走 clearAiResult()，别手写四行清空 —— 之前这里漏了 aiError，
        // 于是上一条的报错会**跟着翻页挂到新一条上**，
        // 而 goTo() 走的是 clearAiResult()、清得干净，两条翻页路行为不一致。
        clearAiResult()
        syncCursorToStore()
        reprefillPrompt()
    }

    /** 跳到指定条 */
    fun goTo(index: Int) {
        if (index !in 0 until total) return
        draft = emptySet()
        cursor = index
        clearAiResult()
        syncCursorToStore()
        reprefillPrompt()
    }

    /**
     * 跳到下一条**还没处理过**的（存档里没有记录的、且有改动的）。
     *
     * 扫描范围**不含当前条**：先往后找 `cursor+1 .. last`，再绕回头找 `0 .. cursor-1`。
     * 都没有就原地不动并返回 false。
     *
     * 「下一条」是字面意思上的**下一条**，不是「这一条或后面的某一条」。
     * 当前条没处理时用户眼前看到的它就是它，再「跳」到它自己没有任何反馈，
     * 按钮会像是坏的。真要处理当前条，直接勾就行，用不着跳。
     */
    fun jumpUnhandled(): Boolean {
        val order = (cursor + 1 until total) + (0 until cursor)
        for (i in order) {
            if (!hasChangeAt(i)) continue
            val row = rawRows.getOrNull(i) ?: continue
            if (store.of(row.fileName).isNotEmpty()) continue
            goTo(i)
            return true
        }
        return false
    }

    fun hasChangeAt(index: Int): Boolean = viewOf(index).hasChange

    private fun syncCursorToStore() {
        store = store.copy(cursor = cursor)
    }

    // ---------------------------------------------------------------- AI 结果

    /**
     * 把 AI 生成的结果**填入当前条**。
     *
     * 「填入」= 记进 [aiInjected]，于是 [viewOf] 会把它合并成候选项；
     * 同时把**真正有候选值的**字段预勾上（`draft`），因为用户点「填入本条」
     * 的意图显然就是「这些我都要」。**但采纳仍然是可逆的** —— 每个字段的勾还在，
     * 取消勾选就是拒绝这一项。
     *
     * ## 为什么不能无脑勾满五个
     *
     * 必须**先合并再看结果**：AI 给了值不等于这一条就有了候选。
     * 典型是**乐曲类型**——原值是「教学」这类用户自己的分类时，AI 的 `Sonata`
     * 只作「备选」（见 [viewOf]），此时 `genre` 的 `proposed` 是 null。
     * 若把 `genre` 也预勾上，界面上就会出现「勾着一个没值的字段」，
     * 而用户想把它提升为正式建议时反而要走**取消勾选**这一步，语义完全反了。
     *
     * 所以这里只勾 `viewOf(cursor)` 里 `proposedOf(code) != null` 的那些；
     * 备选字段留给用户主动去点（那一下 `toggleField` 才做「提升」）。
     *
     * 返回真的填入了几个字段（AI 非空字段数，与预勾数量可能不同）。
     */
    fun fillCurrentWithAi(): Int {
        val ai = aiResult ?: return 0
        if (ai.isBlank) return 0
        val map = linkedMapOf(
            "title" to ai.title,
            "composers" to ai.composer,
            "tags" to ai.instrument,
            "genres" to ai.genre,
            "key" to ai.key,
        ).filterValues { it.isNotEmpty() }
        if (map.isEmpty()) return 0
        aiInjected[cursor] = map

        // 合并之后再决定勾哪些 —— 备选字段的 proposed 是 null，不会被勾上
        val view = viewOf(cursor)
        val codes = map.keys
            .mapNotNull { aiKeyToCode(it) }
            .filter { view.proposedOf(it) != null }
            .toSet()
        draft = draft + codes
        adopt(cursor, codes)
        return map.size
    }

    /**
     * AI 生成中 / 完成时更新状态，供界面显示。
     *
     * **刻意不叫 `setAiResult`**：`aiResult` 是属性，自带生成的 setter 就叫
     * `setAiResult(OneResult?)`；再手写一个同名同参的方法会撞 JVM 签名，
     * 报 `Platform declaration clash`。这是本项目第三次踩同一个坑了。
     */
    fun landAiResult(result: AiFill.OneResult?) {
        aiResult = result
        aiUnknown = result == null || result.isBlank
        aiBusy = false
    }

    /**
     * 「只认出了曲名」——AI 回了对象，但曲名以外四项全空。
     *
     * 见 [AiFill.OneResult.titleOnly]：这个形态跟「认得但都不确定」长得一样，
     * 意思却相反。界面**不能**给它打「识别完成」——
     * 那会让用户以为 AI 真认出了这首曲子、只是信息少，
     * 于是照着那行曲名去存，其实曲名只是他刚敲进去的原话。
     *
     * 提示词已明确禁止这个形态，这里是双保险。
     */
    val aiTitleOnly: Boolean
        get() = aiResult?.titleOnly == true

    // ---------------------------------------------------------------- 重算

    /**
     * 用当前开关重算全部建议。
     *
     * **不动存档与草稿**：用户已经做过的采纳决定跟规则开关无关，
     * 改个开关就把 549 条的选择全清掉是没人受得了的。
     */
    fun recompute() {
        baseProposals = rawRows.map { propose(it) }
    }

    /** 待补全的行索引（整表 AI 补全那条路用；目前留作接口） */
    fun aiTargets(fields: List<AiField>): List<Int> =
        AiFill.pickTargets(
            rows = rawRows,
            columns = columns,
            fields = fields,
            onlyEmpty = !aiSuggestExisting,
            skipBookmark = skipBookmarks,
        )

    // ---------------------------------------------------------------- 导出

    /** 某一行最终会写出哪些字段（用于回写与展示） */
    fun takenFields(index: Int): Set<String> {
        val view = viewOf(index)
        val taken = view.proposed.entries
            .filter { it.value != null && isTaken(index, it.key) }
            .map { it.key }
            .toSet()
        // **被用户清空的字段也要算「已采纳」** ——
        // 它的值不在 `proposed` 里（是无，不是 null），但用户确实做了决定：
        // 这个字段要空。漏掉它的话，导出/回写会跳过这一格，
        // 而 `CsvAdapter` 遇到「没采纳」是**保留原值** ——
        // 于是用户点过清空的字段，导出来还是那个错值。白清。
        return taken + view.blankedByUser.filter { isTaken(index, it) }.toSet()
    }

    /**
     * 某一行的建议（已合并 AI），给 [LibraryAdapter.applyTo] 这类外部消费者用。
     *
     * 暴露这一层是必要的：写回曲库走的是 `LibraryAdapter`，它只认 [FixProposal]；
     * 而界面上的候选值是「规则 + AI」合并后的。让写回也走同一份合并结果，
     * 才不会出现「界面上改了、写回没变」。
     */
    fun proposalOf(index: Int): FixProposal = proposalFor(index) ?: FixProposal()

    /**
     * 清空存档（顶栏「清空存档」）。采纳记录与位置一起归零。
     *
     * **「用过的值」不清** —— 那是攒出来的个人词表，清进度时顺手清掉
     * 等于让用户白敲；它也不是「进度」，留着不违反这个按钮的语义。
     */
    fun resetStore() {
        store = store.clearedKeepUsed()
        draft = emptySet()
        promoted.clear()
        typed.clear()
        aiInjected.clear()
        cursor = 0
        clearAiResult()
        // 位置归零了，输入框的预填也要跟着换回第 0 条的文件名。
        // 这里必须把 lastPrefill 一起抹掉：用户如果手改过提示词，
        // `reprefillPrompt` 会认为「这不是我写的」而不动它 —— 但清空存档
        // 是「从头再来」的意思，手写痕迹也该一起走。
        aiPrompt = ""
        lastPrefill = ""
        prefillPrompt()
    }

    /**
     * 导出成 CSV 文本。
     *
     * CSV 来源：按采纳的字段改写单元格，**其余列一个字节不动**，
     * 表头也原样写回（forScore 靠列名匹配，动列名等于毁掉这份文件）。
     * 曲库来源：把整个曲库当成一张新表生成，列名用 forScore 的标准 15 列。
     *
     * 注意取值必须走 [viewOf]（合并了 AI）而不是 `baseProposals`——
     * 只走规则的话 AI 生成的值导不出去，用户会看到「界面上明明改了、导出的文件没变」。
     */
    fun exportCsv(): String = when (source) {
        FixSource.Csv -> toCsv(
            originalHeader,
            rawRows.indices.map { i ->
                CsvAdapter.toCells(
                    originalHeader,
                    rawCells.getOrElse(i) { emptyList() },
                    columns,
                    proposalFor(i),
                    takenFields(i),
                    // 用户明确清空的字段要一起传下去 —— proposal 里表达不了「要求它空」
                    blank = viewOf(i).blankedByUser,
                )
            },
        )

        FixSource.Library -> toCsv(LIBRARY_HEADER, rawRows.indices.map { i ->
            val cells = MutableList(LIBRARY_HEADER.size) { "" }
            val view = viewOf(i)
            val fields = takenFields(i)
            val r = view.row
            cells[0] = r.fileName
            // 清空的字段在 proposed 里没有值（不是 null），pick 会回落到原值 ——
            // 那就是「白清」。所以这里先看清空表，压过一切。
            fun pick(key: String, fallback: String): String = when {
                view.isBlanked(key) -> ""
                key in fields -> view.proposedOf(key) ?: fallback
                else -> fallback
            }
            cells[1] = pick("title", r.title)
            cells[4] = pick("comp", r.composer)
            cells[5] = pick("genre", r.genre)
            cells[6] = pick("tag", r.tag)
            cells[7] = pick("label", r.label)
            cells[8] = pick("ref", r.reference)
            if (view.isBlanked("keysf")) {
                // 调性被要求清空：两列都写空，别回落到原值
                cells[13] = ""
                cells[14] = ""
            } else if ("keysf" in fields) {
                val sig = ScoreKey.parse(view.proposedOf("keysf"))
                cells[13] = (sig?.keysf ?: r.keysf)?.toString().orEmpty()
                cells[14] = (sig?.keymi ?: r.keymi)?.toString().orEmpty()
            } else {
                cells[13] = r.keysf?.toString().orEmpty()
                cells[14] = r.keymi?.toString().orEmpty()
            }
            cells
        })
    }

    /**
     * 把 [FixEntryView] 折回一个 [FixProposal]，供 [CsvAdapter.toCells] 复用。
     *
     * 之所以不直接改 [CsvAdapter] 去接受视图：那个适配器的入参形态（[FixProposal]）
     * 是「规则产物」，而视图是「规则 + AI 合并后的结果」。中间加这一层转换，
     * 好处是 AI 那条路完全不必去懂 CSV 的列语义 —— 列语义仍然只有一处。
     */
    private fun proposalFor(index: Int): FixProposal? {
        val view = viewOf(index)
        val p = baseProposals.getOrNull(index) ?: FixProposal()
        val sig = ScoreKey.parse(view.proposedOf("keysf"))
        return p.copy(
            title = view.proposedOf("title"),
            composer = view.proposedOf("comp"),
            genre = view.proposedOf("genre"),
            tag = view.proposedOf("tag"),
            label = view.proposedOf("label"),
            reference = view.proposedOf("ref"),
            keysf = sig?.keysf,
            keymi = sig?.keymi,
            overwrite = view.overwritten,
        )
    }

    companion object {
        /** forScore 的标准 15 列，曲库导出用 */
        val LIBRARY_HEADER: List<String> = listOf(
            "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)", "Composers",
            "Genres", "Tags", "Labels", "Reference", "Rating", "Difficulty",
            "Minutes", "Seconds", "keysf", "keymi",
        )

        /** 从 CSV 文本开一次会话 */
        fun fromCsv(
            text: String,
            switches: RuleSwitches = RuleSwitches(),
            skipBookmarks: Boolean = true,
            store: FixStore = FixStore.EMPTY,
        ): FixSession {
            val table: CsvTable = parseCsv(text)
            val col = ColumnMap.of(table.header)
            val rows = table.rows.map { CsvAdapter.toRow(it, col) }
            return FixSession(
                source = FixSource.Csv,
                header = table.header,
                columns = col,
                rawRows = rows,
                rawCells = table.rows,
                originalHeader = table.header,
                initialStore = store,
            ).also { it.skipBookmarks = skipBookmarks; it.switches = switches }
        }

        /** 从曲库开一次会话 */
        fun fromLibrary(
            scores: List<Score>,
            switches: RuleSwitches = RuleSwitches(),
            skipBookmarks: Boolean = true,
            store: FixStore = FixStore.EMPTY,
        ): FixSession {
            val col = ColumnMap.of(LIBRARY_HEADER)
            val rows = scores.map { LibraryAdapter.toFixRow(it) }
            return FixSession(
                source = FixSource.Library,
                header = LIBRARY_HEADER,
                columns = col,
                rawRows = rows,
                rawCells = scores.map { emptyList() },
                scores = scores,
                originalHeader = LIBRARY_HEADER,
                initialStore = store,
            ).also { it.skipBookmarks = skipBookmarks; it.switches = switches }
        }
    }
}
