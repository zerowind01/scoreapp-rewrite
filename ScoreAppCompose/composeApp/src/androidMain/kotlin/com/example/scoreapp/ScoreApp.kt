package com.example.scoreapp

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.scoreapp.domain.CrashLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口：安装崩溃日志器。
 *
 * 逐条对译原应用 `ScoreApp.java`：
 *
 *  - 包住默认的 [Thread.UncaughtExceptionHandler]；写完日志**必须转交默认处理器**，
 *    让系统照常走崩溃流程——捕获只落盘，绝不吞异常；
 *  - 日志同时写 `filesDir/crash_last.txt` 与 `getExternalFilesDir()/crash_last.txt`
 *    两份：内部份给程序自己读，外部份（`Android/data/<包名>/files/`）让用户
 *    不接电脑也能用文件管理器取走；
 *  - [lastCrash] 的判据是「存在且非空」，与原应用 `file.length() <= 0` 一致；
 *  - 每一步都套 try/catch——崩溃处理路径上再抛异常就真的没救了。
 *
 * 与原应用的两处刻意差异：
 *  1. `app=` 段用 PackageManager 现取的真实版本，不写死（原应用 stale 成 `1.2 (3)`）；
 *  2. 时间格式沿用 `yyyy-MM-dd HH:mm:ss`（Locale.US），保持日志可对读。
 */
class ScoreApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(throwable, "uncaught@" + thread.name)
            } catch (_: Throwable) {
            }
            // 关键一步：转交。不转交等于把崩溃吞了，系统不会弹「已停止运行」，
            // 问题会被静默掩盖成「行为怪异」。
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 落盘。内部 + 外部两份；任何一份失败不影响另一份，更不影响转交 */
    fun writeCrashLog(throwable: Throwable, tag: String) {
        try {
            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val text = CrashLog.build(
                time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                tag = tag,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                abi = Build.SUPPORTED_ABIS.joinToString(", "),
                app = appVersionLabel(),
                stack = stack,
            )
            val internal = File(filesDir, CrashLog.FILE_NAME)
            internal.parentFile?.mkdirs()
            internal.writeText(text)
            getExternalFilesDir(null)?.let { ext ->
                ext.mkdirs()
                File(ext, CrashLog.FILE_NAME).writeText(text)
            }
            Log.e(TAG, text)
        } catch (_: Throwable) {
        }
    }

    /** 上次崩溃的日志全文；不存在或空一律 null（与原应用同判据） */
    fun lastCrash(): String? {
        return try {
            val f = File(filesDir, CrashLog.FILE_NAME)
            if (f.exists() && f.length() > 0) f.readText() else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 读取后立刻删除。MainActivity 在 setContent 之前调用，保证一次崩溃只提示一次 */
    fun clearCrash() {
        try {
            File(filesDir, CrashLog.FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    /** 从 PackageManager 现取「版本名 (versionCode)」；取不到退化为 unknown */
    private fun appVersionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    } catch (_: Throwable) {
        "unknown"
    }

    private companion object {
        const val TAG = "ScoreApp"
    }
}

/** 供浮层取外部份日志路径做展示文案（与原型同句式） */
fun Context.externalCrashHint(): String =
    "${getExternalFilesDir(null)?.path ?: filesDir.path}/${CrashLog.FILE_NAME}"
