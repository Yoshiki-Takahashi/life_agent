package com.yoshiki.lifeagent.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun CalendarDateButton(
    label: String,
    selectedDate: LocalDate?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    allowPastDates: Boolean = true,
    allowFutureDates: Boolean = true,
    onDateSelected: (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val selectDate by rememberUpdatedState(onDateSelected)
    var pickerOpen by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { pickerOpen = true },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
    ) { Text(label) }

    if (pickerOpen) DisposableEffect(context) {
        val today = LocalDate.now()
        val minDate = today.takeUnless { allowPastDates }
        val maxDate = today.takeUnless { allowFutureDates }
        val date = (selectedDate ?: today).coerceIn(minDate, maxDate)
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            selectDate(LocalDate.of(year, month + 1, day))
        }, date.year, date.monthValue - 1, date.dayOfMonth)
        val zone = ZoneId.systemDefault()
        minDate?.let { dialog.datePicker.minDate = it.atStartOfDay(zone).toInstant().toEpochMilli() }
        maxDate?.let { dialog.datePicker.maxDate = it.atStartOfDay(zone).toInstant().toEpochMilli() }
        dialog.setOnDismissListener { pickerOpen = false }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}
