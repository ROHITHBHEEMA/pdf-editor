package app.pdfeditor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.rendering.PDFRenderer
import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.EditCaps
import app.pdfeditor.engine.EditRequest
import app.pdfeditor.engine.EditResult
import app.pdfeditor.engine.ElementEditor
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.engine.ParsedPage
import app.pdfeditor.engine.collectGroupIds
import app.pdfeditor.engine.findNode
import app.pdfeditor.ui.PageTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfDocumentViewModel : ViewModel() {

    var state by mutableStateOf(PdfUiState())
        private set

    private var document: PDDocument? = null
    var parsed: ParsedPage? = null
        private set

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var cacheFile: File? = null
    private val renderMutex = Mutex()

    private var fontCatalog: FontCatalog? = null
    private var embeddedFonts = false

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private var historyBytes = 0L

    fun open(context: Context, uri: Uri) {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot open the selected file" }.readBytes()
                    }
                    val file = File(context.cacheDir, "working.pdf")
                    file.writeBytes(bytes)
                    PDDocument.load(bytes) to file
                }
                document?.close()
                closeRenderer()
                clearHistory()
                document = loaded.first
                cacheFile = loaded.second
                fontCatalog = FontCatalog(context.applicationContext)
                embeddedFonts = false
                openRenderer(loaded.second)
                renderPage(0)
                state = state.copy(
                    loading = false,
                    hasDocument = true,
                    pageCount = loaded.first.numberOfPages,
                    pageIndex = 0,
                    fileName = displayName(context, uri),
                    documentToken = state.documentToken + 1,
                    canUndo = false,
                    canRedo = false,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                state = state.copy(loading = false, error = t.message ?: "Failed to open PDF")
            }
        }
    }

    fun showPage(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.numberOfPages || index == state.pageIndex) return
        viewModelScope.launch {
            state = state.copy(busy = "Loading page")
            renderPage(index)
            state = state.copy(busy = null, pageIndex = index)
        }
    }

    fun select(id: Int?, reveal: Boolean = false) {
        state = state.copy(
            selectedId = id,
            revealTick = if (reveal) state.revealTick + 1 else state.revealTick,
        )
    }

    fun copySelectedText(context: Context) {
        val node = findNode(parsed?.root ?: return, state.selectedId) ?: return
        val text = node.text ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
        Toast.makeText(context, "Copied text", Toast.LENGTH_SHORT).show()
    }

    fun toggleExpand(id: Int) {
        val e = state.expanded
        state = state.copy(expanded = if (id in e) e - id else e + id)
    }

    fun toggleRaw() {
        state = state.copy(showRaw = !state.showRaw)
    }

    fun deleteSelected() {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val node = findNode(parsedPage.root, state.selectedId) ?: return
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            state = state.copy(busy = "Deleting")
            withContext(Dispatchers.IO) {
                recordUndo(doc, pageIndex)
                ElementEditor.deleteRange(
                    doc, doc.getPage(pageIndex), parsedPage.tokens,
                    node.startIndex, node.endIndex,
                )
            }
            resyncCacheAndReopen()
            renderPage(pageIndex)
            state = state.copy(busy = null, dirty = true, canUndo = true, canRedo = false)
        }
    }

    fun beginEdit(id: Int) {
        state = state.copy(selectedId = id, editingId = id)
    }

    fun cancelEdit() {
        state = state.copy(editingId = null)
    }

    fun editTarget(): EditTarget? {
        val parsedPage = parsed ?: return null
        val node = findNode(parsedPage.root, state.editingId ?: return null) ?: return null
        val caps = ElementEditor.capabilities(parsedPage.tokens, node)
        val b = node.bounds
        return EditTarget(
            node = node,
            caps = caps,
            x = b?.minX ?: 0f,
            y = b?.minY ?: 0f,
            w = b?.width ?: 0f,
            h = b?.height ?: 0f,
            fillArgb = if (caps.canFill) state.swatchColors[node.id] ?: node.colorArgb else null,
            strokeArgb = if (caps.canStroke) node.colorArgb else null,
            text = if (caps.canText) node.text else null,
            colorSpace = node.colorSpace,
            fontOptions = if (caps.canText) fontCatalog?.options() ?: emptyList() else emptyList(),
        )
    }

    fun applyEdit(context: Context, request: EditRequest) {
        val node = findNode(parsed?.root ?: return, state.editingId) ?: return
        applyEditInternal(context, node, request)
    }

    // Inline canvas editing: retype one text run in place. Always routes through
    // the auto fallback so the new characters are guaranteed to encode, matching
    // the edit sheet's default.
    fun applyInlineText(context: Context, id: Int, newText: String) {
        val node = findNode(parsed?.root ?: return, id) ?: return
        if (node.kind != NodeKind.TEXT) return
        if (newText == (node.text ?: "")) return
        applyEditInternal(
            context, node, EditRequest(newText = newText, fontEntryId = AUTO_FONT_ID),
        )
    }

    // The fallback face the inline editor renders while typing, matching what
    // applyInlineText embeds (the auto match for the run's own font).
    fun inlineFontFace(id: Int): FontCatalog.FaceSource? {
        val node = findNode(parsed?.root ?: return null, id) ?: return null
        return fontCatalog?.autoMatchFace(node.font)
    }

    // For the debug overlay: which matcher step picked the run's substitute.
    fun fontDecisionFor(id: Int): FontCatalog.MatchExplain? {
        val node = findNode(parsed?.root ?: return null, id) ?: return null
        if (node.kind != NodeKind.TEXT) return null
        return fontCatalog?.explainMatch(node.font)
    }

    // Width scale the inline editor applies so its preview size tracks what the
    // commit embeds (both use the auto match for the run's own font).
    fun inlineFontScale(id: Int): Float {
        val node = findNode(parsed?.root ?: return 1f, id) ?: return 1f
        val cat = fontCatalog ?: return 1f
        val matchId = cat.autoMatchId(node.font) ?: return 1f
        return cat.widthScale(node.font, matchId)
    }

    private fun applyEditInternal(context: Context, node: DrawNode, request: EditRequest) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            // editElement is cheap and may reject; do it first so a failed edit
            // keeps the sheet open with the user's input. Only the slow resync +
            // render is overlaid, and the sheet closes the instant it starts.
            val result = withContext(Dispatchers.IO) {
                val page = doc.getPage(pageIndex)
                val before = ElementEditor.snapshot(page) ?: ByteArray(0)
                val sub = resolveSubstitute(doc, node, request)
                val r = ElementEditor.editElement(
                    doc, page, parsedPage.tokens, node, request, sub?.font, sub?.scale ?: 1f,
                )
                if (r is EditResult.Applied) {
                    pushUndo(pageIndex, before)
                    if (sub != null) embeddedFonts = true
                }
                r
            }
            when (result) {
                is EditResult.Applied -> {
                    state = state.copy(editingId = null, busy = "Applying edits")
                    resyncCacheAndReopen()
                    renderPage(pageIndex)
                    state = state.copy(
                        busy = null, dirty = true, canUndo = true, canRedo = false,
                    )
                }
                is EditResult.TextEncodeFailed -> {
                    val chars = result.chars
                    val msg = if (chars.isNullOrBlank()) {
                        "This font cannot encode that text"
                    } else {
                        "This font lacks: $chars"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                EditResult.Degenerate ->
                    Toast.makeText(context, "Cannot transform this element", Toast.LENGTH_SHORT).show()
                EditResult.NoChange -> state = state.copy(editingId = null)
            }
        }
    }

    private class Substitute(val font: PDFont, val scale: Float)

    // Prefer-confident policy: on a text edit with no explicit pick, swap to a
    // precisely identified metric-compatible font even when the original could
    // encode, so missing glyphs and unreliable embedded widths stop biting.
    private fun resolveSubstitute(doc: PDDocument, node: DrawNode, request: EditRequest): Substitute? {
        val cat = fontCatalog ?: return null
        val id = request.fontEntryId
        val realId = when {
            id == AUTO_FONT_ID -> cat.autoMatchId(node.font)
            id != null -> id
            request.newText != null -> cat.confidentMatchId(node.font)
            else -> null
        } ?: return null
        val font = cat.resolve(doc, realId) ?: return null
        return Substitute(font, cat.widthScale(node.font, realId))
    }

    fun importFont(context: Context, uri: Uri) {
        val cat = fontCatalog
        if (cat == null) {
            Toast.makeText(context, "Open a document first", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { cat.importFont(uri) }
            if (ok) {
                state = state.copy(fontCatalogTick = state.fontCatalogTick + 1)
                Toast.makeText(context, "Font added", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not import that font", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun undo() = stepHistory(undoStack, redoStack)

    fun redo() = stepHistory(redoStack, undoStack)

    private fun stepHistory(from: ArrayDeque<EditSnapshot>, to: ArrayDeque<EditSnapshot>) {
        val doc = document ?: return
        if (from.isEmpty()) return
        viewModelScope.launch {
            state = state.copy(busy = "Working")
            val entry = from.removeLast()
            historyBytes -= entry.content.size
            val pageIndex = entry.pageIndex
            withContext(Dispatchers.IO) {
                val page = doc.getPage(pageIndex)
                val current = ElementEditor.snapshot(page) ?: ByteArray(0)
                to.addLast(EditSnapshot(pageIndex, current))
                historyBytes += current.size
                ElementEditor.restore(doc, page, entry.content)
            }
            resyncCacheAndReopen()
            renderPage(pageIndex)
            state = state.copy(
                busy = null,
                pageIndex = pageIndex,
                dirty = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
    }

    private fun recordUndo(doc: PDDocument, pageIndex: Int) {
        pushUndo(pageIndex, ElementEditor.snapshot(doc.getPage(pageIndex)) ?: ByteArray(0))
    }

    // Capped both ways so history never grows without bound.
    private fun pushUndo(pageIndex: Int, content: ByteArray) {
        undoStack.addLast(EditSnapshot(pageIndex, content))
        historyBytes += content.size
        for (e in redoStack) historyBytes -= e.content.size
        redoStack.clear()
        while (undoStack.size > MAX_HISTORY || historyBytes > MAX_HISTORY_BYTES) {
            if (undoStack.size <= 1) break
            historyBytes -= undoStack.removeFirst().content.size
        }
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        historyBytes = 0
    }

    fun saveCopy(context: Context, uri: Uri) {
        val doc = document ?: return
        viewModelScope.launch {
            state = state.copy(busy = "Saving")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { doc.save(it) }
                }
                state = state.copy(dirty = false)
                Toast.makeText(context, "Saved a copy", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                Toast.makeText(context, "Failed to save", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun closeDocument() {
        closeRenderer()
        document?.close()
        document = null
        parsed = null
        clearHistory()
        runCatching { cacheFile?.delete() }
        cacheFile = null
        fontCatalog = null
        embeddedFonts = false
        state = PdfUiState()
    }

    private suspend fun renderPage(index: Int) {
        val doc = document ?: return
        try {
            val result = withContext(Dispatchers.IO) {
                val page = doc.getPage(index)
                val crop = page.cropBox
                val scale = RENDER_DPI / 72f
                val rot = ((page.rotation % 360) + 360) % 360
                val baseW = Math.round(crop.width * scale)
                val baseH = Math.round(crop.height * scale)
                val pxW = if (rot == 90 || rot == 270) baseH else baseW
                val pxH = if (rot == 90 || rot == 270) baseW else baseH
                val bmp = renderPageBitmap(index, pxW, pxH)
                val parsedPage = ContentStreamEngine.parse(page)
                val transform = PageTransform(
                    crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height,
                    page.rotation, scale,
                )
                Triple(bmp, parsedPage, transform)
            }
            parsed = result.second
            state = state.copy(
                bitmap = result.first.asImageBitmap(),
                elementCount = result.second.leaves.size,
                page = result.second,
                pageTransform = result.third,
                swatchColors = sampleLeafColors(result.first, result.second.leaves, result.third),
                selectedId = null,
                expanded = collectGroupIds(result.second.root),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "render failed page=$index", t)
            state = state.copy(error = t.message ?: "Failed to render page")
        }
    }

    // pdfbox-android can't resolve some color spaces (Separation, etc.), so the
    // true paint color is read from the pixel pdfium actually drew at the
    // element's center. Text keeps its parsed color (glyph centers are usually
    // background).
    private fun sampleLeafColors(
        bmp: Bitmap,
        leaves: List<DrawNode>,
        t: PageTransform,
    ): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        val w = bmp.width
        val h = bmp.height
        for (leaf in leaves) {
            if (leaf.kind != NodeKind.PATH && leaf.kind != NodeKind.IMAGE) continue
            val b = leaf.bounds ?: continue
            val r = t.toRect(b)
            val cx = ((r.left + r.right) * 0.5f).toInt().coerceIn(0, w - 1)
            val cy = ((r.top + r.bottom) * 0.5f).toInt().coerceIn(0, h - 1)
            val px = bmp.getPixel(cx, cy)
            if (px ushr 24 != 0) map[leaf.id] = px or (0xFF shl 24)
        }
        return map
    }

    private suspend fun renderPageBitmap(index: Int, pxW: Int, pxH: Int): Bitmap =
        try {
            renderWithPdfium(index, pxW, pxH)
        } catch (t: Throwable) {
            Log.e(TAG, "pdfium render failed page=$index, falling back to pdfbox", t)
            PDFRenderer(requireNotNull(document)).renderImageWithDPI(index, RENDER_DPI)
        }

    // pdfium leaves empty pixels transparent and PDFs assume white paper, so
    // prefill white. It is single-page and not thread-safe, hence the mutex.
    private suspend fun renderWithPdfium(index: Int, pxW: Int, pxH: Int): Bitmap =
        renderMutex.withLock {
            val renderer = requireNotNull(pdfRenderer) { "renderer not open" }
            val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = renderer.openPage(index)
            try {
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        }

    // Crisp re-render of just the visible window at the current zoom. The matrix
    // maps page points (top-left, y-down) onto the tile: scale carries points to
    // tile pixels, translate pulls the region's corner to the origin. Rotation 0
    // only; rotated pages keep the upscaled base bitmap.
    suspend fun renderRegion(
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outW: Int,
        outH: Int,
    ): Bitmap? {
        val doc = document ?: return null
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return null
        val bw = right - left
        val bh = bottom - top
        if (bw < 1f || bh < 1f) return null
        val w = outW.coerceIn(1, MAX_TILE_PX)
        val h = outH.coerceIn(1, MAX_TILE_PX)
        return withContext(Dispatchers.IO) {
            val page = doc.getPage(pageIndex)
            if (((page.rotation % 360) + 360) % 360 != 0) return@withContext null
            val s = RENDER_DPI / 72f
            val matrix = Matrix().apply {
                setScale((w / bw) * s, (h / bh) * s)
                postTranslate(-left * (w / bw), -top * (h / bh))
            }
            renderMutex.withLock {
                val renderer = pdfRenderer ?: return@withLock null
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val pdfPage = renderer.openPage(pageIndex)
                try {
                    pdfPage.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    pdfPage.close()
                }
                bmp
            }
        }
    }

    private fun openRenderer(file: File) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        pdfRenderer = PdfRenderer(fd)
    }

    private fun closeRenderer() {
        runCatching { pdfRenderer?.close() }
        runCatching { pfd?.close() }
        pdfRenderer = null
        pfd = null
    }

    // After an in-memory edit the cache file is stale; rewrite it and reopen
    // pdfium on the fresh bytes. saveIncremental appends only the changed objects
    // to the original, so editing one page skips re-serializing the whole doc.
    // Once a font is embedded the increment can't reach the new font objects
    // (commit only flags the page chain), so switch to full saves from then on.
    private suspend fun resyncCacheAndReopen() {
        val doc = document ?: return
        val file = cacheFile ?: return
        renderMutex.withLock {
            closeRenderer()
            withContext(Dispatchers.IO) {
                try {
                    if (embeddedFonts) {
                        file.outputStream().use { doc.save(it) }
                    } else {
                        file.outputStream().use { doc.saveIncremental(it) }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "incremental save failed, full save", t)
                    file.outputStream().use { doc.save(it) }
                }
            }
            openRenderer(file)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    override fun onCleared() {
        closeRenderer()
        document?.close()
        document = null
        runCatching { cacheFile?.delete() }
        cacheFile = null
    }

    companion object {
        const val RENDER_DPI = 144f
        private const val MAX_TILE_PX = 4096
        private const val TAG = "PdfInspector"
        private const val MAX_HISTORY = 50
        private const val MAX_HISTORY_BYTES = 16L * 1024 * 1024
    }
}

private class EditSnapshot(val pageIndex: Int, val content: ByteArray)

class EditTarget(
    val node: DrawNode,
    val caps: EditCaps,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fillArgb: Int?,
    val strokeArgb: Int?,
    val text: String?,
    val colorSpace: String?,
    val fontOptions: List<FontOption> = emptyList(),
)

data class PdfUiState(
    val loading: Boolean = false,
    val busy: String? = null,
    val hasDocument: Boolean = false,
    val bitmap: ImageBitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val elementCount: Int = 0,
    val fileName: String = "",
    val page: ParsedPage? = null,
    val pageTransform: PageTransform? = null,
    val selectedId: Int? = null,
    val editingId: Int? = null,
    val swatchColors: Map<Int, Int> = emptyMap(),
    val revealTick: Int = 0,
    val expanded: Set<Int> = emptySet(),
    val showRaw: Boolean = false,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val documentToken: Int = 0,
    val fontCatalogTick: Int = 0,
    val error: String? = null,
)
