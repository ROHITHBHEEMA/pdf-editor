# Test corpus

Every PDF in this folder is run by `CorpusTest` automatically (no code change needed to add one).

| File | Producer |
|------|----------|
| `sample-letter.pdf` | Original sample letter shipped with the project |
| `chrome-print.pdf` | Google Chrome headless print-to-PDF of a small local HTML page (heading, three paragraphs, one bold word) |
| `pdfbox-justified-2page.pdf` | Generated with PdfBox (`PdfFixtures.letter`, justified, 2+ pages) |
| `pdfbox-header-footer.pdf` | Generated with PdfBox (`PdfFixtures.withHeaderFooter`, 2 pages with header and footer) |

Still missing: exports from Microsoft Word, Google Docs and LibreOffice. The owner should add a few
(non-sensitive documents only) by copying them into this folder.
