package com.yoshiki.lifeagent.data

import java.math.BigDecimal

data class Goal(
    val id: String,
    val title: String,
    val description: String?,
    val targetDate: String,
    val status: String,
    val createdAt: String,
    val metrics: List<Metric>,
    val milestones: List<Milestone>,
    val updatedAt: String = createdAt,
    val progressLogs: List<ProgressLog> = emptyList(),
    val planRevision: Int = 1,
)

data class GoalSummary(
    val id: String,
    val title: String,
    val targetDate: String,
    val status: String,
    val metricCount: Int,
    val milestoneCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

data class GoalPlan(
    val title: String,
    val description: String?,
    val targetDate: String,
    val metrics: List<Metric>,
    val milestones: List<Milestone>,
)

data class Metric(
    val name: String,
    val targetValue: Double,
    val unit: String,
    val position: Int = 0,
    val id: String = "",
    val currentValue: Double = 0.0,
    val kind: String = "cumulative",
    val checklistItems: List<String> = emptyList(),
    val completedItems: List<String> = emptyList(),
)

data class Milestone(
    val title: String,
    val targetDate: String,
    val position: Int = 0,
)

data class ProgressLog(
    val id: String,
    val body: String,
    val recordedAt: String,
    val createdAt: String,
    val metricUpdates: List<ProgressMetricUpdate>,
)

data class ProgressMetricUpdate(
    val metricId: String,
    val metricName: String,
    val value: Double,
    val unit: String,
    val kind: String = "cumulative",
    val completedItems: List<String> = emptyList(),
    val activityDate: String? = null,
)

data class MetricProgressInput(
    val metricId: String,
    val value: Double,
    val completedItems: List<String>? = null,
    val activityDate: String? = null,
    val expectedCompletedItems: List<String>? = null,
    val kind: String? = null,
)

data class ReplanProposal(
    val proposalId: String,
    val basePlanRevision: Int,
    val reason: String,
    val proposedPlan: GoalPlan,
    val diff: List<ReplanDiffItem>,
    val createdAt: String,
)

data class ReplanDiffItem(
    val changeType: String,
    val targetType: String,
    val targetLabel: String,
    val before: String?,
    val after: String?,
    val rationale: String,
    val changeId: String? = null,
)

fun validProgressValue(text: String): Boolean {
    val value = text.toBigDecimalOrNull() ?: return false
    return value >= BigDecimal.ZERO && value <= BigDecimal("1000000000") &&
        value.stripTrailingZeros().scale() <= 2
}

val metricKinds = linkedMapOf("cumulative" to "累計", "occurrence" to "実行日", "measurement" to "測定値", "checklist" to "チェックリスト")

fun selectedItems(value: String): List<String> = runCatching {
    kotlinx.serialization.json.Json.decodeFromString<List<String>>(value)
}.getOrDefault(emptyList())

fun metricInput(metric: Metric, value: String): MetricProgressInput? {
    if (value.isBlank()) return null
    if (metric.kind == "checklist") {
        val items = kotlinx.serialization.json.Json.decodeFromString<List<String>>(value)
        require(items.distinct().size == items.size && metric.checklistItems.containsAll(items))
        return MetricProgressInput(metric.id, items.size.toDouble(), items, expectedCompletedItems = metric.completedItems, kind = metric.kind)
    }
    val number = value.toBigDecimalOrNull() ?: error("数値を入力してください")
    require(number >= BigDecimal.ZERO && number <= BigDecimal("1000000000") && number.stripTrailingZeros().scale() <= 2)
    when (metric.kind) {
        "cumulative" -> require(validProgressValue(value))
        "occurrence" -> require(number.compareTo(BigDecimal.ONE) == 0)
        "measurement" -> Unit
        else -> error("対応していない種別です")
    }
    return MetricProgressInput(metric.id, number.toDouble(),
        activityDate = if (metric.kind == "occurrence") java.time.LocalDate.now().toString() else null,
        kind = metric.kind.takeUnless { it == "cumulative" })
}

fun progressEditValue(metric: Metric, update: ProgressMetricUpdatePayload?): String = when {
    update == null -> ""
    metric.kind == "checklist" -> kotlinx.serialization.json.Json.encodeToString(update.completedItems.orEmpty())
    else -> java.math.BigDecimal.valueOf(update.value).stripTrailingZeros().toPlainString()
}

fun metricSavedValue(metric: Metric, input: MetricProgressInput): Double =
    if (metric.kind in setOf("measurement", "checklist")) input.value
    else (BigDecimal.valueOf(metric.currentValue) + BigDecimal.valueOf(input.value)).toDouble()

fun metricNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

fun progressLabel(update: ProgressMetricUpdate): String = when (update.kind) {
    "checklist" -> "${update.metricName}: 完了 ${update.completedItems.joinToString("、").ifEmpty { "なし" }}"
    "occurrence" -> "${update.metricName}: ${update.activityDate} 実行済み"
    "measurement" -> "${update.metricName}: ${metricNumber(update.value)} ${update.unit}"
    else -> "${update.metricName}: +${metricNumber(update.value)} ${update.unit}"
}
