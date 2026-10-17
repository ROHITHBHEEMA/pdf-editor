# Trustworthy PDF Editor v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the MIT-licensed PDF Inspector Android app into a simple, offline PDF editor that rewrites paragraphs with Word-style reflow across pages, adds/deletes pages, and saves a full-rewrite copy.

**Architecture:** Every edit is a change to an immutable `EditState`. `EditSession` renders a state by re-loading the original PDF bytes and regenerating only the pages whose content moved. Untouched content stays byte-identical. Moved and edited content is removed from its original place and redrawn at the end of the target page's content stream, wrapped in `q … Q`. A pure `FlowLayout` decides which page each block lands on, so the result is the same whichever direction you edit in, undo is a pop of the state stack, and auto-created pages vanish once they're empty.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (Material 3), PdfBox-Android 2.0.27.0, Android Gradle Plugin 8.12.3, JUnit 4 JVM unit tests, JDK 17.

**Spec:** `docs/superpowers/specs/2026-10-17-pdf-editor-v1-design.md` (read it before starting any task).

## Global Constraints

- Android 8.0+ (`minSdk = 26`), `compileSdk`/`targetSdk = 35`; Kotlin package and `applicationId`: `app.pdfeditor`; placeholder app name: `PDF Editor`.
- The merged APK manifest declares **no** `<uses-permission>` at all (in particular no `INTERNET`); no analytics, crash-reporting, ads or network SDKs.
- Files only via Storage Access Framework; the opened file is never written; saving always writes a new file through the system picker.
- Saving is a **full** save (`PDDocument.save`), never `saveIncremental`; replaced text must be absent from the saved file's text extraction.
- Dependencies: permissive licences only (MIT/Apache-2.0/BSD/OFL/GUST). No MuPDF, no paid SDKs. Keep PDF Inspector's `LICENSE` and credit it in About.
- Version 1 edits English (Latin-script) text only; text in other scripts is left untouched and still moves/renders correctly; editing it shows: "Editing this language isn't supported yet."
- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (installed at `~/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1`). Android Studio's bundled Java 25 cannot run this Gradle build.
- All commands run from the repo root `~/Desktop/projects/pdf-editor`. Unit tests: `./gradlew testDebugUnitTest`. Emulator AVD: `Pixel_8_API_36`; adb: `~/Library/Android/sdk/platform-tools/adb`.
- Shared metric for every text line, original or new: `ascent = 0.75 × effectiveFontSize`, `descent = 0.25 × effectiveFontSize` (the parser's existing convention). Use the same function everywhere so an unedited document lays out exactly where it was.

## Review Focus

1. **User deletes all text of a paragraph** → the paragraph disappears and content below moves up; no empty BT/ET artefacts, no crash. *(test in Task 10)*
2. **Per-word or per-glyph positioned text** (Chrome, Google Docs, LibreOffice emit separate `Tj`/`TJ` per word, kerned `TJ` arrays) → runs on one baseline merge into one line, and the paragraph is still detected. *(test in Task 3)*
3. **Pages with `/Rotate 90` or rotated/skewed text** → the page is pinned (no reflow). Paragraph edits that fit are allowed, edits that grow are rejected with `NO_ROOM`, and nothing is drawn in the wrong orientation. *(test in Task 3 and Task 10)*
4. **Characters no available font can draw** (emoji, Devanagari, tab characters pasted into the editor) → `Rejected(UNSUPPORTED_CHARS)` naming the characters (tabs become spaces before checking); never a crash or blank glyphs. *(test in Task 7)*
5. **Password-protected or malformed PDFs** → open fails with a clear message ("This PDF is password-protected" / "This file couldn't be opened as a PDF"); the app stays usable. *(test in Task 10)*

---

### Task 1: Import PDF Inspector as the project base

**Files:**
- Create: whole tree from `https://github.com/shardulvs/pdfinspector-android` (with git history) in `~/Desktop/projects/pdf-editor/`
- Modify: `app/build.gradle.kts`, `settings.gradle.kts`, `app/src/main/res/values/strings.xml`, every `package SVS.pdfinspector…` line → `package app.pdfeditor…` (move `app/src/main/java/SVS/pdfinspector` → `app/src/main/java/app/pdfeditor`, same for `src/test`)
- Create: `local.properties` (gitignored, `sdk.dir=/Users/rohithbheema/Library/Android/sdk`)
- Create: `app/src/test/resources/com/tom_roush/fontbox/resources/cmap/Identity-H` (copied from the pdfbox-android 2.0.27.0 AAR's `assets/com/tom_roush/fontbox/resources/cmap/Identity-H`; JVM tests need it to load TrueType fonts with `PDType0Font.load`)
- Create: `app/src/test/resources/fixtures/sample-letter.pdf` (copy of `~/Desktop/projects/REA-MCP/sample-letter.pdf`)
- Create: `scripts/check-no-permissions.sh`
- Create: `app/src/test/java/app/pdfeditor/testing/PdfFixtures.kt`, `app/src/test/java/app/pdfeditor/testing/PdfFixturesTest.kt`

**Interfaces:**
- Produces (test helpers used by every later task):
  - `object PdfFixtures`
    - `fun font(doc: PDDocument, file: String = "LiberationSerif-Regular"): PDFont` loads `src/main/assets/fonts/$file.ttf`
    - `fun letter(paragraphs: List<String>, fontFile: String = "LiberationSerif-Regular", size: Float = 11f, leading: Float = 16f, paragraphGap: Float = 8f, align: String = "left", pageSize: PDRectangle = PDRectangle.A4, margin: Float = 72f): ByteArray` wraps greedily and starts new pages as needed; `align` is `"left"` or `"justify"`
    - `fun perWordRuns(text: String): ByteArray` emits one `Tj` per word on a line
    - `fun twoColumn(left: List<String>, right: List<String>): ByteArray`
    - `fun withHeaderFooter(pages: Int, bodyParagraphs: List<String>): ByteArray` puts "Riverside Trust" at the top and "Page N" at the bottom of each page
    - `fun rotated(): ByteArray` has `/Rotate 90`
    - `fun encrypted(userPassword: String): ByteArray`
    - `fun sampleLetter(): ByteArray` reads `/fixtures/sample-letter.pdf`
  - `fun extractText(bytes: ByteArray, page: Int? = null): String` uses `PDFTextStripper`

- [ ] **Step 1: Bring the code in.** Clone the repository into the scratchpad, then copy its contents including `.git` into `~/Desktop/projects/pdf-editor/` (the existing `docs/` merges in). Run `git remote rename origin upstream`. Check `git config user.name`/`user.email`; if they're unset, ask the owner before the first commit.
- [ ] **Step 2: Rename.** Change `namespace`, `applicationId` and the Kotlin package to `app.pdfeditor`. Set `versionCode = 1` and `versionName = "0.1.0"`, `app_name` to `PDF Editor`, and the theme name to `Theme.PdfEditor`. Rename `InspectorApp` to `PdfEditorApp`.
- [ ] **Step 3: Write `PdfFixtures` and the failing test `PdfFixturesTest`**

```kotlin
@Test fun letterWrapsAndPaginates() {
    val bytes = PdfFixtures.letter(List(40) { "Paragraph $it " + "word ".repeat(60) })
    PDDocument.load(bytes).use { assertTrue(it.numberOfPages >= 2) }
    assertTrue(extractText(bytes, page = 1).contains("Paragraph 0"))
}
@Test fun sampleLetterLoads() {
    assertTrue(extractText(PdfFixtures.sampleLetter()).contains("Octobr"))
}
@Test fun ttfLoadsOnJvm() {
    PDDocument().use { assertEquals("LiberationSerif", PdfFixtures.font(it).name.substringAfter('+')) }
}
```

- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: all tests pass, including the existing ones under the renamed package.
- [ ] **Step 5: Write `scripts/check-no-permissions.sh`.** It runs `$ANDROID_HOME/build-tools/<latest>/aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` (default `ANDROID_HOME=$HOME/Library/Android/sdk`), exits 1 if any line starts with `uses-permission`, and otherwise prints `OK: no permissions`. Run `./gradlew assembleDebug && scripts/check-no-permissions.sh`. Expected: `OK: no permissions`.
- [ ] **Step 6: Commit** `chore: import PDF Inspector (MIT) as base, rename to app.pdfeditor`.

---

### Task 2: Capture full text state per run in the parser

**Files:**
- Modify: `app/src/main/java/app/pdfeditor/engine/ContentStreamEngine.kt`, `engine/DrawNode.kt`
- Test: `app/src/test/java/app/pdfeditor/engine/TextRunInfoTest.kt`

**Interfaces:**
- Produces (in `DrawNode.kt`):

```kotlin
class TextState(val charSpacing: Float, val wordSpacing: Float, val horizontalScaling: Float /* Tz, percent */,
                val leading: Float, val rise: Float, val renderMode: Int)
class TextRunInfo(
    val fontResourceName: String, val font: PDFont?, val fontSize: Float,
    val textMatrix: Affine,          // Tm at run start
    val ctm: Affine,                 // CTM at run start
    val state: TextState,
    val fillColorTokens: List<Any>,  // tokens that re-establish the current fill: optional "/CS cs" + operands + colour operator
    val originX: Float, val originY: Float, // baseline start, page user space
    val endX: Float,                 // baseline end x, page user space
    val effectiveFontSize: Float,    // fontSize × vertical scale of (Tm·CTM)
    val isUpright: Boolean,          // (Tm·CTM) has no rotation/skew (b≈0, c≈0, d>0)
    val text: String,
    val showOperator: String,        // "Tj" | "TJ" | "'" | "\""
)
```

  - `DrawNode` gains `val run: TextRunInfo? = null`, set on every text-run node.
  - `ParsedPage` gains `val hasTextInForms: Boolean` (any `Do` of a form XObject whose content stream contains `Tj`/`TJ`/`'`/`"`; scanned shallowly with `PDFStreamParser`).
- Consumes: existing `ContentStreamEngine.parse(page: PDPage): ParsedPage`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun runCarriesBaselineAndState() {
    // hand-built stream (same mini-PDF helper style as ContentStreamEngineTest.samplePdf):
    // "BT /F1 10 Tf 2 Tc 50 Tz 1 0 0 1 100 700 Tm (AB) Tj ET"
    val run = runs(pdf).single()
    assertEquals(100f, run.originX, 0.01f); assertEquals(700f, run.originY, 0.01f)
    assertEquals(2f, run.state.charSpacing, 0f); assertEquals(50f, run.state.horizontalScaling, 0f)
    // Helvetica A=667, B=667: ((0.667*10)+2)*0.5 per glyph
    assertEquals(100f + 2 * ((0.667f * 10f + 2f) * 0.5f), run.endX, 0.05f)
}
@Test fun ctmScaleGivesEffectiveSize() { /* "2 0 0 2 0 0 cm BT /F1 10 Tf 10 10 Td (A) Tj ET" */
    assertEquals(20f, run.effectiveFontSize, 0.01f); assertEquals(20f, run.originX, 0.01f) }
@Test fun flippedCtmIsNotUpright() { /* "1 0 0 -1 0 792 cm BT /F1 10 Tf 10 10 Td (A) Tj ET" */
    assertFalse(run.isUpright) }
@Test fun fillColourTokensRecorded() { /* "0.2 0.4 0.6 rg BT ... ET" */
    assertEquals(listOf("0.2", "0.4", "0.6", "rg"), run.fillColorTokens.map(::tokenString)) }
@Test fun sampleLetterBaselines() {
    val runs = runs(PdfFixtures.sampleLetter())
    assertEquals("Date: 17 Octobr 2026", runs[1].text)
    assertTrue(runs.all { it.isUpright && it.effectiveFontSize in 10.9f..16.1f })
}
@Test fun wordSpacingAppliesOnlyToSingleByteSpace() { /* "BT /F1 10 Tf 5 Tw (A B) Tj ET": width includes +5 once */ }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.engine.TextRunInfoTest'`. Expected: FAIL (`run` is unresolved).
- [ ] **Step 3: Implement.** Track `Tc Tw Tz TL Ts Tr` in the builder. Remember the token slice that set the current fill colour (and the preceding `cs` name for `sc`/`scn`). Glyph advance = `((w/1000)·Tfs + Tc + (Tw if single-byte code 32)) · Th`, applied in `showText`. For `TJ` numbers, use `tx = −n/1000·Tfs·Th`. Build the `TextRunInfo` in `emitRun` from the matrix at `beginRun`. Every `q`/`Q` saves and restores the text state and the fill-colour tokens along with the CTM.
- [ ] **Step 4: Run** the full suite `./gradlew testDebugUnitTest`. Expected: PASS. The existing `ContentStreamEngineTest`/`ElementEditorTest` still pass.
- [ ] **Step 5: Commit** `feat(engine): capture full text state and baseline per run`.

---

### Task 3: Page analysis — lines, graphics, units, header/footer, pinned pages

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/FlowModel.kt`, `flow/PageAnalyzer.kt`, `flow/DocumentAnalyzer.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/DocumentAnalyzerTest.kt`

**Interfaces:**
- Consumes: `ContentStreamEngine.parse`, `DrawNode.run`, `ParsedPage.hasTextInForms`.
- Produces (`FlowModel.kt`):

```kotlin
sealed class BlockId { data class Orig(val page: Int, val index: Int) : BlockId()
                       data class Edited(val paragraph: ParagraphId, val line: Int) : BlockId() }
enum class BlockKind { LINE, GRAPHIC, UNIT }
class Block(val id: BlockId, val kind: BlockKind, val originPage: Int,
            val top: Float, val bottom: Float, val left: Float, val right: Float, // page user space, y up
            val gapBefore: Float,            // previous block's bottom − this top, in reading order on the page (0 for first)
            val runNodes: List<DrawNode>,    // text-run nodes drawn by this block
            val leafNodes: List<DrawNode>)   // path/image/form leaves drawn by this block
enum class PinReason { SCANNED_TEXT, TEXT_IN_FORM, ROTATED, OVERSIZED_UNIT }
class PageAnalysis(val pageIndex: Int, val mediaBox: PDRectangle, val parsed: ParsedPage,
                   val bodyTop: Float, val bodyLimit: Float,
                   val blocks: List<Block>,            // body blocks, reading order (top→bottom)
                   val fixedNodes: List<DrawNode>,     // headers/footers/backgrounds: never moved
                   val pinned: PinReason?)
class DocumentAnalysis(val pages: List<PageAnalysis>, val paragraphGap: Float, val medianBodyTop: Float)
fun lineTop(baseline: Float, size: Float) = baseline + 0.75f * size
fun lineBottom(baseline: Float, size: Float) = baseline - 0.25f * size
```

  - `object DocumentAnalyzer { fun analyze(doc: PDDocument): DocumentAnalysis }`
- Rules, which are the decisions this task pins:
  - **Lines:** upright runs whose baselines are within `0.2 × size` of each other, sorted by x, form one LINE. A line's extent uses `lineTop`/`lineBottom` of its largest effective size.
  - **Units:** if one baseline has segments separated by a horizontal gap greater than `2 × size`, it's a multi-segment row. A maximal vertical stretch of such rows becomes one UNIT, together with every node whose vertical range overlaps it.
  - **Graphics:** a path or image overlapping a line's vertical range by more than 50% of its own height belongs to that line's block. Other body graphics are their own GRAPHIC blocks. Paths or images covering more than 80% of the page area go in `fixedNodes`.
  - **Header/footer:** a node centred in the top or bottom 12% of the page height is a header/footer when either:
    - the same normalised text (digits → `#`) or the same graphic bounds (±2 pt) appears at the same y (±2 pt) on at least half the pages (documents of 2 pages or more), or
    - the document has 1 page and the node is separated from the body by at least 3 × the median line advance.
  - **Body area:**
    - `bodyTop` = the first block's top. Pages with no blocks use `medianBodyTop`.
    - `bodyLimit` = `min(lowest body bottom on any page, mediaBox.lowerLeftY + (mediaBox.upperRightY − medianBodyTop))`, then raised to at least `footerTop + paragraphGap` on pages that have a footer.
    - `paragraphGap` = the median `gapBefore` of blocks whose gap exceeds 1.3 × the median line gap. Default `8f`.
  - **Pinned pages:**
    - any run with `renderMode == 3` → `SCANNED_TEXT`
    - `hasTextInForms` → `TEXT_IN_FORM`
    - page `rotation != 0` or any non-upright body run → `ROTATED`
    - a UNIT taller than 90% of `bodyTop − bodyLimit` → `OVERSIZED_UNIT`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun sampleLetterHasOneLinePerTextLine() {
    val p = analyze(PdfFixtures.sampleLetter()).pages[0]
    assertEquals(17, p.blocks.count { it.kind == BlockKind.LINE }); assertNull(p.pinned)
}
@Test fun perWordRunsMergeIntoOneLine() {
    assertEquals(1, analyze(PdfFixtures.perWordRuns("The quick brown fox")).pages[0].blocks.size)
}
@Test fun twoColumnsBecomeOneUnit() {
    val p = analyze(PdfFixtures.twoColumn(List(5) { "left $it" }, List(5) { "right $it" })).pages[0]
    assertEquals(listOf(BlockKind.UNIT), p.blocks.map { it.kind })
}
@Test fun repeatedHeaderFooterAreFixed() {
    val a = analyze(PdfFixtures.withHeaderFooter(3, List(10) { "Body $it" }))
    a.pages.forEach { pg -> assertTrue(pg.fixedNodes.any { it.run?.text == "Riverside Trust" }) }
    a.pages.forEach { pg -> assertTrue(pg.blocks.none { b -> b.runNodes.any { it.run!!.text.startsWith("Page ") } }) }
}
@Test fun rotatedPageIsPinned() { assertEquals(PinReason.ROTATED, analyze(PdfFixtures.rotated()).pages[0].pinned) }
@Test fun gapsReproduceOriginalTops() { // first block at bodyTop, each next top == prev.bottom − gapBefore
    analyze(PdfFixtures.sampleLetter()).pages[0].blocks.zipWithNext { a, b -> assertEquals(b.top, a.bottom - b.gapBefore, 0.01f) }
}
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.DocumentAnalyzerTest'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** `DocumentAnalyzer.analyze` and `PageAnalyzer` (one page, given the cross-page header/footer keys) following the rules above.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): analyze pages into flow blocks`.

---

### Task 4: Paragraph detection

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/Paragraphs.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/ParagraphDetectorTest.kt`

**Interfaces:**
- Consumes: `PageAnalysis`, `Block`, `TextRunInfo`.
- Produces:

```kotlin
data class ParagraphId(val page: Int, val firstBlock: Int)
enum class Alignment { LEFT, RIGHT, CENTER, JUSTIFIED }
data class SpanStyle(val fontResourceName: String, val effectiveFontSize: Float, val horizontalScale: Float,
                     val fillColorTokens: List<String>, val rise: Float, val charSpacing: Float) {
    var font: PDFont? = null   // declared in the body, so not part of equals/hashCode
}
data class Span(val text: String, val style: SpanStyle)
class Paragraph(val id: ParagraphId, val lineBlocks: List<BlockId>, val spans: List<Span>,
                val left: Float, val right: Float, val firstLineIndent: Float,
                val lineAdvance: Float,          // baseline-to-baseline
                val firstBaseline: Float, val alignment: Alignment,
                val canGrow: Boolean,            // false inside a UNIT or on a pinned page
                val nonLatin: Boolean)           // any char outside Basic Latin + Latin-1 Supplement + Latin Extended-A/B + General Punctuation
object ParagraphDetector { fun detect(page: PageAnalysis): List<Paragraph> }
fun Paragraph.text(): String = spans.joinToString("") { it.text }
```

- Rules:
  - Consecutive LINE blocks join a paragraph when all of these hold:
    - the dominant effective size is equal (±0.5 pt)
    - the baseline gap is within ±10% of the paragraph's first gap and at most 1.5 × line height
    - the left edge is equal (±2 pt); the second line may differ from the first to allow a first-line indent
  - **Alignment:**
    - JUSTIFIED: every line but the last ends within 2 pt of `right`
    - CENTER: line midpoints are equal (±2 pt)
    - RIGHT: right edges are equal (±2 pt) but left edges vary
    - otherwise LEFT
  - **Joining text:** runs on one line join with a space where the x gap exceeds `0.2 × size`. Lines join with one space. Adjacent runs with equal `SpanStyle` merge into one span.
  - **Fallback:** a line that doesn't fit any multi-line paragraph becomes a one-line paragraph. Its `right` = the max right edge of body lines on the page, so it can still wrap.
  - Lines inside a UNIT form paragraphs with `canGrow = false`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun sampleLetterLongParagraph() {
    val ps = detect(PdfFixtures.sampleLetter())
    val p = ps.single { it.text().startsWith("The construction team") }
    assertEquals(6, p.lineBlocks.size); assertEquals(Alignment.LEFT, p.alignment)
    assertTrue(p.text().endsWith("before the official opening."))
    assertEquals(16f, p.lineAdvance, 0.1f); assertTrue(p.canGrow); assertFalse(p.nonLatin)
}
@Test fun typoLineIsOneLineParagraph() {
    assertEquals(1, detect(PdfFixtures.sampleLetter()).single { it.text() == "Date: 17 Octobr 2026" }.lineBlocks.size)
}
@Test fun justifiedDetected() {
    assertEquals(Alignment.JUSTIFIED, detect(PdfFixtures.letter(listOf("word ".repeat(80)), align = "justify")).first().alignment)
}
@Test fun boldWordStaysInParagraphAsSeparateSpan() { /* fixture line with LiberationSerif-Bold for one word */
    assertEquals(3, p.spans.size); assertEquals(1, ps.size) }
@Test fun unitParagraphsCannotGrow() { assertTrue(detect(PdfFixtures.twoColumn(listOf("a"), listOf("b"))).none { it.canGrow }) }
@Test fun latinCheck() { assertFalse(isLatinText("नमस्ते")); assertTrue(isLatinText("Café – “ok”")) }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.ParagraphDetectorTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `ParagraphDetector.detect`, plus `fun isLatinText(s: String): Boolean` (public, same file).
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): detect paragraphs, alignment and styled spans`.

---

### Task 5: Paragraph text layout (wrapping)

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/ParagraphLayout.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/ParagraphLayoutTest.kt`

**Interfaces:**
- Consumes: `Span`, `SpanStyle`, `Alignment`; `FontPlan.fontFor` from Task 7. Define the `FontPlan` class in this task with a constructor `FontPlan(fonts: Map<SpanStyle, PDFont>, warning: FontWarning?)`; Task 7 adds the resolver.
- Produces:

```kotlin
class FontWarning(val missingChars: String, val substituteNames: List<String>)
class FontPlan(private val fonts: Map<SpanStyle, PDFont>, val warning: FontWarning?) { fun fontFor(style: SpanStyle): PDFont }
class PlacedFragment(val text: String, val style: SpanStyle, val x: Float, val width: Float,
                     val spaceAdjust: Float)   // extra page-space width added after each ' ' (justification)
class LaidLine(val fragments: List<PlacedFragment>, val baseline: Float)
object ParagraphLayout {
    fun layout(spans: List<Span>, plan: FontPlan, left: Float, right: Float, firstLineIndent: Float,
               firstBaseline: Float, lineAdvance: Float, alignment: Alignment): List<LaidLine>
    fun textWidth(text: String, style: SpanStyle, font: PDFont): Float // font.getStringWidth/1000·size·hScale + charSpacing·len
}
```

- Rules:
  - Break at spaces. `'\n'` forces a break.
  - A single word wider than the line width is broken at the last character that fits.
  - Leading spaces on wrapped lines are dropped.
  - JUSTIFIED spreads the leftover width evenly over the spaces of every line except the last line and lines ending in `'\n'`.
  - Empty text → empty list.

- [ ] **Step 1: Write failing tests** (fonts: `PdfFixtures.font(doc)`, Liberation Serif 11 pt, width 200)

```kotlin
@Test fun wrapsGreedily() { val lines = lay("aaa bbb ccc", width = textWidth("aaa bbb") + 1f)
    assertEquals(listOf("aaa bbb", "ccc"), lines.map { l -> l.fragments.joinToString("") { it.text } }) }
@Test fun baselinesStepByAdvance() { assertEquals(listOf(700f, 684f), lay("x\ny", advance = 16f).map { it.baseline }) }
@Test fun hardBreakKeepsEmptyLine() { assertEquals(3, lay("a\n\nb").size) }
@Test fun longWordBreaks() { assertTrue(lay("x".repeat(500)).size > 1) }
@Test fun justifyFillsWidthExceptLast() {
    val ls = lay("word ".repeat(40).trim(), align = Alignment.JUSTIFIED)
    ls.dropLast(1).forEach { l -> val f = l.fragments.last(); assertEquals(right, f.x + f.width + f.spaceAdjust * f.text.count { it == ' ' }, 0.5f) }
    assertEquals(0f, ls.last().fragments.sumOf { it.spaceAdjust.toDouble() }.toFloat(), 0f)
}
@Test fun centerAndRight() { /* single short line: CENTER x == left + (w − tw)/2 ; RIGHT x == right − tw */ }
@Test fun stylesSplitIntoFragments() { /* spans [plain "a ", bold "b", plain " c"] → one line, 3 fragments with their styles */ }
@Test fun firstLineIndent() { /* first line starts at left + indent, second at left */ }
@Test fun emptyTextGivesNoLines() { assertTrue(lay("").isEmpty()) }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.ParagraphLayoutTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `ParagraphLayout.layout` and `textWidth`.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): paragraph wrapping and alignment`.

---

### Task 6: Map styles onto edited text

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/StyleMapper.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/StyleMapperTest.kt`

**Interfaces:**
- Produces: `object StyleMapper { fun apply(old: List<Span>, newText: String): List<Span> }`
- Rule: character-level diff using the longest common subsequence via dynamic programming over code points. Fall back to common prefix + suffix when `old.length × new.length > 4_000_000`.
  - Kept characters keep their style.
  - Inserted characters take the style of the kept or inserted character before them, or of the first old character when inserted at the start.
  - Adjacent equal styles merge. An empty `newText` gives an empty list.

- [ ] **Step 1: Write failing tests**

```kotlin
private val R = style("F1"); private val B = style("F2")
@Test fun typoFixKeepsStyle() { assertEquals(listOf(Span("October", R)), StyleMapper.apply(listOf(Span("Octobr", R)), "October")) }
@Test fun boldWordSurvivesRewrite() {
    val out = StyleMapper.apply(listOf(Span("see ", R), Span("this", B), Span(" now", R)), "please see this right now")
    assertEquals(listOf("please see ", "this", " right now"), out.map { it.text }); assertEquals(B, out[1].style)
}
@Test fun insertAtStartTakesFirstStyle() { assertEquals(B, StyleMapper.apply(listOf(Span("x", B)), "yx")[0].style) }
@Test fun deleteAllGivesEmpty() { assertTrue(StyleMapper.apply(listOf(Span("abc", R)), "").isEmpty()) }
@Test fun hugeInputUsesFallback() { StyleMapper.apply(listOf(Span("a".repeat(3000), R)), "b".repeat(3000)) /* completes < 2 s */ }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.StyleMapperTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `StyleMapper.apply`.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): carry styles across text edits`.

---

### Task 7: Font resolution (reuse embedded font or substitute the whole paragraph)

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/FontResolver.kt`
- Modify: `app/src/main/java/app/pdfeditor/FontCatalog.kt` (implement `FontProvider`)
- Test: `app/src/test/java/app/pdfeditor/flow/FontResolverTest.kt`, `app/src/test/java/app/pdfeditor/testing/TestFontProvider.kt`

**Interfaces:**
- Consumes: `Span`, `SpanStyle.font`, `FontPlan`, `FontWarning` (Task 5).
- Produces:

```kotlin
interface FontProvider {
    fun matchId(original: PDFont?): String?              // FontCatalog.autoMatchId
    fun load(doc: PDDocument, id: String): PDFont?        // FontCatalog.resolve
    fun displayName(id: String): String
}
sealed class FontPlanResult { class Ok(val plan: FontPlan) : FontPlanResult()
                              class Unsupported(val chars: String) : FontPlanResult() }
object FontResolver { fun plan(doc: PDDocument, spans: List<Span>, provider: FontProvider): FontPlanResult }
```

  - `TestFontProvider` maps family names to bundled assets: Georgia → `Gelasio-*`, Times → `LiberationSerif-*`, Helvetica/Arial → `LiberationSans-*`, Courier → `LiberationMono-*`, keeping Bold/Italic from the base font name. It loads from `src/main/assets/fonts`.
- Rules:
  - Tabs → single space before checking.
  - If every span's own font encodes its text, the plan uses those fonts with no warning.
  - Otherwise every span switches to `provider.load(doc, provider.matchId(span.font) ?: "bundled:LiberationSerif-Regular")`. The warning lists the distinct missing characters in order and the distinct substitute display names.
  - If a substitute also can't encode some characters, the result is `Unsupported` with those characters.
- `FontCatalog` already has `autoMatchId`/`resolve`; add `: FontProvider` and `displayName` (the entry label).

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun typoFixReusesEmbeddedFont() { // sample letter "Date: 17 October 2026" — 'e' exists in the subset
    val r = plan(sampleSpans("Date: 17 October 2026")) as FontPlanResult.Ok; assertNull(r.plan.warning) }
@Test fun missingGlyphSubstitutesWholeParagraph() {
    val r = plan(sampleSpans("Zebra Quiz")) as FontPlanResult.Ok
    assertEquals("Z Q", r.plan.warning!!.missingChars.toList().joinToString(" "))
    assertEquals(listOf("Gelasio"), r.plan.warning!!.substituteNames)
}
@Test fun emojiUnsupported() { assertEquals("😀", (plan(sampleSpans("hi 😀")) as FontPlanResult.Unsupported).chars) }
@Test fun devanagariUnsupported() { assertTrue(plan(sampleSpans("नमस्ते")) is FontPlanResult.Unsupported) }
@Test fun tabBecomesSpace() { assertTrue(plan(sampleSpans("a\tb")) is FontPlanResult.Ok) }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.FontResolverTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `FontResolver.plan`, `TestFontProvider`, and `FontCatalog : FontProvider`.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): font plan with whole-paragraph substitution`.

---

### Task 8: Flow layout across pages

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/FlowLayout.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/FlowLayoutTest.kt`

**Interfaces:**
- Consumes: `BlockId`.
- Produces:

```kotlin
sealed class PageSlot { data class Original(val index: Int) : PageSlot()
                        data class UserBlank(val uid: Int) : PageSlot()
                        data class Auto(val n: Int) : PageSlot() }
class SlotSpec(val slot: PageSlot, val width: Float, val height: Float,
               val bodyTop: Float, val bodyLimit: Float, val pinned: Boolean)   // UserBlank is always pinned
class FlowBlock(val id: BlockId, val originSlot: Int, val height: Float, val gapBefore: Float,
                val paragraph: ParagraphId?)
class Placement(val id: BlockId, val slotIndex: Int, val top: Float)  // slotIndex into FlowResult.slots
class FlowResult(val slots: List<SlotSpec>, val placements: List<Placement>)
object FlowLayout { fun layout(slots: List<SlotSpec>, blocks: List<FlowBlock>, paragraphGap: Float): FlowResult }
```

- Algorithm (deterministic):
  - Walk the slots in order with a queue: blocks carried over from the previous slot first, then this slot's origin blocks in reading order.
  - On each slot the first placed block sits at `bodyTop`. Each later block sits at `previousBottom − gap`, where `gap` is:
    - the block's `gapBefore`, if it's on its origin slot and its predecessor is also from that slot
    - otherwise the line gap of its paragraph when the predecessor is in the same paragraph, else `paragraphGap`
  - A block that would end below `bodyLimit` moves, together with everything after it in the queue, to the next slot. The exception is a block that's first on its slot, which is placed anyway.
  - If the next slot is pinned or doesn't exist, insert `Auto` (same width/height/bodyTop/bodyLimit as the current slot) before it.
  - Pinned slots keep their own blocks exactly at their original positions (`top` = original top) and accept no carried blocks.
  - Blocks never move to a slot earlier than their origin.
  - `Auto` slots exist only in the output, so an empty one is never produced.

- [ ] **Step 1: Write failing tests** (helper `slot(i)` has height 800, bodyTop 700, bodyLimit 100; blocks of height 20 with gap 5)

```kotlin
@Test fun unchangedInputReproducesOriginalTops() { /* tops 700,675,650… */ }
@Test fun overflowMovesToNextOriginalPage() { /* slot0 gets 30 blocks → those past limit start at slot1.bodyTop, before slot1's own blocks */ }
@Test fun overflowFromLastPageCreatesAutoPage() { assertTrue(result.slots.last().slot is PageSlot.Auto) }
@Test fun shrinkingBringsCarriedBlocksBackAndDropsAuto() { /* same blocks minus 10 → no Auto slot, all on slot0 */ }
@Test fun neverMovesToEarlierPage() { /* slot0 has 2 blocks, slot1's blocks stay on slot1 */ }
@Test fun userBlankPageStaysBlank() { /* [Original0 overflowing, UserBlank, Original1] → Auto inserted between Original0 and UserBlank; UserBlank has no placements */ }
@Test fun pinnedPageGetsAutoBeforeIt() { /* [Original0 overflowing, Original1 pinned] → slots [O0, Auto, O1]; O1 blocks at original tops */ }
@Test fun oversizedFirstBlockIsPlacedAnyway() { /* block taller than body → placed at bodyTop, no infinite loop */ }
@Test fun paragraphSplitAcrossPagesUsesLineGapOnRejoin() { /* carried line followed by same-paragraph line on next slot → gap = line gap, not paragraphGap */ }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.FlowLayoutTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `FlowLayout.layout`.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): page flow layout with auto pages and pinned barriers`.

---

### Task 9: Page writer — remove, move and draw content

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/PageWriter.kt`, `flow/ResourceCopier.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/PageWriterTest.kt`

