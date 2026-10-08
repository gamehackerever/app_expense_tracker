package com.expensetracker.offline.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppRadius

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumDropdownField(selectedOption: String, options: List<String>, onOptionSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme

    // MONOCHROMATIC UPGRADE: Focused state uses stark neutral instead of primary color
    val borderColor by animateColorAsState(
        targetValue = if (expanded) colors.onSurface else colors.outlineVariant.copy(alpha = 0.3f),
        animationSpec = tween(150),
        label = "dropdownBorder"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "dropdownChevron"
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp).menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(AppRadius.small),
            color = colors.surface,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = selectedOption,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse options" else "Expand options",
                    tint = if (expanded) colors.onSurface else colors.onSurfaceVariant, // Stark neutral chevron
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.surface)
        ) {
            options.forEach { target ->
                val isSelected = target == selectedOption
                DropdownMenuItem(
                    text = {
                        Text(
                            text = target,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = colors.onSurface // Consistent neutral text
                        )
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOptionSelected(target)
                        expanded = false
                    },
                    // Subtle neutral background for selection
                    modifier = Modifier.background(if (isSelected) colors.onSurface.copy(alpha = 0.04f) else Color.Transparent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
fun PremiumFilterChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AppRadius.pill)

    // MONOCHROMATIC UPGRADE: Active states use heavy onSurface grounding
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) colors.onSurface else Color.Transparent,
        animationSpec = tween(150),
        label = "chipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) Color.Transparent else colors.outlineVariant.copy(alpha = 0.3f), // Softened unselected border
        animationSpec = tween(150),
        label = "chipBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) colors.surface else colors.onSurfaceVariant, // High contrast text
        animationSpec = tween(150),
        label = "chipText"
    )

    Surface(
        shape = shape,
        color = bgColor,
        border = if (isSelected) null else BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .semantics { this.selected = isSelected }
            .clickable(role = Role.Button) {
                if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, // Bumped weight for active chips
                color = textColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}