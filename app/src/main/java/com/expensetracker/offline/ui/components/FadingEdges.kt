package com.expensetracker.offline.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades the edges of a scrolling container only while more content exists in that direction,
 * so clipped chips and cards read as "scrollable" instead of "broken".
 */
internal fun Modifier.fadingEdges(
    state: ScrollState,
    vertical: Boolean = false,
    edge: Dp = 20.dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val e = edge.toPx()
        val length = if (vertical) size.height else size.width
        if (length <= e * 2f) return@drawWithContent

        if (state.canScrollBackward) {
            drawRect(
                brush = if (vertical) Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, e)
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, e),
                size = if (vertical) Size(size.width, e) else Size(e, size.height),
                blendMode = BlendMode.DstIn
            )
        }
        if (state.canScrollForward) {
            val start = length - e
            drawRect(
                brush = if (vertical) Brush.verticalGradient(listOf(Color.Black, Color.Transparent), start, length)
                else Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), start, length),
                topLeft = if (vertical) Offset(0f, start) else Offset(start, 0f),
                size = if (vertical) Size(size.width, e) else Size(e, size.height),
                blendMode = BlendMode.DstIn
            )
        }
    }

@Composable
internal fun Modifier.fadingHorizontalScroll(edge: Dp = 24.dp): Modifier {
    val state = rememberScrollState()
    return this.fadingEdges(state, vertical = false, edge = edge).horizontalScroll(state)
}

@Composable
internal fun Modifier.fadingVerticalScroll(edge: Dp = 16.dp): Modifier {
    val state = rememberScrollState()
    return this.fadingEdges(state, vertical = true, edge = edge).verticalScroll(state)
}
