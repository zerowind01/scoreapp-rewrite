package com.example.scoreapp.domain.netdisk

import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.MiniJson

/**
 * 网盘乐谱**入库乐谱库**的纯逻辑。
 *
 * 与 `Netdisk.kt`（浏览页）的分工：那边是「临时看看、不入库」，
 * 这边是「绑定文件夹 → 首页网盘页直接看到 → 能编辑元数据」。
 *
 * 对应原型 `prototype/netdisk-library-demo.html`，规格写在那个文件的头注释里。
 * 与原型同一条拆法：判断全在 commonMain、只进出纯数据，落盘与发请求由
 * `FileBridge` / `NetdiskBridge` 承担，这样这几条最容易改坏的语义
 * （同步不能覆盖用户填的、条目身份是路径不是文件名）能在单测里锁住。
 */

/** 网盘条目的来源标签。与校对页那套占位符同一口径 */
const val SRC_NETDISK = "网盘"

/** 一次同步里某个绑定目录拉到的东西 */
data class RemoteDir(val path: String, val files: List<NetdiskEntry>)

/**
 * 入库后的一个条目。
 *
 * [key] 是**规范化后的远端路径**（[NetLibrary.metaKeyOf]），不是文件名 ——
 * 不同目录下同名谱子必须各自独立。
 */
data class NetLibItem(
    val key: String,
    val title: String,
    val composer: String,
    val instrument: String,
    val type: String,
    val period: String,
    val level: String,
    val source: String = SRC_NETDISK,
    val remotePath: String,
    val size: Long = 0L,
)

/** 一次同步的结果。[added] / [removed] 用来给一句「新增 N 份、移除 M 份」的提示 */
data class NetLibSync(
    val items: List<NetLibItem>,
    val added: Int,
    val removed: Int,
)

/** 同步状态。交给 [NetLibrary.syncStatusText] 翻成人话，界面不许自己拼 */
data class NetSyncState(
    val busy: Boolean = false,
    val boundCount: Int = 0,
    val boundPaths: List<String> = emptyList(),
    val error: String? = null,
    val syncedAt: Long = 0L,
    val ageMs: Long = 0L,
)

/** 同步的节流窗口：切走再切回来不该每次都拉一次 */
const val SYNC_THROTTLE_MS = 60_000L

/**
 * 网盘入库的持久化内容（`library.json` 的 `net` 段）。
 *
 * [metas] 是**用户改过的字段**，按条目 key 索引，**跨同步永久保留** ——
 * 条目从远端消失也不删（改名 / 误删的容错）。
 */
/**
 * 「打开网盘谱子」的一次请求 —— 等同一次用户点击。
 *
 * 界面拿**它本身**当 effect 的 key：换了它必须重跑，而它**只由点击产生、
 * effect 一个字节都不许回写**（回写 = 改掉自己的 key = 正在跑的协程被取消）。
 * 详见 [NetLibrary.nextOpenRequest]。
 */
data class NetOpenRequest(val remotePath: String, val seq: Int)

