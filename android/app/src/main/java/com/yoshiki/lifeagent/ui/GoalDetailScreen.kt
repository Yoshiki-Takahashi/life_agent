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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Goal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(
    state: GoalDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onProgressBodyChange: (String) -> Unit,
    onMetricProgressChange: (Int, String) -> Unit,
    onSaveProgress: () -> Unit,
) {
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
                state.goal != null -> GoalDetail(
                    state = state,
                    onProgressBodyChange = onProgressBodyChange,
                    onMetricProgressChange = onMetricProgressChange,
                    onSaveProgress = onSaveProgress,
                )
            }
        }
    }
}

@Composable
private fun GoalDetail(
    state: GoalDetailUiState,
    onProgressBodyChange: (String) -> Unit,
    onMetricProgressChange: (Int, String) -> Unit,
    onSaveProgress: () -> Unit,
) {
    val goal = requireNotNull(state.goal)
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
                Text(
                    "${formatNumber(metric.currentValue)} / ${formatNumber(metric.targetValue)} ${metric.unit}",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    ProgressEntry(
        state = state,
        onProgressBodyChange = onProgressBodyChange,
        onMetricProgressChange = onMetricProgressChange,
        onSaveProgress = onSaveProgress,
    )
    ProgressHistory(goal)
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

@Composable
private fun ProgressEntry(
    state: GoalDetailUiState,
    onProgressBodyChange: (String) -> Unit,
    onMetricProgressChange: (Int, String) -> Unit,
    onSaveProgress: () -> Unit,
) {
    Text("進捗を記録", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.progressBody,
                onValueChange = onProgressBodyChange,
                label = { Text("進捗メモ") },
                modifier = Modifier.fillMaxWidth(),
            )
            state.metricProgress.forEachIndexed { index, item ->
                OutlinedTextField(
                    value = item.value,
                    onValueChange = { onMetricProgressChange(index, it) },
                    label = { Text("${item.name} の今回値") },
                    supportingText = {
                        Text(
                            "現在 ${formatNumber(item.currentValue)} / ${formatNumber(item.targetValue)} ${item.unit}",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.progressError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSaveProgress, enabled = !state.isSavingProgress) {
                if (state.isSavingProgress) CircularProgressIndicator() else Text("進捗を保存")
            }
        }
    }
}

@Composable
private fun ProgressHistory(goal: Goal) {
    Text("進捗履歴", style = MaterialTheme.typography.titleLarge)
    if (goal.progressLogs.isEmpty()) {
        Text("進捗履歴はまだありません")
    } else {
        goal.progressLogs.forEach { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(log.body, style = MaterialTheme.typography.titleMedium)
                    log.metricUpdates.forEach { update ->
                        Text(
                            "${update.metricName}: +${formatNumber(update.value)} ${update.unit}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(log.recordedAt, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
