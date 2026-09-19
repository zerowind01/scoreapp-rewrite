package com.example.scoreapp.domain.csvfix

import com.example.scoreapp.model.Score

/**
 * 两个适配器：把「外部 CSV」与「App 自己的曲库」都翻译成 [FixRow]。
 *
 * 这是「一套规则服务两个来源」的关键 —— 清洗规则只认 [FixRow]，
 * 所以修 forScore 导出的 CSV 与修自家曲库走的是**同一份规则代码**，
 * 不存在「两边行为不一样」的可能。
 *
 * 两者最容易出错的地方是 **[FixRow.fileName]**：
 * - CSV 侧它就是 `Filename` 列，必须**逐字符保真**（forScore 导入时拿它做关联主键，
 *   且对全小写名有 capitalize 行为，改一个字节就匹配不上）；
 * - 曲库侧它取自 `score.filePath` 的**文件名部分**（不含目录），
 *   这样导出的 CSV 才能被 forScore 按文件名匹配上。
 */
object CsvAdapter {

    /** CSV 数据行 → [FixRow]。越界或缺失的列一律给空串/空值，不抛异常 */
    fun toRows(header: List<String>, rows: List<List<String>>, columns: ColumnMap): List<FixRow> =
        rows.map { toRow(it, columns) }

    fun toRow(cells: List<String>, columns: ColumnMap): FixRow = FixRow(
        // Filename 不 trim：前后空格虽然少见，但它是要拿去匹配的主键，
        // 任何「顺手清理」都可能把一份能匹配的乐谱变成匹配不上的
        fileName = cells.cell(columns[ColumnMap.FILE]),
        title = cells.cell(columns[ColumnMap.TITLE]),
        composer = cells.cell(columns[ColumnMap.COMP]),
        genre = cells.cell(columns[ColumnMap.GENRE]),
        tag = cells.cell(columns[ColumnMap.TAG]),
        label = cells.cell(columns[ColumnMap.LABEL]),
        reference = cells.cell(columns[ColumnMap.REF]),
        startPage = cells.cell(columns[ColumnMap.SP]),
        endPage = cells.cell(columns[ColumnMap.EP]),
        keysf = cells.cell(columns.keysf).trim().toIntOrNull(),
        keymi = cells.cell(columns.keymi).trim().toIntOrNull(),
    )

    /**
     * 把「原行 + 该行的建议」合成新的 CSV 单元格。
     *
     * 三层优先级，由上到下：
     *  1. [FixProposal] 里被采纳的字段值（用户勾选了这条建议）；
     *  2. 原行的值；
     *  3. 空串。
     *
     * **起止页、评分、难度、Minutes/Seconds 等不在规则范围内的列一律原样保留**
     * —— 这个工具只修它该修的标签字段，其余列一个字节都不动。
     *
     * @param take 这一行哪些字段被采纳了（键为字段名：title/comp/tag/genre/label/ref/keysf）
     */
    fun toCells(
        header: List<String>,
        original: List<String>,
        columns: ColumnMap,
        proposal: FixProposal?,
        take: Set<String>,
    ): List<String> {
        val out = original.toMutableList()
        // 补齐：原行可能比表头短（CSV 里行尾空列常被省略）
        while (out.size < header.size) out.add("")

        fun write(col: String, value: String?) {
            if (value == null) return
            columns[col]?.let { if (it in out.indices) out[it] = value }
        }

        if (proposal != null) {
            if ("title" in take) write(ColumnMap.TITLE, proposal.title)
            if ("comp" in take) write(ColumnMap.COMP, proposal.composer)
            if ("genre" in take) write(ColumnMap.GENRE, proposal.genre)
            if ("tag" in take) write(ColumnMap.TAG, proposal.tag)
            if ("label" in take) write(ColumnMap.LABEL, proposal.label)
            if ("ref" in take) write(ColumnMap.REF, proposal.reference)
            // 调性必须两列一起写：只写 keysf 不写 keymi 会得到一个
            // 「有调号但不知大小调」的半残值，forScore 那边显示不出来
            if ("keysf" in take || "keymi" in take) {
                proposal.keysf?.let { write(ColumnMap.KEY_KEYSF, it.toString()) }
                proposal.keymi?.let { write(ColumnMap.KEY_KEYMI, it.toString()) }
            }
        }
        return out
    }

