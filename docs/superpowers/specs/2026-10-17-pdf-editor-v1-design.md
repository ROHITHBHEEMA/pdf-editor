# Trustworthy PDF Editor for Android — Version 1 Design

Date: 2026-10-17

## Intent and why

The owner wanted to edit PDFs on an Android phone and found no app they trusted.
The apps they found upload files to servers, show ads, or ask for permissions
they don't need. Nobody should have to hand a personal document to an unknown
server just to fix a typo.

This app is a PDF editor you can trust with personal documents: everything
happens on the phone, nothing is uploaded, and the app is honest about what it
can and can't do.

What makes it special is real paragraph editing. An existing open-source app
(PDF Inspector, below) already fixes words inside a line. The owner tested it on
a sample letter. Spelling fixes worked, but they couldn't start a new line, and
a longer paragraph couldn't push the text below it down. Version 1 closes that
gap.

## Goals (version 1)

1. **Fix words in place.** Tap a word or line in a text PDF and correct it (a
   typo, a name, a date, a number). It keeps the original look.
2. **Rewrite whole paragraphs.** Tap a paragraph and edit it like ordinary text.
   You can type, delete and press Enter for a new line, and the text wraps
   automatically within the paragraph's original width. On save it's redrawn in
   the same font, size, colour, line spacing and alignment, so it looks like it
   was always there.
3. **Push following content down (or pull it up).** When a rewritten paragraph
   gets longer, the content below it on the page moves down to make room. When
   it gets shorter, that content moves up. Nothing overlaps.
4. **Flow onto the next page, like Word** *(confirmed by the owner)*. Content
   pushed past the bottom margin continues at the top of the next page, pushing
   that page's content down in turn. If the last page overflows, a new page of
   the same size is created.
5. **Add and delete pages.** Insert a blank page (same size as its neighbour)
   before or after any page, and delete any page.
6. **Save safely.** Saving writes a new file. The original is never modified.

## Non-goals (later, explicitly deferred by the owner)

Editing non-English scripts (Telugu, Hindi and others), annotations,
highlighting, drawing, signatures, form filling, merge/split,
compression, conversion to/from Word or images, OCR or editing scanned PDFs,
reordering pages, cloud sync, password-protected PDFs, multiple languages in the
UI, tablets-specific layout, iOS or desktop versions.

## Anti-goals (would make it a failure even if it technically works)

- **Any network access.** The app must not declare the `INTERNET` permission
  at all, and must include no analytics, crash reporting, ads or other network
  SDKs. Anyone can check this in the app's permission list, which is a
  promise users can verify for themselves.
- **Broad file access.** No "all files" or storage permissions. Files are opened
  and saved only through the system file picker (Storage Access Framework).
- **An account or sign-in** of any kind.
- **Edited text that visibly doesn't match.** A wrong font, size or spacing, or
  text overlapping other content, means the edit failed. If the app can't
  reproduce the original look faithfully, it must **say so before saving** (for
  example, "the original font doesn't contain 'Z'; a similar font will be used")
  instead of silently producing a worse result.
- **Corrupting or losing the original file.**

## Constraints

- Platform: Android phones, Android 8.0 (API 26) and newer. That matches the
  base project below.
- Fully offline and free of ads.
- The owner is new to Android development. Code, build steps and README must be
  simple enough for a beginner to build and run from Android Studio.
- Licensing: dependencies must be permissive (MIT, Apache 2.0, BSD or similar).
  **MuPDF (AGPL) and paid SDKs (Nutrient, ComPDF and others) are excluded.**
- Development machine: Apple M4 Mac, 16 GB of memory. Android Studio, the SDK and
  an emulator (`Pixel_8_API_36`, Android 16) are already installed, with PDF
  Inspector installed on it.
- **Documents can come from anywhere** *(owner)*: Word, Google Docs,
  LibreOffice, browser "print to PDF", websites, bank or government portals and
  so on. The test set must cover several producers, at least Word, Google Docs,
  LibreOffice, Chrome print-to-PDF and LaTeX, plus generated files. Unusual
  encodings must fail safely by falling back to line editing or a clear message.
  They must never produce a corrupted file.