**Interfaces:**
- Consumes: `Block`, `TextRunInfo`, `LaidLine`, `FontPlan`, `SpanStyle`.
- Produces:

```kotlin
sealed class Draw {
    class MoveBlock(val block: Block, val sourcePage: PDPage, val dy: Float) : Draw()   // redraw original tokens shifted
    class NewLine(val line: LaidLine, val sourcePage: PDPage) : Draw()                 // text from ParagraphLayout
}
object ResourceCopier {
    /** Ensures `name` from `source` (category = Font/XObject/ExtGState/ColorSpace/Pattern/Shading) exists in `target`; returns the name to use there. */
    fun ensure(target: PDResources, source: PDResources, category: COSName, name: COSName): COSName
    fun ensureFont(target: PDResources, font: PDFont): COSName
}
object PageWriter {
    /** Rewrites `page`: `q` + original tokens with `removed` blocks' drawing tokens taken out + `Q`, then each draw wrapped in `q … Q`. */
    fun write(doc: PDDocument, page: PDPage, originalTokens: List<Any>?, removed: List<Block>, draws: List<Draw>)
    fun removalTokens(run: TextRunInfo): List<Any>  // replacement for a removed show op: [] for Tj/TJ, [T*] for ', [aw Tw ac Tc T*] for "
}
```

