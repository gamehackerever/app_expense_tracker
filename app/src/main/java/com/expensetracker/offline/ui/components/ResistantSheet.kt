package com.expensetracker.offline.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity

/**
 * Keeps a bottom sheet from being dismissed by accident while its content is scrolled.
 *
 * Plain Material sheets hand any scroll left over at the top of the list straight to the sheet, so
 * scrolling back up fast (or flicking) closes it. Here the sheet only follows [followFraction] of that
 * leftover pull, which feels like pulling against a rubber band: a deliberate drag still dismisses,
 * a slip does not. Flicks that start inside scrolling content never throw the sheet away.
 * Dragging the handle, tapping the scrim, Back and "Done" buttons are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResistantModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit
) {
    val resistance = remember { SheetResistance() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = tonalElevation,
        properties = properties,
        dragHandle = dragHandle
    ) {
        Column(modifier = Modifier.nestedScroll(resistance)) { content() }
    }
}

/** Sits between the scrolling content and the sheet, so it sees leftover scroll before the sheet does. */
internal class SheetResistance(private val followFraction: Float = 0.45f) : NestedScrollConnection {

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Content is at its top and the finger keeps pulling down: let the sheet follow only part of it.
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        return Offset(0f, available.y * (1f - followFraction))
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        // A downward flick from inside scrolling content must not carry the sheet off the screen.
        if (available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
}
