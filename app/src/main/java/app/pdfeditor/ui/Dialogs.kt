package app.pdfeditor.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.pdfeditor.flow.FontWarning

/** Characters as the user sees them, separated by spaces: "é ß". */
fun spacedChars(chars: String): String =
    chars.codePoints().toArray().joinToString(" ") { String(Character.toChars(it)) }

@Composable
fun FontWarningDialog(warning: FontWarning, onUseSimilar: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Font not available") },
        text = {
            Text(
                "The original font doesn't include: ${spacedChars(warning.missingChars)}. " +
                    "This paragraph will use ${warning.substituteNames.joinToString(", ")}, which looks similar.",
            )
        },
        confirmButton = { TextButton(onClick = onUseSimilar) { Text("Use similar font") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
