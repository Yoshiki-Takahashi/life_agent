package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Goal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalFormScreen(
    state: GoalUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("LifeAgent") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
                enabled = !state.isSaving,
                singleLine = true,
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("説明（任意）") },
                supportingText = { Text("2,000文字以内") },
                enabled = !state.isSaving,
                minLines = 4,
            )
            state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving,
            ) {
                if (state.isSaving) CircularProgressIndicator() else Text("保存する")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDetailScreen(state: GoalUiState, onRetry: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Goal詳細") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
    Text("状態: ${if (goal.status == "active") "進行中" else goal.status}")
    Text("作成日時: ${goal.createdAt}")
}
