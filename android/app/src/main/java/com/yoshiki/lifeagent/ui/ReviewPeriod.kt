package com.yoshiki.lifeagent.ui

import com.yoshiki.lifeagent.data.ProgressLog
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

fun reviewLogs(logs: List<ProgressLog>, now: Instant, days: Int, zone: ZoneId): List<ProgressLog> {
    val start = now.atZone(zone).toLocalDate().minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant()
    return logs.filter {
        val time = runCatching { Instant.parse(it.recordedAt) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(it.recordedAt).toInstant() }.getOrNull()
        time != null && !time.isBefore(start) && !time.isAfter(now)
    }.sortedByDescending { java.time.OffsetDateTime.parse(it.recordedAt).toInstant() }
}

fun nextReviewTime(now: Instant, zone: ZoneId, weekly: Boolean): ZonedDateTime {
    var next = now.atZone(zone).withHour(20).withMinute(0).withSecond(0).withNano(0)
    if (weekly) {
        next = next.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
    }
    if (!next.toInstant().isAfter(now)) next = next.plusDays(if (weekly) 7 else 1)
    return next
}
