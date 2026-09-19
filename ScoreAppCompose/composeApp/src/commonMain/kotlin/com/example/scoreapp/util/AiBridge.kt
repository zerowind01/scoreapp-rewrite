package com.example.scoreapp.util

import com.example.scoreapp.domain.csvfix.AiFill

/**
 * AI 补全的设置。
 *
 * 刻意不含「用哪个模型」以外的业务参数 —— 提示词、字段开关、是否覆盖
 * 都属于**每次校对任务**的选择，不进全局设置。
 *
 * 用 `data class` 而不是普通类：设置界面要逐项改（改地址、改模型、改密钥），
 * 每次改动都是「在上一份基础上换一个字段」，`copy` 正是这个语义。
 * 没有 `copy` 就得手写三份几乎一样的构造调用，加字段时必漏。
 */
data class AiConfig(
    /** 接口地址。只有 OpenAI 兼容的 `/chat/completions` 形态被支持 */
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    val ready: Boolean get() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_MODEL = "deepseek-chat"
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
