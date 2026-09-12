package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.yoshiki.lifeagent.data.GoalSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalHomeScreen(
    state: GoalListUiState,
    onCreateGoal: () -> Unit,
    onGoalClick: (String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LifeAgent") },
                actions = { TextButton(onClick = onSignOut) { Text("ログアウト") } },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.goals.isEmpty() -> CenteredContent(padding = padding) {
                CircularProgressIndicator()
            }
            state.error != null && state.goals.isEmpty() -> CenteredContent(padding = padding) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("再読み込み") }
            }
            state.goals.isEmpty() -> CenteredContent(padding = padding) {
                Text("最初のGoalを作りましょう", style = MaterialTheme.typography.headlineSmall)
                Text("曖昧な目標から、MetricとMilestoneを含む計画を作成します。")
                Button(onClick = onCreateGoal) { Text("Goalを作る") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("あなたのGoal", style = MaterialTheme.typography.headlineSmall)
                            Text("${state.goals.size}件の進行中Goal")
                        }
                        Button(onClick = onCreateGoal) { Text("＋ 新規") }
                    }
                }
                state.error?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("再読み込み") }
                    }
                }
                items(state.goals, key = GoalSummary::id) { goal ->
                    GoalSummaryCard(goal = goal, onClick = { onGoalClick(goal.id) })
                }
            }
        }
    }
}

@Composable
private fun CenteredContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun GoalSummaryCard(goal: GoalSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(goal.title, style = MaterialTheme.typography.titleLarge)
                Text(statusLabel(goal.status), color = MaterialTheme.colorScheme.primary)
            }
            Text("期限  ${goal.targetDate}")
            Text("Metric ${goal.metricCount}件  ・  Milestone ${goal.milestoneCount}件")
        }
    }
}

private fun statusLabel(status: String) = if (status == "active") "進行中" else status
