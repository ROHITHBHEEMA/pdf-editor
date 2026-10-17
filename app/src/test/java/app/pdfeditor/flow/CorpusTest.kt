package app.pdfeditor.flow

import app.pdfeditor.engine.ContentStreamEngine
import app.pdfeditor.engine.NodeKind
import app.pdfeditor.engine.TextRunInfo
import app.pdfeditor.testing.TestFontProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs every PDF in fixtures/corpus/ (see the README there). Dropping a new PDF into that folder is enough:
 * it is picked up automatically.
 */
class CorpusTest {

    private val files: List<File> by lazy {
        val dir = File(CorpusTest::class.java.getResource("/fixtures/corpus")!!.toURI())
        dir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }!!.sortedBy { it.name }
    }

    private fun open(bytes: ByteArray): EditSession =
        (EditSession.open(bytes, TestFontProvider) as OpenResult.Opened).session

    private fun runs(bytes: ByteArray): List<TextRunInfo> = PDDocument.load(bytes).use { doc ->
        (0 until doc.numberOfPages).flatMap { p ->
            ContentStreamEngine.parse(doc.getPage(p)).leaves.filter { it.kind == NodeKind.TEXT }
                .flatMap { it.children }.mapNotNull { it.run }
        }
    }

    @Test fun corpusIsNotEmpty() = assertTrue(files.size >= 4)

    @Test fun noEditRoundTrip() {
        for (f in files) {
            val bytes = f.readBytes()
            open(bytes).use { s ->
                val before = runs(bytes)
                val after = runs(ByteArrayOutputStream().also { s.save(it) }.toByteArray())
                assertEquals("${f.name}: run count", before.size, after.size)
                before.zip(after).forEachIndexed { i, (a, b) ->
                    assertEquals("${f.name} run $i text", a.text, b.text)
                    assertEquals("${f.name} run $i x", a.originX, b.originX, 0.05f)
                    assertEquals("${f.name} run $i y", a.originY, b.originY, 0.05f)
                }
            }
        }
    }

    @Test fun editFirstEditableParagraph() {
        var applied = 0
        for (f in files) {
            val bytes = f.readBytes()
            val target = PDDocument.load(bytes).use { doc ->
                DocumentAnalyzer.analyze(doc).pages.flatMap { ParagraphDetector.detect(it) }
                    .firstOrNull { !it.nonLatin && it.text().isNotBlank() }
            }
            if (target == null) { println("${f.name}: no editable paragraph"); continue }
            open(bytes).use { s ->
                val outcome = s.apply(EditOp.EditParagraph(target.id, target.text() + " Added sentence for testing."), acceptWarnings = true)
                when (outcome) {
                    is EditOutcome.Applied -> applied++
                    is EditOutcome.Rejected ->
                        assertTrue("${f.name}: unexpected ${outcome.reason}", outcome.reason == RejectReason.NO_ROOM || outcome.reason == RejectReason.NON_LATIN)
                    else -> fail("${f.name}: unexpected outcome $outcome")
                }
                println("${f.name}: edit -> ${outcome::class.simpleName}")
                val out = ByteArrayOutputStream().also { s.save(it) }.toByteArray()
                PDDocument.load(out).use { assertTrue(it.numberOfPages >= 1) }
            }
        }
        assertTrue("no corpus file accepted an edit", applied >= 1)
    }
}
