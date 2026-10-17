package app.pdfeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun InspectorDock(
    dock: Dock,
    transparent: Boolean,
    sizeDp: Dp,
    onResizePx: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val color =
        if (transparent) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.80f)
        else MaterialTheme.colorScheme.surfaceContainer
    val shape =
        if (dock == Dock.BOTTOM) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        else RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)

    if (dock == Dock.BOTTOM) {
        Column(modifier) {
            ResizeHandle(dock, onResizePx)
            Surface(
                modifier = Modifier.fillMaxWidth().height(sizeDp),
                color = color,
                shape = shape,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) { content() }
        }
    } else {
        Row(modifier) {
            ResizeHandle(dock, onResizePx)
            Surface(
                modifier = Modifier.width(sizeDp).fillMaxHeight(),
                color = color,
                shape = shape,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) { content() }
        }
    }
}

@Composable
private fun ResizeHandle(dock: Dock, onResizePx: (Float) -> Unit) {
    val grip = MaterialTheme.colorScheme.outline
    if (dock == Dock.BOTTOM) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        onResizePx(dy)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(grip))
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(20.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        onResizePx(dx)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 5.dp, height = 40.dp).clip(RoundedCornerShape(3.dp)).background(grip))
        }
    }
}
