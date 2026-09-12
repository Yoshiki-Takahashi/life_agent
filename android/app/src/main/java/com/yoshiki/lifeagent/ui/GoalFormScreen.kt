package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormScreen(
    state: GoalUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onTargetDateChange: (String) -> Unit,
    onPreview: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新しいGoal") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("達成したいことを教えてください", style = MaterialTheme.typography.headlineSmall)
            Text("AIが測定できるMetricと、途中のMilestoneを提案します。")
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
                supportingText = { Text("背景や取り組み方を2,000文字以内で入力") },
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
