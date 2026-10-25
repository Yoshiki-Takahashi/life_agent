package com.yoshiki.lifeagent.ui

import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.ProgressLog
import java.math.BigDecimal
import java.time.OffsetDateTime

data class TrendPoint(val time: String, val seconds: Long, val value: Double)

fun metricTrend(metric: Metric, logs: List<ProgressLog>): List<TrendPoint> {
    var total = BigDecimal.ZERO
    val entries = logs.flatMap { log ->
        log.metricUpdates.filter { it.metricId == metric.id }.map { log to it }
    }
    val ordered = entries.map { (log, update) ->
        val instant = runCatching { OffsetDateTime.parse(log.recordedAt).toInstant() }.getOrNull()
            ?: return emptyList()
        Triple(log, update, instant)
    }.sortedWith(compareBy({ it.third }, { it.first.id }))
    val points = ordered.map { (log, update, instant) ->
        if (!update.value.isFinite() || update.value <= 0 || update.unit != metric.unit) return emptyList()
        total += BigDecimal.valueOf(update.value)
        TrendPoint(log.recordedAt, instant.epochSecond, total.toDouble())
    }
    if (!metric.currentValue.isFinite() ||
        total.compareTo(BigDecimal.valueOf(metric.currentValue)) != 0) return emptyList()
    return points
}

