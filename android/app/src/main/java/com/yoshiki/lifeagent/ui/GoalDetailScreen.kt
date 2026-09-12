package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Goal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(state: GoalDetailUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Goal詳細") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator()
                state.error != null -> {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("再読み込み") }
                }
                state.goal != null -> GoalDetail(state.goal)
            }
        }
    }
}

@Composable
private fun GoalDetail(goal: Goal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(goal.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        Text(if (goal.status == "active") "進行中" else goal.status, color = MaterialTheme.colorScheme.primary)
    }
    Text(goal.description ?: "説明はありません")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Goal期限", style = MaterialTheme.typography.labelLarge)
            Text(goal.targetDate, style = MaterialTheme.typography.titleLarge)
        }
    }
    Text("達成を測るMetric", style = MaterialTheme.typography.titleLarge)
    goal.metrics.sortedBy { it.position }.forEach { metric ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(metric.name, style = MaterialTheme.typography.titleMedium)
                Text("${formatNumber(metric.targetValue)} ${metric.unit}", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Text("達成までのMilestone", style = MaterialTheme.typography.titleLarge)
    goal.milestones.sortedBy { it.position }.forEachIndexed { index, milestone ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${index + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                Column {
                    Text(milestone.title, style = MaterialTheme.typography.titleMedium)
                    Text(milestone.targetDate)
                }
            }
        }
    }
    Text("作成日時  ${goal.createdAt}", style = MaterialTheme.typography.bodySmall)
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
