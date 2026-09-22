package com.example.scoreapp.util

import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.MiniJson

/**
 * AI 补全的设置。
 *
 * 刻意不含「用哪个模型」以外的业务参数 —— 提示词、字段开关、是否覆盖
 * 都属于**每次校对任务**的选择，不进全局设置。
 *
 * 用 `data class` 而不是普通类：设置界面要逐项改（改地址、改模型、改密钥），
 * 每次改动都是「在上一份基础上换一个字段」，`copy` 正是这个语义。
 * 没有 `copy` 就得手写三份几乎一样的构造调用，加字段时必漏。
 *
 * ## 关于「密钥是否落盘」这个决定
 *
 * 早先的版本把它定成**只在内存里、每次启动重填**，理由是「密钥留在设备上的
 * 风险大于每次重填的麻烦」。那个取舍在**逐条翻阅**这个模型出现之后就不成立了：
 * 一屏一条、几百条要过，用户可能每翻几条就点一次「生成」，
 * 每次冷启动都要重输地址 + 密钥 + 模型，等于把这个功能废掉一半。
 *
 * 所以改成**随校对存档落盘**（见 [FixStore.toJson] 的 `ai` 段）：明文，
 * 不加密。这是一个自用工具、密钥是用户自己填的，为它引一套加密与密钥派生
 * 得不偿失。**设置页面上会明确写出这一点**，不让用户在不知情的情况下
 * 把密钥留在设备里。
 */
data class AiConfig(
    /** 接口地址。只有 OpenAI 兼容的 `/chat/completions` 形态被支持 */
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,

    /**
     * 联网兜底开关（默认开）。
     *
     * 开着时：单条生成第一遍没认出（整条空 / 只回曲名），自动搜一次网页资料
     * 再问一遍。关掉后结果区出「联网搜一次」手动键，想搜再搜。
     *
     * 默认值取**开**而不是关：兜底只在「本来就要失败了」的时候才发生，
     * 多花的只是一次搜索 + 一次补问，换来的是本来会空手的条目有机会被认出 ——
     * 默认关等于把这个功能藏起来，用户得先知道它存在才会去打开。
     */
    val searchOn: Boolean = true,

    /**
     * Tavily 搜索密钥（`tvly-…`，tavily.com 免费申请）。
     *
     * 与 [apiKey] 分开存而不是共用一个「密钥」：模型接口和搜索接口是两家服务商，
     * 换模型不该连带换搜索、换搜索也不该连带换模型 —— 一个字段塞两家的 key，
     * 用户每换一边都得动另一边的配置，还容易把 A 家的 key 粘到 B 家去。
     */
    val searchKey: String = "",
) {
    val ready: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 界面上「已配置 / 未配置」那一栏的判据，与 [ready] 同一口径 */
    val configured: Boolean get() = ready

    /**
     * 联网兜底能不能自动跑：总开关开着**且**密钥填了。
     * 只有开关没密钥时不算 ready —— 那种状态兜底必失败，
     * 与其搜一次报一次错，不如直接按「手动键 + 明说没配好」处理。
     */
    val searchReady: Boolean get() = searchOn && searchKey.isNotBlank()

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_MODEL = "deepseek-chat"

        /** 存档里 `ai` 段的字段名。改这里等于改存档格式，要同步 [fromJson] */
        private const val K_ENDPOINT = "endpoint"
        private const val K_API_KEY = "apiKey"
        private const val K_MODEL = "model"
        private const val K_SEARCH_ON = "searchOn"
        private const val K_SEARCH_KEY = "searchKey"

        /**
         * 序列化成 `{"endpoint":"…","apiKey":"…","model":"…","searchOn":"1","searchKey":"…"}`。
         *
         * 复用 [com.example.scoreapp.domain.csvfix.AiFill.jsonEsc]，不引序列化库 ——
         * 与 [FixStore] 同一条理由：结构固定、五个字段，为它拉进
         * kotlinx-serialization 的编译器插件不划算。
         *
         * `searchOn` 存 `"1"/"0"` 而不是 JSON 布尔：这个文件里的值全部走字符串通道
         * （[com.example.scoreapp.domain.csvfix.MiniJson] 对布尔的支持是后来才补的），
         * 统一形态省得解析侧写两条分支。
         */
        fun toJson(config: AiConfig): String = buildString {
            append('{')
            append("\"").append(K_ENDPOINT).append("\":\"")
            append(AiFill.jsonEsc(config.endpoint)).append("\",")
            append("\"").append(K_API_KEY).append("\":\"")
            append(AiFill.jsonEsc(config.apiKey)).append("\",")
            append("\"").append(K_MODEL).append("\":\"")
            append(AiFill.jsonEsc(config.model)).append("\",")
            append("\"").append(K_SEARCH_ON).append("\":\"")
            append(if (config.searchOn) "1" else "0").append("\",")
            append("\"").append(K_SEARCH_KEY).append("\":\"")
            append(AiFill.jsonEsc(config.searchKey)).append("\"")
            append('}')
        }

        /**
         * 从 JSON 文本还原。
         *
         * **任何解析失败一律退化成 [AiConfig] 默认值，绝不抛异常**：
         * 这段 JSON 是从磁盘上读回来的，而「老版本写的存档里根本没有 `ai` 段」
         * 是必然会遇到的情况 —— 那时应该安静地给出默认配置（用户重填一次），
         * 而不是让整个校对存档解析失败、连带把上次的进度也丢掉。
         *
         * 空串也当默认值处理：界面上用户可能把地址栏清空再保存，
         * 那不该变成「地址为空但系统认为已配置」。
         */
        fun fromJson(text: String?): AiConfig {
            if (text.isNullOrBlank()) return AiConfig()
            val obj = runCatching { MiniJson.parse(text) }.getOrNull() as? MiniJson.Value.Obj
                ?: return AiConfig()
            fun str(key: String): String? = (obj.fields[key] as? MiniJson.Value.Str)?.value

            // searchOn 兼容两种存法：自己写的 "1"/"0"，以及手改出来的布尔/其他写法 ——
            // 认不出就当「开」（与默认值一致；用户手改坏配置文件不该把兜底关掉）
            fun bool(key: String, fallback: Boolean): Boolean = when (val v = obj.fields[key]) {
                is MiniJson.Value.Str -> when (v.value.trim()) {
                    "1", "true", "on" -> true
                    "0", "false", "off" -> false
                    else -> fallback
                }
                is MiniJson.Value.Bool -> v.value
                else -> fallback
            }

            return AiConfig(
                endpoint = str(K_ENDPOINT)?.ifBlank { null } ?: DEFAULT_ENDPOINT,
                apiKey = str(K_API_KEY).orEmpty(),
                model = str(K_MODEL)?.ifBlank { null } ?: DEFAULT_MODEL,
                searchOn = bool(K_SEARCH_ON, fallback = true),
                searchKey = str(K_SEARCH_KEY).orEmpty(),
            )
        }
    }
}

