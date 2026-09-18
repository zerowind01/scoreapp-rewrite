package com.example.scoreapp.domain

/**
 * 崩溃日志的格式与文件名。
 *
 * 规格逐条对译原应用 `ScoreApp.java`（反编译产物），不是自己发明的：
 *
 *  - 文件名固定 `crash_last.txt`；
 *  - 六段头 + `--- stacktrace ---` 分隔行 + 堆栈：
 *    `time` / `tag` / `device` / `os` / `abi` / `app`；
 *  - `tag` 是 `"uncaught@" + 线程名`；
 *  - `app` 段是「版本名 (versionCode)」。
 *
 * 原应用把 `app` 写死成旧值 `1.2 (3)`——它自己的 stale 值，这里要求调用方
 * 传真实版本，不做任何常量兜底。
 *
 * 放 commonMain 是为了能被普通单元测试直接锁住格式；
 * 落盘、装处理器这些平台动作在 androidMain 的 `ScoreApp` 里。
 */
object CrashLog {

    /** 固定文件名。原应用同时写内、外两份，都叫这个名 */
    const val FILE_NAME = "crash_last.txt"

    /** 头段与堆栈之间的分隔行 */
    const val STACK_SEPARATOR = "--- stacktrace ---"

    /**
     * 拼一份崩溃日志。
     *
     * @param time 形如 `yyyy-MM-dd HH:mm:ss`（原应用 SimpleDateFormat / Locale.US）
     * @param tag  原应用为 `"uncaught@" + thread.name`；主线程即 `uncaught@main`
     * @param app  `"版本名 (versionCode)"`，由调用方从 PackageManager 现取
     * @param stack `printStackTrace` 的输出；为空时保留占位行，结构永不塌
     */
    fun build(
        time: String,
        tag: String,
        device: String,
        os: String,
        abi: String,
        app: String,
        stack: String,
    ): String = buildString {
        append("time=").append(time).append('\n')
        append("tag=").append(tag).append('\n')
        append("device=").append(device).append('\n')
        append("os=").append(os).append('\n')
        append("abi=").append(abi).append('\n')
        append("app=").append(app).append('\n')
        append(STACK_SEPARATOR).append('\n')
        append(if (stack.isBlank()) "(no stack)" else stack)
    }

    /** 解析回结构化字段，供浮层按段展示（识别不出时整段落 [CrashParsed.raw]） */
    fun parse(text: String): CrashParsed {
        val idx = text.indexOf(STACK_SEPARATOR)
        if (idx < 0) return CrashParsed(emptyMap(), "", text)
        val head = text.substring(0, idx)
        val stack = text.substring(idx + STACK_SEPARATOR.length).trimStart('\n')
        val fields = head.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
        return CrashParsed(fields, stack, text)
    }
}

/** [CrashLog.parse] 的结果。[raw] 永远是完整原文，复制日志时用它 */
class CrashParsed(
    val fields: Map<String, String>,
    val stack: String,
    val raw: String,
)
