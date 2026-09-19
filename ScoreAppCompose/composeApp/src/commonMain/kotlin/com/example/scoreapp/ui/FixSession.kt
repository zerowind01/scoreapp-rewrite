package com.example.scoreapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.scoreapp.domain.csvfix.AiApplyOptions
import com.example.scoreapp.domain.csvfix.AiField
import com.example.scoreapp.domain.csvfix.AiFill
import com.example.scoreapp.domain.csvfix.AiItem
import com.example.scoreapp.domain.csvfix.ColumnMap
import com.example.scoreapp.domain.csvfix.CsvAdapter
import com.example.scoreapp.domain.csvfix.CsvTable
import com.example.scoreapp.domain.csvfix.FixProposal
import com.example.scoreapp.domain.csvfix.FixProposer
import com.example.scoreapp.domain.csvfix.FixRow
import com.example.scoreapp.domain.csvfix.LibraryAdapter
import com.example.scoreapp.domain.csvfix.RuleSwitches
import com.example.scoreapp.domain.csvfix.parseCsv
import com.example.scoreapp.domain.csvfix.toCsv
import com.example.scoreapp.model.Score

/**
 * 校对的**数据来源**。
 *
 * 两条来源的业务规则完全相同，差别只在于「改完之后写回哪里」：
 * - [Csv]：forScore 导出的 CSV，改完导出成新文件给用户导回 iPad；
 * - [Library]：App 自己的曲库，改完直接原地更新 [ScoreAppState.allScores]。
 *
 * 这正是 Jackson 拍板的「两者都要」—— 规则只有一份（[FixProposer] / [AiFill]），
 * 靠 [CsvAdapter] / [LibraryAdapter] 两个适配器接驳。
 */
enum class FixSource(val label: String) {
    Csv("forScore CSV"),
    Library("App 曲库"),
}

/** 校对表里的一行：原始数据 + 建议 + 用户是否采纳 */
class FixEntry(
    /** 原始单元格。曲库来源时为空列表（它没有 CSV 行） */
    val cells: List<String>,
    val row: FixRow,
    val proposal: FixProposal,
    /** 曲库来源时对应的乐谱；CSV 来源为 null */
    val score: Score? = null,
) {
    /** 用户勾选了这一行。默认「有改动就勾上」——工具的意图就是帮用户把该改的改掉 */
    var taken by mutableStateOf(proposal.hasChange)

    /** 这一行有改动可采纳吗 */
    val hasChange: Boolean get() = proposal.hasChange
}

/**
 * 一次 CSV 校对会话的全部状态。
 *
 * 为什么单独一个类而不是塞进 [ScoreAppState]：校对是一个**有明确开始与结束**的
 * 子任务（选文件 → 生成建议 → AI 补全 → 导出），它的临时态（表头、列映射、
 * 逐行勾选、AI 弹层）与曲库的常驻状态没有重叠。独立出来之后，
 * 关掉校对页就能整体丢弃，不会在曲库状态里留下残渣。
 */
