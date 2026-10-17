package app.pdfeditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pdfeditor.ui.AboutSheet
import app.pdfeditor.ui.EditorToolbar
import app.pdfeditor.ui.FitMode
import app.pdfeditor.ui.ConfirmEditDialog
import app.pdfeditor.ui.MessageDialog
import app.pdfeditor.ui.PageIndicator
import app.pdfeditor.ui.ParagraphEditSheet
import app.pdfeditor.ui.PdfCanvas
import app.pdfeditor.ui.theme.InspectorTheme
import compose.icons.TablerIcons
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Folder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on first launch: after a configuration change the view model still holds the document and its edits.
        val initialUri: Uri? =
            if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) intent?.data else null
        setContent {
            InspectorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EditorScreen(initialUri = initialUri)
                }
            }
        }
    }
}

@Composable
fun EditorScreen(initialUri: Uri? = null, viewModel: PdfDocumentViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.state

    LaunchedEffect(initialUri) {
        if (initialUri != null) viewModel.open(context, initialUri)
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.open(context, it) }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { viewModel.saveCopy(context, it) }
    }
    var showAbout by remember { mutableStateOf(false) }
    fun pickPdf() = openLauncher.launch(arrayOf("application/pdf"))

    Box(Modifier.fillMaxSize()) {
        if (state.hasDocument) {
            Column(Modifier.fillMaxSize()) {
                EditorToolbar(
                    fileName = state.fileName,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onOpen = ::pickPdf,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onAddPageBefore = { viewModel.addPageBefore(state.pageIndex) },
                    onAddPageAfter = { viewModel.addPageAfter(state.pageIndex) },
                    onDeletePage = viewModel::deleteCurrentPage,
                    onSave = { saveLauncher.launch(viewModel.suggestedCopyName()) },
                    onAbout = { showAbout = true },
                )
                PageView(viewModel, state, Modifier.weight(1f).fillMaxWidth())
                PageIndicator(
                    pageIndex = state.pageIndex,
                    pageCount = state.pageCount,
                    onPrev = { viewModel.showPage(state.pageIndex - 1) },
                    onNext = { viewModel.showPage(state.pageIndex + 1) },
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(onOpen = ::pickPdf)
            }
        }
        state.busy?.let { BusyOverlay(it) }
    }

    val editing = state.editing
    if (editing != null && state.busy == null && state.confirmation == null && state.message == null) {
        ParagraphEditSheet(
            key = editing.id,
            initialText = state.draft ?: editing.text,
            onDone = viewModel::submitParagraph,
            onCancel = viewModel::cancelEdit,
        )
    }
    state.confirmation?.let { c ->
        ConfirmEditDialog(
            fontWarning = c.fontWarning,
            decorationsStay = c.decorationsStay,
            onConfirm = viewModel::confirmEdit,
            onCancel = viewModel::cancelConfirmation,
        )
    }
    if (showAbout) AboutSheet(onDismiss = { showAbout = false })
    state.message?.let { MessageDialog(it, onDismiss = viewModel::dismissMessage) }
}

@Composable
private fun PageView(viewModel: PdfDocumentViewModel, state: PdfUiState, modifier: Modifier) {
    val bmp = state.bitmap
    val transform = state.pageTransform
    // Held here so zoom/pan persist across page changes and re-renders.
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset.Zero) }
    var fitMode by remember { mutableStateOf(FitMode.WIDTH) }
    LaunchedEffect(state.documentToken) { fitMode = FitMode.WIDTH }

    if (bmp == null || transform == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    PdfCanvas(
        bitmap = bmp,
        pageIndex = state.pageIndex,
        scaleState = scaleState,
        offsetState = offsetState,
        outline = state.outline,
        highlightColor = MaterialTheme.colorScheme.primary,
        backdropColor = MaterialTheme.colorScheme.surfaceVariant,
        fitMode = fitMode,
        onUserTransform = { fitMode = FitMode.NONE },
        onTap = { p ->
            val (x, y) = transform.toPage(p.x, p.y)
            viewModel.tap(x, y)
        },
        renderTile = { idx, src, outW, outH ->
            viewModel.renderRegion(idx, src.left, src.top, src.right, src.bottom, outW, outH)?.asImageBitmap()
        },
        modifier = modifier,
    )
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(
            imageVector = TablerIcons.FileText,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Edit a PDF", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Open a document, then tap any paragraph to change its text. " +
                "Your original file is never changed — you save an edited copy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onOpen) {
            Icon(TablerIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open a PDF")
        }
    }
}

@Composable
private fun BusyOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
