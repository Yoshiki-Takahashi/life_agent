package com.yoshiki.lifeagent

import com.yoshiki.lifeagent.data.*
import com.yoshiki.lifeagent.ui.metricTrend
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
    @Test fun missingTargetStillAllowsHistory() {
        assertEquals(1, metricTrend(metric.copy(targetValue = 0.0),
            listOf(log("a", "2026-10-25T10:00:00Z", 0.3))).size)
    }
}
