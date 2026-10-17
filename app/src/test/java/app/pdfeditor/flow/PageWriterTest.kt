package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.DrawNode
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.engine.TextRunInfo
import app.pdfeditor.testing.PdfFixtures
import app.pdfeditor.testing.extractText
import app.pdfeditor.testing.miniPdf
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PageWriterTest {

    private fun save(doc: PDDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    private fun runNodes(page: PDPage): List<DrawNode> =
        ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.TEXT }.flatMap { it.children }.filter { it.run != null }

    private fun runs(page: PDPage): List<TextRunInfo> = runNodes(page).map { it.run!! }

    private fun runsOf(bytes: ByteArray, pageIndex: Int = 0): List<TextRunInfo> =
        PDDocument.load(bytes).use { runs(it.getPage(pageIndex)) }

    private fun tokStr(t: Any): String = when (t) {
        is COSNumber -> t.floatValue().toString()
        is COSName -> "/" + t.name
        is Operator -> t.name
        else -> t.toString()
    }

    private fun lineBlock(nodes: List<DrawNode>, leaves: List<DrawNode> = emptyList()) =
        Block(BlockId.Orig(0, 0), BlockKind.LINE, 0, 0f, 0f, 0f, 0f, 0f, nodes, leaves)

    /** Two pages, each with one font under its own resource name. */
    private fun twoPageDoc(name0: String, font0: PDFont, name1: String, font1: PDFont): ByteArray = PDDocument().use { doc ->
        fun page(name: String, font: PDFont, content: String) {
            val p = PDPage(PDRectangle.LETTER)
            val res = PDResources()
            res.put(COSName.getPDFName(name), font)
            p.resources = res
            val s = PDStream(doc)
            s.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
            p.setContents(s)
            doc.addPage(p)
        }
        page(name0, font0, "BT /$name0 12 Tf 72 700 Td (Moved line) Tj ET\n")
        page(name1, font1, "BT /$name1 12 Tf 72 700 Td (Body text) Tj ET\n")
        save(doc)
    }

    @Test fun moveLineDownShiftsBaseline() {
        val bytes = PdfFixtures.letter(listOf("Hello world", "Second paragraph here"))
        val (old, saved) = PDDocument.load(bytes).use { doc ->
            val a = DocumentAnalyzer.analyze(doc)
            val page = doc.getPage(0)
            val block = a.pages[0].blocks[0]
            val old = block.runNodes[0].run!!
            PageWriter.write(doc, page, a.pages[0].parsed.tokens, block.runNodes, listOf(Draw.MoveBlock(block, page, -30f)))
            old to save(doc)
        }
        val moved = runsOf(saved).filter { it.text == old.text }
        assertEquals(1, moved.size)
        assertEquals(old.originY - 30f, moved[0].originY, 0.01f)
        assertEquals(old.originX, moved[0].originX, 0.01f)
        assertEquals(old.endX, moved[0].endX, 0.01f)
        assertTrue(runsOf(saved).any { it.text == "Second paragraph here" })
    }

    @Test fun movedTextKeepsFontAndColour() {
        val bytes = miniPdf("BT /DeviceRGB cs 1 0 0 scn /F1 12 Tf 72 700 Td (Red line) Tj ET\n")
        val (old, saved) = PDDocument.load(bytes).use { doc ->
            val a = DocumentAnalyzer.analyze(doc)
            val page = doc.getPage(0)
            val block = a.pages[0].blocks[0]
            PageWriter.write(doc, page, a.pages[0].parsed.tokens, block.runNodes, listOf(Draw.MoveBlock(block, page, -30f)))
            block.runNodes[0].run!! to save(doc)
        }
        val r = runsOf(saved).single { it.text == "Red line" }
        assertEquals(old.fillColorTokens.map(::tokStr), r.fillColorTokens.map(::tokStr))
        assertEquals(12f, r.fontSize, 0f)
        assertEquals(old.fontResourceName, r.fontResourceName)
        assertEquals(700f - 30f, r.originY, 0.01f)
    }

    @Test fun moveLineToOtherPageCopiesFontResource() {
        val bytes = twoPageDoc("FA", PDType1Font.HELVETICA, "FB", PDType1Font.TIMES_ROMAN)
        val saved = PDDocument.load(bytes).use { doc ->
            val p0 = doc.getPage(0); val p1 = doc.getPage(1)
            val t0 = ContentStreamEngine.parse(p0).tokens; val t1 = ContentStreamEngine.parse(p1).tokens
            val block = lineBlock(runNodes(p0))
            // Source page rewritten first: the move must still use its original tokens.
            PageWriter.write(doc, p0, t0, block.runNodes, emptyList())
            PageWriter.write(doc, p1, t1, emptyList(), listOf(Draw.MoveBlock(block, p0, -100f)))
            save(doc)
        }
        assertTrue(extractText(saved, page = 2).contains("Moved line"))
        assertTrue(extractText(saved, page = 2).contains("Body text"))
        assertFalse(extractText(saved, page = 1).contains("Moved line"))
        val moved = runsOf(saved, 1).single { it.text == "Moved line" }
        assertEquals("FA", moved.fontResourceName)
        assertEquals("Helvetica", moved.font!!.name)
        assertEquals(600f, moved.originY, 0.01f)
    }

    @Test fun moveToOtherPageRenamesConflictingFont() {
        val bytes = twoPageDoc("F1", PDType1Font.HELVETICA, "F1", PDType1Font.TIMES_ROMAN)
        val saved = PDDocument.load(bytes).use { doc ->
            val block = lineBlock(runNodes(doc.getPage(0)))
            val t1 = ContentStreamEngine.parse(doc.getPage(1)).tokens
            PageWriter.write(doc, doc.getPage(1), t1, emptyList(), listOf(Draw.MoveBlock(block, doc.getPage(0), -100f)))
            save(doc)
        }
        val rs = runsOf(saved, 1)
        val moved = rs.single { it.text == "Moved line" }
        assertNotEquals("F1", moved.fontResourceName)
        assertEquals("Helvetica", moved.font!!.name)
        assertEquals("Times-Roman", rs.single { it.text == "Body text" }.font!!.name)
    }

    @Test fun moveToOtherPageCopiesColourSpace() {
        val bytes = PDDocument().use { doc ->
            fun page(content: String, cs: Boolean) {
                val p = PDPage(PDRectangle.LETTER)
                val res = PDResources()
                res.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
                if (cs) {
                    val cal = COSArray()
                    cal.add(COSName.getPDFName("CalRGB"))
                    cal.add(COSDictionary().apply {
                        setItem(COSName.getPDFName("WhitePoint"), COSArray().apply {
                            add(COSFloat(0.9505f)); add(COSFloat(1f)); add(COSFloat(1.089f))
                        })
                    })
                    val csDict = COSDictionary().apply { setItem(COSName.getPDFName("CS0"), cal) }
                    res.cosObject.setItem(COSName.COLORSPACE, csDict)
                }
                p.resources = res
                val s = PDStream(doc)
                s.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
                p.setContents(s)
                doc.addPage(p)
            }
            page("BT /CS0 cs 1 0 0 scn /F1 12 Tf 72 700 Td (Tinted) Tj ET\n", true)
            page("BT /F1 12 Tf 72 700 Td (Body) Tj ET\n", false)
            save(doc)
        }
        val saved = PDDocument.load(bytes).use { doc ->
            val block = lineBlock(runNodes(doc.getPage(0)))
            PageWriter.write(doc, doc.getPage(1), ContentStreamEngine.parse(doc.getPage(1)).tokens, emptyList(),
                listOf(Draw.MoveBlock(block, doc.getPage(0), -100f)))
            save(doc)
        }
        PDDocument.load(saved).use { doc ->
            val page = doc.getPage(1)
            assertTrue(page.resources.colorSpaceNames.any { it.name == "CS0" })
            val r = runs(page).single { it.text == "Tinted" }
            assertEquals(listOf("/CS0", "cs", "1.0", "0.0", "0.0", "scn"), r.fillColorTokens.map(::tokStr))
        }
    }

    @Test fun removedTextIsGoneFromExtraction() {
        val saved = PDDocument.load(PdfFixtures.sampleLetter()).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            val node = runNodes(page).single { it.run!!.text.contains("Octobr") }
            PageWriter.write(doc, page, parsed.tokens, listOf(node), emptyList())
            save(doc)
        }
        val text = extractText(saved)
        assertFalse(text.contains("Octobr"))
        assertTrue(text.isNotBlank())
        assertEquals(runsOf(PdfFixtures.sampleLetter()).size - 1, runsOf(saved).size)
    }

    @Test fun tickQuoteRemovalKeepsFollowingLinesInPlace() {
        val bytes = miniPdf("BT /F1 10 Tf 12 TL 72 700 Td (a) Tj (b) ' (c) ' ET\n")
        val oldC = runsOf(bytes).single { it.text == "c" }
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            PageWriter.write(doc, page, parsed.tokens, listOf(runNodes(page).single { it.run!!.text == "b" }), emptyList())
            save(doc)
        }
        val rs = runsOf(saved)
        assertTrue(rs.none { it.text == "b" })
        assertEquals(oldC.originY, rs.single { it.text == "c" }.originY, 0.001f)
        assertEquals(676f, rs.single { it.text == "c" }.originY, 0.001f)
    }

    @Test fun doubleQuoteRemovalKeepsSpacingAndPosition() {
        val bytes = miniPdf("BT /F1 10 Tf 12 TL 72 700 Td (a) Tj 3 1 (b) \" (c) ' ET\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            PageWriter.write(doc, page, parsed.tokens, listOf(runNodes(page).single { it.run!!.text == "b" }), emptyList())
            save(doc)
        }
        val c = runsOf(saved).single { it.text == "c" }
        assertEquals(676f, c.originY, 0.001f)
        assertEquals(3f, c.state.wordSpacing, 0f)
        assertEquals(1f, c.state.charSpacing, 0f)
        assertTrue(runsOf(saved).none { it.text == "b" })
    }

    @Test fun movedTickQuoteRunIsRedrawnWithTj() {
        val bytes = miniPdf("BT /F1 10 Tf 12 TL 72 700 Td (a) Tj (b) ' ET\n")
        val oldB = runsOf(bytes).single { it.text == "b" }
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            val node = runNodes(page).single { it.run!!.text == "b" }
            PageWriter.write(doc, page, parsed.tokens, listOf(node), listOf(Draw.MoveBlock(lineBlock(listOf(node)), page, -30f)))
            save(doc)
        }
        val b = runsOf(saved).single { it.text == "b" }
        assertEquals(oldB.originY - 30f, b.originY, 0.001f)
        assertEquals("Tj", b.showOperator)
    }

    @Test fun moveBlockSkipsExcludedRuns() {
        val bytes = miniPdf("BT /F1 10 Tf 72 700 Td (left) Tj ET BT /F1 10 Tf 300 700 Td (right) Tj ET\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            val nodes = runNodes(page)
            val right = nodes.single { it.run!!.text == "right" }
            PageWriter.write(doc, page, parsed.tokens, nodes, listOf(Draw.MoveBlock(lineBlock(nodes), page, -30f, exclude = setOf(right))))
            save(doc)
        }
        val rs = runsOf(saved)
        assertEquals(670f, rs.single { it.text == "left" }.originY, 0.001f)
        assertTrue(rs.none { it.text == "right" })
    }

    @Test fun movedPathShifts() {
        val bytes = miniPdf("0 0 1 rg 72 600 100 20 re f\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            val path = parsed.leaves.single { it.kind == NodeKind.PATH }
            PageWriter.write(doc, page, parsed.tokens, listOf(path), listOf(Draw.MoveBlock(lineBlock(emptyList(), listOf(path)), page, -30f)))
            save(doc)
        }
        val paths = PDDocument.load(saved).use { d -> ContentStreamEngine.parse(d.getPage(0)).leaves.filter { it.kind == NodeKind.PATH } }
        assertEquals(1, paths.size)
        assertEquals(570f, paths[0].bounds!!.minY, 0.01f)
    }

    @Test fun movedUnderlineKeepsStrokeWidthAndColour() {
        val bytes = miniPdf("q 3 w 1 0 0 RG 72 600 m 300 600 l S Q\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            val path = parsed.leaves.single { it.kind == NodeKind.PATH }
            PageWriter.write(doc, page, parsed.tokens, listOf(path), listOf(Draw.MoveBlock(lineBlock(emptyList(), listOf(path)), page, -30f)))
            save(doc)
        }
        PDDocument.load(saved).use { d ->
            val parsed = ContentStreamEngine.parse(d.getPage(0))
            val paths = parsed.leaves.filter { it.kind == NodeKind.PATH }
            assertEquals(1, paths.size)
            assertEquals(570f, paths[0].bounds!!.minY, 0.01f)
            // The moved block is the last top-level q…Q group; it must re-establish width and colour.
            val group = parsed.root.children.last()
            val moved = parsed.tokens.subList(group.startIndex, group.endIndex + 1).joinToString(" ", transform = ::tokStr)
            assertTrue(moved, moved.contains("3.0 w"))
            assertTrue(moved, moved.contains("1.0 0.0 0.0 RG"))
        }
    }

    @Test fun movedRunKeepsColourSetInEarlierTextObject() {
        val bytes = miniPdf("BT 1 0 0 rg /F1 12 Tf 72 700 Td (red) Tj ET BT /F1 12 Tf 72 680 Td (still red) Tj ET\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            val node = runNodes(page).single { it.run!!.text == "still red" }
            PageWriter.write(doc, page, parsed.tokens, listOf(node), listOf(Draw.MoveBlock(lineBlock(listOf(node)), page, -30f)))
            save(doc)
        }
        PDDocument.load(saved).use { d ->
            val parsed = ContentStreamEngine.parse(d.getPage(0))
            val group = parsed.root.children.last()
            val moved = parsed.tokens.subList(group.startIndex, group.endIndex + 1).joinToString(" ", transform = ::tokStr)
            assertTrue(moved, moved.contains("1.0 0.0 0.0 rg"))
            val r = runs(d.getPage(0)).single { it.text == "still red" }
            assertEquals(listOf("1.0", "0.0", "0.0", "rg"), r.fillColorTokens.map(::tokStr))
            assertEquals(650f, r.originY, 0.01f)
        }
    }

    @Test fun removedRunKeepsFollowingRunOnSameLineInPlace() {
        val bytes = miniPdf("BT /F1 10 Tf 72 700 Td (Edited) Tj (Other) Tj ET\n")
        val oldOther = runsOf(bytes).single { it.text == "Other" }
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            PageWriter.write(doc, page, parsed.tokens, listOf(runNodes(page).single { it.run!!.text == "Edited" }), emptyList())
            save(doc)
        }
        assertEquals(oldOther.originX, runsOf(saved).single { it.text == "Other" }.originX, 0.05f)
        assertFalse(extractText(saved).contains("Edited"))
    }

    @Test fun removedTjArrayWithSpacingKeepsFollowingRunInPlace() {
        val bytes = miniPdf("BT /F1 10 Tf 2 Tw 1 Tc 80 Tz 72 700 Td [(Ed) -120 (ited text)] TJ (Other) Tj ET\n")
        val oldOther = runsOf(bytes).single { it.text == "Other" }
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            PageWriter.write(doc, page, parsed.tokens, listOf(runNodes(page).first { it.run!!.text.startsWith("Ed") }), emptyList())
            save(doc)
        }
        assertEquals(oldOther.originX, runsOf(saved).single { it.text == "Other" }.originX, 0.05f)
        assertFalse(extractText(saved).contains("Ed"))
    }

    @Test fun unbalancedOriginalDoesNotLeakIntoDraws() {
        val bytes = miniPdf("q 2 0 0 2 0 0 cm BT /F1 10 Tf 10 300 Td (x) Tj ET\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val font = page.resources.getFont(COSName.getPDFName("F1"))!!
            val style = SpanStyle("F1", 10f, 1f, emptyList(), 0f, 0f)
            val line = LaidLine(listOf(PlacedFragment("Hi", style, 72f, 10f, 0f)), 650f)
            PageWriter.write(doc, page, ContentStreamEngine.parse(page).tokens, emptyList(),
                listOf(Draw.NewLine(line, FontPlan(mapOf(style to font), null), page)))
            save(doc)
        }
        val rs = runsOf(saved)
        val hi = rs.single { it.text == "Hi" }
        assertEquals(10f, hi.effectiveFontSize, 0.01f)
        assertEquals(650f, hi.originY, 0.01f)
        assertEquals(20f, rs.single { it.text == "x" }.effectiveFontSize, 0.01f)
    }

    @Test fun surplusQInOriginalDoesNotLeakIntoDraws() {
        val bytes = miniPdf("q Q Q 3 0 0 3 0 0 cm BT /F1 10 Tf 10 100 Td (x) Tj ET\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val font = page.resources.getFont(COSName.getPDFName("F1"))!!
            val style = SpanStyle("F1", 10f, 1f, emptyList(), 0f, 0f)
            val line = LaidLine(listOf(PlacedFragment("Hi", style, 72f, 10f, 0f)), 650f)
            PageWriter.write(doc, page, ContentStreamEngine.parse(page).tokens, emptyList(),
                listOf(Draw.NewLine(line, FontPlan(mapOf(style to font), null), page)))
            save(doc)
        }
        val rs = runsOf(saved)
        assertEquals(10f, rs.single { it.text == "Hi" }.effectiveFontSize, 0.01f)
        assertEquals(650f, rs.single { it.text == "Hi" }.originY, 0.01f)
        assertEquals(30f, rs.single { it.text == "x" }.effectiveFontSize, 0.01f)
    }

    @Test fun newLineJustifiedTjArray() {
        val saved = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val font = PdfFixtures.font(doc)
            val style = SpanStyle("F9", 11f, 1f, listOf("0", "0", "1", "rg"), 0f, 0f)
            val plan = FontPlan(mapOf(style to font), null)
            val text = "word one two"
            val w = ParagraphLayout.textWidth(text, style, font)
            val line = LaidLine(listOf(PlacedFragment(text, style, 72f, w, 2f)), 700f)
            PageWriter.write(doc, page, null, emptyList(), listOf(Draw.NewLine(line, plan, page)))
            val expectedRight = 72f + w + 2f * 2
            save(doc) to expectedRight
        }
        val r = runsOf(saved.first).single()
        assertEquals("word one two", r.text)
        assertEquals(72f, r.originX, 0.01f)
        assertEquals(700f, r.originY, 0.01f)
        assertEquals(saved.second, r.endX, 0.5f)
        assertEquals(listOf("0.0", "0.0", "1.0", "rg"), r.fillColorTokens.map(::tokStr))
        assertTrue(extractText(saved.first).contains("word one two"))
    }

    @Test fun newLineReusesSourcePageFontName() {
        val bytes = miniPdf("BT /F1 10 Tf 72 700 Td (x) Tj ET\n")
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val font = page.resources.getFont(COSName.getPDFName("F1"))!!
            val style = SpanStyle("F1", 10f, 1f, emptyList(), 0f, 0f)
            val plan = FontPlan(mapOf(style to font), null)
            val line = LaidLine(listOf(PlacedFragment("Hi", style, 72f, 10f, 0f)), 650f)
            PageWriter.write(doc, page, ContentStreamEngine.parse(page).tokens, emptyList(), listOf(Draw.NewLine(line, plan, page)))
            save(doc)
        }
        val hi = runsOf(saved).single { it.text == "Hi" }
        assertEquals("F1", hi.fontResourceName)
        assertEquals(650f, hi.originY, 0.01f)
    }

    @Test fun untouchedPageBytesUnchanged() {
        val bytes = PdfFixtures.withHeaderFooter(2, List(5) { "Body $it" })
        val before = PDDocument.load(bytes).use { it.getPage(1).contents.readBytes() }
        val saved = PDDocument.load(bytes).use { doc ->
            val page = doc.getPage(0)
            val parsed = ContentStreamEngine.parse(page)
            PageWriter.write(doc, page, parsed.tokens, listOf(runNodes(page).first { it.run!!.text == "Body 0" }), emptyList())
            save(doc)
        }
        val after = PDDocument.load(saved).use { it.getPage(1).contents.readBytes() }
        assertArrayEquals(before, after)
        assertFalse(extractText(saved, page = 1).contains("Body 0"))
    }
}
