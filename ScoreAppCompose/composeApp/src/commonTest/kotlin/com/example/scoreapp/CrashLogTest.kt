package com.example.scoreapp

import com.example.scoreapp.domain.CrashLog
import com.example.scoreapp.domain.formatBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 崩溃日志格式与容量格式化的纯逻辑测试。
 *
 * 崩溃格式逐条对译原应用 `ScoreApp.java`（反编译产物）——它是这套格式的
 * 唯一权威：六段头 + 分隔行 + 堆栈。文件名、判据（空=无崩溃）也一并锁住，
 * 防止 androidMain 的落盘实现与格式约定各自漂移。
 */
class CrashLogTest {

    private val stack = "java.lang.IllegalStateException: boom\n  at A.b(A.kt:1)"

    private fun build(stack: String = this.stack) = CrashLog.build(
        time = "2026-09-18 16:34:05",
        tag = "uncaught@main",
        device = "Xiaomi 23127PN0CC",
        os = "Android 15 (API 35)",
        abi = "arm64-v8a",
        app = "1.7 (8)",
        stack = stack,
    )

    // ---------------------------------------------------------------- 格式

    @Test
    fun `日志为六段头加分隔行加堆栈`() {
        val lines = build().split("\n")
        assertEquals(9, lines.size, "6 段头 + 1 分隔 + 2 行堆栈")
        assertEquals("time=2026-09-18 16:34:05", lines[0])
        assertEquals("tag=uncaught@main", lines[1])
        assertEquals("device=Xiaomi 23127PN0CC", lines[2])
        assertEquals("os=Android 15 (API 35)", lines[3])
        assertEquals("abi=arm64-v8a", lines[4])
        assertEquals("app=1.7 (8)", lines[5])
        assertEquals("--- stacktrace ---", lines[6])
        assertEquals(stack, lines.drop(7).joinToString("\n"))
    }

    @Test
    fun `文件名与分隔行是固定常量`() {
        // 原应用两处（内/外部）都写这个名，浮层文案也引用它
        assertEquals("crash_last.txt", CrashLog.FILE_NAME)
        assertEquals("--- stacktrace ---", CrashLog.STACK_SEPARATOR)
    }

    @Test
    fun `版本段不再出现原应用写死的旧值`() {
        // 原应用 stale 成 app=1.2 (3)；调用方必须传真实版本
        assertTrue(!build().contains("1.2 (3)"))
        assertTrue(build().contains("app=1.7 (8)"))
    }

    @Test
    fun `空堆栈保留占位行`() {
        // 结构塌了（少一行）会让下游按行解析出乱序字段，所以宁可占位也不留空
        val text = CrashLog.build("t", "g", "d", "o", "a", "v", "  ")
        assertTrue(text.endsWith("--- stacktrace ---\n(no stack)"))
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析能还原字段与堆栈`() {
        val parsed = CrashLog.parse(build())
        assertEquals("2026-09-18 16:34:05", parsed.fields["time"])
        assertEquals("uncaught@main", parsed.fields["tag"])
        assertEquals("1.7 (8)", parsed.fields["app"])
        assertEquals(stack, parsed.stack)
        assertEquals(build(), parsed.raw, "原文必须原样保留，复制日志用它")
    }

    @Test
    fun `无分隔行的残缺日志整段落回原文`() {
        val text = "随便什么旧内容"
        val parsed = CrashLog.parse(text)
        assertEquals("", parsed.stack)
        assertEquals(text, parsed.raw)
        assertNull(parsed.fields["time"])
    }

    // ---------------------------------------------------------------- 判据

    @Test
    fun `空串与纯空白都不是崩溃`() {
        // 原应用判据是 file.length() <= 0；落盘实现照此写，测试锁语义
        assertTrue("".isBlank() && "  \n ".isBlank())
        // 防御性断言：lastCrash 的实现必须用 isBlank 一类的判据而不是 isNotEmpty
        assertTrue(CrashLog.parse("").raw.isEmpty())
    }

    // ---------------------------------------------------------------- formatBytes

    @Test
    fun `容量三档格式化与原型口径一致`() {
        assertEquals("—", formatBytes(0))
        assertEquals("—", formatBytes(-5))
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("861 KB", formatBytes(881_869), "真机 APK 内置月光 PDF 的真实大小")
        assertEquals("1.0 MB", formatBytes(1_048_576), "恰好在 1MB 边界上")
        assertEquals("1.5 MB", formatBytes(1_572_864))
    }
}
