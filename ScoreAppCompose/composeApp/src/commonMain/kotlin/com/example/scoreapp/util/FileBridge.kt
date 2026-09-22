package com.example.scoreapp.util

import androidx.compose.ui.graphics.ImageBitmap
import com.example.scoreapp.model.Score

/**
 * 相册图片合成 PDF 的结果。
 *
 * 字段口径与反编译产物的 `ImportResult` 完全一致：
 *  - [path] 为 null 表示失败，此时 [error] 必非空；
 *  - [pages] 是**成功写入**的页数，[total] 是**提交的图片总数**，两者不等说明有图片被跳过；
 *  - [error] 在成功时也不是一定为空——「有 N 张图片无法读取，已跳过」就是成功路径上的警告。
 */
class ImportOutcome(
    val path: String?,
    val pages: Int,
    val total: Int,
    val error: String?,
)

/**
 * 分享派发结果。
 *
 * [hadFile] 用来区分两种降级：带附件分享，还是只发纯文本曲谱信息。
 * 调用方据此决定提示哪一句文案。
 */
class ShareOutcome(
    val dispatched: Boolean,
    val hadFile: Boolean,
)

/**
 * 分享所需的乐谱信息。
 *
 * 刻意不用 [Score] 本身：`Score` 带 30 多个字段，而生成分享意图只用到其中 8 个。
 * 收窄成这个小结构后，`ShareUtil` 的纯文本摘要逻辑可以独立测试，
 * 不必构造一份完整的乐谱。
 */
class ScoreShareInfo(
    val title: String,
    val composer: String,
    val type: String,
    val instrument: String,
    val period: String,
    val level: String,
    val pages: Int,
    val source: String,
    val filePath: String?,
)

/**
 * 乐谱存储目录的真实占用。
 *
 * 「我的 → 导入与存储」原先写死「本地 128 MB」——原应用自己的瑕疵（第 4 处），
 * 数字永不变化。这里每次现算，标签与点击提示用同一份口径。
 */
class StorageUsage(
    /** `filesDir/scores/` 下所有文件字节之和 */
    val bytes: Long,
    /** 文件份数 */
    val files: Int,
)

/** 把 [Score] 收窄成分享所需字段 */
fun Score.toShareInfo(): ScoreShareInfo = ScoreShareInfo(
    title = title,
    composer = composer,
    type = type,
    instrument = instrument,
    period = period,
    level = level,
    pages = pages,
    source = source,
    filePath = filePath,
)

/**
 * 文件能力桥。
 *
 * `android.graphics.pdf`、`FileProvider`、`ContentResolver` 都只存在于 Android，
 * 而曲库状态机住在 `commonMain`。这里把「需要平台」的那几个动作收成一个接口，
 * 由 `androidMain` 提供实现，`commonMain` 只依赖这个接口。
 *
 * **签名里不出现任何平台类型**（`Uri` / `Context` / `Bitmap` 一律不出现），
 * 这样新增 iOS / Desktop target 时，`commonMain` 一行都不用改。
 */
interface FileBridge {

    /**
     * 把相册图片按 A4 逐页排版合成为一份 PDF，落到 `filesDir/scores/` 下。
     * 相册返回的是 `content://`，所以入参用字符串传递。
     */
    fun imagesToPdf(uris: List<String>): ImportOutcome

    /** 从 `content://` 里读出显示名并去掉 `.pdf` 后缀，作为新乐谱的标题 */
    fun pdfTitle(uri: String): String

    /** 把选中的 PDF 拷进 `filesDir/scores/` 并返回绝对路径；失败返回 null */
    fun copyPdfToLocal(uri: String, title: String): String?

    /**
     * 按文本读一个 `content://` 文件（用于 forScore 的 CSV）。
     *
     * 不落盘：校对会话只在内存里过一遍，出口是「导出成新文件」，
     * 中间没必要在设备上多留一份副本。
     * 读不到（不是文本、权限不足、文件不存在）一律返回 null。
     */
    fun readTextFile(uri: String): String?

    /**
     * 把文本交给系统「保存/分享」出去（导出校对后的 CSV）。
     *
     * 走 `ACTION_CREATE_DOCUMENT` 的语义在桥里不好做（它需要一个 Activity 回调），
     * 所以这里用分享面板：与 [share] 同一条路，只是内容是文本附件。
     * 用户可以从分享面板里选「保存到文件」落到任意位置。
     */
    fun shareText(text: String, fileName: String): Boolean

    /**
     * 把文本放进系统剪贴板。
     *
     * 「AI 手动粘贴」那条路的第一步：用户要先拿到提示词与数据，
     * 才能去网页聊天窗里提问。返回 false 表示剪贴板不可用（极少见）。
     */
    fun copyText(text: String): Boolean

