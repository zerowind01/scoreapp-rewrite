package com.example.scoreapp.domain.csvfix

/**
 * 联网兜底（方案 B：App 自己搜，把资料喂回模型再问一遍）—— **纯逻辑**。
 *
 * 与 [AiFill] 同一条拆法：提示词怎么拼、搜索结果怎么解析、二问结果怎么裁决，
 * 这些容易错、最值得测的部分全是纯函数，能在 commonTest 里跑；
 * 「发一个 HTTP 请求」这句交给 [com.example.scoreapp.util.SearchBridge]（expect/actual）。
 *
 * ## 为什么是「App 自己搜」而不是换自带联网的模型
 *
 * 换联网模型等于换服务商 + 逐家适配联网开关；Gemini 的 OpenAI 兼容端点
 * 用不了它的搜索接地。自己搜只有一次 POST，模型随便换。
 * 真机上走 Tavily（[TAVILY_ENDPOINT]，免费 1000 次/月）。
 *
 * ## 语义（原型 `fix-stepper-demo.html` 的注释是权威规格，这里逐条落实）
 *
 * 1. 只有**单条**生成会兜底。批量那条路已经不存在（逐条校对的模型下没有位置），
 *    就算将来复活，也**不许**接上兜底 —— 549 条每条多一搜一问，又慢又贵。
 * 2. 只在「第一遍没认出」时触发：整条空或只回了曲名（见 [eligible]）。
 *    认出来了（哪怕字段不全）不搜 —— 那是正常回答，联网反而容易画蛇添足。
 * 3. 每次生成只搜**一轮**：搜索 → 带着资料二问。二问仍不认得就明说，不三问。
 * 4. 搜回来的结果**照样过闸门**（`looksRelated` / titleOnly / 勾选）——
 *    联网不是通行证，搜到同名电影、同名诗集都不算数。
 * 5. 结果必须标**来源**（域名，最多 [MAX_SOURCES] 条）。
 *    用户得能自己判断可信度，不能只给结论。
 * 6. 搜不到就老实说「联网也没找到」，绝不硬编。
 * 7. 搜索期间用户翻了页，结果必须作废 —— 那是给上一条查的。
 *    这一条在调用方（`ScoreAppState.runSearchFallback`）落实，纯逻辑管不着协程。
 */
object SearchFallback {

    /** Tavily 的搜索端点。一次普通 POST，与 AI 那条路同套路 */
    const val TAVILY_ENDPOINT: String = "https://api.tavily.com/search"

    /** 一次搜索带几个结果。多了烧 token，少了资料不够判断 */
    const val MAX_HITS: Int = 5

    /**
     * 来源展示上限 —— 列一排链接没人看，三条足够定位。
     * 摘要可以多喂（模型要读），域名只亮三条。
     */
    const val MAX_SOURCES: Int = 3

    /**
     * 每条摘要截断长度。判断「是不是这首曲子」用不着全文，但**作曲家归属
     * 常在摘要后半段** —— 160 那版在真机上把 Donizetti 截掉了，
     * 模型读不到就不敢认，只会把曲名再抄一遍。放宽到 300，多喂烧不了几个 token。
     */
    const val SNIPPET_MAX: Int = 300

    /** 一条搜索结果。Tavily 的 `results[]` 项，字段名照它文档的来 */
    data class SearchHit(
        val title: String,
        val snippet: String,
        val url: String,
    ) {
        val domain: String get() = domainOf(url)
    }

    /**
     * 从 URL 抠域名：掐掉协议与路径，去掉 `www.` 前缀。
     * 解析不出（空串、纯路径）就原样返回 —— 反正展示时空的会被滤掉。
     */
    fun domainOf(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        return s
    }

