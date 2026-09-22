package com.example.scoreapp.domain.library

import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.MiniJson
import com.example.scoreapp.model.Score

/**
 * **本机**曲库的持久化（`library.json` 的 `local` 段）。
 *
 * 为什么现在才做持久化：原先曲库全在内存里（`allScores`），导入的谱子与改过的元数据
 * 一重启就没了。网盘入库要求「用户改过的元数据跨同步、跨重启都在」，
 * 既然要落盘，本机这一半顺手一起做 —— 否则用户会发现一件很怪的事：
 * 网盘谱子的修改记得住，本机谱子的记不住。
 *
 * 存什么：只存**用户改过的字段**（[LocalStore.metas]）与**导入进来的谱子**
 * （[LocalStore.imported]）。内置样例来自 `SampleLibrary`，不落盘；
 * 但用户删掉过哪份要记（[LocalStore.hidden]），否则重启又会复活。
 */

/** 一份导入进来的乐谱 —— 能重建 [Score] 所需的最小集合 */
data class ImportedScore(
    val title: String,
    val composer: String,
    val type: String,
    val instrument: String,
    val period: String,
    val level: String,
    val source: String,
    val pages: Int,
    val dateAdded: Long,
    val filePath: String,
    val thumbSeed: Int,
    val thumbRows: Int,
)

/**
 * 本机曲库的落盘内容。
 *
 * [metas] 的键来自 [scoreKeyOf]；值只放**改过的**字段，值是空串表示「退回默认」。
 */
data class LocalStore(
    val metas: Map<String, Map<String, String>> = emptyMap(),
    val imported: List<ImportedScore> = emptyList(),
    val hidden: Set<String> = emptySet(),
) {
    fun toJson(): String = buildString {
        append("{\"v\":1,\"imported\":[")
        imported.forEachIndexed { i, it ->
            if (i > 0) append(',')
            append("{\"title\":").append(q(it.title))
            append(",\"composer\":").append(q(it.composer))
            append(",\"type\":").append(q(it.type))
            append(",\"instrument\":").append(q(it.instrument))
            append(",\"period\":").append(q(it.period))
            append(",\"level\":").append(q(it.level))
            append(",\"source\":").append(q(it.source))
            append(",\"pages\":").append(it.pages)
            append(",\"dateAdded\":").append(it.dateAdded)
            append(",\"filePath\":").append(q(it.filePath))
            append(",\"thumbSeed\":").append(it.thumbSeed)
            append(",\"thumbRows\":").append(it.thumbRows)
            append('}')
        }
        append("],\"hidden\":[")
        hidden.forEachIndexed { i, h -> if (i > 0) append(','); append(q(h)) }
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
        /** 与网盘存档同一条规矩：解析失败一律退空，不抛异常 */
        fun fromJson(text: String?): LocalStore {
            if (text.isNullOrBlank()) return LocalStore()
            val obj = runCatching { MiniJson.parse(text) }.getOrNull() as? MiniJson.Value.Obj
                ?: return LocalStore()
            return fromValue(obj)
        }

        /** 从已解析好的 JSON 值还原。理由同 `NetLibStore.fromValue`：存档是一份文件装两块 */
        internal fun fromValue(v: MiniJson.Value?): LocalStore {
            val obj = v as? MiniJson.Value.Obj ?: return LocalStore()
            fun str(v: MiniJson.Value?): String = (v as? MiniJson.Value.Str)?.value.orEmpty()
            fun int(v: MiniJson.Value?): Int = (v as? MiniJson.Value.Num)?.value?.toIntOrNull() ?: 0

            val imported = (obj.fields["imported"] as? MiniJson.Value.Arr)?.items.orEmpty()
                .mapNotNull { raw ->
                    val o = raw as? MiniJson.Value.Obj ?: return@mapNotNull null
                    val path = str(o.fields["filePath"])
                    // 没有真实文件就没有重建的意义：那份 PDF 已经不在手机上了
                    if (path.isBlank()) return@mapNotNull null
                    ImportedScore(
                        title = str(o.fields["title"]),
                        composer = str(o.fields["composer"]),
                        type = str(o.fields["type"]),
                        instrument = str(o.fields["instrument"]),
                        period = str(o.fields["period"]),
                        level = str(o.fields["level"]),
                        source = str(o.fields["source"]).ifBlank { "本地导入" },
                        pages = int(o.fields["pages"]),
                        dateAdded = (o.fields["dateAdded"] as? MiniJson.Value.Num)?.value
                            ?.toLongOrNull() ?: 0L,
                        filePath = path,
                        thumbSeed = int(o.fields["thumbSeed"]),
                        thumbRows = int(o.fields["thumbRows"]).takeIf { it > 0 } ?: 5,
                    )
                }

            val hidden = (obj.fields["hidden"] as? MiniJson.Value.Arr)?.items.orEmpty()
                .map { str(it) }.filter { it.isNotBlank() }.toSet()

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
            return LocalStore(metas, imported, hidden)
        }
    }
}

/**
 * 本机条目的身份键。
 *
 * **优先 `assetPdf`**：内置谱的 `filePath` 是启动时才由 `installBundledScores`
 * 解析出来的，拿它当键会在「装过 / 没装过内置资源」之间漂移，改过的元数据就找不回来了。
 */
fun scoreKeyOf(score: Score): String =
    score.assetPdf?.takeIf { it.isNotBlank() }?.let { "asset:$it" }
        ?: score.filePath?.takeIf { it.isNotBlank() }?.let { "file:$it" }
        ?: "id:${score.id}"

/** 用户可以改的 6 个字段（与网盘条目同一套，来源另说） */
private val META_FIELDS = listOf("title", "composer", "instrument", "type", "period", "level")

/** 把用户改过的字段盖到本机条目上。只盖 meta 里有的字段 */
fun applyLocalMeta(score: Score, meta: Map<String, String>?): Score {
    if (meta == null) return score
    var out = score
    for (f in META_FIELDS) {
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

/** 存草稿：空串 = 退回默认；全空则整条删掉 */
fun saveLocalMeta(
    metas: Map<String, Map<String, String>>,
    key: String,
    draft: Map<String, String>,
): Map<String, Map<String, String>> {
    val cur = LinkedHashMap<String, String>(metas[key].orEmpty())
    for (f in META_FIELDS) {
        val v = draft[f]?.trim().orEmpty()
        if (v.isNotEmpty()) cur[f] = v else cur.remove(f)
    }
    val next = LinkedHashMap(metas)
    if (cur.isEmpty()) next.remove(key) else next[key] = cur
    return next
}

/** 导入记录 → 曲库条目。`id` 由调用方分配，避免与内置样例撞号 */
fun ImportedScore.toScore(id: Long): Score = Score(
    id = id,
    title = title,
    composer = composer,
    type = type,
    instrument = instrument,
    period = period,
    level = level,
    source = source,
    pages = pages,
    dateAdded = dateAdded,
    filePath = filePath,
    thumbSeed = thumbSeed,
    thumbRows = thumbRows,
)

private fun q(s: String): String = "\"" + AiFill.jsonEsc(s) + "\""
