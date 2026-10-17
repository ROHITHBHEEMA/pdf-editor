package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.engine.TextRunInfo
import app.pdfeditor.flow.EditOp.AddBlankPage
import app.pdfeditor.flow.EditOp.DeletePage
import app.pdfeditor.flow.EditOp.EditParagraph
import app.pdfeditor.testing.PdfFixtures
import app.pdfeditor.testing.TestFontProvider
import app.pdfeditor.testing.extractText
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream

class EditSessionTest {

    private val fonts = TestFontProvider

    private fun open(bytes: ByteArray): EditSession = (EditSession.open(bytes, fonts) as OpenResult.Opened).session

    private var bytes: ByteArray = PdfFixtures.sampleLetter()
    private val session = lazy { open(bytes) }
    private val s: EditSession by session

    @After fun closeSession() { if (session.isInitialized()) s.close() }

    private fun saved(session: EditSession = s): ByteArray = ByteArrayOutputStream().also { session.save(it) }.toByteArray()

    private fun paragraphs(b: ByteArray = bytes): List<Paragraph> =
        PDDocument.load(b).use { doc -> DocumentAnalyzer.analyze(doc).pages.flatMap { ParagraphDetector.detect(it) } }

    private fun idOf(text: String, b: ByteArray = bytes): ParagraphId = paragraphs(b).single { it.text() == text }.id

    private val longPara by lazy { paragraphs().single { it.text().startsWith("The construction team") } }
    private val longId get() = longPara.id
    private val longText get() = longPara.text()

    private fun runs(b: ByteArray, page: Int = 0): List<TextRunInfo> = PDDocument.load(b).use { doc ->
        ContentStreamEngine.parse(doc.getPage(page)).leaves.filter { it.kind == NodeKind.TEXT }
            .flatMap { it.children }.mapNotNull { it.run }
    }

    private fun originY(b: ByteArray, text: String, page: Int = 0): Float = runs(b, page).single { it.text == text }.originY

    // ---- brief tests ----