- Rules:
  - **Removing text:** drop each run's operand+operator slice (use `DrawNode.startIndex..endIndex`) and splice in `removalTokens`. Removing a path drops its construction+paint slice; removing an image drops its `Do` slice.
  - **Moving a block:**
    - each run → `q 1 0 0 1 0 dy cm <ctm> cm BT <fill tokens> /F size Tf Tc Tw Tz TL Ts Tr <Tm> <original show tokens> ET Q`
    - each path/image leaf → `q 1 0 0 1 0 dy cm <ctm> cm <original tokens> Q`
    - resource names are passed through `ResourceCopier`, with tokens renamed when the target uses a different name
  - **New lines:** `q BT <fill tokens> /F size Tf Tc Tz Ts 1 0 0 1 x baseline Tm [TJ array] ET Q` per fragment, where `size` = `effectiveFontSize`, `Tz` = `horizontalScale × 100`, and the TJ array spreads `spaceAdjust` as `−spaceAdjust×1000/size` numbers after each space (this works for Type0 fonts, where `Tw` doesn't).
  - `originalTokens == null` (blank or auto page) → only the draws.
  - Pages are always written with `ContentStreamWriter` into a new FlateDecode `PDStream` set via `page.setContents`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun moveLineDownShiftsBaseline() { /* letter fixture, move block 0 by dy=-30 → reparsed run originY == old − 30 */ }
@Test fun movedTextKeepsFontAndColour() { /* coloured fixture line moved → TextRunInfo.fillColorTokens and fontSize unchanged */ }
@Test fun moveLineToOtherPageCopiesFontResource() {
    // fixture with 2 pages whose fonts have different resource names; move a page-0 line onto page 1 (dy to land in body)
    assertTrue(extractText(saved, page = 2).contains(movedLineText))
}
@Test fun removedTextIsGoneFromExtraction() { assertFalse(extractText(saved).contains("Octobr")) }
@Test fun tickQuoteRemovalKeepsFollowingLinesInPlace() { /* stream "BT /F1 10 Tf 12 TL 72 700 Td (a) Tj (b) ' (c) ' ET", remove run "b" → "c" baseline unchanged */ }
@Test fun newLineJustifiedTjArray() { /* NewLine with spaceAdjust 2 → reparsed run endX == expected right edge ±0.5 */ }
@Test fun untouchedPageBytesUnchanged() { /* write() not called for page 1 → its content stream bytes identical */ }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.PageWriterTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `PageWriter` and `ResourceCopier`.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): page writer for moved and new content`.

---

### Task 10: EditSession — state, edits, pages, undo, full save

**Files:**
- Create: `app/src/main/java/app/pdfeditor/flow/EditSession.kt`
- Test: `app/src/test/java/app/pdfeditor/flow/EditSessionTest.kt`

**Interfaces:**
- Consumes: Tasks 3–9.
- Produces:

```kotlin
sealed class EditOp { class EditParagraph(val id: ParagraphId, val newText: String) : EditOp()
                      class AddBlankPage(val beforePage: Int) : EditOp()      // index in current output; == pageCount appends
                      class DeletePage(val page: Int) : EditOp() }
