package com.example.scoreapp.util

/**
 * 当前时间戳（Unix 毫秒）。
 *
 * commonMain 里拿不到 `java.lang.System`，也没有 `kotlinx-datetime` 依赖，
 * 因此用 expect/actual 把这一处平台能力隔离出去。业务代码只依赖这个函数，
 * 将来新增 iOS / Desktop target 时补一个 actual 即可，无需改动 commonMain。
 */
expect fun nowMillis(): Long

/**
 * 当前本地时间的文件名戳，形如 `20260919-1630`。
 *
 * 单独一个 expect 而不是在 commonMain 里算：算出「年月日时分」需要时区，
 * 而 commonMain 没有 kotlinx-datetime。导出文件名用的是**本地**时间 ——
 * 用户晚上八点导出，文件名里就该写今天的日期，不能跟着 UTC 跑到明天去。
 */
expect fun nowStamp(): String
