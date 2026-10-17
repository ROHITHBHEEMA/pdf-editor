package app.pdfeditor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.FileMinus
import compose.icons.tablericons.FilePlus
import compose.icons.tablericons.Files

/** The Pages button and its menu; every action applies to the page being shown. */
@Composable
fun PagesMenu(onAddBefore: () -> Unit, onAddAfter: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.Files, "Pages", Modifier.size(22.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Add blank page before") },
                leadingIcon = { Icon(TablerIcons.FilePlus, null, Modifier.size(20.dp)) },
                onClick = { expanded = false; onAddBefore() },
            )
            DropdownMenuItem(
                text = { Text("Add blank page after") },
                leadingIcon = { Icon(TablerIcons.FilePlus, null, Modifier.size(20.dp)) },
                onClick = { expanded = false; onAddAfter() },
            )
            DropdownMenuItem(
                text = { Text("Delete this page") },
                leadingIcon = { Icon(TablerIcons.FileMinus, null, Modifier.size(20.dp)) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}
