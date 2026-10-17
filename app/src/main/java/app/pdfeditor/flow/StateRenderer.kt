package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.DrawNode
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Immutable editing state: page slots (Original/UserBlank only), edited paragraphs' spans, deleted blocks. */
internal data class EditState(
    val slots: List<PageSlot>,
    val edits: Map<ParagraphId, List<Span>>,
    val deletedBlocks: Set<BlockId>,
)

/** Everything derived once from the opened document. */
internal class SessionModel(
    val originalBytes: ByteArray,
    val doc: PDDocument, // analysis document, open for the session
    val analysis: DocumentAnalysis,
    val provider: FontProvider,
) {
    val observed: ObservedEncodings = ObservedEncodings.build(doc)
    val paragraphs: List<Paragraph> = analysis.pages.flatMap { ParagraphDetector.detect(it) }
    val byId: Map<ParagraphId, Paragraph> = paragraphs.associateBy { it.id }

    /** Normal-flow LINE block → its paragraph. */
    val paragraphOfLine = HashMap<BlockId, Paragraph>()

    /** UNIT block → the paragraphs inside it. */
    val unitParagraphs = HashMap<BlockId, MutableList<Paragraph>>()

    init {
        for (p in paragraphs) {
            val first = p.lineBlocks.firstOrNull() as? BlockId.Orig ?: continue
            if (block(first).kind == BlockKind.UNIT) unitParagraphs.getOrPut(first) { mutableListOf() } += p
            else p.lineBlocks.forEach { paragraphOfLine[it] = p }
        }
    }

    fun block(id: BlockId.Orig): Block = analysis.pages[id.page].blocks[id.index]

    private val plans = HashMap<List<Span>, FontPlanResult>()
    private val layouts = HashMap<Pair<ParagraphId, List<Span>>, List<LaidLine>>()

    /** Font plan against the analysis document (measuring and decisions). */
    fun plan(spans: List<Span>): FontPlanResult = plans.getOrPut(spans) { FontResolver.plan(doc, spans, provider, observed) }

    /** Lines of [p] laid out with [spans] at the paragraph's original position; [spans] must have an Ok plan. */
    fun lines(p: Paragraph, spans: List<Span>): List<LaidLine> = layouts.getOrPut(p.id to spans) {
        if (spans.isEmpty()) return@getOrPut emptyList()
        val plan = (plan(spans) as FontPlanResult.Ok).plan
        ParagraphLayout.layout(spans, plan, p.left, p.right, p.firstLineIndent, p.firstBaseline, p.lineAdvance, p.alignment)
    }

    /** Height a paragraph that can't grow may use: its original lines × lineAdvance, or its drawn extent if taller. */
    fun originalHeight(p: Paragraph): Float {
        val runs = p.runNodes.mapNotNull { it.run }
        val extent = if (runs.isEmpty()) 0f
        else runs.maxOf { lineTop(it.originY, it.effectiveFontSize) } - runs.minOf { lineBottom(it.originY, it.effectiveFontSize) }
        return max(p.lineBlocks.size * p.lineAdvance, extent)
    }
}

/** Effective size of a laid line: its largest fragment, or the paragraph's first span for an empty line. */
internal fun lineSize(line: LaidLine, spans: List<Span>): Float =
    line.fragments.maxOfOrNull { it.style.effectiveFontSize } ?: spans.firstOrNull()?.style?.effectiveFontSize ?: 0f

/** The flow of one state: output slots, placements, and what each placed block is. */
internal class StateFlow(
    val state: EditState,
    val result: FlowResult,
    val blocks: Map<BlockId, FlowBlock>,
    val rects: List<PDRectangle>, // media box per output page
)

internal object StateRenderer {
    private const val EPS = 0.01f

