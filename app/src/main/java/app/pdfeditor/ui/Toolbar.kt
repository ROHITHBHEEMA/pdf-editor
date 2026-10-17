package app.pdfeditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBackUp
import compose.icons.tablericons.ArrowForwardUp
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.DeviceFloppy
import compose.icons.tablericons.Folder

@Composable
fun EditorToolbar(
    fileName: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPageBefore: () -> Unit,
    onAddPageAfter: () -> Unit,
    onDeletePage: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(60.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpen) {
                Icon(TablerIcons.Folder, "Open", Modifier.size(22.dp))
            }
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(TablerIcons.ArrowBackUp, "Undo", Modifier.size(22.dp))
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(TablerIcons.ArrowForwardUp, "Redo", Modifier.size(22.dp))
            }
            PagesMenu(onAddBefore = onAddPageBefore, onAddAfter = onAddPageAfter, onDelete = onDeletePage)
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(onClick = onSave) {
                Icon(TablerIcons.DeviceFloppy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save copy", maxLines = 1)
            }
        }
    }
}

/** "Page 2 of 5" with previous/next buttons, shown under the page. */
@Composable
fun PageIndicator(
    pageIndex: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrev, enabled = pageIndex > 0) {
                Icon(TablerIcons.ChevronLeft, "Previous page", Modifier.size(22.dp))
            }
            Text(
                "Page ${pageIndex + 1} of $pageCount",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
                Icon(TablerIcons.ChevronRight, "Next page", Modifier.size(22.dp))
            }
        }
    }
}
