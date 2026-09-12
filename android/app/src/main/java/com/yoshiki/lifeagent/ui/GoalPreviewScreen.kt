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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

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
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            Text("期限  ${state.targetDate}")
            Text("達成を測るMetric", style = MaterialTheme.typography.titleLarge)
            state.metrics.forEachIndexed { index, metric ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(metric.name, { onMetricNameChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("Metric名") }, enabled = !state.isConfirming, singleLine = true)
                        OutlinedTextField(metric.targetValue, { onMetricValueChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("目標値") }, enabled = !state.isConfirming, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(metric.unit, { onMetricUnitChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("単位") }, enabled = !state.isConfirming, singleLine = true)
                    }
                }
            }
            Text("達成までのMilestone", style = MaterialTheme.typography.titleLarge)
            state.milestones.forEachIndexed { index, milestone ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("STEP ${index + 1}", color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(milestone.title, { onMilestoneTitleChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("Milestone名") }, enabled = !state.isConfirming, singleLine = true)
                        OutlinedTextField(milestone.targetDate, { onMilestoneDateChange(index, it) }, Modifier.fillMaxWidth(), label = { Text("期限") }, supportingText = { Text("YYYY-MM-DD") }, enabled = !state.isConfirming, singleLine = true)
                    }
                }
            }
            state.planError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(), enabled = !state.isConfirming) {
                if (state.isConfirming) CircularProgressIndicator() else Text("この計画で保存する")
            }
        }
    }
}
