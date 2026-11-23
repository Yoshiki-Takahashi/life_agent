package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.ProgressTimeAxis
import com.yoshiki.lifeagent.ui.metricTrend
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class MetricTrendTest {
    private val metric = Metric("読書", 10.0, "冊", id = "m", currentValue = 0.3)
    private fun log(id: String, time: String, value: Double, metricId: String = "m") =
        ProgressLog(id, "", time, time, listOf(ProgressMetricUpdate(metricId, "読書", value, "冊")))

    @Test fun chronologicalDecimalAccumulationFiltersOtherMetrics() {
        val points = metricTrend(metric, listOf(
            log("b", "2026-10-25T10:00:00Z", 0.2),
            log("x", "2026-10-25T08:00:00Z", 9.0, "other"),
            log("a", "2026-10-25T18:00:00+09:00", 0.1)))
        assertEquals(listOf(0.1, 0.3), points.map { it.value })
    }
    @Test fun missingRecordsAndInconsistentHistoryAreNotPlotted() {
        assertTrue(metricTrend(metric, emptyList()).isEmpty())
        assertTrue(metricTrend(metric, listOf(log("a", "2026-10-25T10:00:00Z", 1.0))).isEmpty())
        assertTrue(metricTrend(metric, listOf(log("a", "invalid", 0.3))).isEmpty())
    }
    @Test fun zeroRecordsKeepAFlatPointAndValuesAboveTargetArePlotted() {
        val points = metricTrend(metric.copy(currentValue = 11.0), listOf(
            log("a", "2026-10-25T09:00:00Z", 1.0),
            log("b", "2026-10-25T10:00:00Z", 0.0),
            log("c", "2026-10-25T11:00:00Z", 10.0)))
        assertEquals(listOf(1.0, 1.0, 11.0), points.map { it.value })
    }
    @Test fun missingTargetStillAllowsHistory() {
        assertEquals(1, metricTrend(metric.copy(targetValue = 0.0),
            listOf(log("a", "2026-10-25T10:00:00Z", 0.3))).size)
    }
}

class ProgressTimeAxisTest {
    @Test fun recordsAndMonthStartsShareTheSameLocalCalendarScale() {
        val first = timestamp("2026-09-30T15:00:00Z")
        val last = timestamp("2026-12-15T15:00:00Z")
        val axis = ProgressTimeAxis(first, last, ZoneId.of("Asia/Tokyo"))
        val grid = axis.gridMonths(maxLines = 10).toList()

        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12)),
            grid.map { it.first })
        assertEquals(0f, axis.fraction(first), 0f)
        assertEquals(31f / 92, grid[1].second, 0.00001f)
        assertEquals(61f / 92, grid[2].second, 0.00001f)
        assertEquals(76f / 92, axis.fraction(last), 0.00001f)
    }

    @Test fun aSingleRecordKeepsItsPositionWithinTheMonth() {
        val record = timestamp("2026-06-16T00:00:00Z")
        val axis = ProgressTimeAxis(record, record, ZoneOffset.UTC)

        assertEquals(YearMonth.of(2026, 6) to 0f, axis.gridMonths(maxLines = 10).single())
        assertEquals(0.5f, axis.fraction(record), 0f)
    }

    @Test fun longHistoriesLimitTheGridToReadableMonthStarts() {
        val axis = ProgressTimeAxis(timestamp("2010-03-15T00:00:00Z"), timestamp("2026-06-30T00:00:00Z"), ZoneOffset.UTC)
        val grid = axis.gridMonths(maxLines = 6).toList()

        assertEquals(6, grid.size)
        assertTrue(grid.zipWithNext().all { (left, right) -> left.second < right.second })
        grid.forEach { (month, fraction) ->
            assertEquals(axis.fraction(month.atDay(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)), fraction, 0f)
        }
    }

    private fun timestamp(value: String) = Instant.parse(value).epochSecond
}

class TypedMetricTrendTest {
    @Test fun observationsReplaceInsteadOfAccumulateAndRetainZero() {
        val metric = Metric("体脂肪率", 15.0, "%", id = "m", currentValue = 0.0, kind = "measurement")
        val logs = listOf(25.0, 20.0, 0.0).mapIndexed { index, value ->
            val time = "2026-11-0${index + 1}T00:00:00Z"
            ProgressLog("$index", "", time, time, listOf(ProgressMetricUpdate("m", "体脂肪率", value, "%", "measurement")))
        }
        assertEquals(listOf(25.0, 20.0, 0.0), metricTrend(metric, logs.reversed()).map { it.value })
        assertTrue(metricTrend(metric.copy(currentValue = 30.0), logs).isEmpty())
    }

    @Test fun inputsMatchTheirRecordingMeaning() {
        val measurement = Metric("測定", 15.0, "%", id = "m", kind = "measurement")
        assertEquals(25.0, metricInput(measurement, "25")!!.value, 0.0)
        assertEquals(0.0, metricInput(measurement, "0")!!.value, 0.0)
        assertEquals(25.0, metricInput(measurement.copy(kind = "cumulative"), "25")!!.value, 0.0)
        val checklist = Metric("準備", 2.0, "項目", id = "c", kind = "checklist", checklistItems = listOf("日付", "宿"), completedItems = listOf("宿"))
        val update = metricInput(checklist, "[]")!!
        assertEquals(emptyList<String>(), update.completedItems)
        assertEquals(listOf("宿"), update.expectedCompletedItems)
        assertNull(metricInput(checklist, ""))
        val occurrence = metricInput(measurement.copy(kind = "occurrence"), "1")!!
        assertEquals(java.time.LocalDate.now().toString(), occurrence.activityDate)
    }
}