    fun flow(m: SessionModel, st: EditState): StateFlow {
        val pages = m.analysis.pages
        val specs = ArrayList<SlotSpec>()
        val rectOf = java.util.IdentityHashMap<SlotSpec, PDRectangle>()
        fun add(spec: SlotSpec, rect: PDRectangle, at: Int = specs.size) { specs.add(at, spec); rectOf[spec] = rect }

        fun originalSpec(p: Int, slot: PageSlot): SlotSpec {
            val pa = pages[p]
            val lowest = pa.blocks.minOfOrNull { it.bottom } ?: pa.bodyLimit
            return SlotSpec(slot, pa.mediaBox.width, pa.mediaBox.height, pa.bodyTop, min(pa.bodyLimit, lowest), pa.pinned != null)
        }
        for ((i, slot) in st.slots.withIndex()) when (slot) {
            is PageSlot.Original -> add(originalSpec(slot.index, slot), pages[slot.index].mediaBox)
            else -> {
                val before = st.slots.subList(0, i).filterIsInstance<PageSlot.Original>().lastOrNull()
                val n = pages[(before ?: st.slots.drop(i + 1).filterIsInstance<PageSlot.Original>().firstOrNull())?.index ?: 0]
                add(SlotSpec(slot, n.mediaBox.width, n.mediaBox.height, n.bodyTop, n.bodyLimit, true), n.mediaBox)
            }
        }

        // Body blocks per original page, reading order.
        val perPage = pages.indices.map { p -> pageBlocks(m, st, p) }
        val blocks = ArrayList<FlowBlock>()
        val byId = HashMap<BlockId, FlowBlock>()
        fun unpinned(s: SlotSpec) = !s.pinned && s.slot !is PageSlot.UserBlank
        for (p in pages.indices) {
            val drafts = perPage[p]
            if (drafts.isEmpty()) continue
            var target = specs.indexOfFirst { it.slot == PageSlot.Original(p) }
            val orphan = target < 0
            if (orphan) {
                // The page was deleted but some of its content sits elsewhere: flow it with a neighbouring page.
                val pos = specs.indexOfFirst { ((it.slot as? PageSlot.Original)?.index ?: -1) > p }.let { if (it < 0) specs.size else it }
                val ghost = PageSlot.Auto(-1 - p)
                target = specs.indexOfFirst { it.slot == ghost }
                if (target < 0) target = (pos - 1 downTo 0).firstOrNull { unpinned(specs[it]) } ?: (pos until specs.size).firstOrNull { unpinned(specs[it]) } ?: -1
                if (target < 0) { add(originalSpec(p, ghost).let { SlotSpec(ghost, it.width, it.height, it.bodyTop, it.bodyLimit, false) }, pages[p].mediaBox, pos); target = pos; shift(blocks, byId, pos) }
            }
            for ((k, d) in drafts.withIndex()) {
                val gap = if (orphan && k == 0) m.analysis.paragraphGap else d.gapBefore
                val fb = FlowBlock(d.id, target, d.height, gap, d.paragraph, d.originalTop, d.lineGap)
                blocks += fb; byId[d.id] = fb
            }
        }

        val result = FlowLayout.layout(specs, blocks, m.analysis.paragraphGap)
        val rects = ArrayList<PDRectangle>()
        for (s in result.slots) rects += rectOf[s] ?: rects.lastOrNull() ?: pages.firstOrNull()?.mediaBox ?: PDRectangle.A4
        return StateFlow(st, result, byId, rects)
    }

