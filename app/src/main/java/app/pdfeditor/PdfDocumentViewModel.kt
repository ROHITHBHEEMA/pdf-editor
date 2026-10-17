package app.pdfeditor

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfeditor.flow.EditOp
import app.pdfeditor.flow.EditOutcome
import app.pdfeditor.flow.EditSession
import app.pdfeditor.flow.FontWarning
import app.pdfeditor.flow.OpenResult
import app.pdfeditor.flow.ParagraphHit
import app.pdfeditor.flow.RejectReason
import app.pdfeditor.ui.PageTransform
import app.pdfeditor.ui.spacedChars
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the editor screen from one [EditSession]. The session's current state is written to
 * `cacheDir/working.pdf` and shown through PdfRenderer. Every heavy session call runs on the IO dispatcher; an
 * unexpected failure shows [SOMETHING_WRONG] and leaves the previous document state in place.
 */
class PdfDocumentViewModel : ViewModel() {

    var state by mutableStateOf(PdfUiState())
        private set

    private var session: EditSession? = null
    private var fonts: FontCatalog? = null
    private var pendingEdit: EditOp.EditParagraph? = null

    // Display of the current session state: the cache file, pdfium on it, and a pdfbox view of the same bytes
    // for page geometry and as a render fallback.
    private var cacheFile: File? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var displayDoc: PDDocument? = null
    private var geometry: List<PageGeometry> = emptyList()
    private val renderMutex = Mutex()

    private val busy get() = state.busy != null

