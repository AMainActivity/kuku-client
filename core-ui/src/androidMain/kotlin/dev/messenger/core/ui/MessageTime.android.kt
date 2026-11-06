package dev.messenger.core.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeOnly=DateTimeFormatter.ofPattern("HH:mm")
private val dateAndTime=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal actual fun formatLocalMessageTime(epochMillis:Long,nowMillis:Long):String =
    formatLocalMessageTime(epochMillis,nowMillis,ZoneId.systemDefault())

internal fun formatLocalMessageTime(epochMillis:Long,nowMillis:Long,zone:ZoneId):String {
    val local=Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today=Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return local.format(if(local.toLocalDate()==today) timeOnly else dateAndTime)
}