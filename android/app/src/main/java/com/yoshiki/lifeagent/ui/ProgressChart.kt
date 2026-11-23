package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.ProgressLog
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
        if (!update.value.isFinite() || update.value < 0 || update.unit != metric.unit || update.kind != metric.kind) return emptyList()
        if (metric.kind == "measurement") total = BigDecimal.valueOf(update.value)
        else {
            total += BigDecimal.valueOf(update.value)
        }
        TrendPoint(log.recordedAt, instant.epochSecond, total.toDouble())
    }
    if (!metric.currentValue.isFinite() ||
        total.compareTo(BigDecimal.valueOf(metric.currentValue)) != 0) return emptyList()
    return points
}

internal class ProgressTimeAxis(firstTimestamp: Long, lastTimestamp: Long, private val zone: ZoneId) {
    private val firstMonth = monthAt(firstTimestamp)
    private val lastMonth = monthAt(lastTimestamp)
    private val start = monthStart(firstMonth)
    private val span = monthStart(lastMonth.plusMonths(1)) - start

    fun fraction(timestamp: Long): Float = ((timestamp - start).toDouble() / span).toFloat()

    fun gridMonths(maxLines: Int): Sequence<Pair<YearMonth, Float>> {
        val count = ChronoUnit.MONTHS.between(firstMonth, lastMonth) + 1
        val limit = maxLines.coerceAtLeast(1)
        val step = (count + limit - 1) / limit
        return generateSequence(firstMonth) { it.plusMonths(step) }
            .takeWhile { it <= lastMonth }
            .map { it to fraction(monthStart(it)) }
    }

    private fun monthAt(timestamp: Long) = YearMonth.from(Instant.ofEpochSecond(timestamp).atZone(zone))

    private fun monthStart(month: YearMonth) = month.atDay(1).atStartOfDay(zone).toEpochSecond()
}

@Composable
fun MetricTrendCard(metric: Metric, logs: List<ProgressLog>) {
    if (metric.kind == "occurrence") { ActivityCalendar(metric, logs); return }
    if (metric.kind == "checklist") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(metric.name, style = MaterialTheme.typography.titleMedium)
                Text("${metric.completedItems.size} / ${metric.checklistItems.size} 項目完了")
                metric.checklistItems.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item in metric.completedItems, null)
                    Text(item)
                } }
            }
        }
        return
    }
    val measurement = metric.kind == "measurement"
    val points = metricTrend(metric, logs)
    val target = metric.targetValue.takeIf { it.isFinite() && it > 0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.name, style = MaterialTheme.typography.titleMedium)
            Text(if (measurement && points.isEmpty()) "測定値はまだありません" else "現在 ${metric.currentValue} ${metric.unit}")
            Text(target?.let { "現在の目標 $it ${metric.unit}（破線）" } ?: "目標値なし")
            if (points.isEmpty()) {
                Text(if (logs.none { log -> log.metricUpdates.any { it.metricId == metric.id } })
                    "まだ記録がありません" else "履歴と現在値を確認できないため推移を表示できません")
            } else {
                target?.takeIf { !measurement }?.let { Text("達成率 ${java.math.BigDecimal.valueOf(metric.currentValue / it * 100).setScale(1, java.math.RoundingMode.HALF_UP)}%") }
                ProgressLineChart(points, target, measurement, metric.unit)
                Text("縦軸: ${if (measurement) "測定値" else "累積"}（${metric.unit}）、横軸: 記録日時")
            }
        }
    }
}

