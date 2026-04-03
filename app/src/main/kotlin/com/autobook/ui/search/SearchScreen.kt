package com.autobook.ui.search

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.autobook.data.db.AppDatabase
import com.autobook.data.db.BookEntity
import com.autobook.data.db.BookFormat
import com.autobook.data.db.ChapterEntity
import com.autobook.data.repository.BookRepository
import com.autobook.domain.chapter.ChapterDetector
import com.autobook.domain.chapter.ContentCleaner
import com.autobook.domain.cover.CoverArtFetcher
import com.autobook.domain.parser.EpubParser
import com.autobook.domain.parser.PdfParser
import com.autobook.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "SearchScreen"
private const val SEARCH_URL = "https://oceanofpdf.com/"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onBookImported: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val downloadIds = remember { mutableSetOf<Long>() }
    val scope = rememberCoroutineScope()

    // Register download completion receiver
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                if (id !in downloadIds) return
                downloadIds.remove(id)

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor: Cursor? = dm.query(query)

                if (cursor?.moveToFirst() == true) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                    if (cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {
                        val localUri = cursor.getString(uriCol)
                        Log.d(TAG, "Download complete: $localUri")

                        scope.launch {
                            autoImportBook(context, localUri, onBookImported) { status ->
                                importStatus = status
                            }
                        }
                    }
                }
                cursor?.close()
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            webView?.destroy()
        }
    }

    Scaffold(
        containerColor = Navy,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Find Books", color = TextPrimary)
                        importStatus?.let {
                            Text(it, color = Amber, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (webView?.canGoBack() == true) {
                            webView?.goBack()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavySurface)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            setSupportMultipleWindows(false)
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val lower = url.lowercase()
                                if (lower.endsWith(".pdf") || lower.endsWith(".epub")) {
                                    val dlId = downloadFile(ctx, url)
                                    if (dlId != null) downloadIds.add(dlId)
                                    return true
                                }
                                return false
                            }
                        }

                        webChromeClient = object : WebChromeClient() {}

                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            Log.d(TAG, "Download: url=$url, mime=$mimetype, cd=$contentDisposition")
                            val dlId = downloadFile(ctx, url, userAgent, contentDisposition, mimetype)
                            if (dlId != null) downloadIds.add(dlId)
                        }

                        loadUrl(SEARCH_URL)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter),
                    color = Amber,
                    trackColor = NavyMuted
                )
            }
        }
    }
}

private fun downloadFile(
    context: Context,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimetype: String? = null
): Long? {
    return try {
        val guessedMime = when {
            url.lowercase().endsWith(".epub") -> "application/epub+zip"
            url.lowercase().endsWith(".pdf") -> "application/pdf"
            mimetype != null -> mimetype
            else -> "application/octet-stream"
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, guessedMime)
        Log.d(TAG, "Downloading: $fileName from $url")

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("Downloading book...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AIAnyBook/$fileName")
            if (userAgent != null) addRequestHeader("User-Agent", userAgent)
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            setMimeType(guessedMime)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        Toast.makeText(context, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
        id
    } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        null
    }
}

