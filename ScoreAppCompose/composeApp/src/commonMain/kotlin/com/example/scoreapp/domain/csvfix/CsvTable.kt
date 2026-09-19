package com.example.scoreapp.domain.csvfix

/**
 * forScore CSV 的解析与序列化。
 *
 * 行为逐条对齐网页版 `tools/forscore-csv-fixer.html` 里的 `parseCsv` / `toCsv`，
 * 两端测试互相对照；任何一处改动都要同步另一边（在网页版作废前）。
 *
 * 为什么不用现成 CSV 库：forScore 的导出格式很朴素（QUOTE_MINIMAL、无转义花样），
 * 自己实现能保证「导出后与导入前逐字符一致」这条硬要求，也避免为一个功能引依赖。
 */

/** 解析结果：表头 + 数据行 */
data class CsvTable(
    val header: List<String>,
    val rows: List<List<String>>,
)

/**
 * 解析 CSV 文本。
 *
 * 处理：BOM 剥除、引号内逗号/换行/双引号转义（`""` → `"`）、CRLF、丢弃全空行。
 * 与网页版一致：**先按行解析再把首行当表头**，所以空表返回空表头 + 空行列表。
 */
fun parseCsv(text: String): CsvTable {
    // 去 BOM：Windows 下导出的 CSV 常带，留着会让第一个列名匹配不上
    val src = text.removePrefix("\uFEFF")
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuote = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (inQuote) {
            if (c == '"') {
                // 连续两个引号是转义，落一个引号字符
                if (i + 1 < src.length && src[i + 1] == '"') {
                    cur.append('"'); i += 2; continue
                }
                inQuote = false; i++; continue
            }
            cur.append(c); i++; continue
        }
        when (c) {
            '"' -> { inQuote = true; i++ }
            ',' -> { row.add(cur.toString()); cur.clear(); i++ }
            // CR 单独丢掉，换行统一由 \n 触发；否则 CRLF 会多出一个空行
            '\r' -> i++
            '\n' -> {
                row.add(cur.toString()); cur.clear()
                rows.add(row); row = mutableListOf()
                i++
            }
            else -> { cur.append(c); i++ }
        }
    }
    // 收尾：最后一行没有换行符时也要落盘
    if (cur.isNotEmpty() || row.isNotEmpty()) {
        row.add(cur.toString())
        rows.add(row)
    }
    // 参与比对时全空行没有意义（尾随换行会产生一行空行），丢掉
    val cleaned = rows.filter { r -> r.any { it.isNotEmpty() } }
    val header = cleaned.firstOrNull() ?: emptyList()
    return CsvTable(header, cleaned.drop(1))
}

/** 单个单元格的转义：含逗号 / 引号 / 换行才包引号，内部引号翻倍 */
private fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else {
        s
    }

/**
 * 序列化为 CSV 文本。
 *
 * 换行用 CRLF（与网页版、以及 forScore 自己的导出一致），尾部补一个换行。
 */
fun toCsv(header: List<String>, rows: List<List<String>>): String =
    buildString {
        append(header.joinToString(",") { csvCell(it) })
        append("\r\n")
        rows.forEach { r ->
            append(r.joinToString(",") { csvCell(it) })
            append("\r\n")
        }
    }

/**
 * 列定位。
 *
 * 先按标准英文名找，找不到按 forScore 固定 15 列的位置兜底 —— 所以用户把列头
 * 改成中文（「乐曲类型」「编配」「标签」「声部」「困难度」）也照样认得。
 *
 * **keysf / keymi 只在表长 ≥ 15 时才按位置兜底**：源 CSV 未必是 forScore 的格式，
 * 硬塞会让后面写出去时多出两列、把别人的表写坏。
 */
class ColumnMap private constructor(private val map: Map<String, Int>) {
    operator fun get(key: String): Int? = map[key]
    val keysf: Int? get() = map[KEY_KEYSF]
    val keymi: Int? get() = map[KEY_KEYMI]

    companion object {
        const val FILE = "file"
        const val TITLE = "title"
        const val SP = "sp"
        const val EP = "ep"
        const val COMP = "comp"
        const val GENRE = "genre"
        const val TAG = "tag"
        const val LABEL = "label"
        const val REF = "ref"
        const val RATING = "rating"
        const val DIFF = "diff"
        const val KEY_KEYSF = "keysf"
        const val KEY_KEYMI = "keymi"

        /** 按名字查找时的候选（小写、已去掉 `(Bookmark)` 后缀） */
        private val WANT: Map<String, List<String>> = mapOf(
            FILE to listOf("filename", "file"),
            TITLE to listOf("title"),
            COMP to listOf("composers", "composer"),
            GENRE to listOf("genres", "genre"),
            TAG to listOf("tags", "tag"),
            LABEL to listOf("labels", "label"),
            REF to listOf("reference"),
            RATING to listOf("rating"),
            DIFF to listOf("difficulty"),
            KEY_KEYSF to listOf("keysf"),
            KEY_KEYMI to listOf("keymi"),
        )

        /** forScore 固定列序的兜底位置 */
        private val POS: Map<String, Int> = mapOf(
            FILE to 0, TITLE to 1, SP to 2, EP to 3, COMP to 4,
            GENRE to 5, TAG to 6, LABEL to 7, REF to 8, RATING to 9, DIFF to 10,
        )

        fun of(header: List<String>): ColumnMap {
            val byName = mutableMapOf<String, Int>()
            WANT.forEach { (key, names) ->
                val idx = header.indexOfFirst { raw ->
                    val h = raw.lowercase().replace(Regex("\\s*\\(bookmark\\)"), "").trim()
                    names.contains(h)
                }
                if (idx >= 0) byName[key] = idx
            }
            val out = mutableMapOf<String, Int>()
            POS.forEach { (key, pos) -> out[key] = byName[key] ?: pos }
            // 只有确实是 15 列的 forScore 表才按位置补 keysf/keymi
            val sf = byName[KEY_KEYSF]
            if (sf != null) out[KEY_KEYSF] = sf else if (header.size >= 15) out[KEY_KEYSF] = 13
            val mi = byName[KEY_KEYMI]
            if (mi != null) out[KEY_KEYMI] = mi else if (header.size >= 15) out[KEY_KEYMI] = 14
            return ColumnMap(out)
        }
    }
}

/** 取单元格；越界或缺失一律给空串，调用方不必到处判空 */
fun List<String>.cell(index: Int?): String =
    if (index == null || index < 0 || index >= size) "" else this[index]

/** 按 [col] 代号取值 */
fun List<String>.cell(col: ColumnMap, key: String): String = cell(col[key])