    /** 探测 PDF 页数。`content://` 或无法解析一律返回 0 */
    fun pdfPageCount(path: String): Int

    /** 删除本地文件，用于导入失败后的清理 */
    fun deleteLocal(path: String)

    /** 拉起系统分享面板 */
    fun share(info: ScoreShareInfo): ShareOutcome

    /**
     * 把随包分发的内置乐谱 PDF 补齐到本地，并把曲库里带 `assetPdf` 的乐谱
     * 解析成**真实绝对路径**。
     *
     * 为什么要在启动时做：`assetPdf` 只是一个文件名（如 `moonlight_op27_no2.pdf`），
     * 直接当路径用会以进程工作目录为基准解析，在设备上必然不存在。
     * 原应用用 `PdfAssets.installMissing` 把它换成 `filesDir/scores/...` 后写回。
     *
     * 幂等：已安装的文件不重写；没有内置资源时原样返回（封面自动退化为程序化绘制）。
     */
    fun installBundledScores(scores: List<Score>): List<Score>

    /** 统计乐谱存储目录的真实占用（现算，不缓存） */
    fun storageUsage(): StorageUsage

    /** 封面缓存当前占用（字节），只读不清。给「我的」页标签显示用 */
    fun coverCacheBytes(): Long

    /**
     * 清空封面缓存，返回**实际释放**的字节数。
     *
     * 缓存是内存 LruCache（见 CoverRenderer），清空后下次进列表会重新渲染——
     * 这是「清理缓存」的真实语义。返回 0 表示本来就空，调用方照实说，
     * 不再像原应用那样报一个写死的「已清理 24 MB」。
     */
    fun clearCoverCache(): Long

    /**
     * 读逐条校对的存档（`filesDir/fix-store.json`）。
     *
     * 返回 null 表示还没存过或读不到，调用方退化成空存档 —— 存档是纯附加信息，
     * 读失败只该表现为「上次进度没了」，不该阻断功能。
     *
     * 之所以走桥而不是在 commonMain 直接读文件：`java.io.File` 只存在于 Android，
     * 而 [FixStoreHolder] 的逻辑（翻页、逐字段采纳）必须能在 commonTest 里跑。
     */
    fun readFixStore(): String?

    /** 写逐条校对的存档。返回是否写成功 */
    fun writeFixStore(json: String): Boolean

    /** 删掉存档文件（「清空存档」用），返回是否删掉了 */
    fun clearFixStore(): Boolean

    /**
     * 网盘谱子的缓存目录（`filesDir/netdisk`），不存在则创建。
     *
     * 与 `scores/` 分开：网盘谱子**不进乐谱库**（只在线打开、看完就丢），
     * 混在一起会让「导入与存储」那个统计把临时文件也算进去。
     */
    fun netdiskCacheDir(): String

    /**
     * 网盘连接配置的读写（`filesDir/netdisk.json`）。
     *
     * 与存档同一条理由走桥：地址 / 账号 / 口令要明文留在手机上，
     * 而 `java.io.File` 只存在于 Android。
     */
    fun readNetdiskConfig(): String?

    fun writeNetdiskConfig(json: String): Boolean

    /**
     * 读乐谱库存档（`filesDir/library.json`）。
     *
     * 一份文件装两块：网盘入库的条目与用户改过的元数据（`net` 段），
     * 以及本机的同一类信息（`local` 段）。合成一份是为了**一次写盘** ——
     * 分成两个文件的话，写了一半崩了就会得到「网盘记得住、本机不记得」的半截状态。
     *
     * 返回 null 表示还没存过（首次启动），调用方退化成空存档。
     */
    fun readLibraryJson(): String?

    /** 写乐谱库存档。返回是否写成功 */
    fun writeLibraryJson(json: String): Boolean
}

/**
 * 创建当前平台的文件能力实现。
 *
 * `androidMain` 里持有的实现需要 `Context`，因此由 `MainActivity` 在
 * `setContent` 之前 `ScoreAppState.bridge = createFileBridge()` 注入。
 */
expect fun createFileBridge(): FileBridge

/**
 * 读取某个 PDF 的**首页**并渲成位图，用于列表封面。
 *
 * 缩放在实现侧按「目标宽 640 px、放大不超过 1.4×」夹取。
 * 失败（文件不存在、不是 PDF、页数为 0、解码异常）一律返回 null，
 * 由调用方降级——这是有意的：封面是附加信息，不该让一次渲染失败打断整页布局。
 */
expect fun loadCoverBitmap(path: String): ImageBitmap?

/**
 * 当前平台是否具备封面真渲染能力。
 *
 * HTML 原型里没有 `PdfRenderer`，只能退回程序化绘制；本工程在 Android 上为 true。
 * 演示边界说明用它来区分「实现差异」与「功能缺失」，避免把平台限制说成没做。
 */
expect val supportsCoverRender: Boolean
