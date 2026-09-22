package com.example.scoreapp.util

/**
 * 网盘（WebDAV）能力的平台桥。
 *
 * 与 [AiBridge] / [SearchBridge] 同一条拆法：`commonMain` 里没有网络栈，
 * 也不为一个功能引整套网络库。这里只定义三件事 —— 列目录、下载、取消，
 * HTTP 细节在 `androidMain` 实现。
 *
 * 本层保持最薄：**XML 怎么解析、哪些条目能进列表、缓存怎么淘汰**
 * 全在 `Netdisk`（纯逻辑、可单测）。这里只负责把请求发出去、把原始报文带回来。
 *
 * 同样的硬约束：签名里不出现任何平台类型。
 */
interface NetdiskBridge {

    /**
     * 列一个目录（PROPFIND + Depth: 1）。
     *
     * 返回**原始 XML 原文**，解析交给 [com.example.scoreapp.domain.netdisk.Netdisk.parsePropfind]。
     * [self] 是这次请求的完整 URL，实现侧不用它 —— 它只是让调用方在解析时能
     * 认出「目录自己」那条响应（见 parsePropfind 的注释）。
     */
    suspend fun propfind(url: String, user: String, pass: String): PropfindReply

    /**
     * 下载一个文件到 [destPath]。
     *
     * [onProgress] 三个参数：百分比、已收字节、总字节（总长度未知时总字节为 0、百分比为 -1）。
     *
     * **百分比在读到 EOF 之前最高只报 99**：`Content-Length` 不一定准（服务端按估算值
     * 或缓存大小给都有可能），提前顶满会让进度条说谎 —— 看着跑完了其实还在传字节，
     * 界面随即退回「正在下载…」。所以「100」必须严格等于「字节真的收完了」。
     */
    suspend fun download(
        url: String,
        user: String,
        pass: String,
        destPath: String,
        onProgress: (pct: Int, done: Long, total: Long) -> Unit,
    ): DownloadReply
}

/** 一次 PROPFIND 的结果。失败时 [kind] 是给 `Netdisk.errorText` 用的分类码 */
class PropfindReply(
    val ok: Boolean,
    val xml: String,
    val kind: String = "",
)

/**
 * 一次下载的结果。
 *
 * [detail] 是**给排查用的技术串**（`HTTP 404` / `MalformedURLException: no protocol`）。
 * 必须留着：分类码 [kind] 只有「网络不通」这种人话，而 1.20 那次事故里
 * 「请求压根没发出去」和「服务器拒了」被翻成同一句话，谁也看不出区别。
 * 界面上以灰色小字显示，用户念出来就能定位。
 */
class DownloadReply(
    val ok: Boolean,
    val kind: String = "",
    val detail: String = "",
)

/** 创建当前平台的网盘实现。不需要 `Context`，可无参构造 */
expect fun createNetdiskBridge(): NetdiskBridge
