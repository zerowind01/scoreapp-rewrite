package com.example.scoreapp.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun nowStamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