data class NetLibStore(
    val items: List<NetLibItem> = emptyList(),
    val metas: Map<String, Map<String, String>> = emptyMap(),
    val bound: List<String> = emptyList(),
    val syncedAt: Long = 0L,
) {
    fun toJson(): String = buildString {
        append("{\"v\":1,\"bound\":[")
        bound.forEachIndexed { i, b -> if (i > 0) append(','); append(q(b)) }
        append("],\"syncedAt\":").append(syncedAt)
        append(",\"items\":[")
        items.forEachIndexed { i, it ->
            if (i > 0) append(',')
            append("{\"key\":").append(q(it.key))
            append(",\"title\":").append(q(it.title))
            append(",\"composer\":").append(q(it.composer))
            append(",\"instrument\":").append(q(it.instrument))
            append(",\"type\":").append(q(it.type))
            append(",\"period\":").append(q(it.period))
            append(",\"level\":").append(q(it.level))
            append(",\"source\":").append(q(it.source))
            append(",\"remotePath\":").append(q(it.remotePath))
            append(",\"size\":").append(it.size)
            append('}')
        }
        append("],\"metas\":{")
        var first = true
        metas.forEach { (k, m) ->
            if (m.isEmpty()) return@forEach
            if (!first) append(',')
            first = false
            append(q(k)).append(":{")
            var f2 = true
            m.forEach { (f, v) ->
                if (!f2) append(',')
                f2 = false
                append(q(f)).append(':').append(q(v))
            }
            append('}')
        }
        append("}}")
    }

    companion object {

        /** **任何解析失败一律退成空存档，绝不抛异常**：读不回来只该表现为「上次进度没了」 */
        fun fromJson(text: String?): NetLibStore {
            if (text.isNullOrBlank()) return NetLibStore()
            val obj = runCatching { MiniJson.parse(text) }.getOrNull() as? MiniJson.Value.Obj
                ?: return NetLibStore()
            return fromValue(obj)
        }

        /**
         * 从已解析好的 JSON 值还原。
         *
         * 单独留这个入口是因为存档是**一份文件装两块**（`net` 段 + `local` 段）：
         * 外层只解析一次，两段各自认领自己的子树，不必把子串再拼回文本去解析。
         */
        internal fun fromValue(v: MiniJson.Value?): NetLibStore {
            val obj = v as? MiniJson.Value.Obj ?: return NetLibStore()
            fun str(v: MiniJson.Value?): String = (v as? MiniJson.Value.Str)?.value.orEmpty()

            val bound = (obj.fields["bound"] as? MiniJson.Value.Arr)?.items.orEmpty()
                .map { str(it) }.filter { it.isNotBlank() }

            val items = (obj.fields["items"] as? MiniJson.Value.Arr)?.items.orEmpty()
                .mapNotNull { raw ->
                    val o = raw as? MiniJson.Value.Obj ?: return@mapNotNull null
                    val key = str(o.fields["key"])
                    if (key.isBlank()) return@mapNotNull null
                    NetLibItem(
                        key = key,
                        title = str(o.fields["title"]),
                        composer = str(o.fields["composer"]),
                        instrument = str(o.fields["instrument"]),
                        type = str(o.fields["type"]),
                        period = str(o.fields["period"]),
                        level = str(o.fields["level"]),
                        source = str(o.fields["source"]).ifBlank { SRC_NETDISK },
                        remotePath = str(o.fields["remotePath"]).ifBlank { key },
                        size = (o.fields["size"] as? MiniJson.Value.Num)?.value?.toLongOrNull() ?: 0L,
                    )
                }

            val metas = LinkedHashMap<String, Map<String, String>>()
            (obj.fields["metas"] as? MiniJson.Value.Obj)?.fields?.forEach { (k, v) ->
                val m = v as? MiniJson.Value.Obj ?: return@forEach
                val fields = LinkedHashMap<String, String>()
                m.fields.forEach { (f, fv) ->
                    val s = str(fv)
                    if (s.isNotBlank()) fields[f] = s
                }
                if (fields.isNotEmpty()) metas[k] = fields
            }

            val syncedAt = (obj.fields["syncedAt"] as? MiniJson.Value.Num)?.value?.toLongOrNull() ?: 0L
            return NetLibStore(items, metas, bound, syncedAt)
        }
    }
}

object NetLibrary {

    /** 用户可以改的 6 个字段。**来源不在内**：网盘就是网盘，改它没意义 */
    private val FIELDS = listOf("title", "composer", "instrument", "type", "period", "level")

    /** 文件名 → 默认标题：去掉扩展名。`拜厄 No.1.pdf` → `拜厄 No.1` */
    fun titleFromFileName(name: String): String {
        val s = name.trim()
        val i = s.lastIndexOf('.')
        return if (i > 0) s.substring(0, i) else s
    }

    /** 身份键。**用规范化远端路径，不是文件名** —— 不同目录下同名谱子要各自独立 */
    fun metaKeyOf(remotePath: String): String = Netdisk.normPath(remotePath)

    /**
     * 首次出现时生成的默认元数据。
     *
     * 占位符沿用校对页那套（别自创一套）：作曲家 佚名 / 类型 未编目 /
     * 乐器 未分类 / 时期 未指定 / 难度 — / 来源 网盘。
     */
    fun defaultMetaOf(name: String, path: String, size: Long): NetLibItem = NetLibItem(
        key = metaKeyOf(path),
        title = titleFromFileName(name),
        composer = "佚名",
        instrument = "未分类",
        type = "未编目",
        period = "未指定",
        level = "—",
        source = SRC_NETDISK,
        remotePath = Netdisk.normPath(path),
        size = size,
    )

    /** 把用户改过的字段盖到条目上。**只盖 meta 里有的字段**，没改的保持默认 */
    fun applyMeta(item: NetLibItem, meta: Map<String, String>?): NetLibItem {
        if (meta == null) return item
        var out = item
        for (f in FIELDS) {
            val v = meta[f]
            if (v.isNullOrEmpty()) continue
            out = when (f) {
                "title" -> out.copy(title = v)
                "composer" -> out.copy(composer = v)
                "instrument" -> out.copy(instrument = v)
                "type" -> out.copy(type = v)
                "period" -> out.copy(period = v)
                "level" -> out.copy(level = v)
                else -> out
            }
        }
        return out
    }

