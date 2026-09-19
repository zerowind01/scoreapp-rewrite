package com.example.scoreapp.domain.csvfix

/**
 * AI 补全：**只做纯逻辑，联网交给平台侧**。
 *
 * 这个文件不引用任何网络 API —— [AiBridge]（expect/actual）负责真正的 HTTP 往返。
 * 拆开的好处：提示词构造、脏回答解析、结果落地这三段最容易出错、也最值得测的部分
 * 全是纯函数，能在 commonTest 里跑；平台侧只剩「发一个 POST」这一句。
 *
 * 行为逐条对齐网页版 `tools/forscore-csv-fixer.html` 的 AI 段，
 * `AiFillTest` 与 `tools/csvfix-test.js` 的 8~11 节互相对照。
 */
object AiFill {

    // ------------------------------------------------------------------ 字段表

    /**
     * 给 AI 用的字段清单。
     *
     * [AiField.key] 是给 AI 看的键名，[AiField.col] 是内部列代号，[AiField.cn] 是中文列名
     * —— 三套名字都要在解析时认，因为模型被中文提示词引导时经常直接吐中文键名。
     */
    val AI_FIELDS: List<AiField> = listOf(
        AiField("title", ColumnMap.TITLE, "曲名"),
        AiField("composers", ColumnMap.COMP, "作曲家"),
        AiField("genres", ColumnMap.GENRE, "乐曲类型"),
        AiField("tags", ColumnMap.TAG, "编配"),
        AiField("labels", ColumnMap.LABEL, "标签"),
        AiField("reference", ColumnMap.REF, "来源"),
        AiField("key", ColumnMap.KEY_KEYSF, "调性", AiField.KIND_KEY),
    )

    /** 只关心调性的调用点用的便捷常量 */
    val KEY_FIELD: AiField = AI_FIELDS.first { it.kind == AiField.KIND_KEY }

    /**
     * 默认提示词。
     *
     * 措辞有两处是踩坑换来的：
     * 1. 明确「不确定就填空字符串」—— 否则模型会为了显得有用而硬编作曲家。
     * 2. 明确「调性写文字、不要写数字编码」—— 模型见过 forScore 的 keysf/keymi
     *    数字格式时爱直接吐 `-3`，那个值对用户毫无可读性，也没法校验。
     */
    val DEFAULT_PROMPT: String = listOf(
        "补全乐谱条目的字段。",
        "",
        "规则：",
        "- 只根据曲名、文件名和已有字段判断，不确定就填空字符串。",
        "- 作曲家写规范拼写（Frédéric Chopin、Johann Sebastian Bach、Ludwig van Beethoven），",
        "  中国作曲家写中文全名；编曲/改编的人名不算作曲家。",
        "- 乐曲类型用简短体裁词（Etude、Sonata、Waltz、Nocturne、合唱、基本功练习、英皇考级曲目）。",
        "- 编配写声部或编制（钢琴、混声四部、童声二部、钢琴伴奏）。",
        "- 标签写用途或场景（教学、考级、演出、伴奏）；多值用半角逗号加空格分隔。",
        "- 调性写「降B大调」「A小调」这种文字，不确定就留空；不要写数字编码。",
        "",
        "输出：JSON 数组，不要任何解释文字，不要 Markdown 代码块，不要写分析过程。",
        "每项形如 {\"i\":0,\"genres\":\"Etude\",\"tags\":\"钢琴\"}。",
        "i 必须与输入里的 i 完全一致；只写待补全字段里列出的键。",
    ).joinToString("\n")

    // ------------------------------------------------------------------ 挑行

