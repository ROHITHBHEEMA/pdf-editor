package app.pdfeditor.flow

import app.pdfeditor.engine.Bounds
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.Closeable
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

sealed class EditOp {
    class EditParagraph(val id: ParagraphId, val newText: String) : EditOp()
    class AddBlankPage(val beforePage: Int) : EditOp() // index in current output; == pageCount appends
    class DeletePage(val page: Int) : EditOp()
}

enum class RejectReason { NON_LATIN, UNSUPPORTED_CHARS, NO_ROOM, NOT_FOUND, LAST_PAGE }

sealed class EditOutcome {
    object Applied : EditOutcome()
    class NeedsFontConfirmation(val warning: FontWarning) : EditOutcome()
    class Rejected(val reason: RejectReason, val detail: String = "") : EditOutcome()
}

/** A paragraph under a point of an output page. [page] (0-based) and [bounds] are in output-page terms. */
class ParagraphHit(
    val id: ParagraphId, val text: String, val page: Int, val bounds: Bounds,
    val editable: Boolean, val reason: RejectReason?,
)

sealed class OpenResult {
    class Opened(val session: EditSession) : OpenResult()
    object PasswordProtected : OpenResult()
    class Failed(val message: String) : OpenResult()
}

/**
 * One open document and its edits. Every state is an immutable [EditState]; display and save both render the
 * current state as a full rewrite of the original bytes (cached per state).
 */
class EditSession private constructor(private val m: SessionModel) : Closeable {

    companion object {
        private const val UNDO_LIMIT = 50
        private const val HIT_SLACK = 2f
        private const val OPEN_FAILED = "This file couldn't be opened as a PDF"

        fun open(bytes: ByteArray, fonts: FontProvider): OpenResult {
            val copy = bytes.copyOf()
            val doc = try {
                PDDocument.load(copy)
            } catch (_: InvalidPasswordException) {
                return OpenResult.PasswordProtected
            } catch (_: Throwable) { // malformed files can raise errors, not just exceptions
                return OpenResult.Failed(OPEN_FAILED)
            }
            return try {
                OpenResult.Opened(EditSession(SessionModel(copy, doc, DocumentAnalyzer.analyze(doc), fonts)))
            } catch (_: Throwable) {
                doc.close()
                OpenResult.Failed(OPEN_FAILED)
            }
        }
    }

    private var state = EditState(m.analysis.pages.indices.map { PageSlot.Original(it) }, emptyMap(), emptySet())
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private var nextBlankUid = 0
    private var flowCache: StateFlow? = null
    private var renderCache: Pair<EditState, ByteArray>? = null

    private fun flow(): StateFlow = flowCache?.takeIf { it.state == state } ?: StateRenderer.flow(m, state).also { flowCache = it }

    val pageCount: Int get() = flow().result.slots.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The current state as a full save; used for display. */
    fun renderedBytes(): ByteArray {
        renderCache?.let { (s, b) -> if (s == state) return b }
        return StateRenderer.render(m, flow()).also { renderCache = state to it }
    }

