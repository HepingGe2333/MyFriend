package com.yap.app.domain

import java.util.TimeZone

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun localUtcOffsetMillis(epochMillis: Long): Int =
    TimeZone.getDefault().getOffset(epochMillis)