- **English only for now** *(owner)*: version 1 edits English (Latin-script)
  text. Other languages come later. If a document contains text in another
  script, that text is left untouched and still shows correctly. If the user
  tries to edit it, the app explains that this isn't supported yet.
- Test file: `~/Desktop/projects/REA-MCP/sample-letter.pdf` is a one-page letter
  in embedded, subset Georgia. It has three typos ("Octobr", "suport",
  "Coordinater") and one long paragraph to rewrite.

## How it gets built (designer's calls — the owner delegated tool choices)

- **Language/UI:** Kotlin and Jetpack Compose (Material 3).
- **PDF engine:** PdfBox-Android (Apache 2.0 port of Apache PDFBox).
- **Starting point:** fork **PDF Inspector**
  (https://github.com/shardulvs/pdfinspector-android, MIT, Kotlin, Compose and
  PDFBox). It already opens PDFs, renders them, identifies text elements and
  edits them in place with font fallback. Keep its MIT licence notice and credit
  it in the app's About screen. Strip or hide its developer-inspector features
  that don't serve goals 1–6, so the app reads as a simple editor for
  non-technical users.
- **Fonts:** reuse the PDF's embedded font when it contains every needed
  character. Otherwise use a bundled, openly licensed, metric-compatible
  substitute (for example the Liberation fonts for Arial, Times New Roman and
  Courier; Gelasio for Georgia; otherwise a close Noto font), and warn the user as
  described in the anti-goals.

## Scope boundaries for paragraph editing and reflow (designer's calls)

Version 1 targets ordinary text documents: letters, reports, simple forms with
a single column of body text. It's built for these. Other layouts must fail
safely:

- A **paragraph** is a group of consecutive lines with the same font, size and
  left edge and regular line spacing. Detection belongs to the builder. When the
  app can't confidently identify a paragraph, it falls back to line-by-line
  editing (goal 1), and doesn't guess.
- **What moves when content is pushed down:** the body content below the edited
  paragraph in the same column, including text, images and simple drawn lines
  that sit between paragraphs. Repeating headers, footers and page numbers stay
  where they are.
- **Multi-column layouts, tables, and text wrapped around images** aren't reflowed in
  version 1. In those areas paragraph editing still rewraps within the
  paragraph, but if the new text needs more height than the original, the app
  tells the user it can't make room there and asks them to shorten it.
- Text flowing across a page break must keep the same left and right margins and
  line spacing on the new page.

## Designer's calls on editing behaviour (the owner can overrule)

- **Flow works in both directions, like Word.** When a paragraph gets shorter,
  content that was earlier pushed onto the next page comes back. A page the app
  created automatically is deleted once it's empty again. Pages the user added
  on purpose are never removed automatically.
- **Mixed formatting is preserved.** A paragraph with a bold word, an italic
  phrase or a link still counts as one paragraph. Words keep their existing
  style when they're re-wrapped. New text takes the style of the text where the
  cursor is. Version 1 adds no buttons for changing styles.
- **Missing characters in a subset font:** if an edited paragraph needs
  characters the embedded font lacks, the whole paragraph is re-set in the
  closest bundled substitute font. Characters are never mixed from two fonts
  inside a paragraph. The user is warned before saving and can cancel.
- **Edits truly remove the old text.** The original text is removed from the
  content stream, not hidden under a white box. Saving is a full rewrite, not
  an incremental save, so earlier revisions aren't kept in the file. A test
  extracts text from the saved file and checks that the old wording is gone.
  Document metadata is copied unchanged.
- **Content that can't be reflowed further down:** if pushed content would flow
  into a page or area that can't be reflowed (a table, multiple columns, a
  full-page image), the app inserts a new page before it to hold the
  overflow. It doesn't break that content. Every edit runs a layout pass over
  all later pages before saving, so failures appear before anything is
  written.

## Left to the builder

- Architecture, module structure, and how much of PDF Inspector to keep.
- Paragraph detection heuristics and how content streams are rewritten.
- Exact screens and interaction design, as long as it stays simple: open file,
  view pages, tap to edit, add/delete page, save as copy.
- Test strategy, including automated tests against `sample-letter.pdf` and other
  generated PDFs.
- App name and icon (placeholder is fine).