    /** Full save of the current state. */
    fun save(out: OutputStream) = out.write(renderedBytes())

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(state); state = prev
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(state); state = next
    }

    private fun push(next: EditState): EditOutcome {
        undoStack.addLast(state)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        state = next
        return EditOutcome.Applied
    }

    fun apply(op: EditOp, acceptSubstitution: Boolean = false): EditOutcome = when (op) {
        is EditOp.EditParagraph -> editParagraph(op, acceptSubstitution)
        is EditOp.AddBlankPage -> addBlankPage(op.beforePage)
        is EditOp.DeletePage -> deletePage(op.page)
    }

    private fun currentSpans(p: Paragraph): List<Span> = state.edits[p.id] ?: p.spans

    private fun editParagraph(op: EditOp.EditParagraph, accept: Boolean): EditOutcome {
        val p = m.byId[op.id] ?: return EditOutcome.Rejected(RejectReason.NOT_FOUND)
        if (p.nonLatin) return EditOutcome.Rejected(RejectReason.NON_LATIN)
        val current = currentSpans(p)
        if (current.joinToString("") { it.text } == op.newText) return EditOutcome.Applied
        // StyleMapper needs styled characters: map from the original spans when the paragraph was emptied.
        val spans = StyleMapper.apply(current.ifEmpty { p.spans }, op.newText)
        val edits = if (spans == p.spans) state.edits - p.id else state.edits + (p.id to spans)
        if (spans.isEmpty()) return push(state.copy(edits = edits))

        val plan = when (val r = m.plan(spans)) {
            is FontPlanResult.Unsupported -> return EditOutcome.Rejected(RejectReason.UNSUPPORTED_CHARS, r.chars)
            is FontPlanResult.Ok -> r.plan
        }
        val lines = m.lines(p, spans)
        if (!p.canGrow && lines.size * p.lineAdvance > m.originalHeight(p) + 0.01f) {
            return EditOutcome.Rejected(RejectReason.NO_ROOM)
        }
        val warning = plan.warning
        if (warning != null && !accept) return EditOutcome.NeedsFontConfirmation(warning)
        return push(state.copy(edits = edits))
    }

    private fun addBlankPage(before: Int): EditOutcome {
        val fl = flow()
        val slots = fl.result.slots
        if (before !in 0..slots.size) return EditOutcome.Rejected(RejectReason.NOT_FOUND, "page $before")
        // Insert before the first output page at or after `before` that is a state slot (auto pages aren't).
        val at = (before until slots.size).asSequence().map { state.slots.indexOf(slots[it].slot) }.firstOrNull { it >= 0 }
            ?: state.slots.size
        val newSlots = state.slots.toMutableList().apply { add(at, PageSlot.UserBlank(nextBlankUid++)) }
        return push(state.copy(slots = newSlots))
    }

    /**
     * Deletes output page [page]. Paragraph lines on it become a text edit: a paragraph wholly on the page is
     * emptied, one partly on it keeps the text of its lines elsewhere (so deleted words can't come back on a later
     * edit). Other placed blocks (graphics, units) go to `deletedBlocks`; an Original or UserBlank slot is removed.
     */
    private fun deletePage(page: Int): EditOutcome {
        val fl = flow()
        val slots = fl.result.slots
        if (page !in slots.indices) return EditOutcome.Rejected(RejectReason.NOT_FOUND, "page $page")
        if (slots.size == 1) return EditOutcome.Rejected(RejectReason.LAST_PAGE)

        // Every placed paragraph line, keyed by paragraph, with its output page.
        val lines = LinkedHashMap<ParagraphId, MutableList<Pair<BlockId, Int>>>()
        val deleted = state.deletedBlocks.toMutableSet()
        for (pl in fl.result.placements) {
            val owner = when (val id = pl.id) {
                is BlockId.Edited -> id.paragraph
                is BlockId.Orig -> m.paragraphOfLine[id]?.id
            }
            if (owner != null) lines.getOrPut(owner) { mutableListOf() } += pl.id to pl.slotIndex
            else if (pl.slotIndex == page) deleted += pl.id
        }
        val edits = state.edits.toMutableMap()
        for ((pid, placed) in lines) {
            val (gone, kept) = placed.partition { it.second == page }
            if (gone.isEmpty()) continue
            val keep = remainingSpans(m.byId.getValue(pid), kept.map { it.first })
            if (keep == null) deleted += gone.map { it.first } // remaining text has no drawable plan: hide the lines
            else edits[pid] = keep
        }
        val newSlots = state.slots - slots[page].slot
        return push(EditState(newSlots, edits, deleted))
    }

    /** Spans of the paragraph's [kept] lines, joined with a space; null when they can't be laid out. */
    private fun remainingSpans(p: Paragraph, kept: List<BlockId>): List<Span>? {
        if (kept.isEmpty()) return emptyList()
        val edited = state.edits[p.id]
        val spans = if (edited == null) {
            val order = p.lineBlocks.withIndex().associate { (i, b) -> b to i }
            ParagraphDetector.spansOf(kept.sortedBy { order[it] }.map { m.block(it as BlockId.Orig).runNodes })
        } else {
            val laid = m.lines(p, edited)
            joinLines(kept.map { (it as BlockId.Edited).line }.sorted().map { laid[it] })
        }
        return spans.takeIf { it.isEmpty() || m.plan(it) is FontPlanResult.Ok }
    }

    /** Laid lines back to spans: fragments in order, a space between lines, same-style neighbours merged. */
    private fun joinLines(lines: List<LaidLine>): List<Span> {
        val out = ArrayList<Span>()
        fun append(text: String, style: SpanStyle) {
            val last = out.lastOrNull()
            if (last != null && last.style == style) out[out.lastIndex] = Span(last.text + text, style) else out += Span(text, style)
        }
        for ((i, line) in lines.withIndex()) {
            val frags = line.fragments.filter { it.text.isNotEmpty() }
            if (frags.isEmpty()) continue
            if (i > 0 && out.isNotEmpty() && !out.last().text.endsWith(" ")) append(" ", out.last().style)
            frags.forEach { append(it.text, it.style) }
        }
        return out
    }

    /** The paragraph whose placed lines on output page [page] contain (x, y), with 2pt slack. */
    fun paragraphAt(page: Int, x: Float, y: Float): ParagraphHit? {
        val fl = flow()
        val rects = LinkedHashMap<ParagraphId, MutableList<Bounds>>()
        fun add(id: ParagraphId, b: Bounds) { if (b.isValid) rects.getOrPut(id) { mutableListOf() } += b }
        fun lineBounds(line: LaidLine, spans: List<Span>, dy: Float): Bounds? {
            if (line.fragments.isEmpty()) return null
            val size = lineSize(line, spans)
            val base = line.baseline + dy
            return Bounds(line.fragments.minOf { it.x }, lineBottom(base, size), line.fragments.maxOf { it.x + it.width }, lineTop(base, size))
        }
        for (pl in fl.result.placements) {
            if (pl.slotIndex != page) continue
            when (val id = pl.id) {
                is BlockId.Edited -> {
                    val par = m.byId[id.paragraph] ?: continue
                    val spans = state.edits[par.id] ?: continue
                    val line = m.lines(par, spans).getOrNull(id.line) ?: continue
                    lineBounds(line, spans, 0f)?.let { b ->
                        add(par.id, Bounds(b.minX, pl.top - fl.blocks.getValue(id).height, b.maxX, pl.top))
                    }
                }
                is BlockId.Orig -> {
                    val b = m.block(id)
                    val dy = pl.top - b.top
                    m.paragraphOfLine[id]?.let { add(it.id, Bounds(b.left, b.bottom + dy, b.right, b.top + dy)) }
                    for (par in m.unitParagraphs[id].orEmpty()) {
                        val spans = state.edits[par.id]
                        if (spans != null) m.lines(par, spans).forEach { l -> lineBounds(l, spans, dy)?.let { add(par.id, it) } }
                        else par.runNodes.mapNotNull { it.run }.forEach { r ->
                            add(par.id, Bounds(min(r.originX, r.endX), lineBottom(r.originY + dy, r.effectiveFontSize),
                                max(r.originX, r.endX), lineTop(r.originY + dy, r.effectiveFontSize)))
                        }
                    }
                }
            }
        }
        val hit = rects.entries.firstOrNull { (_, bs) ->
            bs.any { x >= it.minX - HIT_SLACK && x <= it.maxX + HIT_SLACK && y >= it.minY - HIT_SLACK && y <= it.maxY + HIT_SLACK }
        } ?: return null
        val par = m.byId.getValue(hit.key)
        val bounds = Bounds.empty().apply { hit.value.forEach(::includeBounds) }
        return ParagraphHit(
            par.id, currentSpans(par).joinToString("") { it.text }, page, bounds,
            editable = !par.nonLatin, reason = if (par.nonLatin) RejectReason.NON_LATIN else null,
        )
    }

    /** Releases the analysis document. */
    override fun close() = m.doc.close()
}
