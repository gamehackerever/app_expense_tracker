package com.expensetracker.offline.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import java.util.Locale

@Composable
fun SheetAmountCard(
    amountStr: String,
    onAmountChange: (String) -> Unit,
    calculatedAmount: Double?,
    isDebit: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val accent = if (isDebit) MaterialTheme.colorScheme.onSurface else AppColors.positive
    val allowedSymbols = remember { setOf('.', '+', '-', '*', '/', '(', ')', ' ', ',') }

    val bigStyle = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, letterSpacing = (-1).sp)

    SheetCard(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(AppRadius.large),
        contentPadding = 16.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Amount", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trailing != null) trailing()
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("₹", fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = accent, letterSpacing = (-1).sp)
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = amountStr,
                onValueChange = { input ->
                    if (input.all { it.isDigit() || it in allowedSymbols }) onAmountChange(input)
                },
                singleLine = true,
                textStyle = bigStyle,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (amountStr.isEmpty()) {
                            Text(
                                "0.00",
                                fontSize = 48.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        if (calculatedAmount != null && amountStr.any { it in listOf('+', '-', '*', '/') }) {
            Text(
                text = "= ₹${String.format(Locale.US, "%.2f", calculatedAmount)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 36.dp, top = 2.dp)
            )
        }
    }
}

internal fun formatSheetAmount(amount: Double): String =
    if (amount % 1.0 == 0.0) String.format(Locale.US, "%,.0f", amount) else String.format(Locale.US, "%,.2f", amount)