    /**
     * 解析 Tavily 的响应 JSON，取 `results[]`。
     *
     * 复用 [MiniJson] 而不引序列化库，与 [AiFill] 同一条理由。
     * **解析失败一律回空表而不是抛**：调用方对「没搜到」与「解析不了」
     * 的处理是同一种（老实说没找到），没必要让调用方分叉。
     * 没有 url 的条目直接丢 —— 没有来源的结果不能要，那是「不知道从哪听说的」。
     */
    fun parseTavily(json: String): List<SearchHit> {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? MiniJson.Value.Obj
            ?: return emptyList()
        val arr = root.fields["results"] as? MiniJson.Value.Arr ?: return emptyList()
        return arr.items.mapNotNull { item ->
            val o = item as? MiniJson.Value.Obj ?: return@mapNotNull null
            fun str(k: String): String = (o.fields[k] as? MiniJson.Value.Str)?.value.orEmpty()
            val url = str("url").trim()
            if (url.isEmpty()) return@mapNotNull null
            SearchHit(
                title = str("title").trim(),
                snippet = str("content").trim().take(SNIPPET_MAX),
                url = url,
            )
        }
    }

    /**
     * 来源域名列表：去重、滤空、最多 [max] 条。
     * 去重是因为同一个站常占两三条（百度百科的 PC/移动端、曲谱网的列表页/详情页）。
     */
    fun domainsOf(hits: List<SearchHit>, max: Int = MAX_SOURCES): List<String> {
        val out = mutableListOf<String>()
        for (h in hits) {
            val d = h.domain
            if (d.isNotEmpty() && !out.contains(d)) out.add(d)
            if (out.size >= max) break
        }
        return out
    }

    /**
     * 从文件名派生搜索词。与 AI 输入框预填**同一条清洗链**
     * （去 `.pdf` 后缀 → [FixRules.cleanTitle] 去书名号）——
     * 两处各写一份迟早分叉，预填改了清洗规则搜索词却没跟上，是最难查的那种不一致。
     */
    fun fileNameQuery(fileName: String?): String {
        val raw = fileName.orEmpty().trim()
        if (raw.isEmpty()) return ""
        val noExt = raw.removeSuffix(".pdf").removeSuffix(".PDF")
        return FixRules.cleanTitle(noExt).ifBlank { noExt }
    }

    /**
     * 搜索词：框里的字优先 —— 用户可能写了「七月的草原 合唱」这种比文件名
     * 更准的问法；空了退回文件名清洗结果（与预填同源）。
     */
    fun buildQuery(prompt: String, fileName: String?): String =
        prompt.trim().ifBlank { fileNameQuery(fileName) }

    /**
     * 谁该触发兜底：整条没认出（[unknown]），或只回了曲名（`titleOnly`）。
     *
     * **认出来了的不搜，哪怕字段不全** —— 那是正常回答（提示词允许某几项空着），
     * 联网再问一遍容易把已经对的答案搅浑。
     *
     * [result] 与 [unknown] 分开传：unknown 还可能是「回答根本解析不出来」，
     * 那时 result 是 null，`result?.isBlank` 永远为 false，漏判。
     */
    fun eligible(result: AiFill.OneResult?, unknown: Boolean): Boolean =
        unknown || result?.titleOnly == true