enum class RejectReason { NON_LATIN, UNSUPPORTED_CHARS, NO_ROOM, NOT_FOUND, LAST_PAGE }
sealed class EditOutcome { object Applied : EditOutcome()
                           class NeedsFontConfirmation(val warning: FontWarning) : EditOutcome()
                           class Rejected(val reason: RejectReason, val detail: String = "") : EditOutcome() }
class ParagraphHit(val id: ParagraphId, val text: String, val page: Int, val bounds: Bounds,
                   val editable: Boolean, val reason: RejectReason?)
sealed class OpenResult { class Opened(val session: EditSession) : OpenResult()
                          object PasswordProtected : OpenResult()
                          class Failed(val message: String) : OpenResult() }
class EditSession private constructor(/* original bytes, analysis doc, DocumentAnalysis, paragraphs, provider */) {
    companion object { fun open(bytes: ByteArray, fonts: FontProvider): OpenResult }
    val pageCount: Int
    fun renderedBytes(): ByteArray                   // current state, full save — used for display
    fun paragraphAt(page: Int, x: Float, y: Float): ParagraphHit?
    fun apply(op: EditOp, acceptSubstitution: Boolean = false): EditOutcome
    val canUndo: Boolean; val canRedo: Boolean
    fun undo(); fun redo()
    fun save(out: OutputStream)                      // full save of current state
}
```

- Decisions pinned here:
  - **State:** `EditState(val slots: List<PageSlot> /* Original/UserBlank only */, val edits: Map<ParagraphId, List<Span>>, val deletedBlocks: Set<BlockId>)` is immutable, with undo and redo stacks of states capped at 50.
  - **Rendering a state:**
    1. Build `FlowBlock`s. Edited paragraphs contribute `BlockId.Edited` lines from `ParagraphLayout` (height = `lineTop − lineBottom`; the first line's gap is the original paragraph's first `gapBefore`; later lines use `lineAdvance − height`).
    2. Run `FlowLayout`.
    3. `PDDocument.load(originalBytes)`, then rebuild the page tree in slot order: Original pages reused, UserBlank and Auto pages created with the neighbour's media box, deleted originals removed.
    4. For every page holding a moved, removed or new block, call `PageWriter.write`. Pages whose placements all equal their original tops on their origin page are not written.
  - **Edits:**
    - `EditParagraph` on a `nonLatin` paragraph → `Rejected(NON_LATIN)`.
    - `FontResolver` `Unsupported` → `Rejected(UNSUPPORTED_CHARS, chars)`.
    - A warning with `acceptSubstitution == false` → `NeedsFontConfirmation`, and the state is unchanged.
    - When `canGrow == false` and the new line count × `lineAdvance` exceeds the original height → `Rejected(NO_ROOM)`.
    - Empty text deletes the paragraph's lines.
  - **Pages:**
    - `DeletePage` removes a UserBlank slot outright. On a page holding original content, it adds that page's currently placed blocks to `deletedBlocks` and removes the slot if it's an Original. Deleting the only page → `Rejected(LAST_PAGE)`.
    - `AddBlankPage` inserts `UserBlank(uid)`.
  - **Opening:** `InvalidPasswordException` → `PasswordProtected`; any other load failure → `Failed("This file couldn't be opened as a PDF")`. An owner-password-only PDF opens, and `save` sets `isAllSecurityToBeRemoved = true`.
  - **Saving:** `save` writes `doc.save(out)`. The document information dictionary carries over unchanged.

- [ ] **Step 1: Write failing tests** (all on `PdfFixtures` + `TestFontProvider`)

```kotlin
@Test fun noEditRoundTripKeepsLayout() { /* open sample letter, save → every run's originY equals original ±0.01 */ }
@Test fun fixTypo() {
    s.apply(EditParagraph(idOf("Date: 17 Octobr 2026"), "Date: 17 October 2026"))
    val t = extractText(saved()); assertTrue(t.contains("October 2026")); assertFalse(t.contains("Octobr"))
}
@Test fun substitutionNeedsConfirmation() {
    assertTrue(s.apply(EditParagraph(id, "Zebra")) is EditOutcome.NeedsFontConfirmation); assertFalse(s.canUndo)
    assertEquals(EditOutcome.Applied, s.apply(EditParagraph(id, "Zebra"), acceptSubstitution = true))
}
@Test fun longParagraphFlowsToNewPageAndUndoRemovesIt() {
    s.apply(EditParagraph(longId, longText.repeat(6)), acceptSubstitution = true)
    assertEquals(2, s.pageCount); assertTrue(extractText(saved(), page = 2).contains("Project Coordinater"))
    s.undo(); assertEquals(1, s.pageCount)
}
@Test fun shorterParagraphPullsContentUp() { /* "Warm regards," originY increases */ }
@Test fun deleteAllTextRemovesParagraph() { /* Review Focus 1: paragraph gone, next block moves up by its height+gap */ }
@Test fun addBlankPageStaysBlankEvenWhenFlowing() {
    s.apply(AddBlankPage(beforePage = 1)); s.apply(EditParagraph(longId, longText.repeat(6)), true)
    assertEquals("", extractText(saved(), page = 3).trim())   // [p1, auto, blank]
}
@Test fun deletePage() { /* 3-page letter, DeletePage(1) → pageCount 2, text of old page 2 absent */ }
@Test fun cannotDeleteOnlyPage() { assertEquals(RejectReason.LAST_PAGE, (s.apply(DeletePage(0)) as EditOutcome.Rejected).reason) }
@Test fun rotatedPageGrowthRejected() { /* Review Focus 3: NO_ROOM when growing; a same-length edit is Applied */ }
@Test fun passwordPdfReported() { assertTrue(EditSession.open(PdfFixtures.encrypted("pw"), fonts) is OpenResult.PasswordProtected) }
@Test fun garbageReported() { assertTrue(EditSession.open("hello".toByteArray(), fonts) is OpenResult.Failed) }
@Test fun saveIsFullRewrite() { assertEquals(1, String(saved(), Charsets.ISO_8859_1).split("%%EOF").size - 1) }
@Test fun metadataPreserved() { assertEquals("Sample Letter", PDDocument.load(saved()).documentInformation.title) }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.EditSessionTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `EditSession`.
- [ ] **Step 4: Run** the same test, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** `feat(flow): edit session with reflow, pages, undo and full save`.

