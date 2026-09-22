package com.example.scoreapp.domain.csvfix

import com.example.scoreapp.util.AiConfig

/**
 * 逐条校对的**跨会话存档**。
 *
 * 为什么这个功能非要有个存档层不可：「一次几百条、一屏一条」的交互，
 * 用户不可能一口气翻完 549 条。没有存档，中途退出等于全部白做；
 * 有了存档，第二天打开还能接着上次那一条往下翻。这跟原来那张
 * 「一屏全列出来、批量勾选、一次导出」的长表是两种工作方式：
 * 长表的成果在内存里活到导出那一刻就够了，逐条翻阅的成果必须落盘。
 *
 * 存档的粒度是**字段级**而不是行级：`Filename → {title, composers, key}`。
 * 行级的「这条处理过了」太粗 —— 用户可能只采纳了作曲家、把标题留着没动，
 * 下次进来看不出他到底决定过什么。字段级还带来一个额外好处：
 * 取消采纳（把勾去掉）就是从这个集合里删一个元素，不需要任何额外状态。
 *
 * **键必须用 `Filename`**（forScore 的关联主键），不能用行号：
 * 行号只在一次会话内稳定，用户重新导一份顺序不同的 CSV 就全对不上了。
 * 用文件名做键还有个实际好处 —— 同一批数据在「外部 CSV」与「本机曲库」两个
 * 来源之间切换时，已采纳的字段能互相认出来（曲库侧的函数名就是这么做出来的）。
 *
 * 存储形态刻意用 JSON 文本而不是 Room/DataStore：
 * 这份数据就是两个 map，量级是几百个键；引 Room 要加依赖、写 Entity/DAO/Migration，
 * 换来的是完全用不上的查询能力。用 `MiniJson` 手写读写，几十行搞定，
 * 且能在 commonTest 里纯逻辑验证（不需要 Android 运行时）。
 */
