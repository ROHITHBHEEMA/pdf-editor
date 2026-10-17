package app.pdfeditor.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FlowLayoutTest {
    private val paragraphGap = 12f

    private fun slot(i: Int, pinned: Boolean = false) = SlotSpec(PageSlot.Original(i), 600f, 800f, 700f, 100f, pinned)
    private fun blank(uid: Int) = SlotSpec(PageSlot.UserBlank(uid), 600f, 800f, 700f, 100f, true)

    /** [count] blocks of height 20, gap 5, originally laid out top-down from 700 on [originSlot]. */
    private fun blocks(originSlot: Int, count: Int, startIndex: Int = 0, paragraph: ParagraphId? = null) =
        (0 until count).map { k ->
            FlowBlock(
                BlockId.Orig(originSlot, startIndex + k), originSlot, 20f, if (k == 0) 0f else 5f, paragraph,
                originalTop = 700f - 25f * k,
            )
        }

    private fun FlowResult.of(id: BlockId) = placements.single { it.id == id }
    private fun FlowResult.on(slotIndex: Int) = placements.filter { it.slotIndex == slotIndex }

    @Test fun unchangedInputReproducesOriginalTops() {
        val input = blocks(0, 10) + blocks(1, 5)
        val r = FlowLayout.layout(listOf(slot(0), slot(1)), input, paragraphGap)
        assertEquals(2, r.slots.size)
        assertEquals(15, r.placements.size)
        for (b in input) {
            val p = r.of(b.id)
            assertEquals(b.originSlot, p.slotIndex)
            assertEquals(b.originalTop, p.top, 0.0001f)
        }
        assertEquals(listOf(700f, 675f, 650f), r.on(0).take(3).map { it.top })
    }

    @Test fun overflowMovesToNextOriginalPage() {
        // 24 blocks fit in [100, 700]: block 23 has top 125, bottom 105; block 24 would end at 80.
        val input = blocks(0, 30) + blocks(1, 3)
        val r = FlowLayout.layout(listOf(slot(0), slot(1)), input, paragraphGap)
        assertEquals(2, r.slots.size)
        assertEquals(24, r.on(0).size)
        val onSlot1 = r.on(1)
        assertEquals((24 until 30).map { BlockId.Orig(0, it) } + (0 until 3).map { BlockId.Orig(1, it) }, onSlot1.map { it.id })
        assertEquals(700f, onSlot1[0].top, 0f)
        // carried blocks are off their origin slot and in no paragraph → paragraphGap.
        assertEquals(700f - 20f - paragraphGap, onSlot1[1].top, 0.0001f)
        // slot1's first own block follows a carried block → paragraphGap; its successor uses its own gapBefore.
        val own0 = r.of(BlockId.Orig(1, 0))
        val own1 = r.of(BlockId.Orig(1, 1))
        assertEquals(own0.top - 20f - 5f, own1.top, 0.0001f)
    }

    @Test fun overflowFromLastPageCreatesAutoPage() {
        val r = FlowLayout.layout(listOf(slot(0)), blocks(0, 30), paragraphGap)
        assertEquals(2, r.slots.size)
        assertTrue(r.slots.last().slot is PageSlot.Auto)
        assertEquals(PageSlot.Auto(0), r.slots.last().slot)
        assertEquals(6, r.on(1).size)
        assertEquals(700f, r.on(1)[0].top, 0f)
        val auto = r.slots.last()
        assertEquals(600f, auto.width, 0f); assertEquals(800f, auto.height, 0f)
        assertEquals(700f, auto.bodyTop, 0f); assertEquals(100f, auto.bodyLimit, 0f)
        assertEquals(false, auto.pinned)
    }

    @Test fun shrinkingBringsCarriedBlocksBackAndDropsAuto() {
        val grown = FlowLayout.layout(listOf(slot(0)), blocks(0, 30), paragraphGap)
        assertEquals(2, grown.slots.size)
        val r = FlowLayout.layout(listOf(slot(0)), blocks(0, 20), paragraphGap)
        assertEquals(1, r.slots.size)
        assertTrue(r.slots.none { it.slot is PageSlot.Auto })
        assertEquals(20, r.on(0).size)
    }

    @Test fun neverMovesToEarlierPage() {
        val input = blocks(0, 2) + blocks(1, 5)
        val r = FlowLayout.layout(listOf(slot(0), slot(1)), input, paragraphGap)
        assertEquals(2, r.on(0).size)
        assertEquals(5, r.on(1).size)
        assertTrue(r.on(1).all { it.id is BlockId.Orig && (it.id as BlockId.Orig).page == 1 })
    }

    @Test fun userBlankPageStaysBlank() {
        val input = blocks(0, 30) + blocks(2, 3) // slot(1) is input index 2 here
        val r = FlowLayout.layout(listOf(slot(0), blank(7), slot(1)), input, paragraphGap)
        assertEquals(listOf(PageSlot.Original(0), PageSlot.Auto(0), PageSlot.UserBlank(7), PageSlot.Original(1)), r.slots.map { it.slot })
        assertEquals(6, r.on(1).size)
        assertTrue(r.on(2).isEmpty())
        assertEquals(3, r.on(3).size)
        assertEquals(700f, r.on(3)[0].top, 0f)
    }

    @Test fun pinnedPageGetsAutoBeforeIt() {
        val own = blocks(1, 3).mapIndexed { k, b ->
            FlowBlock(b.id, 1, 20f, 5f, null, originalTop = 650f - 40f * k) // deliberately not on the flow grid
        }
        val r = FlowLayout.layout(listOf(slot(0), slot(1, pinned = true)), blocks(0, 30) + own, paragraphGap)
        assertEquals(listOf(PageSlot.Original(0), PageSlot.Auto(0), PageSlot.Original(1)), r.slots.map { it.slot })
        assertEquals(6, r.on(1).size)
        assertEquals(own.map { it.id }, r.on(2).map { it.id })
        assertEquals(own.map { it.originalTop }, r.on(2).map { it.top })
    }

    @Test fun oversizedFirstBlockIsPlacedAnyway() {
        val big = FlowBlock(BlockId.Orig(0, 0), 0, 900f, 0f, null, originalTop = 700f)
        val after = FlowBlock(BlockId.Orig(0, 1), 0, 20f, 5f, null, originalTop = -205f)
        val r = FlowLayout.layout(listOf(slot(0)), listOf(big, after), paragraphGap)
        assertEquals(0, r.of(big.id).slotIndex)
        assertEquals(700f, r.of(big.id).top, 0f)
        assertEquals(1, r.of(after.id).slotIndex)
        assertEquals(700f, r.of(after.id).top, 0f)
        assertEquals(2, r.slots.size)

        // A big block that isn't first moves on, then is placed as first on the next slot.
        val small = FlowBlock(BlockId.Orig(0, 0), 0, 20f, 0f, null, originalTop = 700f)
        val big2 = FlowBlock(BlockId.Orig(0, 1), 0, 900f, 5f, null, originalTop = 675f)
        val r2 = FlowLayout.layout(listOf(slot(0)), listOf(small, big2), paragraphGap)
        assertEquals(1, r2.of(big2.id).slotIndex)
        assertEquals(700f, r2.of(big2.id).top, 0f)
        assertEquals(2, r2.slots.size)
    }

    @Test fun paragraphSplitAcrossPagesUsesLineGapOnRejoin() {
        val p = ParagraphId(0, 22)
        val lead = blocks(0, 22)
        // 4 paragraph lines with line gap 3: lines 0,1 fit (tops 150 and 127 → bottom 107), lines 2,3 carried.
        val para = (0 until 4).map { k ->
            FlowBlock(BlockId.Edited(p, k), 0, 20f, if (k == 0) 5f else 3f, p, originalTop = 150f - 23f * k, lineGap = 3f)
        }
        // slot1 opens with the paragraph's continuation (originally split across pages), then a new paragraph.
        val cont = FlowBlock(BlockId.Orig(1, 0), 1, 20f, 0f, p, originalTop = 700f, lineGap = 3f)
        val next = FlowBlock(BlockId.Orig(1, 1), 1, 20f, 9f, null, originalTop = 671f)
        val r = FlowLayout.layout(listOf(slot(0), slot(1)), lead + para + cont + next, paragraphGap)
        assertEquals(0, r.of(para[1].id).slotIndex)
        val s1 = r.on(1)
        assertEquals(listOf(para[2].id, para[3].id, cont.id, next.id), s1.map { it.id })
        assertEquals(700f, s1[0].top, 0f)
        assertEquals(700f - 23f, s1[1].top, 0.0001f) // same paragraph off origin → line gap
        assertEquals(700f - 46f, s1[2].top, 0.0001f) // on origin, predecessor from other slot, same paragraph → line gap
        assertEquals(700f - 66f - 9f, s1[3].top, 0.0001f) // on origin, predecessor same origin → own gapBefore
    }

    @Test fun raisedBodyLimitMovesNonFirstBlockOn() {
        // A footer raised bodyLimit above the original bottom of the last block.
        val raised = SlotSpec(PageSlot.Original(0), 600f, 800f, 700f, 640f, false)
        val r = FlowLayout.layout(listOf(raised), blocks(0, 3), paragraphGap)
        // block0: 700..680, block1: 675..655, block2: 650..630 < 640 → moves.
        assertEquals(2, r.on(0).size)
        assertEquals(PageSlot.Auto(0), r.slots[1].slot)
        assertEquals(640f, r.slots[1].bodyLimit, 0f)
        assertEquals(1, r.on(1).size)
    }

    @Test fun autoCounterRunsAcrossCall() {
        val input = blocks(0, 30) + blocks(1, 30)
        val r = FlowLayout.layout(listOf(slot(0), slot(1, pinned = false)), input, paragraphGap)
        // slot0 overflows into slot1 (6 carried + 30 own = 36 → 24 + 12), then Auto(0).
        assertEquals(listOf(PageSlot.Original(0), PageSlot.Original(1), PageSlot.Auto(0)), r.slots.map { it.slot })
        val r2 = FlowLayout.layout(listOf(slot(0), slot(1, pinned = true), slot(2)), blocks(0, 30) + blocks(1, 2) + blocks(2, 30), paragraphGap)
        assertEquals(
            listOf(PageSlot.Original(0), PageSlot.Auto(0), PageSlot.Original(1), PageSlot.Original(2), PageSlot.Auto(1)),
            r2.slots.map { it.slot },
        )
    }

    @Test fun randomInputsKeepInvariants() {
        val rnd = Random(20261017)
        repeat(500) { iter ->
            val slotCount = rnd.nextInt(1, 5)
            val slots = (0 until slotCount).map { i ->
                when {
                    rnd.nextInt(6) == 0 -> SlotSpec(PageSlot.UserBlank(i), 600f, 800f, 700f, 100f, true)
                    else -> {
                        val bodyTop = 600f + rnd.nextInt(0, 150)
                        SlotSpec(PageSlot.Original(i), 600f, 800f, bodyTop, rnd.nextInt(50, 300).toFloat(), rnd.nextInt(5) == 0)
                    }
                }
            }
            val blocks = mutableListOf<FlowBlock>()
            for (s in slots.indices) {
                if (slots[s].slot is PageSlot.UserBlank) continue
                var top = slots[s].bodyTop
                repeat(rnd.nextInt(0, 40)) { k ->
                    val h = if (rnd.nextInt(30) == 0) rnd.nextInt(400, 900).toFloat() else rnd.nextInt(5, 40).toFloat()
                    val gap = if (k == 0) 0f else rnd.nextInt(0, 15).toFloat()
                    top -= gap
                    val para = if (rnd.nextBoolean()) ParagraphId(s, k / 3) else null
                    blocks += FlowBlock(BlockId.Orig(s, k), s, h, gap, para, top, lineGap = rnd.nextInt(0, 6).toFloat())
                    top -= h
                }
            }
            val r = FlowLayout.layout(slots, blocks, paragraphGap)
            val msg = "iteration $iter"
            assertEquals(msg, blocks.map { it.id }.toSet(), r.placements.map { it.id }.toSet())
            assertEquals(msg, blocks.size, r.placements.size)
            // Map output slot index back to input slot index (Auto → index of the last input slot before it).
            var inputIdx = -1
            val outToInput = r.slots.map { if (it.slot is PageSlot.Auto) inputIdx else { inputIdx++; inputIdx } }
            assertEquals(msg, slots.size, inputIdx + 1)
            assertEquals(msg, slots, r.slots.filter { it.slot !is PageSlot.Auto })
            val byId = blocks.associateBy { it.id }
            for ((slotIndex, ps) in r.placements.groupBy { it.slotIndex }) {
                val spec = r.slots[slotIndex]
                ps.forEachIndexed { i, p ->
                    val b = byId.getValue(p.id)
                    assertTrue(msg, outToInput[slotIndex] >= b.originSlot)
                    if (spec.pinned) {
                        assertEquals(msg, b.originSlot, outToInput[slotIndex])
                        assertEquals(msg, b.originalTop, p.top, 0f)
                    } else if (i > 0) {
                        assertTrue(msg, p.top - b.height >= spec.bodyLimit - 0.01f)
                    }
                }
            }
            assertTrue(msg, r.slots.indices.filter { r.slots[it].slot is PageSlot.Auto }.all { i -> r.on(i).isNotEmpty() })
        }
    }
}
