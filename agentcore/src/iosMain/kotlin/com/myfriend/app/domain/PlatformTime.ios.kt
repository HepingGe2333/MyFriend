package com.myfriend.app.domain

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun localUtcOffsetMillis(epochMillis: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val offsetSeconds = NSTimeZone.localTimeZone.secondsFromGMTForDate(date)
    return (offsetSeconds * 1000L).toInt()
}
