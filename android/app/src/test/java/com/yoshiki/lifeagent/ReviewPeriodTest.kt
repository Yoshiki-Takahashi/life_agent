package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.ProgressLog
import com.yoshiki.lifeagent.ui.nextReviewTime
import com.yoshiki.lifeagent.ui.reviewLogs
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReviewPeriodTest {
    @Test fun filtersLocalCalendarPeriodAndFutureRecords() {
        val now = Instant.parse("2026-10-25T12:00:00Z")
        fun log(time: String) = ProgressLog(time, "", time, time, emptyList())
        val logs = listOf(log("2026-10-18T14:59:59Z"), log("2026-10-18T15:00:00Z"),
            log("2026-10-25T12:00:00Z"), log("2026-10-25T12:00:01Z"), log("invalid"))
        assertEquals(2, reviewLogs(logs, now, 7, ZoneId.of("Asia/Tokyo")).size)
    }
    @Test fun dailyAtTwentyAdvancesAfterDelivery() {
        val zone = ZoneId.of("Asia/Tokyo")
        assertEquals("2026-10-26T11:00:00Z",
            nextReviewTime(Instant.parse("2026-10-25T11:00:00Z"), zone, false).toInstant().toString())
    }
    @Test fun weeklyIsSundayAndTimezoneAware() {
        val next = nextReviewTime(Instant.parse("2026-10-25T12:00:00Z"), ZoneId.of("Asia/Tokyo"), true)
        assertEquals("2026-11-01T11:00:00Z", next.toInstant().toString())
        val dst = nextReviewTime(Instant.parse("2026-03-08T01:00:00Z"), ZoneId.of("America/New_York"), false)
        assertEquals(20, dst.hour)
        assertTrue(dst.toInstant().isAfter(Instant.parse("2026-03-08T01:00:00Z")))
    }
}
