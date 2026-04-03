package com.autobook.domain.parser

import android.util.Log
import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EPUB parser — no external library needed.
 * EPUB is just a ZIP containing XHTML files + metadata XML.
 */
class EpubParser {

    companion object {
        private const val TAG = "EpubParser"
    }

    fun extractText(filePath: String, onProgress: ((Float) -> Unit)? = null): String {
        val zip = ZipFile(File(filePath))
        val spineItems = getSpineItems(zip)
        val sb = StringBuilder()

        spineItems.forEachIndexed { index, itemPath ->
            try {
                val entry = zip.getEntry(itemPath) ?: return@forEachIndexed
                val html = zip.getInputStream(entry).bufferedReader().readText()
                val doc = Jsoup.parse(html)
                val text = doc.body()?.text() ?: ""
                if (text.isNotBlank()) {
                    sb.appendLine(text)
                    sb.appendLine()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read spine item $index: $itemPath", e)
            }
            onProgress?.invoke((index + 1).toFloat() / spineItems.size)
        }

        zip.close()
        return sb.toString()
    }

    /**
     * Returns Triple(title, author, language)
     */
    fun extractMetadata(filePath: String): Triple<String?, String?, String?> {
        return try {
            val zip = ZipFile(File(filePath))
            val opfPath = findOpfPath(zip)
            if (opfPath == null) {
                zip.close()
                return Triple(null, null, null)
            }

            val opfEntry = zip.getEntry(opfPath)
            val opfDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(zip.getInputStream(opfEntry))

            var title: String? = null
            var author: String? = null
            var language: String? = null

            val metadataNodes = opfDoc.getElementsByTagName("metadata")
            if (metadataNodes.length == 0) {
                // Try dc:metadata
                val dcNodes = opfDoc.getElementsByTagName("dc-metadata")
                // Fall through to element scan
            }

            // Scan all elements for dc: metadata
            fun scanForDc(tagName: String): String? {
                // Try with namespace prefix
                for (prefix in listOf("dc:", "")) {
                    val nodes = opfDoc.getElementsByTagName("${prefix}$tagName")
                    if (nodes.length > 0) {
                        val text = nodes.item(0).textContent?.trim()
                        if (!text.isNullOrBlank()) return text
                    }
                }
                return null
            }

            title = scanForDc("title")
            author = scanForDc("creator")
            language = scanForDc("language")

            zip.close()
            Triple(title, author, language)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract EPUB metadata", e)
            Triple(null, null, null)
        }
    }

    /**
     * Extract embedded cover image bytes from the EPUB.
     * Returns the image bytes or null if no cover found.
     */
    fun extractCoverImage(filePath: String): ByteArray? {
        return try {
            val zip = ZipFile(File(filePath))
            val opfPath = findOpfPath(zip)
            if (opfPath == null) {
                zip.close()
                return null
            }

            val opfDir = opfPath.substringBeforeLast("/", "")
            val opfEntry = zip.getEntry(opfPath)
            val opfDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(zip.getInputStream(opfEntry))

            // Strategy 1: Look for <meta name="cover" content="cover-image-id"/>
            var coverHref: String? = null
            val metaNodes = opfDoc.getElementsByTagName("meta")
            for (i in 0 until metaNodes.length) {
                val meta = metaNodes.item(i) as Element
                if (meta.getAttribute("name") == "cover") {
                    val coverId = meta.getAttribute("content")
                    // Find this id in the manifest
                    val items = opfDoc.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        if (item.getAttribute("id") == coverId) {
                            coverHref = item.getAttribute("href")
                            break
                        }
                    }
                    break
                }
            }

            // Strategy 2: Look for item with properties="cover-image"
            if (coverHref == null) {
                val items = opfDoc.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as Element
                    if (item.getAttribute("properties")?.contains("cover-image") == true) {
                        coverHref = item.getAttribute("href")
                        break
                    }
                }
            }

            // Strategy 3: Look for item with id containing "cover" and image media-type
            if (coverHref == null) {
                val items = opfDoc.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as Element
                    val id = item.getAttribute("id").lowercase()
                    val mediaType = item.getAttribute("media-type")
                    if (id.contains("cover") && mediaType.startsWith("image/")) {
                        coverHref = item.getAttribute("href")
                        break
                    }
                }
            }

            if (coverHref != null) {
                val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$coverHref" else coverHref
                val coverEntry = zip.getEntry(fullPath) ?: zip.getEntry(coverHref)
                if (coverEntry != null) {
                    val bytes = zip.getInputStream(coverEntry).readBytes()
                    zip.close()
                    return bytes
                }
            }

            zip.close()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract EPUB cover", e)
            null
        }
    }

    /**
     * Find the OPF file path from META-INF/container.xml
     */
    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(zip.getInputStream(containerEntry))
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.length > 0) {
            return (rootfiles.item(0) as Element).getAttribute("full-path")
        }
        return null
    }

    /**
     * Parse the OPF spine to get ordered list of content file paths.
     */
    private fun getSpineItems(zip: ZipFile): List<String> {
        val opfPath = findOpfPath(zip) ?: return emptyList()
        val opfEntry = zip.getEntry(opfPath) ?: return emptyList()
        val opfDir = opfPath.substringBeforeLast("/", "")

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(zip.getInputStream(opfEntry))

        // Build manifest map: id -> href
        val manifest = mutableMapOf<String, String>()
        val manifestNodes = doc.getElementsByTagName("item")
        for (i in 0 until manifestNodes.length) {
            val item = manifestNodes.item(i) as Element
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            // Only include XHTML/HTML content
            if (mediaType.contains("html") || mediaType.contains("xml")) {
                manifest[id] = href
            }
        }

        // Read spine order
        val spineNodes = doc.getElementsByTagName("itemref")
        val result = mutableListOf<String>()
        for (i in 0 until spineNodes.length) {
            val itemref = spineNodes.item(i) as Element
            val idref = itemref.getAttribute("idref")
            val href = manifest[idref] ?: continue
            // Resolve relative to OPF directory
            val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
            result.add(fullPath)
        }

        return result
    }
}