    fun open(context: Context, uri: Uri) {
        if (busy) return
        val app = context.applicationContext
        val file = File(app.cacheDir, CACHE_NAME).also { cacheFile = it }
        state = state.copy(busy = BUSY_OPENING)
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) null
                    else EditSession.open(bytes, fonts ?: FontCatalog(app).also { fonts = it })
                }
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                null
            }
            when (result) {
                is OpenResult.Opened -> adopt(file, result.session, displayName(app, uri))
                OpenResult.PasswordProtected -> fail(PASSWORD)
                is OpenResult.Failed, null -> fail(OPEN_FAILED)
            }
        }
    }

    /** Shows a newly opened session and makes it current; on failure the previous document stays. */
    private suspend fun adopt(file: File, next: EditSession, name: String) {
        val shown = try {
            showSession(file, next, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "first render failed", t)
            false
        }
        if (!shown) {
            runCatching { next.close() }
            session?.let { old -> runCatching { showSession(file, old, state.pageIndex) } }
            fail(OPEN_FAILED)
            return
        }
        runCatching { session?.close() }
        session = next
        pendingEdit = null
        state = state.copy(
            busy = null,
            hasDocument = true,
            fileName = name,
            documentToken = state.documentToken + 1,
            editing = null,
            draft = null,
            outline = null,
            fontWarning = null,
        )
        syncHistory()
    }

    fun showPage(index: Int) {
        if (busy || session == null || index !in geometry.indices || index == state.pageIndex) return
        state = state.copy(busy = BUSY_PAGE, outline = null)
        viewModelScope.launch {
            try {
                renderPage(index)
                state = state.copy(busy = null)
            } catch (t: Throwable) {
                Log.e(TAG, "render failed", t)
                fail(SOMETHING_WRONG)
            }
        }
    }

    /** A tap at PDF user-space point ([pageX], [pageY]) of the shown page. */
    fun tap(pageX: Float, pageY: Float) {
        val s = session ?: return
        if (busy || state.editing != null) return
        val page = state.pageIndex
        viewModelScope.launch {
            val hit = try {
                withContext(Dispatchers.IO) { s.paragraphAt(page, pageX, pageY) }
            } catch (t: Throwable) {
                Log.e(TAG, "hit test failed", t)
                null
            }
            if (hit == null || session !== s || state.pageIndex != page || busy) {
                state = state.copy(outline = null)
                return@launch
            }
            state = state.copy(outline = geometry.getOrNull(page)?.transform?.toRect(hit.bounds))
            if (!hit.editable) {
                state = state.copy(message = rejectMessage(hit.reason ?: RejectReason.NON_LATIN, ""))
                return@launch
            }
            delay(OUTLINE_MS) // let the outline show before the sheet covers the page
            if (session === s && !busy && state.editing == null) state = state.copy(editing = hit, draft = null)
        }
    }

    fun cancelEdit() {
        pendingEdit = null
        state = state.copy(editing = null, draft = null, outline = null, fontWarning = null)
    }

    fun submitParagraph(newText: String) {
        val hit = state.editing ?: return
        state = state.copy(draft = newText)
        runOp(EditOp.EditParagraph(hit.id, newText), accept = false)
    }

    fun confirmSubstitution() {
        val op = pendingEdit ?: return
        pendingEdit = null
        state = state.copy(fontWarning = null)
        runOp(op, accept = true)
    }

    fun cancelSubstitution() {
        pendingEdit = null
        state = state.copy(fontWarning = null)
    }

    fun addPageBefore(i: Int) = runOp(EditOp.AddBlankPage(i), showAfter = i)

    fun addPageAfter(i: Int) = runOp(EditOp.AddBlankPage(i + 1), showAfter = i + 1)

    fun deleteCurrentPage() = runOp(EditOp.DeletePage(state.pageIndex), showAfter = state.pageIndex)

    fun dismissMessage() {
        state = state.copy(message = null, outline = if (state.editing == null) null else state.outline)
    }

    /** Applies [op]; on success re-renders and shows page [showAfter] (clamped), or else the current page. */
    private fun runOp(op: EditOp, accept: Boolean = false, showAfter: Int? = null) {
        val s = session ?: return
        if (busy) return
        state = state.copy(busy = BUSY_UPDATING)
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { s.apply(op, accept) }
            } catch (t: Throwable) {
                Log.e(TAG, "apply failed", t)
                fail(SOMETHING_WRONG)
                return@launch
            }
            when (outcome) {
                EditOutcome.Applied -> {
                    if (!refreshAfterChange(s, showAfter ?: state.pageIndex) { s.undo() }) return@launch
                    state = state.copy(busy = null, editing = null, draft = null, outline = null)
                    syncHistory()
                }
                is EditOutcome.NeedsFontConfirmation -> {
                    pendingEdit = op as? EditOp.EditParagraph
                    state = state.copy(busy = null, fontWarning = outcome.warning)
                }
                is EditOutcome.Rejected ->
                    state = state.copy(busy = null, message = rejectMessage(outcome.reason, outcome.detail))
            }
        }
    }

    fun undo() = step(forward = false)

    fun redo() = step(forward = true)

    private fun step(forward: Boolean) {
        val s = session ?: return
        if (busy || !(if (forward) s.canRedo else s.canUndo)) return
        state = state.copy(busy = BUSY_UPDATING, editing = null, draft = null, outline = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (forward) s.redo() else s.undo() }
            } catch (t: Throwable) {
                Log.e(TAG, "undo/redo failed", t)
                fail(SOMETHING_WRONG)
                return@launch
            }
            if (!refreshAfterChange(s, state.pageIndex) { if (forward) s.undo() else s.redo() }) return@launch
            state = state.copy(busy = null)
            syncHistory()
        }
    }

    /**
     * Shows the session's new state at [page] (clamped). If that fails, [revert] restores the previous session
     * state, which is put back on screen, and the failure message is shown. Returns whether the new state is shown.
     */
    private suspend fun refreshAfterChange(s: EditSession, page: Int, revert: () -> Unit): Boolean {
        val file = cacheFile ?: return false
        val ok = try {
            showSession(file, s, page)
        } catch (t: Throwable) {
            Log.e(TAG, "refresh failed", t)
            false
        }
        if (!ok) {
            runCatching { withContext(Dispatchers.IO) { revert() } }
            runCatching { showSession(file, s, state.pageIndex) }
            syncHistory()
            fail(SOMETHING_WRONG)
        }
        return ok
    }

    fun saveCopy(context: Context, uri: Uri) {
        val s = session ?: return
        if (busy) return
        val app = context.applicationContext
        state = state.copy(busy = BUSY_SAVING)
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { s.save(it); true } ?: false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                false
            }
            if (ok) {
                state = state.copy(busy = null)
                Toast.makeText(app, "Saved a copy", Toast.LENGTH_SHORT).show()
            } else {
                fail(SOMETHING_WRONG)
            }
        }
    }

    /** A file name for the saved copy: "letter.pdf" becomes "letter (edited).pdf". */
    fun suggestedCopyName(): String {
        val name = state.fileName.ifBlank { "document.pdf" }
        val base = if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name
        return "$base (edited).pdf"
    }

    private fun fail(message: String) {
        state = state.copy(busy = null, message = message)
    }

    private fun syncHistory() {
        val s = session
        state = state.copy(canUndo = s?.canUndo == true, canRedo = s?.canRedo == true)
    }

    private fun rejectMessage(reason: RejectReason, detail: String): String = when (reason) {
        RejectReason.NON_LATIN -> "Editing this language isn't supported yet."
        RejectReason.UNREADABLE -> "This text can't be read from the file, so it can't be edited."
        RejectReason.UNSUPPORTED_CHARS -> "These characters can't be added: ${spacedChars(detail)}"
        RejectReason.NO_ROOM -> "There's no room to make this text longer here. Please shorten it."
        RejectReason.LAST_PAGE -> "A document needs at least one page."
        RejectReason.NOT_FOUND -> SOMETHING_WRONG
    }

    // ---- display ----

    /**
     * Writes the session's rendered bytes to [file], reopens the renderer on them and renders [page] (clamped).
     * Returns false when the bytes have no pages.
     */
    private suspend fun showSession(file: File, s: EditSession, page: Int): Boolean {
        renderMutex.withLock {
            withContext(Dispatchers.IO) {
                val bytes = s.renderedBytes()
                closeDisplay()
                geometry = emptyList()
                file.writeBytes(bytes)
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fd
                pdfRenderer = PdfRenderer(fd)
                val doc = PDDocument.load(file).also { displayDoc = it }
                val scale = RENDER_DPI / 72f
                geometry = (0 until doc.numberOfPages).map { i ->
                    val p = doc.getPage(i)
                    val crop = p.cropBox
                    PageGeometry(
                        rotation = ((p.rotation % 360) + 360) % 360,
                        widthPt = crop.width,
                        heightPt = crop.height,
                        transform = PageTransform(
                            crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, p.rotation, scale,
                        ),
                    )
                }
            }
        }
        if (geometry.isEmpty()) return false
        renderPage(page.coerceIn(0, geometry.lastIndex))
        return true
    }

    private suspend fun renderPage(index: Int) {
        val g = geometry[index]
        val bmp = withContext(Dispatchers.IO) {
            val scale = RENDER_DPI / 72f
            val baseW = Math.round(g.widthPt * scale)
            val baseH = Math.round(g.heightPt * scale)
            val sideways = g.rotation == 90 || g.rotation == 270
            renderPageBitmap(index, if (sideways) baseH else baseW, if (sideways) baseW else baseH)
        }
        state = state.copy(
            bitmap = bmp.asImageBitmap(),
            pageIndex = index,
            pageCount = geometry.size,
            pageTransform = g.transform,
        )
    }

    private suspend fun renderPageBitmap(index: Int, pxW: Int, pxH: Int): Bitmap =
        try {
            renderWithPdfium(index, pxW, pxH)
        } catch (t: Throwable) {
            Log.e(TAG, "pdfium render failed page=$index, falling back to pdfbox", t)
            renderMutex.withLock { PDFRenderer(requireNotNull(displayDoc)).renderImageWithDPI(index, RENDER_DPI) }
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
        val g = geometry.getOrNull(pageIndex) ?: return null
        if (g.rotation != 0) return null
        val bw = right - left
        val bh = bottom - top
        if (bw < 1f || bh < 1f) return null
        val w = outW.coerceIn(1, MAX_TILE_PX)
        val h = outH.coerceIn(1, MAX_TILE_PX)
        return withContext(Dispatchers.IO) {
            val s = RENDER_DPI / 72f
            val matrix = Matrix().apply {
                setScale((w / bw) * s, (h / bh) * s)
                postTranslate(-left * (w / bw), -top * (h / bh))
            }
            renderMutex.withLock {
                val renderer = pdfRenderer ?: return@withLock null
                if (pageIndex >= renderer.pageCount) return@withLock null
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

    private fun closeDisplay() {
        runCatching { pdfRenderer?.close() }
        runCatching { pfd?.close() }
        runCatching { displayDoc?.close() }
        pdfRenderer = null
        pfd = null
        displayDoc = null
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    override fun onCleared() {
        closeDisplay()
        runCatching { session?.close() }
        session = null
        runCatching { cacheFile?.delete() }
        cacheFile = null
    }

    private class PageGeometry(val rotation: Int, val widthPt: Float, val heightPt: Float, val transform: PageTransform)

    companion object {
        const val RENDER_DPI = 144f
        private const val MAX_TILE_PX = 4096
        private const val OUTLINE_MS = 250L
        private const val TAG = "PdfEditor"
        private const val CACHE_NAME = "working.pdf"

        private const val BUSY_OPENING = "Opening…"
        private const val BUSY_PAGE = "Loading page…"
        private const val BUSY_UPDATING = "Updating…"
        private const val BUSY_SAVING = "Saving…"

        const val SOMETHING_WRONG = "Something went wrong. Your document wasn't changed."
        private const val OPEN_FAILED = "This file couldn't be opened as a PDF."
        private const val PASSWORD = "This PDF is password-protected."
    }
}

data class PdfUiState(
    val busy: String? = null,
    val hasDocument: Boolean = false,
    val bitmap: ImageBitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val fileName: String = "",
    val pageTransform: PageTransform? = null,
    val documentToken: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** The paragraph whose edit sheet is open. */
    val editing: ParagraphHit? = null,
    /** What the user last submitted for [editing], kept so the sheet reopens with it after a dialog. */
    val draft: String? = null,
    /** The tapped paragraph's bounds in bitmap pixels. */
    val outline: Rect? = null,
    val fontWarning: FontWarning? = null,
    val message: String? = null,
)
