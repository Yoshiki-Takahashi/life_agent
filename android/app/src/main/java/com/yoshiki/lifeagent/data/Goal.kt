package com.yoshiki.lifeagent.data

data class Goal(
    val id: String,
    val title: String,
    val description: String?,
    val status: String,
    val createdAt: String,
)
