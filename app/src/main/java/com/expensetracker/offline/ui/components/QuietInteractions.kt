package com.expensetracker.offline.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The one press behaviour used by every tappable surface in the app: no ripple, a short fade and a tiny
 * shrink while pressed. Keeping a single implementation is what makes every screen feel like the same app.
 */
@Composable
fun Modifier.quietClickable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.75f,
    pressedScale: Float = 0.98f,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed && enabled) pressedAlpha else 1f, tween(100), label = "quietPress")
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "quietScale"
    )
    return this
        .graphicsLayer {
            this.alpha = alpha
            this.scaleX = scale
            this.scaleY = scale
        }
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

/** Tap + long-press with the same press behaviour as [quietClickable]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.quietCombinedClickable(
    pressedAlpha: Float = 0.85f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(100), label = "quietPress")
    return this
        .graphicsLayer { this.alpha = alpha }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick
        )
}
