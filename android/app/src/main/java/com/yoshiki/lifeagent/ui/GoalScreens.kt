package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Goal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormScreen(
    state: GoalUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onTargetDateChange: (String) -> Unit,
    onPreview: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("LifeAgent") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("新しいGoal", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("タイトル") },
                supportingText = { Text("120文字以内") },
                isError = state.formError != null,
                enabled = !state.isPreviewing,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("説明（任意）") },
                supportingText = { Text("2,000文字以内") },
                enabled = !state.isPreviewing,
                minLines = 4,
            )
            OutlinedTextField(
                value = state.targetDate,
                onValueChange = onTargetDateChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Goal期限") },
                supportingText = { Text("YYYY-MM-DD") },
                enabled = !state.isPreviewing,
                singleLine = true,
            )
            state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onPreview,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isPreviewing,
            ) {
                if (state.isPreviewing) CircularProgressIndicator() else Text("計画を作る")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalPreviewScreen(
    state: GoalUiState,
    onBack: () -> Unit,
    onMetricNameChange: (Int, String) -> Unit,
    onMetricValueChange: (Int, String) -> Unit,
    onMetricUnitChange: (Int, String) -> Unit,
    onMilestoneTitleChange: (Int, String) -> Unit,
    onMilestoneDateChange: (Int, String) -> Unit,
    onConfirm: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("計画を確認") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text("期限: ${state.targetDate}")
            Text("Metric", style = MaterialTheme.typography.titleLarge)
            state.metrics.forEachIndexed { index, metric ->
                OutlinedTextField(
                    value = metric.name,
                    onValueChange = { onMetricNameChange(index, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Metric名") },
                    enabled = !state.isConfirming,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = metric.targetValue,
                    onValueChange = { onMetricValueChange(index, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目標値") },
                    enabled = !state.isConfirming,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = metric.unit,
                    onValueChange = { onMetricUnitChange(index, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("単位") },
                    enabled = !state.isConfirming,
                    singleLine = true,
                )
            }
            HorizontalDivider()
            Text("Milestone", style = MaterialTheme.typography.titleLarge)
            state.milestones.forEachIndexed { index, milestone ->
                Text("${index + 1}. ${milestone.title}")
                OutlinedTextField(
                    value = milestone.title,
                    onValueChange = { onMilestoneTitleChange(index, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Milestone名") },
                    enabled = !state.isConfirming,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = milestone.targetDate,
                    onValueChange = { onMilestoneDateChange(index, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("期限") },
                    supportingText = { Text("YYYY-MM-DD") },
                    enabled = !state.isConfirming,
                    singleLine = true,
                )
            }
            state.planError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isConfirming,
            ) {
                if (state.isConfirming) CircularProgressIndicator() else Text("この計画で保存する")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(state: GoalUiState, onRetry: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Goal詳細") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            when {
                state.isLoadingDetail -> CircularProgressIndicator()
                state.detailError != null -> {
                    Text(state.detailError, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("再読み込み") }
                }
                state.detail != null -> GoalDetail(state.detail)
            }
        }
    }
}

@Composable
private fun GoalDetail(goal: Goal) {
    Text(goal.title, style = MaterialTheme.typography.headlineMedium)
    Text(goal.description ?: "説明はありません")
    Text("期限: ${goal.targetDate}")
    Text("状態: ${if (goal.status == "active") "進行中" else goal.status}")
    Text("Metric", style = MaterialTheme.typography.titleLarge)
    goal.metrics.sortedBy { it.position }.forEach { metric ->
        Text("${metric.name}: ${formatNumber(metric.targetValue)} ${metric.unit}")
    }
    Text("Milestone", style = MaterialTheme.typography.titleLarge)
    goal.milestones.sortedBy { it.position }.forEachIndexed { index, milestone ->
        Text("${index + 1}. ${milestone.title}（${milestone.targetDate}）")
    }
    Text("作成日時: ${goal.createdAt}")
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