---

### Task 11: App UI on top of EditSession

**Files:**
- Modify: `app/src/main/java/app/pdfeditor/PdfDocumentViewModel.kt` (rewrite around `EditSession`), `MainActivity.kt`, `ui/PdfCanvas.kt`, `ui/Toolbar.kt`
- Create: `ui/ParagraphEditSheet.kt`, `ui/PagesMenu.kt`, `ui/Dialogs.kt` (font warning, messages)
- Delete: `ui/InspectorDock.kt`, `ui/InspectorPane.kt`, `ui/DebugOverlay.kt`, `ui/ElementEditSheet.kt`, `ui/Tools.kt`, `engine/ElementEditor.kt`, `test/.../ElementEditorTest.kt`, and the font-import UI path (keep `FontCatalog`)

**Interfaces:**
- Consumes: `EditSession`, `EditOp`, `EditOutcome`, `ParagraphHit`, `OpenResult`, `FontCatalog : FontProvider`.
- Produces: `PdfDocumentViewModel` public API
  - `open(context, uri)`
  - `showPage(i)`
  - `tap(pageX, pageY)` → sets `state.editing: ParagraphHit?` or `state.message`
  - `submitParagraph(newText)`
  - `confirmSubstitution()` / `cancelSubstitution()`
  - `addPageBefore(i)`, `addPageAfter(i)`, `deleteCurrentPage()`
  - `undo()`, `redo()`, `saveCopy(context, uri)`
