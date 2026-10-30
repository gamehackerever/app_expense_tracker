package com.expensetracker.offline.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.theme.hasCategoryIcon

/**
 * The one category glyph used across the whole app (transaction list, category sheets, pickers, insights).
 *
 *  - Known category            → its icon
 *  - Custom category           → the category's own initial (never the payee's)
 *  - No category / Uncategorized → the generic category icon
 *
 * Categories are rounded squares; people and accounts stay circles, so the two never get confused.
 * [tint] = null renders the neutral style; pass a colour to tie the tile to a chart colour.
 * [iconOverride] is for special rows such as repayments.
 */
@Composable
internal fun CategoryAvatar(
    category: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color? = null,
    iconOverride: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val glyphColor = tint ?: scheme.onSurfaceVariant
    val containerColor = if (tint != null) tint.copy(alpha = 0.12f) else scheme.onSurface.copy(alpha = 0.05f)

    val isNoCategory = remember(category) {
        category.isBlank() ||
            category.equals("Uncategorized", ignoreCase = true) ||
            category.equals("No category", ignoreCase = true)
    }
    val showIcon = iconOverride != null || isNoCategory || hasCategoryIcon(category)
    val initial = remember(category) {
        category.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    }

    Surface(
        shape = RoundedCornerShape(size * 0.28f),
        color = containerColor,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (showIcon) {
                Icon(
                    imageVector = iconOverride ?: getCategoryIcon(category),
                    contentDescription = null,
                    tint = glyphColor,
                    modifier = Modifier.size(size * 0.5f)
                )
            } else {
                Text(
                    text = initial,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.38f).sp,
                    color = glyphColor
                )
            }
        }
    }
}