    /**
     * 挑出需要问 AI 的行索引。
     *
     * @param onlyEmpty true = 只挑「有字段空着」的行；false = 全部问一遍
     * @param skipBookmark 跳过合集子条目 —— 它们的标题是页码片段，问 AI 没意义还费钱
     *
     * 注意「有字段空着」用的是 `isBlank`，所以 `未填` 这种占位文字算「有值」不算空
     * （它不在垃圾词表里，工具也不该替用户猜它是不是占位符）。
     */
    fun pickTargets(
        rows: List<FixRow>,
        columns: ColumnMap,
        fields: List<AiField>,
        onlyEmpty: Boolean = true,
        skipBookmark: Boolean = true,
    ): List<Int> {
        val out = mutableListOf<Int>()
        rows.forEachIndexed { i, r ->
            if (skipBookmark && FixProposer.isBookmarkRow(r)) return@forEachIndexed
            if (onlyEmpty) {
                val hasEmpty = fields.any { f ->
                    if (f.kind == AiField.KIND_KEY) {
                        // 调性要看 keysf + keymi 两列，任一缺失都算没值
                        ScoreKey.textOf(r, columns).isEmpty()
                    } else {
                        r.value(f.col).isBlank()
                    }
                }
                if (!hasEmpty) return@forEachIndexed
            }
            out.add(i)
        }
        return out
    }