- Behaviour:
  - Every successful apply writes `renderedBytes()` to `cacheDir/working.pdf` and re-renders through `PdfRenderer` (the existing render path).
  - Each blocking state shows the existing busy overlay.
  - The paragraph sheet is a multi-line `OutlinedTextField` (Enter inserts a newline) with **Cancel** and **Done**.
- User-facing text, exact:
  - Font warning dialog: title "Font not available", body "The original font doesn't include: {chars}. This paragraph will use {names}, which looks similar.", buttons "Use similar font" / "Cancel".
  - `NON_LATIN`: "Editing this language isn't supported yet."
  - `UNSUPPORTED_CHARS`: "These characters can't be added: {chars}"
  - `NO_ROOM`: "There's no room to make this text longer here. Please shorten it."
  - `LAST_PAGE`: "A document needs at least one page."
  - `PasswordProtected`: "This PDF is password-protected."
  - Open failure: "This file couldn't be opened as a PDF."
  - Save success toast: "Saved a copy"
- Toolbar: Open, Undo, Redo, Pages (menu: "Add blank page before", "Add blank page after", "Delete this page"), Save copy. A page indicator with previous/next buttons. Tapping text outlines the hit paragraph's bounds before opening the sheet.

- [ ] **Step 1: Remove the inspector-only UI and engine code listed above.** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: BUILD SUCCESSFUL. Fix compile references by deleting dead code, not by stubbing.
- [ ] **Step 2: Implement the view model API and screens above.**
- [ ] **Step 3: Build and install.** Run `./gradlew assembleDebug && scripts/check-no-permissions.sh && ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`. Expected: `OK: no permissions`, then `Success`.
- [ ] **Step 4: Manual acceptance on the emulator** (start `Pixel_8_API_36` if it isn't running, and push `sample-letter.pdf` to `/sdcard/Download/`). Each must hold:
  1. Opening `sample-letter.pdf` shows the letter.
  2. Fixing "Octobr", "suport" and "Coordinater" changes the text with the font unchanged.
  3. Rewriting the long paragraph to about 3× its length with Enter breaks: content below moves down and continues on a new page 2.
  4. Undo removes page 2.
  5. Typing "Zebra" shows the font dialog, and Cancel leaves the text unchanged.
  6. Add blank page after, then delete it.
  7. Save copy to Downloads, and the copy opens in PDF Inspector with the edits.
  8. The app's permission screen in Settings lists "No permissions requested".
- [ ] **Step 5: Commit** `feat(app): simple editor UI on EditSession; remove inspector UI`.

---

### Task 12: Real-world corpus, About screen and README

**Files:**
- Create: `app/src/test/resources/fixtures/corpus/` (`sample-letter.pdf`, plus whatever owner-supplied Word, Google Docs, LibreOffice and Chrome print-to-PDF files are available; see Step 1)
- Create: `app/src/test/java/app/pdfeditor/flow/CorpusTest.kt`
- Create: `ui/AboutSheet.kt` (reachable from the toolbar overflow menu)
- Modify: `README.md`, `fastlane/metadata/android/en-US/*` (new name and description, or delete the folder)

**Interfaces:**
- Consumes: `EditSession`, `TestFontProvider`.

- [ ] **Step 1: Collect corpus files.**
  - If Google Chrome is installed, generate one with `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless --print-to-pdf=chrome-print.pdf <a local HTML page of three paragraphs>`.
  - Ask the owner for 2–3 of their own non-sensitive PDFs exported from Word and Google Docs, and add them when they arrive.
  - Note in `corpus/README.md` where each file came from.
- [ ] **Step 2: Write `CorpusTest`.** For every PDF in `corpus/`:
  - `noEditRoundTrip`: positions of all runs are unchanged ±0.05.
  - `editFirstEditableParagraph`: append " Added sentence for testing." with `acceptSubstitution = true`. The outcome is `Applied` or `Rejected(NO_ROOM | NON_LATIN)`, never an exception, and the saved file reloads with `PDDocument.load`.
- [ ] **Step 3: Run** `./gradlew testDebugUnitTest --tests 'app.pdfeditor.flow.CorpusTest'`. Expected: PASS. Fix any failure in the owning component (Tasks 2–10), with a regression test there.
- [ ] **Step 4: About sheet.** App name and version, "Works fully offline. Your files never leave your phone.", "Based on PDF Inspector by Shardul Vikram Singh (MIT License)" with the full MIT text, and the font licences from `assets/fonts/LICENSE-*.txt`.
- [ ] **Step 5: README** for a beginner:
  - what the app does
  - installing JDK 17 and setting `JAVA_HOME`
  - opening the project in Android Studio
  - `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`
  - starting `Pixel_8_API_36` and installing with adb
  - the privacy promise and `scripts/check-no-permissions.sh`
  - credits
- [ ] **Step 6: Run** `./gradlew testDebugUnitTest assembleDebug && scripts/check-no-permissions.sh`. Expected: BUILD SUCCESSFUL, then `OK: no permissions`.
- [ ] **Step 7: Commit** `docs: corpus tests, about screen and beginner README`.
