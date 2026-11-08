package com.yoshiki.lifeagent.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.yoshiki.lifeagent.data.Metric
import com.yoshiki.lifeagent.data.metricNumber
import com.yoshiki.lifeagent.data.metricInput
import com.yoshiki.lifeagent.data.metricKinds
import com.yoshiki.lifeagent.data.metricSavedValue
import com.yoshiki.lifeagent.data.selectedItems
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Composable
fun MetricValueField(metric: Metric, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column {
        Text("${metric.name}・${metricKinds[metric.kind] ?: metric.kind}")
        when (metric.kind) {
            "occurrence" -> Row(Modifier.fillMaxWidth().toggleable(value == "1", enabled = enabled,
                role = Role.Checkbox, onValueChange = { onChange(if (it) "1" else "") })) {
                Checkbox(value == "1", null, enabled = enabled)
                Text("今日（${LocalDate.now()}）実行した")
            }
            "checklist" -> {
                val selected = if (value.isBlank()) metric.completedItems else selectedItems(value)
                metric.checklistItems.forEach { item -> Row(Modifier.fillMaxWidth().toggleable(item in selected,
                    enabled = enabled, role = Role.Checkbox, onValueChange = { checked ->
                        onChange(Json.encodeToString(metric.checklistItems.filter {
                            if (it == item) checked else it in selected
                        }))
                    })) {
                    Checkbox(item in selected, null, enabled = enabled)
                    Text(item)
                } }
            }
            else -> OutlinedTextField(value, onChange, Modifier.fillMaxWidth(),
                label = { Text(if (metric.kind == "measurement") "${metric.name} の測定値" else "${metric.name} の今回値") },
                supportingText = { Text("現在 ${metric.currentValue} / 目標 ${metric.targetValue} ${metric.unit}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = enabled, singleLine = true)
        }
        runCatching { metricInput(metric, value) }.getOrNull()?.let { input ->
            Text(if (metric.kind == "checklist") "保存後 ${input.value.toInt()} / ${metric.checklistItems.size} 項目完了"
                else "保存後 ${metricNumber(metricSavedValue(metric, input))} ${metric.unit}")
        }
        if (value.isNotBlank()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text("この項目の入力を取り消す") }
    }
}