class FixSession(
    val source: FixSource,
    val header: List<String>,
    val columns: ColumnMap,
    entries: List<FixEntry>,
    /** CSV 来源时的表头行原文，导出要原样写回 */
    private val originalHeader: List<String> = header,
) {
    val entries: SnapshotStateList<FixEntry> = mutableStateListOf<FixEntry>().apply { addAll(entries) }

    /** 规则开关。改任一开关都会重算全部建议（见 [recompute]） */
    var switches by mutableStateOf(RuleSwitches())

    /** 「AI 也在已有值上提建议」—— 默认关闭，用户要显式开 */
    var aiSuggestExisting by mutableStateOf(false)

    /** 「跳过合集子条目」—— 那些行的标题是页码片段，清洗规则会误伤 */
    var skipBookmarks by mutableStateOf(true)

    // ---------- 派生计数 ----------
    val total: Int get() = entries.size
    val changedCount: Int get() = entries.count { it.hasChange }
    val takenCount: Int get() = entries.count { it.hasChange && it.taken }
    /** 其中有多少条是「覆盖已有值」（UI 上给这类默认不勾选） */
    val overwriteCount: Int get() = entries.count { it.hasChange && isOverwrite(it) }

    /**
     * 这一条建议是否覆盖了用户已填的值。
     *
     * 覆盖比补空危险得多 —— 用户填过的内容是「已知正确」的，AI/规则的建议是「推测」。
     * 所以覆盖类默认不勾选，让用户逐条看着办。
     */
    fun isOverwrite(e: FixEntry): Boolean =
        e.proposal.keyOverride || e.proposal.overwrite.isNotEmpty()

    /** 某条建议实际会写哪些字段（用于回写与展示） */
    fun takenFields(e: FixEntry): Set<String> {
        if (!e.taken || !e.hasChange) return emptySet()
        val p = e.proposal
        return buildSet {
            if (p.title != null) add("title")
            if (p.composer != null) add("comp")
            if (p.genre != null) add("genre")
            if (p.tag != null) add("tag")
            if (p.label != null) add("label")
            if (p.reference != null) add("ref")
            if (p.keysf != null) add("keysf")
        }
    }

    fun toggleRow(index: Int) {
        val e = entries.getOrNull(index) ?: return
        if (e.hasChange) e.taken = !e.taken
    }

    /** 全选 / 全不选「有改动」的行 */
    fun setAllTaken(value: Boolean) {
        entries.forEach { if (it.hasChange) it.taken = value }
    }

    /**
     * 用当前开关重算全部建议。
     *
     * 重算会**保留用户已做的勾选决定**：`FixEntry` 对象不重建，只换 `proposal`，
     * 所以 `taken` 不会因为改了个开关就被重置。否则用户勾了 40 条、
     * 手滑碰到一个开关，全部白勾 —— 那种体验没人受得了。
     */
    fun recompute() {
        entries.forEachIndexed { i, e ->
            val next = FixProposer.propose(
                row = e.row,
                switches = switches,
                isBookmark = skipBookmarks && FixProposer.isBookmarkRow(e.row),
            )
            entries[i] = FixEntry(e.cells, e.row, next, e.score).also { it.taken = e.taken || next.hasChange }
        }
    }

    /** 待补全的行索引（交给 AI 的那些） */
    fun aiTargets(fields: List<AiField>): List<Int> =
        AiFill.pickTargets(
            rows = entries.map { it.row },
            columns = columns,
            fields = fields,
            // 「已在已有值上提建议」打开时就全问一遍，否则只问有空的
            onlyEmpty = !aiSuggestExisting,
            skipBookmark = skipBookmarks,
        )

    /**
     * AI 结果落地。**不动原始数据**，只把建议并进对应的 [FixProposal]。
     *
     * @return 产生了几条改动
     */
    fun applyAi(items: List<AiItem>, fields: List<AiField>): Int {
        if (items.isEmpty()) return 0
        val proposals = entries.map { it.proposal }.toMutableList()
        val n = AiFill.apply(
            proposals = proposals,
            rows = entries.map { it.row },
            columns = columns,
            results = items,
            fields = fields,
            allowOverwrite = aiSuggestExisting,
        )
        // 把改过的建议换回 entry，保留用户原有勾选
        proposals.forEachIndexed { i, p ->
            val e = entries[i]
            if (p !== e.proposal) {
                entries[i] = FixEntry(e.cells, e.row, p, e.score).also { it.taken = e.taken }
            }
        }
        return n
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 导出成 CSV 文本。
     *
     * CSV 来源：按采纳的字段改写单元格，**其余列一个字节不动**，
     * 表头也原样写回（forScore 靠列名匹配，动列名等于毁掉这份文件）。
     * 曲库来源：把整个曲库当成一张新表生成，列名用 forScore 的标准 15 列。
     */
    fun exportCsv(): String = when (source) {
        FixSource.Csv -> toCsv(
            originalHeader,
            entries.mapIndexed { i, e -> CsvAdapter.toCells(originalHeader, entries[i].cells, columns, e.proposal, takenFields(e)) },
        )
        FixSource.Library -> toCsv(LIBRARY_HEADER, entries.map { e ->
            val cells = MutableList(LIBRARY_HEADER.size) { "" }
            val p = e.proposal
            val fields = takenFields(e)
            val r = e.row
            cells[0] = r.fileName
            cells[1] = if ("title" in fields) p.title ?: r.title else r.title
            cells[4] = if ("comp" in fields) p.composer ?: r.composer else r.composer
            cells[5] = if ("genre" in fields) p.genre ?: r.genre else r.genre
            cells[6] = if ("tag" in fields) p.tag ?: r.tag else r.tag
            cells[7] = if ("label" in fields) p.label ?: r.label else r.label
            cells[8] = if ("ref" in fields) p.reference ?: r.reference else r.reference
            if ("keysf" in fields) {
                cells[13] = p.keysf?.toString().orEmpty()
                cells[14] = p.keymi?.toString().orEmpty()
            } else {
                cells[13] = r.keysf?.toString().orEmpty()
                cells[14] = r.keymi?.toString().orEmpty()
            }
            cells
        })
    }

    companion object {
        /** forScore 的标准 15 列，曲库导出用 */
        val LIBRARY_HEADER: List<String> = listOf(
            "Filename", "Title", "Start Page (Bookmark)", "End Page (Bookmark)", "Composers",
            "Genres", "Tags", "Labels", "Reference", "Rating", "Difficulty",
            "Minutes", "Seconds", "keysf", "keymi",
        )

        /** 从 CSV 文本开一次会话 */
        fun fromCsv(text: String, switches: RuleSwitches = RuleSwitches(), skipBookmarks: Boolean = true): FixSession {
            val table: CsvTable = parseCsv(text)
            val col = ColumnMap.of(table.header)
            val entries = table.rows.map { cells ->
                val row = CsvAdapter.toRow(cells, col)
                val prop = FixProposer.propose(row, switches, skipBookmarks && FixProposer.isBookmarkRow(row))
                val e = FixEntry(cells, row, prop)
                // 覆盖已有值的条目默认不勾 —— 补空是安全改动，覆盖不是
                e.taken = prop.hasChange && !(prop.keyOverride || prop.overwrite.isNotEmpty())
                e
            }
            return FixSession(FixSource.Csv, table.header, col, entries, table.header)
        }

        /** 从曲库开一次会话 */
        fun fromLibrary(
            scores: List<Score>,
            switches: RuleSwitches = RuleSwitches(),
            skipBookmarks: Boolean = true,
        ): FixSession {
            val col = ColumnMap.of(LIBRARY_HEADER)
            val entries = scores.map { s ->
                val row = LibraryAdapter.toFixRow(s)
                val prop = FixProposer.propose(row, switches, skipBookmarks && FixProposer.isBookmarkRow(row))
                val e = FixEntry(emptyList(), row, prop, s)
                e.taken = prop.hasChange && !(prop.keyOverride || prop.overwrite.isNotEmpty())
                e
            }
            return FixSession(FixSource.Library, LIBRARY_HEADER, col, entries, LIBRARY_HEADER)
        }
    }
}
