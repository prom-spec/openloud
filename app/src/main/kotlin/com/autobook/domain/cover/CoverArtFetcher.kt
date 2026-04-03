package com.autobook.domain.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CoverArtFetcher(private val context: Context) {

    companion object {
        private const val TAG = "CoverArtFetcher"
    }

    suspend fun fetchAndSaveCover(title: String, author: String?, bookId: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching cover for: title='$title', author='$author', bookId='$bookId'")

        // Try Google Books first
        val googleCover = tryGoogleBooks(title, author, bookId)
        if (googleCover != null) return@withContext googleCover

        // Try Open Library as fallback
        val openLibCover = tryOpenLibrary(title, author, bookId)
        if (openLibCover != null) return@withContext openLibCover

        Log.w(TAG, "No cover found from any source, generating placeholder")
        return@withContext generatePlaceholder(title, bookId)
    }

    private fun tryGoogleBooks(title: String, author: String?, bookId: String): String? {
        return try {
            val query = buildSearchQuery(title, author)
            val searchUrl = "https://www.googleapis.com/books/v1/volumes?q=$query&maxResults=3"
            Log.d(TAG, "Google Books query: $searchUrl")

            val connection = URL(searchUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            Log.d(TAG, "Google Books response: $responseCode")

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val items = json.optJSONArray("items")
                Log.d(TAG, "Google Books results: ${items?.length() ?: 0}")

                if (items != null) {
                    // Try each result for a cover
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val volumeInfo = item.optJSONObject("volumeInfo") ?: continue
                        val imageLinks = volumeInfo.optJSONObject("imageLinks") ?: continue

                        // Prefer larger images
                        val imageUrl = imageLinks.optString("medium")
                            .takeIf { it.isNotEmpty() }
                            ?: imageLinks.optString("small")
                                .takeIf { it.isNotEmpty() }
                            ?: imageLinks.optString("thumbnail")
                                .takeIf { it.isNotEmpty() }
                            ?: imageLinks.optString("smallThumbnail")
                                .takeIf { it.isNotEmpty() }

                        if (imageUrl != null) {
                            Log.d(TAG, "Found Google Books image: $imageUrl")
                            val path = downloadAndSaveImage(imageUrl, bookId)
                            if (path != null) {
                                connection.disconnect()
                                return path
                            }
                        }
                    }
                }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Google Books error: $responseCode — $error")
            }

            connection.disconnect()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Google Books fetch failed", e)
            null
        }
    }

    private fun tryOpenLibrary(title: String, author: String?, bookId: String): String? {
        return try {
            val query = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://openlibrary.org/search.json?title=$query&limit=3"
            Log.d(TAG, "Open Library query: $searchUrl")

            val connection = URL(searchUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val docs = json.optJSONArray("docs")

                if (docs != null && docs.length() > 0) {
                    for (i in 0 until docs.length()) {
                        val doc = docs.getJSONObject(i)
                        val coverId = doc.optInt("cover_i", -1)
                        if (coverId > 0) {
                            val imageUrl = "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
                            Log.d(TAG, "Found Open Library cover: $imageUrl")
                            val path = downloadAndSaveImage(imageUrl, bookId)
                            if (path != null) {
                                connection.disconnect()
                                return path
                            }
                        }
                    }
                }
            }

            connection.disconnect()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Open Library fetch failed", e)
            null
        }
    }

    private fun buildSearchQuery(title: String, author: String?): String {
        var titleClean = title
            .replace(Regex("\\(.*?\\)"), "") // remove parenthetical
            .replace(Regex("_"), " ")
            .trim()
        var searchAuthor = author

        // Handle "Author/Title" or "Author - Title" patterns in title
        if (searchAuthor.isNullOrBlank()) {
            val slashParts = titleClean.split("/", "\\").map { it.trim() }
            if (slashParts.size == 2 && slashParts.all { it.isNotBlank() }) {
                searchAuthor = slashParts[0]
                titleClean = slashParts[1]
            } else {
                val dashParts = titleClean.split(" - ").map { it.trim() }
                if (dashParts.size == 2 && dashParts.all { it.isNotBlank() }) {
                    searchAuthor = dashParts[0]
                    titleClean = dashParts[1]
                }
            }
        }

        // Remove any remaining path separators
        titleClean = titleClean.replace("/", " ").replace("\\", " ").trim()

        Log.d(TAG, "Search query: title='$titleClean', author='$searchAuthor'")

        val titleEncoded = URLEncoder.encode(titleClean, "UTF-8")
        return if (searchAuthor != null && searchAuthor.isNotBlank()) {
            val authorEncoded = URLEncoder.encode(searchAuthor, "UTF-8")
            "intitle:$titleEncoded+inauthor:$authorEncoded"
        } else {
            "intitle:$titleEncoded"
        }
    }

    private fun downloadAndSaveImage(imageUrl: String, bookId: String): String? {
        return try {
            // Always use HTTPS
            val url = imageUrl.replace("http://", "https://")
            Log.d(TAG, "Downloading image: $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            // Some APIs need a User-Agent
            connection.setRequestProperty("User-Agent", "AIAnyBook/1.0")

            val responseCode = connection.responseCode
            Log.d(TAG, "Image download response: $responseCode, content-type: ${connection.contentType}")

            if (responseCode == 200 && connection.contentType?.startsWith("image/") == true) {
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()

                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode bitmap from stream")
                    return null
                }

                Log.d(TAG, "Image decoded: ${bitmap.width}x${bitmap.height}")

                val coversDir = File(context.filesDir, "covers")
                if (!coversDir.exists()) coversDir.mkdirs()

                val coverFile = File(coversDir, "$bookId.jpg")
                FileOutputStream(coverFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                Log.d(TAG, "Cover saved to: ${coverFile.absolutePath}")
                coverFile.absolutePath
            } else {
                connection.disconnect()
                Log.w(TAG, "Image download failed: code=$responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image: $imageUrl", e)
            null
        }
    }

    private fun generatePlaceholder(title: String, bookId: String): String {
        val width = 400
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colors = listOf(
            intArrayOf(0xFF6B46C1.toInt(), 0xFF9333EA.toInt()),
            intArrayOf(0xFF1E40AF.toInt(), 0xFF3B82F6.toInt()),
            intArrayOf(0xFF059669.toInt(), 0xFF10B981.toInt()),
            intArrayOf(0xFFDC2626.toInt(), 0xFFEF4444.toInt()),
            intArrayOf(0xFFD97706.toInt(), 0xFFF59E0B.toInt())
        )

        val colorPair = colors[Math.abs(bookId.hashCode()) % colors.size]

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            colorPair[0], colorPair[1],
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        val words = title.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val bounds = Rect()
            textPaint.getTextBounds(testLine, 0, testLine.length, bounds)
            if (bounds.width() < width - 80) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        val displayLines = lines.take(4)
        val lineHeight = 60f
        val startY = height / 2f - (displayLines.size * lineHeight / 2f)

        displayLines.forEachIndexed { index, line ->
            canvas.drawText(
                if (line.length > 20) line.take(20) + "..." else line,
                width / 2f,
                startY + index * lineHeight,
                textPaint
            )
        }

        val coversDir = File(context.filesDir, "covers")
        if (!coversDir.exists()) coversDir.mkdirs()

        val coverFile = File(coversDir, "$bookId.jpg")
        FileOutputStream(coverFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        Log.d(TAG, "Placeholder generated: ${coverFile.absolutePath}")
        return coverFile.absolutePath
    }
}
