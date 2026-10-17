package app.pdfeditor.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import app.pdfeditor.ui.theme.Accent
import app.pdfeditor.ui.theme.Palettes
import app.pdfeditor.ui.theme.ThemeMode
import app.pdfeditor.ui.theme.ThemeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsSheet(theme: ThemeState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = theme.mode == m,
                            onClick = { theme.updateMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(m.label) }
                    }
                }
            }

            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (dynamicAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic color", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Match colors to your wallpaper",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = theme.dynamic, onCheckedChange = { theme.updateDynamic(it) })
                }
            }

            val accentEnabled = !(theme.dynamic && dynamicAvailable)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Accent",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.alpha(if (accentEnabled) 1f else 0.5f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Accent.entries.forEach { accent ->
                        Swatch(
                            color = Palettes.swatch(accent),
                            selected = theme.accent == accent,
                            enabled = accentEnabled,
                            onClick = { theme.updateAccent(accent) },
                        )
                    }
                }
                if (!accentEnabled) {
                    Text(
                        "Turn off dynamic color to pick an accent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = TablerIcons.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}