    /**
     * 组装「带着搜索资料再问一遍」的消息。
     *
     * system 沿用 [AiFill.DEFAULT_PROMPT]（语系规则、五键格式、输出约束原样生效 ——
     * 二问不是另一种问答，只是资料多了一点），末尾追加**资料怎么用**的规则。
     *
     * 写法是「**先抄事实，再判断**」，1.14 真机踩出来的教训：
     * 第一版写的是「不许只根据曲名去猜：曲名是用户敲进来的，不算资料」，
     * 本意是防硬编，结果《Bella siccome un angelo》搜回来了 opera-arias.com ——
     * 页面标题里明明写着 Donizetti，模型却把这句理解成「凡靠曲名搜来的都不能信」，
     * 只敢把曲名再抄一遍交差。反猜的闸门把正当使用资料也一起拦死了（坑 26 的变体）。
     * 现在明说：资料标题/摘要里写明的归属信息**照实抄进答案，不算猜**；
     * 禁止补的只收窄到「资料里没有的」。另加一条封口：
     * 资料只确认了曲名、没有任何归属信息 → 仍回 `[]`（titleOnly 照旧不合法），
     * 否则「五键齐全、四个空串」正好从 titleOnly 那个洞里钻回来。
     */
    fun buildSecondMessages(query: String, hits: List<SearchHit>): List<AiFill.ChatMessage> {
        val sys = buildString {
            append(AiFill.DEFAULT_PROMPT)
            append("\n\n【这一轮不同】下面附上了刚从网上搜到的资料。分两步走：")
            append("\n\n第一步，先从资料里**抄事实**：把每条资料的标题和摘要里写明的信息")
            append("找出来 —— 作曲家名、歌剧/作品归属、体裁、乐器。")
            append("这些是资料原文，**照实抄进答案，不算猜**。")
            append("例：资料标题写着「Bella siccome un angelo — Don Pasquale (Donizetti)」，")
            append("那作曲家就该照资料填 Donizetti（按上面的语系规则写规范全名）。")
            append("\n\n第二步，再判断资料说的是不是用户问的这首曲子：")
            append("- 曲名对得上，且资料给出了归属信息 → 按上面的语系规则")
            append("回五个键齐全的对象，资料里没提到的那个键写空字符串。")
            append("- 曲名对得上，但资料里**只有曲名、没有任何归属信息** → 还是整条回 []：")
            append("只填曲名的对象是不合法的，跟第一轮同一条规矩。")
            append("- 曲名对不上（同名电影、同名诗集、别的作品）→ 整条回 []。")
            append("\n\n【要防的只有一种】资料里**没有写**的信息，不许靠记忆或常识补：")
            append("资料没提作曲家就不要自己猜一个。资料里有的要敢抄，没有的不许编。")
            append("\n\n输出仍然只有 JSON 数组本身，格式与之前完全一致。")
        }
        val materials = buildString {
            append("曲目：").append(query)
            append("\n\n搜索资料（共 ").append(hits.size).append(" 条）：")
            hits.forEachIndexed { i, h ->
                append("\n").append(i + 1).append(". ")
                if (h.title.isNotEmpty()) append("【").append(h.title).append("】")
                if (h.snippet.isNotEmpty()) append(h.snippet)
                if (h.domain.isNotEmpty()) append("（来源：").append(h.domain).append("）")
            }
        }
        return listOf(
            AiFill.ChatMessage("system", sys),
            AiFill.ChatMessage("user", materials),
        )
    }

    /**
     * 二问的裁决结果。
     *
     * [result] 为 null 表示「还是没认出」——沿用第一遍的空结果，
     * 界面继续显示「没认出」，只是措辞换成「联网也没认出」；
     * 非 null 就是二问认出来了（包括 titleOnly 形态 —— 它照样要被单独标出来，
     * 联网搜来的曲名-only 同样不算认出，见规格第 4 条）。
     *
     * [note] 是给用户看的补充说明（如「资料对不上」）；没有就为 null，
     * 界面用默认措辞「资料里没有可靠信息」。
     */
    data class Outcome(
        val result: AiFill.OneResult?,
        val note: String?,
    )

    /**
     * 裁决第二遍模型调用。
     *
     * 三种出口，对应 [AiFill.parseOneReply] 的三种结果：
     * - 解析失败（null）→ 没认出 + 「第二次回答没解析出内容」；
     *   **不能**显示成「联网也没找到」—— 那是模型没好好回答，不是搜不到，
     *   文案混了用户会以为是搜索的问题。
     * - 空结果 → 没认出，默认措辞。
     * - 有值 → 照实给（titleOnly 的单独特判留给界面）。
     */
    fun adjudicate(secondReplyText: String?): Outcome {
        val one = AiFill.parseOneReply(secondReplyText)
            ?: return Outcome(result = null, note = "第二次回答没解析出内容")
        if (one.isBlank) return Outcome(result = null, note = null)
        return Outcome(result = one, note = null)
    }
}