data class FixStore(
    /**
     * 已采纳的字段：`Filename → {字段代号 → 采纳下来的值}`。
     *
     * 字段键用的是**内部列代号**（`title` / `comp` / `genre` / `tag` / `label` / `ref` / `keysf`），
     * 与 `FixSession.takenFields` 同一套，这样存档能直接喂给导出逻辑，
     * 不需要任何翻译。空集合的行会被清掉 —— 留着只会让「已存 N 条」这个计数虚高。
     *
     * ## 为什么从「字段集合」改成「字段 → 值」
     *
     * 早先这里只记**勾了哪些字段**，值是导出时拿规则重新算一遍的 —— 那时值的来源
     * 只有规则一个，重算必然得到同一个结果，这么存没问题。
     * 允许用户**自己填**之后就不成立了：手打的值、AI 给的值都算不出来，
     * 翻一页再回来屏幕上显示的是重算的建议，而导出会用另一个值 ——
     * 同一个字段两个值，是最难查的那类 bug。所以值必须跟着字段一起存。
     *
     * 值可以是空串，含义是「这个字段采纳过、但值丢了」（旧存档迁移上来的，
     * 见 [fromJson]）：此时退回重算出来的建议值，恰好等于旧版本的行为。
     */
    val saved: Map<String, Map<String, String>> = emptyMap(),
    /** 上次停在的条目下标（会话内位置，跨会话只用于「接着上次翻」） */
    val cursor: Int = 0,
    /** 上次这份存档是为哪一批数据建的（来源 + 条数），换了批次就重置位置 */
    val batchKey: String = "",
    /**
     * **用过的值**：字段代号 → 值列表（最近用的排在前）。
     *
     * 549 条里「教学」「钢琴」这类值要敲几十遍，敲一次就该一直能点。
     * 按字段分开存是因为词表是**强字段相关**的：填过「考级」不代表它适合当曲名。
     *
     * 为什么跟存档放一起、且**不受「清空存档」影响**：它是攒出来的个人词表，
     * 清进度时顺手清掉等于让用户白敲。
     */
    val used: Map<String, List<String>> = emptyMap(),
    /**
     * AI 接口设置（地址 / 密钥 / 模型）。
     *
     * 为什么挂在校对存档里，而不是单开一个 `settings.json`：
     *  - 这份存档本来就要读写一次（开校对会话时必读、关时必写），
     *    顺手多带三个字段不增加任何 I/O；
     *  - 存档的 [FileBridge] 通道、[MiniJson] 解析、坏数据兜底全都已经写好并测过，
     *    单开一个文件等于把这些再抄一遍。
     *
     * 语义上它也确实属于这份存档 —— 校对页是唯一消费 AI 的地方，
     * 别处没有「AI 设置」这个需求。
     */
    val ai: AiConfig = AiConfig(),
) {
    /** 已存了多少条 */
    val savedCount: Int get() = saved.size

    /** 某一行已采纳的「字段 → 值」；没存过给空 map（不是 null，调用方少一层判断） */
    fun of(fileName: String): Map<String, String> = saved[fileName].orEmpty()

    /** 某一行是否采纳过这个字段。值可以是空串（旧存档），也算采纳过 */
    fun took(fileName: String, field: String): Boolean = saved[fileName]?.containsKey(field) == true

    /**
     * 记下某一行采纳的字段及其值。
     *
     * @param values 空 map = 这一条什么都不采纳，等价于删除该行的存档
     */
    fun with(fileName: String, values: Map<String, String>): FixStore {
        if (fileName.isEmpty()) return this
        val next = saved.toMutableMap()
        if (values.isEmpty()) next.remove(fileName) else next[fileName] = values
        return copy(saved = next)
    }

    /** 只改某一行的**一个**字段（采纳 / 取消都走这里） */
    fun withField(fileName: String, field: String, value: String?): FixStore {
        if (fileName.isEmpty() || field.isEmpty()) return this
        val row = saved[fileName].orEmpty().toMutableMap()
        if (value == null) row.remove(field) else row[field] = value
        return with(fileName, row)
    }

    /** 删掉某一行（点「清空存档」或取消掉全部采纳） */
    fun without(fileName: String): FixStore {
        if (fileName.isEmpty() || !saved.containsKey(fileName)) return this
        return copy(saved = saved - fileName)
    }

    /** 某个字段攒下的「用过的值」 */
    fun usedOf(field: String): List<String> = used[field].orEmpty()

    /**
     * 记一次「用过的值」：去重、提到最前、超出上限的丢掉。
     *
     * 空白不记 —— 那是用户把框清空了，不是他要用的词。
     */
    fun rememberUsed(field: String, value: String): FixStore {
        val v = value.trim()
        if (field.isEmpty() || v.isEmpty()) return this
        val list = usedOf(field).filter { it != v }.toMutableList()
        list.add(0, v)
        return copy(used = used + (field to list.take(USED_CAP)))
    }

    /** 清掉全部进度（采纳与位置），**保留「用过的值」** */
    fun clearedKeepUsed(batchKey: String = ""): FixStore =
        FixStore(cursor = 0, batchKey = batchKey, used = used)

    companion object {
        val EMPTY = FixStore()

        /** 「用过的值」每个字段最多留几个。再多就挤成一片，也没人往下翻 */
        const val USED_CAP = 8

        /** 全清：采纳与位置都归零，用于「清空存档」（[clearedKeepUsed] 会留下词表） */
        fun cleared(batchKey: String = ""): FixStore = FixStore(batchKey = batchKey)

        /**
         * 一批数据的标识：来源 + 条数 + 首个文件名。
         *
         * 为什么要它：用户换了一份 CSV 之后，旧存档里的 `Filename` 可能一个都
         * 对不上，而游标还停在「第 380 条」—— 新的那份如果只有 50 条，
         * 打开就直接落在末尾，看起来像坏了。带上批次标识，换了批次就从头开始，
         * 但**采纳记录仍然保留**（同一批数据的两个来源之间要能互认）。
         */
        fun batchKeyOf(source: String, rows: List<FixRow>): String =
            "$source|${rows.size}|${rows.firstOrNull()?.fileName.orEmpty()}"

        // ------------------------------------------------------------ 序列化
        //
        // 形态：
        //   {"v":2,"cursor":37,"batch":"csv|549|x.pdf",
        //    "saved":{"a.pdf":{"title":"月光","comp":"贝多芬"}},
        //    "used":{"label":["教学","考级"]},
        //    "ai":{"endpoint":"…","apiKey":"…","model":"…"}}
        //
        // `v` 从 1 升到 2 是因为 `saved` 的**值**换了形状：v1 是字段名数组
        // `["title","comp"]`，v2 是 `{字段: 值}`。读的时候两种都认（见 [fromJson]），
        // 所以老存档照样能读出进度，不需要迁移代码。
        //
        // 用 MiniJson 解析而不是引序列化库，理由同 AiFill：结构固定、
        // 行为完全可测，且不必为一个功能拉进 kotlinx-serialization 的编译器插件。
        //
        // `ai` 段是后加的，**老存档里没有这一段是正常情况**（见 [fromJson]）。

        fun toJson(store: FixStore): String = buildString {
            append("{\"v\":2")
            append(",\"cursor\":").append(store.cursor)
            append(",\"batch\":\"").append(AiFill.jsonEsc(store.batchKey)).append('"')
            append(",\"saved\":{")
            append(
                store.saved.entries.joinToString(",") { (k, v) ->
                    "\"${AiFill.jsonEsc(k)}\":{" +
                        v.entries.joinToString(",") { (f, value) ->
                            "\"${AiFill.jsonEsc(f)}\":\"${AiFill.jsonEsc(value)}\""
                        } + "}"
                },
            )
            append('}')
            append(",\"used\":{")
            append(
                store.used.entries.joinToString(",") { (k, v) ->
                    "\"${AiFill.jsonEsc(k)}\":[" +
                        v.joinToString(",") { "\"${AiFill.jsonEsc(it)}\"" } + "]"
                },
            )
            append('}')
            append(",\"ai\":").append(AiConfig.toJson(store.ai))
            append('}')
        }

        /**
         * 从 JSON 文本还原。
         *
         * **任何解析失败都退化成空存档，绝不抛异常**：存档是纯附加信息，
         * 一份损坏的 json（写到一半被杀进程、用户手动改过）最坏的结果应该只是
         * 「上次的进度没了」，而不是「校对页打不开了」。
         *
         * `ai` 段（后加的）缺失时退化成 [AiConfig] 默认值 —— 这既覆盖了
         * 「老版本写的存档」，也覆盖了「用户还没填过」。注意它**不会**让整个
         * 存档解析失败：进度与密钥是两份独立的附加信息，一个坏了不该拖累另一个。
         */
        fun fromJson(text: String?): FixStore {
            if (text.isNullOrBlank()) return EMPTY
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? MiniJson.Value.Obj
                ?: return EMPTY
            val cursor = (root.fields["cursor"] as? MiniJson.Value.Num)
                ?.value?.trim()?.toIntOrNull() ?: 0
            val batch = (root.fields["batch"] as? MiniJson.Value.Str)?.value.orEmpty()
            val ai = (root.fields["ai"] as? MiniJson.Value.Obj)?.let { obj ->
                // 已经确认是对象，直接照抄字段；这里不重新拼 JSON 字符串，
                // 免得为了复用 AiConfig.fromJson 而把解析出来的对象再序列化一遍
                fun str(key: String) = (obj.fields[key] as? MiniJson.Value.Str)?.value
                AiConfig(
                    endpoint = str("endpoint")?.ifBlank { null } ?: AiConfig.DEFAULT_ENDPOINT,
                    apiKey = str("apiKey").orEmpty(),
                    model = str("model")?.ifBlank { null } ?: AiConfig.DEFAULT_MODEL,
                )
            } ?: AiConfig()
            val savedObj = root.fields["saved"] as? MiniJson.Value.Obj
            val saved = buildMap {
                savedObj?.fields?.forEach { (file, v) ->
                    if (file.isEmpty()) return@forEach
                    val map = when (v) {
                        // v2：字段 → 值。
                        // 显式写出类型参数：嵌套的 buildMap 会去猜外层的类型参数
                        // （Kotlin 的已知推断行为），不写就退化成 Map<*, *>
                        is MiniJson.Value.Obj -> buildMap<String, String> {
                            v.fields.forEach { (f, value) ->
                                val text = (value as? MiniJson.Value.Str)?.value ?: return@forEach
                                if (f.isNotEmpty()) put(f, text)
                            }
                        }
                        // v1：只有字段名，没有值 —— 值丢了就留空串，
                        // 由调用方退回重算的建议值（恰好等于旧版本的行为）
                        is MiniJson.Value.Arr -> v.items
                            .mapNotNull { (it as? MiniJson.Value.Str)?.value }
                            .filter { it.isNotEmpty() }
                            .associateWith { "" }
                        else -> return@forEach
                    }
                    // 空集合与空文件名都不入库：前者等于没采纳，后者匹配不上任何一行
                    if (map.isNotEmpty()) put(file, map)
                }
            }
            val usedObj = root.fields["used"] as? MiniJson.Value.Obj
            val used = buildMap {
                usedObj?.fields?.forEach { (field, v) ->
                    val arr = v as? MiniJson.Value.Arr ?: return@forEach
                    val items = arr.items
                        .mapNotNull { (it as? MiniJson.Value.Str)?.value }
                        .filter { it.isNotEmpty() }
                        .take(USED_CAP)
                    if (items.isNotEmpty() && field.isNotEmpty()) put(field, items)
                }
            }
            return FixStore(
                saved = saved,
                cursor = maxOf(0, cursor),
                batchKey = batch,
                used = used,
                ai = ai,
            )
        }
    }
}

