package com.expensetracker.offline.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.ui.theme.AppRadius

/**
 * The one "nothing here" card used by every list in the app, so an empty cycle, an empty review queue and an empty
 * insights section all look and read the same. [actions] is for an optional button or link under the text.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconModifier: Modifier = Modifier,
    emoji: String? = null,
    actions: @Composable ColumnScope.() -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(AppRadius.medium),
        color = scheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                emoji != null -> Text(emoji, fontSize = 40.sp, textAlign = TextAlign.Center)
                icon != null -> Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = iconModifier.size(32.dp))
            }
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            actions()
        }
    }
}
