package com.example.scoreapp.domain

import java.util.TimeZone

/**
 * Android 侧实现：取设备当前时区在该时刻的 UTC 偏移。
 *
 * 用 `getOffset(atMillis)` 而不是「当前时刻的偏移」：夏令时切换前后的时间戳
 * 必须按各自当天的偏移解释，否则跨切换的记录会差一小时。
 */
actual fun localUtcOffsetSeconds(atMillis: Long): Int =
    TimeZone.getDefault().getOffset(atMillis) / 1000
