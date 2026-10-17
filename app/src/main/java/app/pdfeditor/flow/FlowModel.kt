package app.pdfeditor.flow

import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.ParsedPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

/** Identifies a paragraph by its page and the index of its first LINE block (used by Task 4). */
data class ParagraphId(val page: Int, val firstBlock: Int)

sealed class BlockId {
    data class Orig(val page: Int, val index: Int) : BlockId()
    data class Edited(val paragraph: ParagraphId, val line: Int) : BlockId()
}

enum class BlockKind { LINE, GRAPHIC, UNIT }

class Block(
    val id: BlockId,
    val kind: BlockKind,
    val originPage: Int,
    val top: Float, // page user space, y up
    val bottom: Float,
    val left: Float,
    val right: Float,
    val gapBefore: Float, // previous block's bottom − this top, in reading order on the page (0 for first)
    val runNodes: List<DrawNode>, // text-run nodes drawn by this block
    val leafNodes: List<DrawNode>, // path/image/form leaves drawn by this block
)

enum class PinReason { SCANNED_TEXT, TEXT_IN_FORM, ROTATED, OVERSIZED_UNIT }

class PageAnalysis(
    val pageIndex: Int,
    val mediaBox: PDRectangle,
    val parsed: ParsedPage,
    val bodyTop: Float,
    val bodyLimit: Float,
    val blocks: List<Block>, // body blocks, reading order (top→bottom)
    val fixedNodes: List<DrawNode>, // headers/footers/backgrounds: never moved
    val pinned: PinReason?,
)

class DocumentAnalysis(val pages: List<PageAnalysis>, val paragraphGap: Float, val medianBodyTop: Float)

/** Shared text-line metric (global constraint): ascent 0.75 × size above the baseline, descent 0.25 × size below. */
fun lineTop(baseline: Float, size: Float) = baseline + 0.75f * size
fun lineBottom(baseline: Float, size: Float) = baseline - 0.25f * size
