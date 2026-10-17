package app.pdfeditor.flow

sealed class PageSlot {
    data class Original(val index: Int) : PageSlot()
    data class UserBlank(val uid: Int) : PageSlot()
    data class Auto(val n: Int) : PageSlot()
}

/** One output page. [bodyTop]/[bodyLimit] bound the flowed body (y up). UserBlank is always pinned. */
class SlotSpec(
    val slot: PageSlot,
    val width: Float,
    val height: Float,
    val bodyTop: Float,
    val bodyLimit: Float,
    val pinned: Boolean,
)

/**
 * A body block in reading order. [originSlot] indexes the INPUT slots list; the block occupies [top − height, top].
 * [gapBefore] is the original gap to the previous block on its page; [lineGap] is used instead when the block follows
 * a line of its own paragraph that didn't come from the same origin slot.
 */
class FlowBlock(
    val id: BlockId,
    val originSlot: Int,
    val height: Float,
    val gapBefore: Float,
    val paragraph: ParagraphId?,
    val originalTop: Float,
    val lineGap: Float = gapBefore,
)

/** [slotIndex] indexes [FlowResult.slots]. */
class Placement(val id: BlockId, val slotIndex: Int, val top: Float)

class FlowResult(val slots: List<SlotSpec>, val placements: List<Placement>)

/**
 * Word-style flow of body blocks across pages. Pure and deterministic: an unedited document reproduces its original
 * positions; overflow continues on the next unpinned slot (or a fresh [PageSlot.Auto] slot inserted before a pinned
 * slot / at the end); pinned slots keep their own blocks at their original tops and accept nothing carried.
 */
object FlowLayout {
    /** Tolerance for float noise when an original block ends exactly at bodyLimit. */
    private const val EPS = 1e-3f

    fun layout(slots: List<SlotSpec>, blocks: List<FlowBlock>, paragraphGap: Float): FlowResult {
        val own = slots.indices.map { mutableListOf<FlowBlock>() }
        for (b in blocks) {
            require(b.originSlot in slots.indices) { "originSlot ${b.originSlot} out of range" }
            own[b.originSlot] += b
        }
        val out = mutableListOf<SlotSpec>()
        val placements = mutableListOf<Placement>()
        var autoCount = 0
        var carry: List<FlowBlock> = emptyList()

        for ((i, spec) in slots.withIndex()) {
            val outIndex = out.size
            out += spec
            if (spec.pinned || spec.slot is PageSlot.UserBlank) {
                check(carry.isEmpty())
                own[i].forEach { placements += Placement(it.id, outIndex, it.originalTop) }
                continue
            }
            var queue = carry + own[i]
            var current = spec
            var currentIndex = outIndex
            var currentInput: Int? = i
            while (true) {
                val rest = fill(current, currentIndex, currentInput, queue, paragraphGap, placements)
                if (rest.isEmpty()) { carry = emptyList(); break }
                val next = slots.getOrNull(i + 1)
                if (next != null && !next.pinned && next.slot !is PageSlot.UserBlank) { carry = rest; break }
                current = SlotSpec(PageSlot.Auto(autoCount++), current.width, current.height, current.bodyTop, current.bodyLimit, false)
                currentIndex = out.size
                currentInput = null
                out += current
                queue = rest
            }
        }
        check(carry.isEmpty())
        return FlowResult(out, placements)
    }

    /** Places blocks from [queue] onto one slot; returns the blocks that must move on. */
    private fun fill(
        spec: SlotSpec, outIndex: Int, inputIndex: Int?, queue: List<FlowBlock>, paragraphGap: Float,
        placements: MutableList<Placement>,
    ): List<FlowBlock> {
        var prev: FlowBlock? = null
        var prevBottom = 0f
        for ((k, b) in queue.withIndex()) {
            val top = if (prev == null) spec.bodyTop else prevBottom - gap(b, prev, inputIndex, paragraphGap)
            if (prev != null && top - b.height < spec.bodyLimit - EPS) return queue.subList(k, queue.size)
            placements += Placement(b.id, outIndex, top)
            prev = b
            prevBottom = top - b.height
        }
        return emptyList()
    }

    private fun gap(b: FlowBlock, prev: FlowBlock, inputIndex: Int?, paragraphGap: Float): Float = when {
        b.originSlot == inputIndex && prev.originSlot == inputIndex -> b.gapBefore
        prev.paragraph != null && prev.paragraph == b.paragraph -> b.lineGap
        else -> paragraphGap
    }
}
