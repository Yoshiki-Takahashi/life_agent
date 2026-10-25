package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.ProgressLog

@Composable
fun MetricTrendCard(metric: Metric, logs: List<ProgressLog>) {
    val points = metricTrend(metric, logs)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val target = metric.targetValue.takeIf { it.isFinite() && it > 0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.name, style = MaterialTheme.typography.titleMedium)
            Text("現在 ${metric.currentValue} ${metric.unit}")
            Text(target?.let { "現在の目標 $it ${metric.unit}（破線）" } ?: "目標値なし")
            if (points.isEmpty()) {
                Text(if (logs.none { log -> log.metricUpdates.any { it.metricId == metric.id } })
                    "まだ記録がありません" else "履歴と現在値を確認できないため推移を表示できません")
            } else {
                target?.let { Text("達成率 ${java.math.BigDecimal.valueOf(metric.currentValue / it * 100).setScale(1, java.math.RoundingMode.HALF_UP)}%") }
                val description = points.joinToString("、") { "${it.time}: ${it.value} ${metric.unit}" }
                Canvas(Modifier.fillMaxWidth().height(140.dp).semantics {
                    contentDescription = "記録時点の累積推移。$description"
                }) {
                    val inset = 8.dp.toPx()
                    val width = (size.width - inset * 2).coerceAtLeast(1f)
                    val height = (size.height - inset * 2).coerceAtLeast(1f)
                    val maximum = maxOf(target ?: 0.0, points.last().value, 1.0)
                    fun y(value: Double) = inset + height * (1 - value / maximum).toFloat()
                    val start = points.first().seconds
                    val span = (points.last().seconds - start).coerceAtLeast(1)
                    fun position(point: TrendPoint) = Offset(
                        inset + width * ((point.seconds - start).toDouble() / span).toFloat(), y(point.value))
                    drawLine(outline, Offset(inset, y(0.0)), Offset(inset + width, y(0.0)))
                    target?.let { drawLine(outline, Offset(inset, y(it)), Offset(inset + width, y(it)),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
                    points.zipWithNext().forEach { (a, b) ->
                        drawLine(primary, position(a), position(b), strokeWidth = 2.dp.toPx())
                    }
                    points.forEach { drawCircle(primary, 4.dp.toPx(), position(it)) }
                }
                Text("縦軸: 累積（${metric.unit}）、横軸: 記録日時")
                Text("最初: ${points.first().time}", style = MaterialTheme.typography.bodySmall)
                Text("最新: ${points.last().time}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