    @Test fun noEditRoundTripKeepsLayout() {
        val before = runs(bytes)
        val after = runs(saved())
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) -> assertEquals(a.text, b.text); assertEquals(a.originY, b.originY, 0.01f) }
    }

    @Test fun fixTypo() {
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(idOf("Date: 17 Octobr 2026"), "Date: 17 October 2026")))
        val t = extractText(saved()); assertTrue(t.contains("October 2026")); assertFalse(t.contains("Octobr"))
        assertTrue(s.canUndo)
    }

    @Test fun substitutionNeedsConfirmation() {
        val id = idOf("Date: 17 Octobr 2026")
        val r = s.apply(EditParagraph(id, "Zebra"))
        assertTrue(r is EditOutcome.NeedsConfirmation); assertFalse(s.canUndo)
        assertNotNull((r as EditOutcome.NeedsConfirmation).fontWarning); assertFalse(r.decorationsStay)
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(id, "Zebra"), acceptWarnings = true))
        assertTrue(extractText(saved()).contains("Zebra"))
    }

    @Test fun longParagraphFlowsToNewPageAndUndoRemovesIt() {
        s.apply(EditParagraph(longId, longText.repeat(6)), acceptWarnings = true)
        assertEquals(2, s.pageCount); assertTrue(extractText(saved(), page = 2).contains("Project Coordinater"))
        s.undo(); assertEquals(1, s.pageCount)
        assertEquals(1, PDDocument.load(saved()).use { it.numberOfPages })
    }

    @Test fun lineBreaksFromEnterApplyWithoutSubstitution() {
        val text = List(6) { longText }.joinToString("\n")
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(longId, text)))
        assertEquals(2, s.pageCount)
    }

    @Test fun shorterParagraphPullsContentUp() {
        val before = originY(bytes, "Warm regards,")
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(longId, "The construction team is on schedule.")))
        assertTrue(originY(saved(), "Warm regards,") > before + 1f)
    }

    @Test fun deleteAllTextRemovesParagraph() {
        val before = originY(bytes, "Dear Mr. Sharma,")
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(idOf("Date: 17 Octobr 2026"), "")))
        val out = saved()
        assertFalse(extractText(out).contains("Date:"))
        // The Date line was 11pt high with an 18.75pt gap above it.
        assertEquals(before + 11f + 18.75f, originY(out, "Dear Mr. Sharma,"), 0.01f)
        assertEquals(runs(bytes).size - 1, runs(out).size)
    }

    @Test fun addBlankPageStaysBlankEvenWhenFlowing() {
        s.apply(AddBlankPage(beforePage = 1)); s.apply(EditParagraph(longId, longText.repeat(6)), true)
        assertEquals(3, s.pageCount)
        assertEquals("", extractText(saved(), page = 3).trim())   // [p1, auto, blank]
        assertTrue(extractText(saved(), page = 2).contains("Project Coordinater"))
    }

    private val threePages by lazy {
        PdfFixtures.letter(List(3) { p -> List(20) { i -> "Page$p paragraph $i here." } }.flatten(), leading = 16f, paragraphGap = 20f)
    }

    @Test fun deletePage() {
        bytes = threePages
        assertEquals(3, s.pageCount)
        assertTrue(extractText(bytes, page = 2).contains("Page1 paragraph 0 here."))
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(1)))
        assertEquals(2, s.pageCount)
        val out = saved()
        assertEquals(2, PDDocument.load(out).use { it.numberOfPages })
        val t = extractText(out)
        assertFalse(t.contains("Page1 paragraph 0 here."))
        assertTrue(t.contains("Page0 paragraph 0 here.")); assertTrue(t.contains("Page2 paragraph 0 here."))
    }

    @Test fun cannotDeleteOnlyPage() { assertEquals(RejectReason.LAST_PAGE, (s.apply(DeletePage(0)) as EditOutcome.Rejected).reason) }

    @Test fun rotatedPageGrowthRejected() {
        bytes = PdfFixtures.rotated()
        val id = idOf("Rotated page text")
        val grow = s.apply(EditParagraph(id, "Rotated page text ".repeat(20)))
        assertEquals(RejectReason.NO_ROOM, (grow as EditOutcome.Rejected).reason)
        assertFalse(s.canUndo)
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(id, "Rotated text page")))
        val out = saved()
        // PDFTextStripper splits the first glyph on /Rotate 90 pages (the original too), so check the drawn runs.
        assertEquals(listOf("Rotated text page"), runs(out).map { it.text })
        assertEquals(90, PDDocument.load(out).use { it.getPage(0).rotation })
    }

    @Test fun passwordPdfReported() { assertTrue(EditSession.open(PdfFixtures.encrypted("pw"), fonts) is OpenResult.PasswordProtected) }

    @Test fun truncatedPdfReported() {
        val r = EditSession.open(bytes.copyOf(bytes.size / 3), fonts)
        if (r is OpenResult.Opened) r.session.close() else assertTrue(r is OpenResult.Failed)
    }

    @Test fun garbageReported() {
        val r = EditSession.open("hello".toByteArray(), fonts)
        assertTrue(r is OpenResult.Failed); assertEquals("This file couldn't be opened as a PDF", (r as OpenResult.Failed).message)
    }

    @Test fun saveIsFullRewrite() { assertEquals(1, String(saved(), Charsets.ISO_8859_1).split("%%EOF").size - 1) }

    @Test fun metadataPreserved() { assertEquals("Sample Letter", PDDocument.load(saved()).use { it.documentInformation.title }) }

    // ---- extra coverage ----

    @Test fun ownerPasswordOnlyOpensAndSavesUnencrypted() {
        bytes = PdfFixtures.encrypted("")
        assertTrue(PDDocument.load(bytes).use { it.isEncrypted })
        PDDocument.load(saved()).use { assertFalse(it.isEncrypted) }
        assertTrue(extractText(saved()).contains("Secret text"))
    }

    @Test fun sameTextIsNoOp() {
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(idOf("Dear Mr. Sharma,"), "Dear Mr. Sharma,")))
        assertFalse(s.canUndo)
    }

    @Test fun unknownParagraphNotFound() {
        val r = s.apply(EditParagraph(ParagraphId(0, 99), "x"))
        assertEquals(RejectReason.NOT_FOUND, (r as EditOutcome.Rejected).reason)
    }

    @Test fun unsupportedCharsRejected() {
        val r = s.apply(EditParagraph(idOf("Dear Mr. Sharma,"), "Dear 😀"), acceptWarnings = true)
        assertEquals(RejectReason.UNSUPPORTED_CHARS, (r as EditOutcome.Rejected).reason)
        assertTrue(r.detail.contains("😀")); assertFalse(s.canUndo)
    }

    @Test fun nonLatinRejected() {
        bytes = PdfFixtures.letter(listOf("Привет мир", "Hello there"))
        val id = idOf("Привет мир")
        assertEquals(RejectReason.NON_LATIN, (s.apply(EditParagraph(id, "Hello")) as EditOutcome.Rejected).reason)
        val hit = s.paragraphAt(0, 80f, originY(bytes, "Привет мир") + 2f)!!
        assertEquals(id, hit.id); assertFalse(hit.editable); assertEquals(RejectReason.NON_LATIN, hit.reason)
        assertTrue(s.paragraphAt(0, 80f, originY(bytes, "Hello there") + 2f)!!.editable)
    }

    @Test fun paragraphAtFindsCurrentTextAndBounds() {
        val y = originY(bytes, "Date: 17 Octobr 2026") + 3f
        val hit = s.paragraphAt(0, 100f, y)!!
        assertEquals(idOf("Date: 17 Octobr 2026"), hit.id); assertEquals("Date: 17 Octobr 2026", hit.text)
        assertEquals(0, hit.page); assertTrue(hit.editable)
        s.apply(EditParagraph(hit.id, "Date: 17 October 2026"))
        assertEquals("Date: 17 October 2026", s.paragraphAt(0, 100f, y)!!.text)
        assertNull(s.paragraphAt(0, 560f, 20f))
    }

    @Test fun paragraphAtFollowsMovedContent() {
        s.apply(EditParagraph(longId, longText.repeat(6)), true)
        val sig = idOf("Project Coordinater, Riverside Library Trust")
        val onPage2 = (0..800 step 2).firstNotNullOfOrNull { y -> s.paragraphAt(1, 100f, y.toFloat())?.takeIf { it.id == sig } }
        assertNotNull(onPage2); assertEquals(1, onPage2!!.page)
        val atOld = s.paragraphAt(0, 100f, originY(bytes, "Project Coordinater, Riverside Library Trust") + 3f)
        assertTrue(atOld == null || atOld.id != sig)
    }

    @Test fun redoReappliesAndNewEditClearsRedo() {
        val id = idOf("Date: 17 Octobr 2026")
        s.apply(EditParagraph(id, "Date: 17 October 2026"))
        s.undo(); assertTrue(s.canRedo); assertTrue(extractText(saved()).contains("Octobr"))
        s.redo(); assertFalse(s.canRedo); assertTrue(extractText(saved()).contains("October 2026"))
        s.undo(); assertEquals(EditOutcome.Applied, s.apply(EditParagraph(id, "Date: 18 Octobr 2026"), true)); assertFalse(s.canRedo)
    }

    @Test fun undoStackCappedAt50() {
        val id = idOf("Dear Mr. Sharma,")
        repeat(55) { assertEquals(EditOutcome.Applied, s.apply(EditParagraph(id, "Dear Mr. Sharma $it,"), true)) }
        var n = 0
        while (s.canUndo) { s.undo(); n++ }
        assertEquals(50, n)
    }

    @Test fun renderedBytesCachedPerState() {
        val a = s.renderedBytes(); val b = s.renderedBytes()
        assertTrue(a === b)
        s.apply(EditParagraph(idOf("Dear Mr. Sharma,"), "Dear Ms. Sharma,"))
        assertFalse(a === s.renderedBytes())
    }

    @Test fun deleteBlankPageRemovesIt() {
        s.apply(AddBlankPage(0)); assertEquals(2, s.pageCount)
        assertEquals("", extractText(saved(), page = 1).trim())
        s.apply(DeletePage(0)); assertEquals(1, s.pageCount)
        assertTrue(extractText(saved()).contains("Octobr"))
    }

    @Test fun deleteAutoPageDropsMovedContent() {
        s.apply(EditParagraph(longId, longText.repeat(6)), true)
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(1)))
        assertEquals(1, s.pageCount)
        assertFalse(extractText(saved()).contains("Coordinater"))
    }

    @Test fun deleteFirstPageOfFlowKeepsOverflow() {
        s.apply(EditParagraph(longId, longText.repeat(6)), true)
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(0)))
        assertEquals(1, s.pageCount)
        val t = extractText(saved())
        assertTrue(t.contains("Coordinater")); assertFalse(t.contains("Project Update Letter"))
    }

    // ---- fix round 1: page deletion turns partial paragraphs into text edits ----

    private fun onPage(id: ParagraphId, page: Int): ParagraphHit? =
        (0..850 step 2).firstNotNullOfOrNull { y -> s.paragraphAt(page, 100f, y.toFloat())?.takeIf { it.id == id } }

    @Test fun deletingPageWithPartOfEditedParagraphKeepsOnlyTheRest() {
        val marker = "flibbertigibbet"
        s.apply(EditParagraph(longId, longText.repeat(7) + " $marker"), true)
        assertEquals(2, s.pageCount); assertTrue(extractText(saved(), page = 2).contains(marker))
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(1)))
        assertEquals(1, s.pageCount)
        val hit = onPage(longId, 0)!!
        assertTrue(hit.text.startsWith("The construction team")); assertFalse(hit.text.contains(marker))
        assertTrue(hit.text.length < longText.length * 7)
        assertFalse(extractText(saved()).contains(marker)); assertFalse(extractText(saved()).contains("Coordinater"))
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(longId, hit.text.replaceFirst("The construction team", "The building team")), true))
        val t = extractText(saved()); assertFalse(t.contains(marker)); assertTrue(t.contains("The building team"))
        assertEquals(1, s.pageCount)
        s.undo(); s.undo()
        assertEquals(2, s.pageCount); assertTrue(extractText(saved()).contains(marker))
        assertTrue(onPage(longId, 0)!!.text.endsWith(marker))
    }

    @Test fun deletingSecondPageOfCrossPageParagraph() {
        val long = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega. "
            .repeat(4).trim() + " Finalword"
        bytes = PdfFixtures.letter(List(27) { "Filler line $it." } + long)
        assertEquals(2, s.pageCount)
        val first = paragraphs().single { it.id.page == 0 && it.text().startsWith("Alpha") }
        assertTrue(paragraphs().filter { it.id.page == 1 }.joinToString(" ") { it.text() }.contains("Finalword"))
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(1)))
        val hit = s.paragraphAt(0, 80f, first.firstBaseline + 2f)!!
        assertEquals(first.id, hit.id); assertEquals(first.text(), hit.text)
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(hit.id, hit.text + " added"), true))
        val t = extractText(saved()); assertFalse(t.contains("Finalword")); assertTrue(t.contains("added"))
    }

    @Test fun deletingPageWithMovedPartOfUneditedParagraph() {
        val thank = paragraphs().single { it.text().startsWith("Thank you") }
        // Grow the paragraph above until the (unedited) long paragraph straddles pages 1 and 2.
        val split = (6..24).any { k ->
            s.apply(EditParagraph(thank.id, thank.text().repeat(k)), true)
            (onPage(longId, 0) != null && onPage(longId, 1) != null).also { if (!it) s.undo() }
        }
        assertTrue(split)
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(1)))
        val hit = onPage(longId, 0)!!
        assertTrue(longText.startsWith(hit.text)); assertTrue(hit.text.length < longText.length)
        assertFalse(extractText(saved()).contains("official opening"))
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(longId, hit.text + " more"), true))
        assertFalse(extractText(saved()).contains("official opening"))
    }

    // ---- final fix 1: typo fixes keep hard line breaks ----

    private fun block(lines: List<String>, x: Float = 72f, top: Float = 700f, step: Float = 14f) =
        PdfFixtures.positioned(lines.mapIndexed { i, t -> Triple(t, x, top - step * i) })

    @Test fun fixingTypoInAddressBlockKeepsItsLines() {
        val address = listOf("Jonh Smith", "12 Main Street, Apartment 4B", "Springfield, IL 62704", "Phone 555 0100")
        bytes = block(address)
        val p = paragraphs().single()
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(p.id, p.text().replace("Jonh", "John")), true))
        assertEquals(listOf("John Smith") + address.drop(1), runs(saved()).sortedByDescending { it.originY }.map { it.text })
    }

    @Test fun fixingTypoInSignatureBlockKeepsItsLines() {
        val sig = listOf("Priya Sharmaa", "Project Coordinator, Riverside Library Trust")
        bytes = block(sig, step = 13f)
        val p = paragraphs().single()
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(p.id, p.text().replace("Sharmaa", "Sharma")), true))
        assertEquals(listOf("Priya Sharma", sig[1]), runs(saved()).sortedByDescending { it.originY }.map { it.text })
    }

    // ---- final fix 2: a deleted page's content leaves the saved file ----

    /**
     * Two Helvetica pages (so text is plain ASCII in the streams); page 2 holds [secret] and is referenced by an
     * outline item, a named destination, a structure-like catalog entry and a form field whose only widget is on it.
     */
    private fun referencedSecondPage(secret: String): ByteArray = PDDocument().use { doc ->
        val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
        val pages = List(2) { i ->
            com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4).also { pg ->
                doc.addPage(pg)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, pg).use { cs ->
                    cs.beginText(); cs.setFont(font, 11f); cs.newLineAtOffset(72f, 700f)
                    cs.showText(if (i == 0) "Front page text" else "Back page $secret"); cs.endText()
                }
            }
        }
        val dest = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().apply { page = pages[1] }
        val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        for ((title, target) in listOf("Front" to pages[0], "Back" to pages[1])) {
            outline.addLast(com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem().apply {
                this.title = title
                destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination().apply { page = target }
            })
        }
        val names = com.tom_roush.pdfbox.pdmodel.PDDocumentNameDestinationDictionary(com.tom_roush.pdfbox.cos.COSDictionary())
        doc.documentCatalog.cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.DESTS, names.cosObject)
        names.cosObject.setItem("back", dest)
        doc.documentCatalog.cosObject.setItem("StructLike", com.tom_roush.pdfbox.cos.COSDictionary().apply {
            setItem(com.tom_roush.pdfbox.cos.COSName.PG, pages[1])
        })
        val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form).apply { partialName = "f" }
        field.cosObject.setString(com.tom_roush.pdfbox.cos.COSName.V, secret)
        val widget = field.widgets[0]
        widget.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(72f, 600f, 200f, 20f)
        widget.page = pages[1]
        pages[1].annotations = listOf(widget)
        form.fields = listOf(field)
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test fun deletedPageLeavesNoTraceInSavedFile() {
        val secret = "SECRETWORD40"
        bytes = referencedSecondPage(secret)
        assertTrue(extractText(bytes).contains(secret))
        assertEquals(EditOutcome.Applied, s.apply(DeletePage(1)))
        val out = saved()
        assertFalse(String(out, Charsets.ISO_8859_1).contains(secret))
        PDDocument.load(out).use { doc ->
            assertEquals(1, doc.numberOfPages)
            for (o in doc.document.objects) {
                val obj = o.`object`
                if (obj is com.tom_roush.pdfbox.cos.COSStream) {
                    val text = obj.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    assertFalse("stream ${o.objectNumber} keeps the deleted text", text.contains(secret))
                }
                if (obj is com.tom_roush.pdfbox.cos.COSString) assertFalse(obj.string.contains(secret))
            }
            val items = generateSequence(doc.documentCatalog.documentOutline?.firstChild) { it.nextSibling }.toList()
            assertEquals(listOf("Front"), items.map { it.title })
            assertEquals(0, doc.pages.indexOf(items[0].findDestinationPage(doc)))
            assertTrue(doc.documentCatalog.acroForm?.fields.orEmpty().isEmpty())
            assertTrue(extractText(out).contains("Front page text"))
        }
    }

    // ---- final fix 3: undecodable text is reported as unreadable ----

    @Test fun unreadableParagraphRejected() {
        bytes = app.pdfeditor.testing.miniPdf("BT /F1 12 Tf 72 700 Td (Menu \u0080 item) Tj ET\n")
        val id = paragraphs().single().id
        assertEquals(RejectReason.UNREADABLE, (s.apply(EditParagraph(id, "Menu item")) as EditOutcome.Rejected).reason)
        val hit = s.paragraphAt(0, 80f, 703f)!!
        assertFalse(hit.editable); assertEquals(RejectReason.UNREADABLE, hit.reason)
    }

    // ---- final fix 4: underlines and highlights stay behind, so ask first ----

    private val underlined = app.pdfeditor.testing.miniPdf(
        "BT /F1 12 Tf 72 700 Td (Hello world) Tj ET\n0.5 w 72 698 m 140 698 l S\n" +
            "BT /F1 12 Tf 72 660 Td (Plain line here) Tj ET\n",
    )

    @Test fun editingDecoratedParagraphNeedsConfirmation() {
        bytes = underlined
        val id = idOf("Hello world")
        val r = s.apply(EditParagraph(id, "Hello there, world"))
        assertTrue(r is EditOutcome.NeedsConfirmation); assertFalse(s.canUndo)
        assertTrue((r as EditOutcome.NeedsConfirmation).decorationsStay); assertNull(r.fontWarning)
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(id, "Hello there, world"), acceptWarnings = true))
        assertTrue(extractText(saved()).replace("\n", " ").contains("Hello there, world"))
    }

    @Test fun sameLayoutTypoFixInDecoratedParagraphAlsoAsks() {
        bytes = underlined
        val r = s.apply(EditParagraph(idOf("Hello world"), "Hello world!"))
        assertTrue(r is EditOutcome.NeedsConfirmation && r.decorationsStay)
    }

    @Test fun undecoratedParagraphNeedsNoConfirmation() {
        bytes = underlined
        assertEquals(EditOutcome.Applied, s.apply(EditParagraph(idOf("Plain line here"), "Plain line there")))
    }

    @Test fun noRoomIsCheckedBeforeConfirmation() {
        bytes = PdfFixtures.rotated()
        val r = s.apply(EditParagraph(idOf("Rotated page text"), "Zebra ".repeat(40)))
        assertEquals(RejectReason.NO_ROOM, (r as EditOutcome.Rejected).reason)
    }

    // ---- final fix 5: unsaved changes are tracked ----

    @Test fun isModifiedFollowsEditsUndoAndSave() {
        assertFalse(s.isModified)
        val id = idOf("Date: 17 Octobr 2026")
        s.apply(EditParagraph(id, "Date: 17 October 2026")); assertTrue(s.isModified)
        s.undo(); assertFalse(s.isModified)
        s.redo(); assertTrue(s.isModified)
        saved(); assertFalse(s.isModified)
        s.undo(); assertTrue(s.isModified) // back to the opened text, which differs from what was saved
        s.redo(); assertFalse(s.isModified)
        s.apply(AddBlankPage(0)); assertTrue(s.isModified)
    }

    @Test fun rejectedOrPendingEditIsNotAModification() {
        val id = idOf("Date: 17 Octobr 2026")
        assertTrue(s.apply(EditParagraph(id, "Zebra")) is EditOutcome.NeedsConfirmation)
        assertFalse(s.isModified)
    }

    // ---- final fix 6: no repeated work per edit ----

    @Test fun unmodifiedSessionDisplaysOriginalBytes() {
        assertTrue(s.renderedBytes().contentEquals(bytes))
        s.apply(EditParagraph(idOf("Dear Mr. Sharma,"), "Dear Ms. Sharma,"))
        assertFalse(s.renderedBytes().contentEquals(bytes))
        s.undo()
        assertTrue(s.renderedBytes().contentEquals(bytes))
        assertEquals(1, String(saved(), Charsets.ISO_8859_1).split("%%EOF").size - 1) // save is still a full rewrite
    }

    @Test fun observedEncodingsBuiltOncePerSession() {
        s.pageCount // opens the session
        val before = ObservedEncodings.buildCount.get()
        s.apply(EditParagraph(idOf("Date: 17 Octobr 2026"), "Date: 17 October 2026")); s.renderedBytes()
        s.apply(EditParagraph(idOf("Dear Mr. Sharma,"), "Dear Ms. Sharma,")); s.renderedBytes()
        assertEquals(before, ObservedEncodings.buildCount.get())
        val t = extractText(s.renderedBytes()); assertTrue(t.contains("October 2026")); assertTrue(t.contains("Ms. Sharma"))
    }

    @Test fun pageGeometryWithoutReparsing() {
        bytes = PdfFixtures.rotated()
        val g = s.pageGeometry()
        assertEquals(1, g.size); assertEquals(90, g[0].rotation)
        assertEquals(PDDocument.load(bytes).use { it.getPage(0).cropBox.width }, g[0].width, 0.01f)
        s.apply(AddBlankPage(1))
        val g2 = s.pageGeometry()
        assertEquals(2, g2.size); assertEquals(0, g2[1].rotation)
        PDDocument.load(s.renderedBytes()).use { doc ->
            g2.forEachIndexed { i, pg ->
                val p = doc.getPage(i)
                assertEquals(p.rotation, pg.rotation); assertEquals(p.cropBox.width, pg.width, 0.01f)
                assertEquals(p.cropBox.height, pg.height, 0.01f); assertEquals(p.cropBox.lowerLeftX, pg.left, 0.01f)
                assertEquals(p.cropBox.lowerLeftY, pg.bottom, 0.01f)
            }
        }
    }

    // ---- final fix 8: tapping text that can't be edited ----

    @Test fun headerTextIsFoundButNotEditable() {
        bytes = PDDocument().use { doc ->
            val f = PdfFixtures.font(doc)
            for (n in 0..1) {
                val pg = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4).also { doc.addPage(it) }
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, pg).use { cs ->
                    for ((t, y) in listOf("Riverside Trust" to pg.mediaBox.height - 40f, (if (n == 0) "Body paragraph text here." else "Other body text.") to 700f)) {
                        cs.beginText(); cs.setFont(f, 11f); cs.newLineAtOffset(72f, y); cs.showText(t); cs.endText()
                    }
                }
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val headerY = com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4.height - 40f + 3f
        assertNull(s.paragraphAt(0, 80f, headerY))
        assertTrue(s.uneditableTextAt(0, 80f, headerY))
        assertFalse(s.uneditableTextAt(0, 500f, 400f)) // empty space
        val bodyY = originY(bytes, "Body paragraph text here.") + 3f
        assertNotNull(s.paragraphAt(0, 80f, bodyY)); assertFalse(s.uneditableTextAt(0, 80f, bodyY))
        s.apply(AddBlankPage(0))
        assertFalse(s.uneditableTextAt(0, 80f, headerY)) // the blank page has no text
        assertTrue(s.uneditableTextAt(1, 80f, headerY))
    }
}