@Composable
private fun ProgressLineChart(points: List<TrendPoint>, target: Double?, measurement: Boolean, unit: String) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val labelHeight = with(LocalDensity.current) { labelStyle.lineHeight.toDp() }
    val axis = ProgressTimeAxis(points.first().seconds, points.last().seconds, ZoneId.systemDefault())
    val description = points.joinToString("、") { "${it.time}: ${it.value} $unit" }
    val maximum = maxOf(target ?: 0.0, points.maxOf { it.value }, 1.0)

    Canvas(Modifier.fillMaxWidth().height(140.dp + labelHeight).semantics {
        contentDescription = "${if (measurement) "測定値" else "累積"}の推移。$description"
    }) {
        val inset = 8.dp.toPx()
        val width = (size.width - inset * 2).coerceAtLeast(0f)
        val height = size.height - labelHeight.toPx() - inset * 2
        fun x(fraction: Float) = inset + width * fraction
        fun y(value: Double) = inset + height * (1 - value / maximum).toFloat()
        fun position(point: TrendPoint) = Offset(x(axis.fraction(point.seconds)), y(point.value))

        // At zoomed-out ranges, draw only the month lines that can be distinguished.
        val maxGridLines = (width / 6.dp.toPx()).toInt().coerceAtLeast(1)
        var nextLabelStart = 0f
        axis.gridMonths(maxGridLines).forEach { (month, fraction) ->
            val tickX = x(fraction)
            drawLine(colors.outlineVariant, Offset(tickX, inset), Offset(tickX, y(0.0)), strokeWidth = 1.dp.toPx())
            if (tickX >= nextLabelStart) {
                val label = textMeasurer.measure("${month.year}年${month.monthValue}月", labelStyle, maxLines = 1)
                val lastLabelStart = size.width - label.size.width
                val labelX = (tickX - label.size.width / 2f).coerceAtLeast(0f).coerceAtMost(lastLabelStart)
                if (labelX >= nextLabelStart) {
                    drawText(label, color = colors.onSurfaceVariant, topLeft = Offset(labelX, y(0.0) + inset))
                    nextLabelStart = labelX + label.size.width + inset
                }
            }
        }
        drawLine(colors.outline, Offset(inset, y(0.0)), Offset(inset + width, y(0.0)))
        target?.let {
            drawLine(colors.outline, Offset(inset, y(it)), Offset(inset + width, y(it)),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        points.zipWithNext().forEach { (a, b) ->
            drawLine(colors.primary, position(a), position(b), strokeWidth = 2.dp.toPx())
        }
        points.forEach { drawCircle(colors.primary, 4.dp.toPx(), position(it)) }
    }
}

fun activityDays(metric: Metric, logs: List<ProgressLog>): Set<LocalDate> = logs.flatMap { log ->
    log.metricUpdates.filter { it.metricId == metric.id && it.kind == "occurrence" }.mapNotNull {
        runCatching { LocalDate.parse(it.activityDate) }.getOrNull()
    }
}.toSet()

@Composable
private fun ActivityCalendar(metric: Metric, logs: List<ProgressLog>) {
    var month by remember(metric.id) { mutableStateOf(YearMonth.now()) }
    val days = activityDays(metric, logs)
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(metric.name, style = MaterialTheme.typography.titleMedium)
            Text("実行 ${metric.currentValue.toInt()} / 目標 ${metric.targetValue.toInt()} ${metric.unit}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ month = month.minusMonths(1) }) { Text("前月") }
                Text("${month.year}年${month.monthValue}月")
                TextButton({ month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("翌月") }
            }
            Row { listOf("月", "火", "水", "木", "金", "土", "日").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it) }
            } }
            val start = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
            val weeks = (month.lengthOfMonth() + month.atDay(1).dayOfWeek.value - 2) / 7 + 1
            repeat(weeks) { week -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { day ->
                    val date = start.plusDays((week * 7 + day).toLong())
                    val visible = YearMonth.from(date) == month
                    val recorded = visible && date in days
                    Box(
                        Modifier.weight(1f).height(30.dp)
                            .background(if (recorded) colors.primary else colors.surfaceContainerHighest)
                            .semantics {
                                contentDescription = if (visible) "$date ${if (recorded) "実行済み" else "記録なし"}" else "月外"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (visible) {
                            Text(date.dayOfMonth.toString(), color = if (recorded) colors.onPrimary else colors.onSurface)
                        }
                    }
                }
            } }
            Text("強調表示は実行を記録した日です。未記録の日も実行している場合があります。")
        }
    }
}
