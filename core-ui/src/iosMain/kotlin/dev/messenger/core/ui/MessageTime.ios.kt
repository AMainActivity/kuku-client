package dev.messenger.core.ui

import platform.Foundation.*

internal actual fun formatLocalMessageTime(epochMillis:Long,nowMillis:Long):String {
    val date=NSDate.dateWithTimeIntervalSince1970(epochMillis/1000.0)
    val now=NSDate.dateWithTimeIntervalSince1970(nowMillis/1000.0)
    val formatter=NSDateFormatter().apply {
        locale=NSLocale(localeIdentifier="en_US_POSIX")
        timeZone=NSTimeZone.localTimeZone
        dateFormat="yyyy-MM-dd"
    }
    val today=formatter.stringFromDate(date)==formatter.stringFromDate(now)
    formatter.dateFormat=if(today) "HH:mm" else "yyyy-MM-dd HH:mm"
    return formatter.stringFromDate(date)
}