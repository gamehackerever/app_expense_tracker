package com.expensetracker.offline.ui.components

import com.expensetracker.offline.util.tap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
                        val cal = Calendar.getInstance()
                        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
                        val currentMin = cal.get(Calendar.MINUTE)
                        val currentSec = cal.get(Calendar.SECOND)

                        cal.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        cal.timeInMillis = pickedUtc

                        val localCal = Calendar.getInstance()
                        localCal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DATE), currentHour, currentMin, currentSec)

                        onDateSelected(localCal.timeInMillis)
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
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