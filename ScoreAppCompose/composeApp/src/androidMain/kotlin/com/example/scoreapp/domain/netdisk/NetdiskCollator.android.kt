package com.example.scoreapp.domain.netdisk

import java.text.Collator
import java.util.Locale

/**
 * 中文目录按**拼音**排。
 *
 * `String.compareTo` 是码位序，落在 CJK 基本区里大致是「按部首/笔画」，
 * 跟手机上翻目录的直觉对不上（原型里 `localeCompare(zh-Hans-CN)` 是拼音序）。
 * `Collator` 是 `java.text` 里的，commonMain 拿不到，所以放在 actual 里。
 */
private val COLLATOR: Collator = Collator.getInstance(Locale.CHINA)

internal actual fun collatedCompare(a: String, b: String): Int = COLLATOR.compare(a, b)
