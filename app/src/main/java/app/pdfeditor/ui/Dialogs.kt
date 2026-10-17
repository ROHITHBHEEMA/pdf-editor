package app.pdfeditor.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.pdfeditor.flow.FontWarning

/** Characters as the user sees them, separated by spaces: "é ß". */
fun spacedChars(chars: String): String =
    chars.codePoints().toArray().joinToString(" ") { String(Character.toChars(it)) }

const val DECORATIONS_STAY_TEXT =
    "Underlining or highlighting in this paragraph will stay where it is and won't move with the text."

/** The font dialog's body for [warning]. */
fun fontWarningText(warning: FontWarning): String =
    "The original font doesn't include: ${spacedChars(warning.missingChars)}. " +
        "This paragraph will use ${warning.substituteNames.joinToString(", ")}, which looks similar."

/**
 * Asks before an edit that substitutes a font ([fontWarning]) and/or leaves underlines and highlights behind
 * ([decorationsStay]). With a font warning it is the font dialog, with the decoration sentence as a second
 * paragraph when both apply.
 */
@Composable
fun ConfirmEditDialog(fontWarning: FontWarning?, decorationsStay: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val title: String
    val body: String
    val confirm: String
    if (fontWarning != null) {
        title = "Font not available"
        body = fontWarningText(fontWarning) + if (decorationsStay) "\n\n$DECORATIONS_STAY_TEXT" else ""
        confirm = "Use similar font"
    } else {
        title = "Underline won't move"
        body = DECORATIONS_STAY_TEXT
        confirm = "Continue"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
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

const val DISCARD_DOCUMENT_TEXT = "Your edits haven't been saved. If you continue, they'll be lost."
const val DISCARD_PARAGRAPH_TEXT = "Your changes to this paragraph will be lost."

/** "Discard your changes?" with [body]; Discard runs [onDiscard], Keep editing (or dismissing) runs [onKeep]. */
@Composable
fun DiscardDialog(body: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Discard your changes?") },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep editing") } },
    )
}
