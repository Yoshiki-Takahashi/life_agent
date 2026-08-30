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
)

data class Milestone(
    val title: String,
    val targetDate: String,
    val position: Int = 0,
)