/**
 * 一次对话调用的结果。
 *
 * [ok] 为 false 时 [text] 是**给用户看的**失败说明（而不是原始异常串）——
 * 网络失败的原因五花八门，但用户只需要知道「是密钥不对、还是没网、还是被限流」。
 */
class AiReply(
    val ok: Boolean,
    val text: String,
    /** 失败时给用户的说明；成功时为 null */
    val error: String? = null,
)

/**
 * AI 能力的平台桥。
 *
 * 为什么不直接用 Ktor / OkHttp：`commonMain` 里没有网络栈，也不该为了一个功能
 * 引进一整套依赖与配套的协程调度。这里照 `FileBridge` 的成例把「发一个请求」
 * 收成一个接口，`androidMain` 用 `HttpURLConnection` 实现。
 *
 * 与 `FileBridge` 同样的硬约束：**签名里不出现任何平台类型**（`Uri` / `Context`
 * / `OkHttpClient` 一律不出现），只进出字符串与纯数据。
 *
 * 注意本接口只负责**发请求**。提示词怎么拼、回答怎么解析、结果怎么落地，
 * 全在 `AiFill` 里（纯逻辑、可单测）—— 这一层越薄越好。
 */
interface AiBridge {

    /**
     * 发一轮对话，返回模型的原始回答。
     *
     * 实现侧**不要**在这里解析 JSON 结构，原样把 content 交回来：
     * 模型可能吐带围栏、带废话、带思考过程的脏回答，那是 `AiFill.parseReply` 的事。
     */
    suspend fun chat(messages: List<AiFill.ChatMessage>, config: AiConfig): AiReply
}

/**
 * 创建当前平台的 AI 实现。
 *
 * 与 `createFileBridge` 不同，本实现**不需要 `Context`**（只发 HTTP），
 * 所以可以无参构造 —— 调用点不必再走一次注入。
 */
expect fun createAiBridge(): AiBridge
