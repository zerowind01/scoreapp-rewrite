package com.example.scoreapp.util

/**
 * 搜索能力的平台桥，与 [AiBridge] 同一条拆法：
 * `commonMain` 里没有网络栈，也不为一个功能引整套网络库 ——
 * 这里只定义「发一次搜索」，HTTP 细节在 `androidMain` 用 `HttpURLConnection` 实现。
 *
 * 本层保持最薄：**怎么解析响应、资料怎么喂给模型、结果怎么裁决**，
 * 全在 `SearchFallback`（纯逻辑、可单测）。这里只负责把请求发出去、
 * 把原始 JSON 原样带回来 —— 解析是 `SearchFallback.parseTavily` 的事。
 *
 * 与 [AiBridge] 同样的硬约束：签名里不出现任何平台类型，只进出字符串与纯数据。
 */
interface SearchBridge {

    /**
     * 搜一次网页，返回搜索引擎的**原始响应 JSON**。
     *
     * 实现侧不要在这里解析结构、不要筛字段：响应里除了 `results[]` 还有
     * 用不着的 `response_time`、`score` 等，截减放到解析层做，
     * 万一要换搜索服务商，这一层只改「发什么」不改「怎么读」。
     *
     * [apiKey] 为空串时实现侧直接回「没配置」，不发请求。
     */
    suspend fun search(query: String, apiKey: String): SearchReply
}

/**
 * 一次搜索调用的结果。形状与 [AiReply] 一致：
 * [ok] 为 false 时 [error] 是**给用户看的**失败说明，不是原始异常串。
 */
class SearchReply(
    val ok: Boolean,
    val json: String,
    /** 失败时给用户的说明；成功时为 null */
    val error: String? = null,
)

/**
 * 创建当前平台的搜索实现。与 [createAiBridge] 同理：不需要 `Context`，可无参构造。
 */
expect fun createSearchBridge(): SearchBridge