/**
 * 存档的**纯内存兜底**。
 *
 * `commonMain` 拿不到文件系统（没有 `java.io.File`），所以真正的落盘走
 * `FileBridge.readFixStore/writeFixStore`。但单元测试里没有 `FileBridge`，
 * 而逐条校对的逻辑（翻页、逐字段采纳、备选提升）**必须能脱离 Android 跑测试** ——
 * 那些才是最容易写错的部分。
 *
 * 于是拆成两层：`FixStore` 是不可变数据（纯逻辑），
 * `FixStoreHolder` 负责「当前这个 store 是什么」，把读写实现注入进来即可。
 * 早先的做法是把读写直接写在 `ScoreAppState` 里，导致测试必须构造整个状态中枢，
 * 而状态中枢又依赖 `FileBridge`，一路拖进 Android 运行时。
 */
class FixStoreHolder(
    /** 读：返回 null 表示还没存过 / 读不到 */
    private val read: () -> String? = { null },
    /** 写：返回是否写成功。失败只影响「下次能不能接着翻」，不阻断当前操作 */
    private val write: (String) -> Boolean = { false },
) {
    var value: FixStore = FixStore.EMPTY
        private set

    /** 从存储里读一次；解析失败退化成空存档（见 [FixStore.fromJson]） */
    fun load(): FixStore {
        value = FixStore.fromJson(read())
        return value
    }

    /** 换一份存档并落盘。返回是否写成功 */
    fun update(next: FixStore): Boolean {
        value = next
        return write(FixStore.toJson(next))
    }

    fun clear(): Boolean = update(FixStore.EMPTY)
}
