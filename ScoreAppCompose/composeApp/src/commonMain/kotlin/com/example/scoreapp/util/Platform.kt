package com.example.scoreapp.util

/**
 * 当前时间戳（Unix 毫秒）。
 *
 * commonMain 里拿不到 `java.lang.System`，也没有 `kotlinx-datetime` 依赖，
 * 因此用 expect/actual 把这一处平台能力隔离出去。业务代码只依赖这个函数，
 * 将来新增 iOS / Desktop target 时补一个 actual 即可，无需改动 commonMain。
 */
expect fun nowMillis(): Long
