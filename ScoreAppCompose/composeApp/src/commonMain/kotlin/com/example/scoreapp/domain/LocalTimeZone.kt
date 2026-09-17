package com.example.scoreapp.domain

/**
 * 设备本地时区在给定时刻相对 UTC 的偏移（秒）。
 *
 * 存在的原因：`formatDate` 原先直接把毫秒时间戳按 **UTC** 解释
 * （`timestamp / 1000 / 86400`），在中国（UTC+8）下显示的添加时间会整整早 8 小时——
 * 晚上 20:00 导入的乐谱，详情页写着 12:00。
 *
 * commonMain 里没有任何时区数据可用（不能用 java.time / java.util.TimeZone），
 * 时区是实打实的平台能力，因此以 expect/actual 下沉到各平台实现。
 */
expect fun localUtcOffsetSeconds(atMillis: Long): Int
