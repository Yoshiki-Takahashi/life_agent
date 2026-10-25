package com.yoshiki.lifeagent.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yoshiki.lifeagent.data.Goal
import com.yoshiki.lifeagent.reminders.ReviewReminder
import java.time.Instant
import java.time.ZoneId

@Composable
fun ReviewScreen(goal: Goal?, userId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val reminder = remember { ReviewReminder(context) }
    var enabled by remember(userId) { mutableStateOf(reminder.enabled(userId)) }
    var weekly by remember(userId) { mutableStateOf(reminder.weekly(userId)) }
    var zone by remember(userId) { mutableStateOf(reminder.zone(userId)) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf(7) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) "通知を許可しました" else "通知は許可されていません。手動の振り返りは利用できます。"
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("戻る") }
        Text("定期振り返り", style = MaterialTheme.typography.headlineMedium)
        Text(goal?.title ?: "Goalを読み込み中")
        Row {
            TextButton(onClick = { days = 7 }) { Text("直近7日") }
            TextButton(onClick = { days = 30 }) { Text("直近30日") }
        }
        val selectedZone = runCatching { ZoneId.of(zone) }.getOrElse { ZoneId.systemDefault() }
        val now = Instant.now()
        val logs = reviewLogs(goal?.progressLogs.orEmpty(), now, days, selectedZone)
        Text("直近${days}日（今日を含む・${selectedZone.id}）: ${logs.size}件")
        Text("期間内の記録に基づく助言", style = MaterialTheme.typography.titleMedium)
        Text(if (logs.isEmpty()) "この期間の記録はありません。活動の有無は判断せず、まず最近の実績を記録してみましょう。"
            else "期間内の記録を読み返し、続けられた行動を次の一歩に選びましょう。計画が合わなければ見直しを検討してください。")
        logs.forEach { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(log.body)
                    log.metricUpdates.forEach { Text("${it.metricName}: +${it.value} ${it.unit}") }
                    Text(log.recordedAt)
                }
            }
        }
        HorizontalDivider()
        Text("この端末の通知設定（全Goal共通）", style = MaterialTheme.typography.titleMedium)
        Row { Text("通知を有効にする"); Switch(checked = enabled, onCheckedChange = { enabled = it }) }
        Row {
            RadioButton(selected = !weekly, onClick = { weekly = false }); Text("毎日")
            RadioButton(selected = weekly, onClick = { weekly = true }); Text("毎週日曜")
        }
        Text("20:00頃に通知します。端末の省電力状態により遅れる場合があります。")
        OutlinedTextField(value = zone, onValueChange = { zone = it },
            label = { Text("タイムゾーン（例: Asia/Tokyo）") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it) }
        Button(onClick = {
            if (runCatching { ZoneId.of(zone) }.isFailure) {
                error = "有効なタイムゾーンを入力してください"
            } else {
                error = null
                reminder.save(userId, enabled, weekly, zone)
                notice = "設定を保存しました"
                if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }) { Text("通知設定を保存") }
        Text("通知を無効にしても、この画面でいつでも振り返れます。")
    }
}
