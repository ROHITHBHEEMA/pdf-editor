package app.pdfeditor.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

const val ABOUT_OFFLINE_LINE = "Works fully offline. Your files never leave your phone."
const val ABOUT_CREDIT_LINE = "Based on PDF Inspector by Shardul Vikram Singh (MIT License)"

private class AboutContent(val version: String, val mit: String, val fontLicences: List<Pair<String, String>>)

private fun loadAbout(context: Context): AboutContent {
    val assets = context.assets
    fun read(path: String) = try { assets.open(path).use { String(it.readBytes()) } } catch (_: Exception) { "" }
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }
    val fonts = (assets.list("fonts") ?: emptyArray()).filter { it.startsWith("LICENSE-") && it.endsWith(".txt") }
        .sorted().map { it.removePrefix("LICENSE-").removeSuffix(".txt") to read("fonts/$it") }
    return AboutContent(version, read("LICENSE-PDFInspector.txt"), fonts)
}

/** App name and version, the offline promise, the credit with the full MIT text, then each bundled font's licence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val content = remember { loadAbout(context) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("PDF Editor", style = MaterialTheme.typography.headlineSmall)
            if (content.version.isNotEmpty()) Text("Version ${content.version}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(ABOUT_OFFLINE_LINE, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Text(ABOUT_CREDIT_LINE, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(content.mit, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text("Font licences", style = MaterialTheme.typography.titleMedium)
            for ((name, text) in content.fontLicences) {
                Spacer(Modifier.height(12.dp))
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