private suspend fun autoImportBook(
    context: Context,
    localUriString: String,
    onBookImported: (String) -> Unit,
    onStatus: (String?) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) { onStatus("Importing book...") }

            // Resolve the file from the download URI
            val file = resolveDownloadFile(localUriString)
            if (file == null || !file.exists()) {
                Log.e(TAG, "Downloaded file not found: $localUriString")
                withContext(Dispatchers.Main) { onStatus("Import failed: file not found") }
                return@withContext
            }

            val ext = file.extension.lowercase()
            val format = when (ext) {
                "pdf" -> BookFormat.PDF
                "epub" -> BookFormat.EPUB
                "txt" -> BookFormat.TXT
                else -> {
                    withContext(Dispatchers.Main) { onStatus("Unsupported format: $ext") }
                    return@withContext
                }
            }

            val db = AppDatabase.getDatabase(context)
            val repository = BookRepository(db.bookDao(), db.chapterDao())
            val chapterDetector = ChapterDetector()
            val contentCleaner = ContentCleaner()
            val coverArtFetcher = CoverArtFetcher(context)

            withContext(Dispatchers.Main) { onStatus("Extracting text...") }

            // Copy to app storage
            val appFile = File(context.filesDir, "book_${System.currentTimeMillis()}.$ext")
            file.copyTo(appFile, overwrite = true)

            // Extract text and metadata based on format
            val text: String
            var title: String
            var author: String? = null
            var language: String? = null

            when (format) {
                BookFormat.PDF -> {
                    val pdfParser = PdfParser(context)
                    text = pdfParser.extractText(appFile.absolutePath)
                    val (pdfTitle, pdfAuthor, pdfLang) = pdfParser.extractMetadata(appFile.absolutePath)
                    title = pdfTitle ?: file.nameWithoutExtension
                    author = pdfAuthor
                    language = pdfLang
                }
                BookFormat.EPUB -> {
                    val epubParser = EpubParser()
                    text = epubParser.extractText(appFile.absolutePath)
                    val (epubTitle, epubAuthor, epubLang) = epubParser.extractMetadata(appFile.absolutePath)
                    title = epubTitle ?: file.nameWithoutExtension
                    author = epubAuthor
                    language = epubLang
                }
                else -> {
                    text = file.readText()
                    title = file.nameWithoutExtension
                }
            }

            // Clean title
            if (title.contains("/") || title.contains("\\")) {
                title = title.split("/", "\\").last().trim()
            }
            title = title
                .replace(Regex("_\\d{5,}"), "")
                .replace("_", " ")
                .replace("-", " ")
                .trim()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

            // Check for duplicates
            if (repository.bookExistsByTitle(title)) {
                withContext(Dispatchers.Main) {
                    onStatus("\"$title\" already in library")
                    Toast.makeText(context, "📚 \"$title\" is already in your library", Toast.LENGTH_LONG).show()
                }
                appFile.delete()
                return@withContext
            }

            withContext(Dispatchers.Main) { onStatus("Processing: $title") }

            val cleanedText = contentCleaner.cleanText(text)
            val chapterBreaks = chapterDetector.detectChapters(cleanedText)
            val chapters = chapterDetector.createChapters(cleanedText, chapterBreaks)

            val bookId = UUID.randomUUID().toString()

            withContext(Dispatchers.Main) { onStatus("Fetching cover...") }
            val coverPath = try {
                coverArtFetcher.fetchAndSaveCover(title, author, bookId)
            } catch (_: Exception) { null }

            val book = BookEntity(
                id = bookId,
                title = title,
                author = author,
                language = language,
                coverPath = coverPath,
                filePath = appFile.absolutePath,
                format = format,
                totalChapters = chapters.size,
                totalDuration = (cleanedText.length / 5 / 250) * 60 * 1000L
            )

            val chapterEntities = chapters.mapIndexed { index, (chTitle, content) ->
                ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    index = index,
                    title = chTitle,
                    textContent = content,
                    startOffset = 0,
                    estimatedDuration = (content.length / 5 / 250) * 60 * 1000L
                )
            }

            repository.insertBook(book)
            repository.insertChapters(chapterEntities)

            Log.d(TAG, "Auto-imported: $title (${chapters.size} chapters)")

            withContext(Dispatchers.Main) {
                onStatus("✓ Imported: $title")
                Toast.makeText(context, "📚 $title added to library!", Toast.LENGTH_LONG).show()
                onBookImported(bookId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-import failed", e)
            withContext(Dispatchers.Main) {
                onStatus("Import failed: ${e.message}")
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Resolve a DownloadManager local URI (file:// or content://) to a File.
 */
private fun resolveDownloadFile(uriString: String): File? {
    return try {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "file" -> File(uri.path!!)
            "content" -> {
                // For content URIs, try the common download path
                val path = uri.path
                if (path != null && path.contains("/external/")) {
                    // content://downloads/... -> check external downloads dir
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val autoBookDir = File(downloadDir, "AIAnyBook")
                    // Return the most recently modified file
                    autoBookDir.listFiles()?.maxByOrNull { it.lastModified() }
                } else {
                    path?.let { File(it) }
                }
            }
            else -> File(uriString)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve file: $uriString", e)
        null
    }
}