    /** 按字段键取行的值；调性走特殊编码 */
    private fun FixRow.value(col: String): String = when (col) {
        ColumnMap.TITLE -> title
        ColumnMap.COMP -> composer
        ColumnMap.GENRE -> genre
        ColumnMap.TAG -> tag
        ColumnMap.LABEL -> label
        ColumnMap.REF -> reference
        ColumnMap.FILE -> fileName
        ColumnMap.SP -> startPage
        ColumnMap.EP -> endPage
        else -> ""
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 组装给 AI 看的精简条目：只带行号、文件名、曲名和待补字段。
     *
     * **刻意不带起止页、评分、难度**：这些是要么用户自己有主意、要么跟补全无关的字段，
     * 塞进去白白烧 token 还容易诱导模型乱改。
     */
    fun buildItems(
        rows: List<FixRow>,
        columns: ColumnMap,
        indexes: List<Int>,
        fields: List<AiField>,
    ): List<Map<String, String>> = indexes.map { i ->
        val r = rows.getOrNull(i) ?: FixRow()
        val m = linkedMapOf<String, String>()
        m["i"] = i.toString()
        m["file"] = r.fileName
        m["title"] = r.title
        fields.forEach { f ->
            m[f.key] = if (f.kind == AiField.KIND_KEY) ScoreKey.textOf(r, columns) else r.value(f.col)
        }
        m
    }

    /** 一条聊天消息 */
    data class ChatMessage(val role: String, val content: String)

    /**
     * 组装聊天消息。
     *
     * 两处非显然的设计：
     * 1. **待补字段清单自动追加**，不让用户手抄 —— 用户改提示词时最常漏的就是这个。
     * 2. **格式约束钉在最末尾**：长提示词里末尾指令的约束力最强，有些模型会把 system
     *    开头的格式要求当建议、转头就写起「Let me analyze」。
     */
    fun buildMessages(
        items: List<Map<String, String>>,
        fields: List<AiField>,
        prompt: String? = null,
    ): List<ChatMessage> {
        val list = fields.joinToString("、") { "${it.key}（${it.cn}）" }
        val base = prompt?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT
        val sys = buildString {
            append(base)
            append("\n\n待补全字段：")
            append(list)
            append("\n\n【最高优先级】你的整条回复必须只有 JSON 数组本身，")
            append("第一个字符是 [ ，最后一个字符是 ] 。")
            append("禁止输出思考过程、分析说明、Markdown 代码块，禁止写 \"Let me analyze\"。")
            append("只输出 JSON。")
        }
        return listOf(
            ChatMessage("system", sys),
            ChatMessage("user", "待补全的数据（JSON 数组）：\n" + itemsToJson(items)),
        )
    }

    /**
     * 把条目序列化成 JSON 数组。
     *
     * 自己拼而不引序列化库：结构固定（一层字符串键值），行为完全可控，
     * 也免得为一个功能拉进来 kotlinx-serialization 的编译器插件。
     */
    internal fun itemsToJson(items: List<Map<String, String>>): String =
        items.joinToString(",", "[", "]") { m ->
            m.entries.joinToString(",", "{", "}") { (k, v) -> "\"${jsonEsc(k)}\":\"${jsonEsc(v)}\"" }
        }

    internal fun jsonEsc(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 把 AI 的回答解析成结果。
     *
     * 要吃的「脏」形态，都是真实遇到过的：
     * - 包了 ```json 代码块围栏
     * - 前后带「Sure!」「done」这类废话
     * - 包了一层信封 `{"result":[...]}` / `{"data":{"items":[...]}}`
     * - 字段名写中文（「作曲家」）
     * - 行号写成字符串 `"index":"2"`
     * - 全角冒号、单引号、尾随逗号（兜底正则路径）
     * - 推理模型把「思考过程 + 答案」揉成一段，中间夹一个示例数组
     */
    fun parseReply(text: String?, fields: List<AiField>): AiParseResult {
        // 字段名别名表：英文键 / 中文名 / 去掉空格的中文名，三套都映射到同一个 key
        val keys = mutableMapOf<String, String>()
        fields.forEach { f ->
            keys[f.key] = f.key
            keys[f.cn] = f.key
            keys[f.cn.replace(Regex("\\s"), "")] = f.key
        }

        var body = text.orEmpty()
        // 去掉 markdown 代码块围栏
        body = body.replace(Regex("```[a-zA-Z]*\\s*"), "").replace(Regex("```"), "")
        // 掐掉首尾废话：从第一个 [ 到最后一个 ]
        val a = body.indexOf('[')
        val b = body.lastIndexOf(']')
        if (a >= 0 && b > a) body = body.substring(a, b + 1)

        var objs: List<Map<String, String>> = emptyList()
        var parseFailed = false
        val parsed = runCatching { MiniJson.parse(body) }.getOrNull()
        if (parsed == null) {
            parseFailed = true
        } else {
            objs = flattenEnvelope(parsed)
        }

        if (parseFailed) {
            // 兜底：逐个小对象抠键值，容忍单引号、中文冒号、尾部多余逗号
            objs = salvageObjects(body)
            if (objs.isEmpty()) return AiParseResult(emptyList(), "没从回答里解析出 JSON")
        }

        // 推理模型兜底：从后往前找最后一段像 JSON 数组的内容
        val needFallback = objs.isEmpty() || objs.any { rowIndexOf(it) == null }
        if (needFallback) {
            val found = lastJsonArray(body)
            if (found.isNotEmpty()) objs = found
        }

        val out = mutableListOf<AiItem>()
        objs.forEach { o ->
            val raw = rowIndexOf(o) ?: return@forEach
            val values = linkedMapOf<String, String>()
            o.forEach { (k, v) ->
                if (k == "i" || k == "index" || k == "n") return@forEach
                val want = keys[k] ?: keys[k.trim()] ?: keys[k.lowercase()]
                if (want == null) return@forEach
                val vv = v.trim()
                if (vv.isNotEmpty()) values[want] = vv
            }
            if (values.isNotEmpty()) out.add(AiItem(raw, values))
        }
        return AiParseResult(out, null)
    }

    /**
     * 取行号；`i` / `index` / `n` 都认，且必须能被解析成整数
     * —— `"abc"` 这种不能当成 0，那会把建议落到错误的一行上。
     */
    internal fun rowIndexOf(o: Map<String, String>): Int? {
        for (k in listOf("i", "index", "n")) {
            val v = o[k] ?: continue
            if (Regex("^\\s*-?\\d+\\s*$").matches(v)) return v.trim().toInt()
        }
        return null
    }

    /**
     * 剥信封：`{"result":[...]}` / `{"data":{"items":[...]}}` 这类。
     * 剥不出东西就把整个对象当成一条。
     */
    private fun flattenEnvelope(v: MiniJson.Value): List<Map<String, String>> = when (v) {
        is MiniJson.Value.Arr -> v.items.mapNotNull { flattenEnvelope(it).firstOrNull() }
        is MiniJson.Value.Obj -> {
            val wrapped = listOf("result", "data", "items", "list", "rows", "output", "answer", "response")
                .firstNotNullOfOrNull { k ->
                    v.fields[k]?.let { inner -> flattenEnvelope(inner).takeIf { it.isNotEmpty() } }
                }
            if (wrapped != null) wrapped
            else listOf(v.fields.mapValues { it.value.asText() })
        }
        else -> emptyList()
    }

    /** 兜底正则抠键值：`{...}` 块，键值允许单双引号与全角冒号 */
    private fun salvageObjects(body: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        Regex("\\{[^{}]*\\}").findAll(body).forEach { m ->
            val blk = m.value
            val o = linkedMapOf<String, String>()
            Regex("(?:[\"']?([A-Za-z\\u4e00-\\u9fa5_.]+)[\"']?)\\s*[:：]\\s*(?:[\"']([^\"']*)[\"']|(-?\\d+(?:\\.\\d+)?))")
                .findAll(blk)
                .forEach { k ->
                    val key = k.groupValues[1]
                    val v = k.groupValues[2].ifEmpty { k.groupValues[3] }
                    o[key] = v
                }
            if (o.isNotEmpty()) out.add(o)
        }
        return out
    }

    /**
     * 从一段可能夹着思考过程的文本里，抓**最后一段能解析成数组**的 `[...]`。
     *
     * 从后往前找：推理模型的最终答案总在末尾，前面出现的往往是举例。
     * 判定条件是「非空、且每一项都能取出行号」，用来把它们前面那个示例数组筛掉
     * （示例里通常是 `{"i":0,"note":"think"}` 这种没有有效行号的）。
     */
    internal fun lastJsonArray(text: String): List<Map<String, String>> {
        var best: List<Map<String, String>> = emptyList()
        var end = text.length
        while (end > 0) {
            val e = text.lastIndexOf(']', end - 1)
            if (e < 0) break
            var s = text.lastIndexOf('[', e)
            while (s >= 0) {
                val cand = text.substring(s, e + 1)
                val v = runCatching { MiniJson.parse(cand) }.getOrNull()
                if (v is MiniJson.Value.Arr) {
                    val maps = v.items.mapNotNull { (it as? MiniJson.Value.Obj)?.fields?.mapValues { x -> x.value.asText() } }
                    if (maps.isNotEmpty() && maps.size == v.items.size && maps.all { rowIndexOf(it) != null }) {
                        if (maps.size > best.size) best = maps
                        break
                    }
                }
                val s2 = text.lastIndexOf('[', s - 1)
                if (s2 == s) break
                s = s2
            }
            end = e
        }
        return best
    }

    // ------------------------------------------------------------------ 落地

    /**
     * 把解析结果写成建议。**不动原数据** —— 是否采纳由用户逐条勾选。
     *
     * @param proposals 与 [rows] 同长度、同下标的建议列表（由 `FixProposer.propose` 产出）
     * @param results 三种形态都吃：`AiParseResult` / `List<AiItem>` / `List<Map<String,String>>`
     * @param allowOverwrite 「已有值也提建议」。注意这是**两段式**：打开它之后建议会写进
     *   [FixProposal.overwrite] 与原值并存，**采纳仍需用户在界面上逐条点**，
     *   不是一开就自动覆盖。
     * @return 产生了几条字段级改动
     */
    fun apply(
        proposals: MutableList<FixProposal>,
        rows: List<FixRow>,
        columns: ColumnMap,
        results: Any?,
        fields: List<AiField>,
        allowOverwrite: Boolean = false,
    ): Int {
        val colOf = mutableMapOf<String, String>()
        val kindOf = mutableMapOf<String, String?>()
        fields.forEach { f ->
            colOf[f.key] = f.col
            kindOf[f.key] = f.kind
        }
        var n = 0

        val list: List<Map<String, String>> = when (results) {
            // 三种入参形状先统一成「内部形态」，下面的逻辑只认这一种
            is AiParseResult -> results.items.map { toFlatMap(it)!! }
            is List<*> -> results.mapNotNull { toFlatMap(it) }
            is Map<*, *> -> listOfNotNull(toFlatMap(results))
            else -> emptyList()
        }

        list.forEach { r ->
            val rawIdx = r["i"]?.trim()?.toIntOrNull()
            // 兼容 map 里没有 i 而被塞进 values 的情况
            val idx = rawIdx ?: return@forEach
            val p = proposals.getOrNull(idx) ?: return@forEach
            val row = rows.getOrNull(idx) ?: return@forEach

            // 取「字段键 → 值」。内部形态有两种来源，判断依据是**有没有 `values.` 前缀键**
            // —— 不能用 `containsKey("values")`：{i, values:{...}} 在被 toFlatMap 归一化时
            // 已把 values 摊成 `values.composers` 这样的前缀键，`values` 这个键本身不存在。
            val hasValues = r.keys.any { it.startsWith("values.") }
            val vals: Map<String, String> = if (hasValues) {
                fields.mapNotNull { f -> r["values.${f.key}"]?.let { f.key to it } }.toMap()
            } else {
                r.filterKeys { it != "i" && it != "index" && it != "n" && colOf.containsKey(it) }
            }

            vals.forEach { (k, rawV) ->
                val col = colOf[k] ?: return@forEach
                if (columns[col] == null) return@forEach
                val v = rawV.trim()
                if (v.isEmpty()) return@forEach

                // ---------------- 调性：把「降B大调」翻成 keysf / keymi 两列 ----------------
                if (kindOf[k] == AiField.KIND_KEY) {
                    val pk = ScoreKey.parse(v)
                    if (pk == null) {
                        // 认不出的写法记下来给用户看，不硬写
                        proposals[idx] = p.copy(keyRejected = v)
                        return@forEach
                    }
                    val curKey = ScoreKey.textOf(row, columns)
                    // 已有调性且没允许覆盖
                    if (curKey.isNotEmpty() && !allowOverwrite) return@forEach
                    val newTxt = ScoreKey.label(pk.keysf, pk.keymi)
                    // 值没变化，不算改动
                    if (curKey == newTxt) return@forEach
                    val before = proposals[idx]
                    proposals[idx] = before.copy(
                        keysf = pk.keysf,
                        keymi = pk.keymi,
                        keyOverride = curKey.isNotEmpty(),
                        changes = before.changes.withAi("keysf").withAi("keymi"),
                    )
                    n++
                    return@forEach
                }

                // ---------------- 普通文字字段 ----------------
                val cur = row.value(col)
                if (cur.isNotBlank()) {
                    if (!allowOverwrite) return@forEach
                    if (cur.trim() == v) return@forEach
                }
                val before = proposals[idx]
                val patch: FixProposal = when (col) {
                    ColumnMap.TITLE -> before.copy(title = v)
                    ColumnMap.COMP -> before.copy(composer = v)
                    ColumnMap.GENRE -> before.copy(genre = v)
                    ColumnMap.TAG -> before.copy(tag = v)
                    ColumnMap.LABEL -> before.copy(label = v)
                    ColumnMap.REF -> before.copy(reference = v)
                    else -> before
                }
                val withOv = if (cur.isNotBlank()) {
                    patch.copy(overwrite = patch.overwrite + (col to cur))
                } else {
                    patch
                }
                proposals[idx] = withOv.copy(changes = withOv.changes.withAi(col))
                n++
            }
        }
        return n
    }

    /** changes 里追加一个来源，不重复堆 */
    private fun Map<String, List<String>>.withAi(field: String): Map<String, List<String>> {
        val list = this[field].orEmpty()
        return this + (field to if (list.contains("ai")) list else list + "ai")
    }

    /** [AiItem] → 内部形态：把 `values` 里的字段键打上 `values.` 前缀，避免与行号键 `i` 撞名 */
    private fun AiItem.toFlatMap(): Map<String, String> =
        mapOf("i" to index.toString()) + values.mapKeys { "values.${it.key}" }

    /**
     * 把任意形状的一条结果拍平成「内部形态」`Map<String,String>`。
     *
     * 内部约定两条，[apply] 只认这一种，所以三种入参形状都必须先归一化到这里：
     * - 行号统一在最外层键 `i`；
     * - 嵌套 `values` 里的字段键加 `values.` 前缀（否则 `values.i` 会和行号 `i` 撞名）。
     */
    private fun toFlatMap(v: Any?): Map<String, String>? = when (v) {
        null -> null
        is AiItem -> v.toFlatMap()
        is Map<*, *> -> {
            val out = mutableMapOf<String, String>()
            v.forEach { (k, value) ->
                val ks = k?.toString() ?: return@forEach
                when {
                    // {i, values:{...}} 形态：values 下的键加前缀
                    value is Map<*, *> && ks == "values" ->
                        value.forEach { (k2, v2) -> out["values.${k2}"] = v2?.toString().orEmpty() }
                    value is List<*> -> out[ks] = value.joinToString(", ") { it?.toString().orEmpty() }
                    else -> out[ks] = value?.toString().orEmpty()
                }
            }
            out
        }
        else -> null
    }
}

// ---------------------------------------------------------------------------
// 极简 JSON 解析器
// ---------------------------------------------------------------------------

/**
 * 一个只够用的 JSON 解析器。
 *
 * 为什么不引 kotlinx-serialization：解析结果要按「任意键」迭代（模型返回什么键都有可能），
 * 用带类型的数据类反而要先猜 schema；而这里只需要「对象 / 数组 / 字符串 / 数字 / 布尔」
 * 五种形态，一百行就能覆盖，且行为完全可测。
 */
internal object MiniJson {

    sealed interface Value {
        data class Obj(val fields: Map<String, Value>) : Value
        data class Arr(val items: List<Value>) : Value
        data class Str(val value: String) : Value
        data class Num(val value: String) : Value
        data class Bool(val value: Boolean) : Value
        data object Null : Value

        /** 一律拍成字符串：AI 返回值要写进 CSV，全是文本 */
        fun asText(): String = when (this) {
            is Obj, is Arr -> ""
            is Str -> value
            is Num -> value
            is Bool -> value.toString()
            Null -> ""
        }
    }

    /** 解析失败抛异常，由调用方 [runCatching] 兜住 */
    fun parse(text: String): Value {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("尾部有多余内容")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun readValue(): Value {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("意外结束")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Value.Str(readString())
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Value.Obj {
            expect('{')
            val fields = linkedMapOf<String, Value>()
            skipWs()
            if (peek() == '}') { i++; return Value.Obj(fields) }
            while (true) {
                skipWs()
                // 容忍尾随逗号：`{"a":1,}`
                if (peek() == '}') { i++; break }
                val k = if (peek() == '"' || peek() == '\'') readString() else readBareKey()
                skipWs(); expect(':'); skipWs()
                fields[k] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; break }
                    else -> throw IllegalArgumentException("对象里期望 , 或 }")
                }
            }
            return Value.Obj(fields)
        }

        private fun readArray(): Value.Arr {
            expect('[')
            val items = mutableListOf<Value>()
            skipWs()
            if (peek() == ']') { i++; return Value.Arr(items) }
            while (true) {
                skipWs()
                if (peek() == ']') { i++; break }
                items.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; break }
                    else -> throw IllegalArgumentException("数组里期望 , 或 ]")
                }
            }
            return Value.Arr(items)
        }

        /** 单引号也当字符串引号（模型偶尔吐 JS 字面量） */
        private fun readString(): String {
            val quote = s[i]; i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') {
                    i++
                    if (atEnd()) break
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, minOf(i + 5, s.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 4 }
                        }
                        else -> sb.append(e)
                    }
                    i++
                    continue
                }
                if (c == quote) { i++; return sb.toString() }
                sb.append(c); i++
            }
            throw IllegalArgumentException("字符串没闭合")
        }

        private fun readBareKey(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.' || s[i] == '-' || s[i] == '#')) i++
            if (i == start) throw IllegalArgumentException("期望键名")
            return s.substring(start, i)
        }

        private fun readNumber(): Value.Num {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            if (i == start) throw IllegalArgumentException("期望数字")
            return Value.Num(s.substring(start, i))
        }

        private fun readBool(): Value.Bool = when {
            s.startsWith("true", i) -> { i += 4; Value.Bool(true) }
            s.startsWith("false", i) -> { i += 5; Value.Bool(false) }
            else -> throw IllegalArgumentException("期望布尔值")
        }

        private fun readNull(): Value {
            if (!s.startsWith("null", i)) throw IllegalArgumentException("期望 null")
            i += 4
            return Value.Null
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[i]

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("期望 '$c'，实际 '${peek()}'")
            i++
        }
    }
}