    /** Re-targets blocks already assigned to slots at or after [from] after a slot was inserted there. */
    private fun shift(blocks: MutableList<FlowBlock>, byId: MutableMap<BlockId, FlowBlock>, from: Int) {
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b.originSlot >= from) {
                val nb = FlowBlock(b.id, b.originSlot + 1, b.height, b.gapBefore, b.paragraph, b.originalTop, b.lineGap)
                blocks[i] = nb; byId[b.id] = nb
            }
        }
    }

    private class Draft(val id: BlockId, val height: Float, val gapBefore: Float, val paragraph: ParagraphId?, val originalTop: Float, val lineGap: Float)

    private fun pageBlocks(m: SessionModel, st: EditState, p: Int): List<Draft> {
        val out = ArrayList<Draft>()
        for (b in m.analysis.pages[p].blocks) {
            val par = m.paragraphOfLine[b.id]
            val spans = par?.let { st.edits[it.id] }
            if (par != null && spans != null) {
                if (b.id == par.lineBlocks.first()) {
                    m.lines(par, spans).forEachIndexed { i, line ->
                        val id = BlockId.Edited(par.id, i)
                        val h = lineSize(line, spans) // lineTop − lineBottom = 1.0 × size
                        val lineGap = par.lineAdvance - h
                        if (id !in st.deletedBlocks) out += Draft(id, h, if (i == 0) b.gapBefore else lineGap, par.id, b.top - i * par.lineAdvance, lineGap)
                    }
                }
                continue
            }
            if (b.id in st.deletedBlocks) continue
            out += Draft(b.id, b.top - b.bottom, b.gapBefore, par?.id, b.top, b.gapBefore)
        }
        return out
    }

    fun render(m: SessionModel, fl: StateFlow): ByteArray = PDDocument.load(m.originalBytes).use { out ->
        if (out.isEncrypted) out.isAllSecurityToBeRemoved = true
        val st = fl.state
        val orig = out.pages.toList()
        val removed = HashMap<Int, MutableList<DrawNode>>() // original page index → nodes to take out
        val draws = HashMap<Int, MutableList<Draw>>() // output index → draws
        fun remove(page: Int, nodes: List<DrawNode>) { removed.getOrPut(page) { mutableListOf() } += nodes }
        fun draw(o: Int, d: Draw) { draws.getOrPut(o) { mutableListOf() } += d }

        for (id in st.deletedBlocks) if (id is BlockId.Orig) m.block(id).let { remove(it.originPage, it.runNodes + it.leafNodes) }
        for (pid in st.edits.keys) remove(pid.page, m.byId.getValue(pid).runNodes)

        // Render-time plans bound to the output document's fonts (same spans → same decisions).
        // The session's observed codes, shared with this load's fonts through their object keys.
        // A direct (unkeyed) font object can't be matched across loads: then this document is scanned once.
        var observedOut: ObservedEncodings? = null
        val plans = HashMap<ParagraphId, FontPlan>()
        fun planFor(par: Paragraph, spans: List<Span>): FontPlan = plans.getOrPut(par.id) {
            val res = orig[par.id.page].resources
            var unkeyed = false
            val bound = spans.map { s ->
                val f = try { res?.getFont(COSName.getPDFName(s.style.fontResourceName)) } catch (_: Exception) { null }
                val key = ObservedEncodings.fontKey(res, s.style.fontResourceName)
                if (f != null && key != null) m.observed.bind(f, key) else if (f != null) unkeyed = true
                Span(s.text, s.style.copy().also { it.font = f ?: s.style.font })
            }
            val obs = if (!unkeyed) m.observed else observedOut ?: ObservedEncodings.build(out).also { observedOut = it }
            when (val r = FontResolver.plan(out, bound, m.provider, obs)) {
                is FontPlanResult.Ok -> r.plan
                is FontPlanResult.Unsupported -> error("Font plan changed at render time: ${r.chars}")
            }
        }
        fun unitLines(unit: BlockId, o: Int, dy: Float) {
            for (par in m.unitParagraphs[unit].orEmpty()) {
                val spans = st.edits[par.id] ?: continue
                val plan = if (spans.isEmpty()) continue else planFor(par, spans)
                m.lines(par, spans).forEach { draw(o, Draw.NewLine(LaidLine(it.fragments, it.baseline + dy), plan, orig[par.id.page])) }
            }
        }

        val outIndexOf = HashMap<Int, Int>()
        fl.result.slots.forEachIndexed { o, s -> (s.slot as? PageSlot.Original)?.let { outIndexOf[it.index] = o } }
        for (pl in fl.result.placements) when (val id = pl.id) {
            is BlockId.Orig -> {
                val b = m.block(id)
                val dy = pl.top - b.top
                if (outIndexOf[b.originPage] == pl.slotIndex && abs(dy) < EPS) { unitLines(id, pl.slotIndex, 0f); continue }
                remove(b.originPage, b.runNodes + b.leafNodes)
                val exclude = m.unitParagraphs[id].orEmpty().filter { it.id in st.edits }.flatMap { it.runNodes }.toSet()
                draw(pl.slotIndex, Draw.MoveBlock(b, orig[b.originPage], dy, exclude))
                unitLines(id, pl.slotIndex, dy)
            }
            is BlockId.Edited -> {
                val par = m.byId.getValue(id.paragraph)
                val spans = st.edits.getValue(par.id)
                val line = m.lines(par, spans)[id.line]
                val laid = LaidLine(line.fragments, pl.top - 0.75f * lineSize(line, spans))
                draw(pl.slotIndex, Draw.NewLine(laid, planFor(par, spans), orig[par.id.page]))
            }
        }

        // Page tree in slot order.
        val outputPages = fl.result.slots.mapIndexed { o, s ->
            (s.slot as? PageSlot.Original)?.let { orig[it.index] }
                ?: fl.rects[o].let { r -> PDPage(PDRectangle(r.lowerLeftX, r.lowerLeftY, r.width, r.height)) }
        }
        val sameTree = outputPages.size == orig.size && outputPages.indices.all { outputPages[it] === orig[it] }
        if (!sameTree) {
            orig.forEach(::materialize)
            orig.forEach { out.pages.remove(it) }
            outputPages.forEach { out.addPage(it) }
        }

        // Write only pages with removed nodes or draws.
        val jobs = outputPages.indices.mapNotNull { o ->
            val p = (fl.result.slots[o].slot as? PageSlot.Original)?.index
            val rem = p?.let { removed[it] }.orEmpty()
            val ds = draws[o].orEmpty()
            if (rem.isEmpty() && ds.isEmpty()) null
            else Triple(o, p?.let { ContentStreamEngine.parse(orig[it]).tokens }, rem to ds)
        }
        for ((o, tokens, work) in jobs) PageWriter.write(out, outputPages[o], tokens, work.first, work.second)

        // Deleted original pages may still be referenced (outlines, structure tree, form widgets): scrub them.
        if (!sameTree) {
            val kept: MutableSet<PDPage> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
            kept += outputPages
            DeletedPages.scrub(out, orig.filter { it !in kept })
        }

        ByteArrayOutputStream().also { out.save(it) }.toByteArray()
    }

    /** Copies inheritable attributes onto the page so it keeps them once detached from its parent node. */
    private fun materialize(page: PDPage) {
        val dict = page.cosObject
        for (key in listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)) {
            if (dict.containsKey(key)) continue
            var node = dict.getDictionaryObject(COSName.PARENT) as? COSDictionary
            while (node != null) {
                val v = node.getItem(key)
                if (v != null) { dict.setItem(key, v); break }
                node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            }
        }
    }
}