    /** 从单元格列表取某列；列不存在或越界给空串（与 CsvTable 的 cell 同口径） */
    private fun List<String>.cell(index: Int?): String =
        if (index == null || index < 0 || index >= size) "" else this[index]
}

/**
 * 曲库侧适配器：把 [Score] 塞进 [FixRow] 的壳子里、以及把修好的字段写回 [Score]。
 *
 * 为什么值得单独一个适配器：曲库的字段名与 forScore 的列名并不一一对应
 * （`composer` ↔ `Composers`、`type` ↔ `Genres`、`tag` 在新模型里还没有…），
 * 这层映射关系必须**只写一次**，否则「导出 CSV」与「曲库内清洗」两条路
 * 迟早会各自漂移。
 */
object LibraryAdapter {

    /**
     * [Score] → [FixRow]。
     *
     * `fileName` 取 `filePath` 的**文件名部分**（去掉目录）。
     * 这是为了让导出后的 CSV 能被 forScore 按文件名匹配上 ——
     * 曲库里存的是绝对路径（`/data/user/0/.../scores/x.pdf`），
     * 而 forScore 只认裸文件名。
     *
     * 没有本地文件（`filePath` 与 `assetPdf` 都空）时返回 null 的名字，
     * 由调用方决定是跳过还是用标题兜底 —— 本函数不替它决定。
     */
    fun toFixRow(score: Score): FixRow = FixRow(
        fileName = fileNameOf(score),
        title = score.title,
        composer = score.composer,
        genre = score.type,
        tag = "",
        label = "",
        reference = score.source,
        startPage = "",
        endPage = "",
        keysf = score.keysf,
        keymi = score.keymi,
    )

    /**
     * 取乐谱的文件名。
     *
     * 优先 `filePath`，退到 `assetPdf`（未安装内置乐谱时 `filePath` 为空、
     * 只有 `assetPdf` 这个裸文件名）。
     */
    fun fileNameOf(score: Score): String {
        val raw = score.filePath?.takeIf { it.isNotBlank() }
            ?: score.assetPdf?.takeIf { it.isNotBlank() }
            ?: return ""
        return baseName(raw)
    }

    /** 取路径的最后一段；两种分隔符都认（Windows 上跑测试时可能是 `\`） */
    fun baseName(path: String): String {
        val cut = path.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (cut < 0) path else path.substring(cut + 1)
    }

    /**
     * 把采纳的字段写回 [Score]。
     *
     * **只改规则管得到的字段**：标题 / 作曲家 / 类型 / 来源 / 调性。
     * 乐器、时期、难度、页数、缩略图参数、封面排版参数一概不动 ——
     * 那些是曲库自己的语义，forScore 没有对应列，也没理由被清洗规则碰到。
     */
    fun applyTo(score: Score, proposal: FixProposal, take: Set<String>): Score {
        var next = score
        // 标题：`title` 为 null 表示这条建议没改标题（例如合集子条目），保持原值
        if ("title" in take) proposal.title?.let { next = next.copy(title = it) }
        if ("comp" in take) proposal.composer?.let { next = next.copy(composer = it) }
        if ("genre" in take) proposal.genre?.let { next = next.copy(type = it) }
        if ("ref" in take) proposal.reference?.let { next = next.copy(source = it) }
        // 调性两列必须同时写，理由同 CsvAdapter.toCells
        if ("keysf" in take || "keymi" in take) {
            proposal.keysf?.let { sf -> next = next.copy(keysf = sf) }
            proposal.keymi?.let { mi -> next = next.copy(keymi = mi) }
        }
        return next
    }
}
