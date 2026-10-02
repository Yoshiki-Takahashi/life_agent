package com.yoshiki.lifeagent.data

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
)

data class MetricProgressInput(
    val metricId: String,
    val value: Double,
)
