package com.expensetracker.offline.ui.components

import android.text.format.DateFormat
import com.expensetracker.offline.util.tap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppRadius
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDatePill(millis: Long, onDateSelected: (Long) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val pill = RoundedCornerShape(AppRadius.pill)
    var showPicker by remember { mutableStateOf(false) }
    val label = remember(millis) { sheetShortDateLabel(millis) }

    Surface(
        shape = pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(pill)
            .clickable {
                haptic.tap()
                showPicker = true
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CalendarToday, contentDescription = "Change date", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = millis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    return utcTimeMillis <= System.currentTimeMillis()
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { pickedUtc ->
                        onDateSelected(sheetDateWithExistingTime(pickedUtc, millis))
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SheetTimePill(millis: Long, onTimeSelected: (Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    val pill = RoundedCornerShape(AppRadius.pill)
    val label = remember(millis) { DateFormat.getTimeFormat(context).format(Date(millis)) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Surface(
        shape = pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(pill)
            .clickable {
                haptic.tap()
                showPicker = true
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Schedule, contentDescription = "Change time", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    if (showPicker) {
        val current = Calendar.getInstance().apply { timeInMillis = millis }
        val pickerState = rememberTimePickerState(
            initialHour = current.get(Calendar.HOUR_OF_DAY),
            initialMinute = current.get(Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Select time") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val updated = Calendar.getInstance().apply {
                        timeInMillis = millis
                        set(Calendar.HOUR_OF_DAY, pickerState.hour)
                        set(Calendar.MINUTE, pickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onTimeSelected(updated.timeInMillis)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        )
    }
}

internal fun sheetDateWithExistingTime(pickedUtcMillis: Long, existingLocalMillis: Long): Long {
    val pickedDate = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = pickedUtcMillis
    }
    val existingTime = Calendar.getInstance().apply { timeInMillis = existingLocalMillis }
    return Calendar.getInstance().apply {
        set(
            pickedDate.get(Calendar.YEAR),
            pickedDate.get(Calendar.MONTH),
            pickedDate.get(Calendar.DAY_OF_MONTH),
            existingTime.get(Calendar.HOUR_OF_DAY),
            existingTime.get(Calendar.MINUTE),
            0
        )
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

internal fun sheetIsSameDay(millis: Long, other: Calendar): Boolean {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
}

internal fun sheetShortDateLabel(millis: Long): String {
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sheetIsSameDay(millis, today)) return "Today"
    if (sheetIsSameDay(millis, yesterday)) return "Yesterday"
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
}