    /**
     * 同步：**以远端为准**，但**用户改过的元数据永不被覆盖**。
     *
     * 只做三件事：加新条目、删消失的条目、更新体积。默认元数据只在首次出现时生成一次。
     * [metas] 是跨同步永久保留的用户改动，条目消失也不删。
     */
    fun syncLibrary(
        prev: List<NetLibItem>,
        metas: Map<String, Map<String, String>>,
        remote: List<RemoteDir>,
    ): NetLibSync {
        val byKey = HashMap<String, NetLibItem>()
        prev.forEach { byKey[it.key] = it }
        val out = ArrayList<NetLibItem>()
        val seen = HashSet<String>()
        var added = 0
        for (dir in remote) {
            for (file in dir.files) {
                // 只收当前层的 PDF：目录不进（要更深的目录就单独绑它），非 PDF 也不进
                if (file.dir || !Netdisk.isPdfName(file.name)) continue
                val key = metaKeyOf(file.path)
                if (!seen.add(key)) continue          // 两个绑定目录命中同一份：只进一次
                val base = byKey[key] ?: defaultMetaOf(file.name, file.path, file.size)
                val sized = base.copy(
                    size = if (file.size > 0) file.size else base.size,
                    remotePath = Netdisk.normPath(file.path),
                )
                out.add(applyMeta(sized, metas[key]))
                if (!byKey.containsKey(key)) added++
            }
        }
        val removed = (prev.size + added - out.size).coerceAtLeast(0)
        return NetLibSync(out, added, removed)
    }

    /** 把编辑面板的草稿存进 metas。空串按「没填」处理（退回默认），全空则整条删掉 */
    fun saveMetaDraft(
        metas: Map<String, Map<String, String>>,
        key: String,
        draft: Map<String, String>,
    ): Map<String, Map<String, String>> {
        val cur = LinkedHashMap<String, String>(metas[key].orEmpty())
        for (f in FIELDS) {
            val v = draft[f]?.trim().orEmpty()
            if (v.isNotEmpty()) cur[f] = v else cur.remove(f)
        }
        val next = LinkedHashMap(metas)
        if (cur.isEmpty()) next.remove(key) else next[key] = cur
        return next
    }

    /** 绑定 / 解绑一个文件夹。返回新列表，不改入参 */
    fun toggleBound(bound: List<String>, path: String): List<String> {
        val p = Netdisk.normPath(path)
        val hit = bound.any { Netdisk.normPath(it) == p }
        val out = bound.filter { Netdisk.normPath(it) != p }.toMutableList()
        if (!hit) out.add(p)
        return out
    }

    fun isBound(bound: List<String>, path: String): Boolean {
        val p = Netdisk.normPath(path)
        return bound.any { Netdisk.normPath(it) == p }
    }

    fun boundSummary(bound: List<String>): String =
        if (bound.isEmpty()) "还没绑定文件夹" else "已绑定 ${bound.size} 个文件夹"

    /** 是否要下载。缓存只有最近 1 份，所以除了它都得重下 */
    /**
     * 排一次「打开网盘谱子」的请求。
     *
     * [seq] 每点一次加一，界面拿返回的对象当 effect 的 key：
     *  - 同一份谱子**连点两次也必须得到不同的对象** —— key 没变 effect 就不会重跑，
     *    用户看到的是「点了没反应」；
     *  - 反过来，effect **绝不能回写这个对象**。旧代码在 effect 里先 `netPendingOpen = null`
     *    再干活，而那正是它自己的 key：Compose 会把正在跑的下载协程取消掉，
     *    可 IO 块不看取消、文件照旧写完 —— 于是界面永远停在「已下载，正在打开…」，
     *    既没错误也没得等（1.23 真机定位）。
     */
    fun nextOpenRequest(current: NetOpenRequest?, remotePath: String): NetOpenRequest =
        NetOpenRequest(remotePath, (current?.seq ?: 0) + 1)

    fun needDownload(key: String, cacheKeys: Set<String>): Boolean = key !in cacheKeys

    /** 「多久之前」的人话。同步失败时要说清列表是什么时候的 */
    fun formatAgo(ms: Long): String {
        if (ms <= 0L) return "从没同步过"
        val s = ms / 1000
        if (s < 60) return "刚刚"
        val m = s / 60
        if (m < 60) return "$m 分钟前"
        val h = m / 60
        if (h < 24) return "$h 小时前"
        return "${h / 24} 天前"
    }

    /**
     * 同步条文案。三种态必须都能说清：同步中 / 已同步（多久前）/ 失败（显示多久前的列表）。
     */
    fun syncStatusText(s: NetSyncState): String = when {
        s.busy -> "正在同步…"
        s.boundCount == 0 -> "还没绑定文件夹，点「绑定文件夹」去选一个目录"
        s.error != null -> "同步失败：${s.error}，显示的是${formatAgo(s.ageMs)}的列表"
        s.syncedAt == 0L -> "还没同步过，点「立即同步」"
        else -> "${boundSummary(s.boundPaths)} · 已同步 ${formatAgo(s.ageMs)}"
    }

}

/** 文件级而不是 object 内：`NetLibStore.toJson` 也要用，放在 object 里它看不见 */
private fun q(s: String): String = "\"" + AiFill.jsonEsc(s) + "\""
