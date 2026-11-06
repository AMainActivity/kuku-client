package dev.messenger.core.ui

import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

class MessageTimeTest {
    private fun format(time:String,now:String,zone:String)=formatLocalMessageTime(Instant.parse(time).toEpochMilli(),Instant.parse(now).toEpochMilli(),ZoneId.of(zone))
    @Test fun usesLocalDayRatherThanUtcDay() {
        assertEquals("01:30",format("2026-11-05T20:30:00Z","2026-11-06T02:00:00Z","Asia/Yekaterinburg"))
        assertEquals("2026-11-05 23:30",format("2026-11-05T18:30:00Z","2026-11-05T20:30:00Z","Asia/Yekaterinburg"))
    }
    @Test fun handlesNegativeZoneAndOffsetInput() {
        assertEquals("16:30",format("2026-11-06T00:30:00Z","2026-11-06T01:30:00Z","America/Los_Angeles"))
        assertEquals("01:30",format("2026-11-06T01:30:00+05:00","2026-11-06T02:00:00Z","Asia/Yekaterinburg"))
    }
    @Test fun appliesDstAtMessageInstant() {
        assertEquals("01:30",format("2026-10-25T00:30:00Z","2026-10-25T12:00:00Z","Europe/London"))
        assertEquals("01:30",format("2026-10-25T01:30:00Z","2026-10-25T12:00:00Z","Europe/London"))
    }
    @Test fun invalidTimestampDoesNotCrashOrExposeRawWireValue() {
        assertEquals("—",formatMessageTimestamp("invalid",0))
    }
}