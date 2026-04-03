package com.autobook.ui.import_

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autobook.data.db.BookEntity
import com.autobook.data.db.BookFormat
import com.autobook.data.db.ChapterEntity
import com.autobook.data.repository.BookRepository
import com.autobook.domain.chapter.ChapterDetector
import com.autobook.domain.chapter.ContentCleaner
import com.autobook.domain.cover.CoverArtFetcher
import com.autobook.domain.parser.DocxParser
import com.autobook.domain.parser.EpubParser
import com.autobook.domain.parser.Fb2Parser
import com.autobook.domain.parser.MobiParser
import com.autobook.domain.parser.OdtParser
import com.autobook.domain.parser.PdfParser
import com.autobook.domain.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ImportViewModel(
    private val repository: BookRepository,
    private val context: Context
) : ViewModel() {

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    private val pdfParser = PdfParser(context)
    private val txtParser = TxtParser()
    private val epubParser = EpubParser()
    private val mobiParser = MobiParser()
    private val fb2Parser = Fb2Parser()
    private val odtParser = OdtParser()
    private val docxParser = DocxParser()
    private val chapterDetector = ChapterDetector()
    private val contentCleaner = ContentCleaner()
    private val coverArtFetcher = CoverArtFetcher(context)

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            try {
                _importState.value = ImportState.Processing(0, "Copying file...")

                // Copy file to app storage
                var file = copyFileToAppStorage(uri)
                _importState.value = ImportState.Processing(20, "Reading file...")

                // Determine format — try extension first, fall back to magic bytes
                var format = detectFormat(file.extension)
                if (format == BookFormat.TXT && file.extension.lowercase() !in listOf("txt", "text")) {
                    // Extension wasn't recognized (e.g. .bin) — sniff content
                    format = detectFormatByContent(file)
                    // Rename file to correct extension for parsers that need it
                    if (format != BookFormat.TXT) {
                        val ext = format.name.lowercase()
                        val renamed = File(file.parent, file.nameWithoutExtension + "." + ext)
                        file.renameTo(renamed)
                        file = renamed
                    }
                }
                _importState.value = ImportState.Processing(30, "Extracting text...")

                // Extract text and metadata
                data class BookMeta(val text: String, val title: String, val author: String?, val language: String?, val embeddedCover: ByteArray? = null)
                val meta = when (format) {
                    BookFormat.PDF -> {
                        val text = pdfParser.extractText(file.absolutePath) { pct ->
                            val progressInt = 30 + (pct * 20).toInt() // 30-50%
                            _importState.value = ImportState.Processing(progressInt, "Extracting text... ${(pct * 100).toInt()}%")
                        }
                        val (pdfTitle, pdfAuthor, pdfLang) = pdfParser.extractMetadata(file.absolutePath)
                        val cleanTitle = cleanFileName(pdfTitle ?: file.nameWithoutExtension)
                        BookMeta(text, cleanTitle, pdfAuthor, pdfLang)
                    }
                    BookFormat.TXT -> {
                        val text = txtParser.extractText(file.absolutePath)
                        val (txtTitle, txtAuthor) = txtParser.extractMetadata(file.absolutePath)
                        val cleanTitle = cleanFileName(txtTitle ?: file.nameWithoutExtension)
                        BookMeta(text, cleanTitle, txtAuthor, null)
                    }
                    BookFormat.EPUB -> {
                        val text = epubParser.extractText(file.absolutePath) { pct ->
                            val progressInt = 30 + (pct * 20).toInt()
                            _importState.value = ImportState.Processing(progressInt, "Extracting text... ${(pct * 100).toInt()}%")
                        }
                        val (epubTitle, epubAuthor, epubLang) = epubParser.extractMetadata(file.absolutePath)
                        // Clean up messy epub metadata
                        val cleanTitle = cleanEpubTitle(epubTitle, file.nameWithoutExtension)
                        val cleanAuthor = cleanEpubAuthor(epubAuthor, epubTitle)
                        // Try to extract embedded cover
                        val embeddedCover = epubParser.extractCoverImage(file.absolutePath)
                        BookMeta(text, cleanTitle, cleanAuthor, epubLang, embeddedCover)
                    }
                    BookFormat.MOBI, BookFormat.FB2, BookFormat.ODT, BookFormat.DOCX -> {
                        val parser: com.autobook.domain.parser.BookParser = when (format) {
                            BookFormat.MOBI -> mobiParser
                            BookFormat.FB2 -> fb2Parser
                            BookFormat.ODT -> odtParser
                            BookFormat.DOCX -> docxParser
                            else -> throw IllegalStateException()
                        }
                        val parsed = file.inputStream().use { stream ->
                            parser.parse(stream, file.name) { pct ->
                                val progressInt = 30 + (pct * 20).toInt()
                                _importState.value = ImportState.Processing(progressInt, "Extracting text... ${(pct * 100).toInt()}%")
                            }
                        }
                        val allText = parsed.chapters.joinToString("\n\n") { it.textContent }
                        val cleanTitle = cleanFileName(parsed.title ?: file.nameWithoutExtension)
                        BookMeta(allText, cleanTitle, parsed.author, parsed.language)
                    }
                    else -> throw IllegalArgumentException("Unsupported format: $format")
                }
                val text = meta.text
                val title = meta.title
                val author = meta.author
                val language = meta.language

                // Check for duplicates
                if (repository.bookExistsByTitle(title)) {
                    _importState.value = ImportState.Error("\"$title\" is already in your library")
                    return@launch
                }

                _importState.value = ImportState.Processing(50, "Cleaning content...")

                // Clean text
                val cleanedText = contentCleaner.cleanText(text)

                _importState.value = ImportState.Processing(60, "Detecting chapters...")

                // Detect chapters
                val chapterBreaks = chapterDetector.detectChapters(cleanedText)
                val chapters = chapterDetector.createChapters(cleanedText, chapterBreaks)

                _importState.value = ImportState.Processing(75, "Fetching cover art...")

                // Create book entity
                val bookId = UUID.randomUUID().toString()

                // Fetch cover art — prefer embedded cover from epub, fallback to online search
                val coverPath = try {
                    if (meta.embeddedCover != null) {
                        // Save embedded cover directly
                        val coversDir = java.io.File(context.filesDir, "covers")
                        if (!coversDir.exists()) coversDir.mkdirs()
                        val coverFile = java.io.File(coversDir, "$bookId.jpg")
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(meta.embeddedCover, 0, meta.embeddedCover.size)
                        if (bitmap != null) {
                            java.io.FileOutputStream(coverFile).use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            coverFile.absolutePath
                        } else {
                            coverArtFetcher.fetchAndSaveCover(title, author, bookId)
                        }
                    } else {
                        coverArtFetcher.fetchAndSaveCover(title, author, bookId)
                    }
                } catch (e: Exception) {
                    // If fetching fails, generate placeholder
                    null
                }

                _importState.value = ImportState.Processing(80, "Saving book...")
                val book = BookEntity(
                    id = bookId,
                    title = title,
                    author = author,
                    language = language,
                    coverPath = coverPath,
                    filePath = file.absolutePath,
                    format = format,
                    totalChapters = chapters.size,
                    totalDuration = estimateDuration(cleanedText)
                )

                // Create chapter entities
                val chapterEntities = chapters.mapIndexed { index, (chapterTitle, content) ->
                    ChapterEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = bookId,
                        index = index,
                        title = chapterTitle,
                        textContent = content,
                        startOffset = 0, // Could calculate actual offset if needed
                        estimatedDuration = estimateDuration(content)
                    )
                }

                // Save to database
                repository.insertBook(book)
                repository.insertChapters(chapterEntities)

                _importState.value = ImportState.Success(bookId, chapters.size)

            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun copyFileToAppStorage(uri: Uri): File = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file")

        val fileName = "book_${System.currentTimeMillis()}.${getFileExtension(uri)}"
        val file = File(context.filesDir, fileName)

        inputStream.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        file
    }

    private fun getFileExtension(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        return when {
            mimeType?.contains("pdf") == true -> "pdf"
            mimeType?.contains("epub") == true -> "epub"
            mimeType?.contains("mobi") == true -> "mobi"
            mimeType?.contains("opendocument.text") == true -> "odt"
            mimeType?.contains("wordprocessingml") == true -> "docx"
            mimeType?.contains("fictionbook") == true -> "fb2"
            mimeType?.contains("text") == true -> "txt"
            else -> {
                // Try to get from URI path
                val ext = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext in listOf("pdf", "epub", "mobi", "odt", "docx", "fb2", "txt")) ext
                else "unknown" // Will be resolved by magic byte sniffing after copy
            }
        }
    }

    /**
     * Detect format by reading magic bytes from the actual file content.
     * EPUB/DOCX/ODT are ZIP-based (PK header), PDF starts with %PDF.
     */
    private fun detectFormatByContent(file: File): BookFormat {
        val header = file.inputStream().use { it.readNBytes(8) }
        // ZIP-based formats (EPUB, DOCX, ODT)
        if (header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            // It's a ZIP — peek inside to distinguish epub vs docx vs odt
            return try {
                val zip = java.util.zip.ZipFile(file)
                val entries = zip.entries().toList().map { it.name }
                zip.close()
                when {
                    entries.any { it == "META-INF/container.xml" } -> BookFormat.EPUB
                    entries.any { it.startsWith("word/") } -> BookFormat.DOCX
                    entries.any { it == "mimetype" || it.startsWith("META-INF/manifest.xml") } -> BookFormat.ODT
                    else -> BookFormat.EPUB // ZIP with unknown structure, try epub
                }
            } catch (e: Exception) {
                BookFormat.TXT
            }
        }
        // PDF
        if (header.size >= 4 && String(header.sliceArray(0..3)) == "%PDF") {
            return BookFormat.PDF
        }
        // FB2 (XML-based)
        val headerStr = String(header)
        if (headerStr.startsWith("<?xml") || headerStr.startsWith("<FictionBook")) {
            return BookFormat.FB2
        }
        return BookFormat.TXT
    }

    /**
     * Clean messy epub titles like "Joan D. Vinge   Cat 2   Catspaw (v1)" → "Catspaw"
     */
    private fun cleanEpubTitle(rawTitle: String?, fallback: String): String {
        if (rawTitle.isNullOrBlank()) return cleanFileName(fallback)

        // If title contains multiple segments separated by spaces (3+), author-like prefix, etc.
        // Try to extract the last meaningful segment
        val cleaned = rawTitle
            .replace(Regex("\\(.*?\\)"), "") // remove parentheticals like (v1)
            .replace(Regex("\\[.*?\\]"), "") // remove brackets
            .trim()

        // If the title has segments separated by multiple spaces, take the last substantial one
        val segments = cleaned.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size > 1) {
            // Last segment is typically the actual title
            return segments.last()
        }

        return cleanFileName(cleaned)
    }

    /**
     * Clean epub author — sometimes the title and author fields are swapped or contain
     * the book title instead of the actual author.
     */
    private fun cleanEpubAuthor(rawAuthor: String?, rawTitle: String?): String? {
        if (rawAuthor.isNullOrBlank()) return null

        // If "author" looks like a book title (single word, no space = suspicious for a person name)
        // and the raw title contains what looks like an author name, swap them
        val hasSpace = rawAuthor.contains(" ")
        if (!hasSpace && rawTitle != null) {
            // Single word author is suspicious — could be swapped
            // Try to find a name-like pattern in the title
            val segments = rawTitle.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotBlank() }
            val nameSegment = segments.firstOrNull { seg ->
                seg.contains(" ") && seg.split(" ").size in 2..4 &&
                seg.split(" ").all { word -> word.first().isUpperCase() }
            }
            if (nameSegment != null) return nameSegment
        }

        return rawAuthor.trim()
    }

    private fun detectFormat(extension: String): BookFormat {
        return when (extension.lowercase()) {
            "pdf" -> BookFormat.PDF
            "txt" -> BookFormat.TXT
            "epub" -> BookFormat.EPUB
            "mobi" -> BookFormat.MOBI
            "fb2" -> BookFormat.FB2
            "odt" -> BookFormat.ODT
            "docx" -> BookFormat.DOCX
            else -> BookFormat.TXT
        }
    }

    private fun estimateDuration(text: String): Long {
        // Estimate: ~250 words per minute, ~5 chars per word
        val words = text.length / 5
        val minutes = words / 250
        return minutes * 60 * 1000L // milliseconds
    }

    /**
     * Clean up a filename into a proper book title.
     * "Stross/Accelerando" → "Accelerando"
     * "book_1234567890" → "Book"
     */
    private fun cleanFileName(name: String): String {
        var clean = name
        // Handle "Author/Title" patterns — take the last part as title
        if (clean.contains("/") || clean.contains("\\")) {
            clean = clean.split("/", "\\").last().trim()
        }
        return clean
            .replace(Regex("_\\d{5,}"), "")  // remove timestamp suffixes
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }
}

sealed class ImportState {
    object Idle : ImportState()
    data class Processing(val progress: Int, val message: String) : ImportState()
    data class Success(val bookId: String, val chapterCount: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}
