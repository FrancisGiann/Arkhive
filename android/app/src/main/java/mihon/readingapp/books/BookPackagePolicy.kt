package mihon.readingapp.books

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.URLDecoder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure package validation/conversion; imported publication code is never executed. */
object BookPackagePolicy {
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TEXT_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val PREPARED_COMMENT = "Arkhive-Prepared-2"
    private const val CSP = "default-src 'none'; script-src https://readium-assets.invalid/readium/scripts/; " +
        "style-src 'self' 'unsafe-inline' https://readium-assets.invalid/readium/readium-css/; " +
        "img-src 'self' data:; font-src 'self' data: https://readium-assets.invalid/readium/; " +
        "media-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"

    fun isSafeEntry(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.contains('\\') || name.contains('\u0000') ||
            Regex("^[A-Za-z]:").containsMatchIn(name)) return false
        val decoded = name.replace(Regex("%[0-9a-fA-F]{2}")) { it.value.substring(1).toInt(16).toChar().toString() }
        return decoded.split('/').none { it == ".." || it == "." || it.contains('\\') || it.contains('\u0000') } &&
            !decoded.startsWith('/')
    }

    fun sanitizeXhtml(input: InputStream, xml: Boolean): ByteArray {
        val doc = Jsoup.parse(input, null, "", if (xml) Parser.xmlParser() else Parser.htmlParser())
        doc.childNodes().filterIsInstance<XmlDeclaration>().forEach { it.remove() }
        doc.select("script, iframe, frame, frameset, object, embed, form, base, foreignObject").remove()
        doc.select("meta[http-equiv]").filter { it.attr("http-equiv").equals("refresh", true) ||
            it.attr("http-equiv").equals("Content-Security-Policy", true) }.forEach { it.remove() }
        doc.getAllElements().forEach { element ->
            element.attributes().asList().forEach { attr ->
                val key = attr.key.lowercase()
                if (key.startsWith("on") || key == "srcdoc" ||
                    (key in setOf("href", "src", "xlink:href", "action", "formaction", "poster") && !safeResourceUrl(attr.value))) {
                    element.removeAttr(attr.key)
                }
            }
        }
        if (xml) {
            doc.selectFirst("html")?.let { html ->
                if (!html.hasAttr("xmlns")) html.attr("xmlns", "http://www.w3.org/1999/xhtml")
            }
            doc.select("meta[charset], meta[http-equiv]").filter {
                it.hasAttr("charset") || it.attr("http-equiv").equals("content-type", true)
            }.forEach { it.remove() }
            // CSS is raw text in HTML, but XHTML needs XML-escaped text nodes.
            doc.select("style").forEach { style ->
                val css = style.data()
                style.empty()
                style.appendText(css)
            }
        }
        val head = doc.selectFirst("head") ?: doc.selectFirst("html")?.prependElement("head")
        if (xml) head?.prependElement("meta")?.attr("charset", "utf-8")
        head?.prependElement("meta")?.attr("http-equiv", "Content-Security-Policy")?.attr("content", CSP)
        doc.outputSettings().charset(Charsets.UTF_8).prettyPrint(false)
            .escapeMode(if (xml) Entities.EscapeMode.xhtml else Entities.EscapeMode.base)
            .syntax(if (xml) Document.OutputSettings.Syntax.xml else Document.OutputSettings.Syntax.html)
        return doc.outerHtml().toByteArray(Charsets.UTF_8)
    }

    private fun safeResourceUrl(value: String): Boolean {
        val v = value.trim().replace(Regex("[\\u0000-\\u0020]"), "")
        if (v.startsWith("data:image/", true)) return true
        if (v.startsWith("//") || v.startsWith("/") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(v)) return false
        return true // Archive-relative links are resolved by the publication's rooted container.
    }

    fun prepareEpub(source: File, destination: File) {
        require(source.length() in 1..MAX_FILE_BYTES) { "The EPUB is empty or exceeds the 256 MiB import limit." }
        var total = 0L
        ZipFile(source).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size in 1..MAX_ENTRIES) { "Too many files in this EPUB." }
            require(entries.map { it.name }.toSet().size == entries.size) { "Duplicate EPUB file paths." }
            require(entries.all { isSafeEntry(it.name) }) { "Unsafe EPUB file path." }
            require(zip.getEntry("META-INF/container.xml") != null) { "This archive is not an EPUB publication." }
            require(zip.getInputStream(zip.getEntry("mimetype") ?: error("Missing EPUB media type")).use {
                String(boundedRead(it, 128), Charsets.US_ASCII).trim() == "application/epub+zip"
            }) { "This archive is not an EPUB publication." }
            require(zip.getEntry("META-INF/license.lcpl") == null) { "DRM-protected EPUBs are not supported." }
            val mediaTypes = publicationMediaTypes(zip)
            // Font obfuscation is supported by Readium; encrypted book text is not.
            zip.getEntry("META-INF/encryption.xml")?.let { entry ->
                val encryption = zip.getInputStream(entry).use { boundedRead(it, 128 * 1024) }.toString(Charsets.UTF_8)
                val algorithms = Regex("Algorithm=[\"']([^\"']+)").findAll(encryption).map { it.groupValues[1] }.toList()
                require(algorithms.isNotEmpty() && algorithms.all { it in setOf(
                    "http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC") }) {
                    "Encrypted EPUB content is not supported."
                }
            }
            ZipOutputStream(destination.outputStream().buffered()).use { out ->
                out.setComment(PREPARED_COMMENT)
                writeStoredMime(out)
                entries.filter { it.name != "mimetype" }.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    require(entry.size in 0..MAX_ENTRY_BYTES) { "An EPUB resource exceeds the 32 MiB limit." }
                    val bytes = zip.getInputStream(entry).use { stream ->
                        val data = boundedRead(stream, (MAX_ENTRY_BYTES + 1).toInt())
                        require(data.size <= MAX_ENTRY_BYTES) { "Oversized EPUB resource." }
                        data
                    }
                    require(entry.crc < 0 || CRC32().apply { update(bytes) }.value == entry.crc) { "Damaged EPUB resource." }
                    total += bytes.size
                    require(total <= MAX_EXPANDED_BYTES) { "The expanded EPUB exceeds 512 MiB." }
                    val lower = entry.name.lowercase()
                    val mediaType = mediaTypes[entry.name]?.lowercase()
                    val isXhtml = mediaType == "application/xhtml+xml" || lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                    val isSvg = mediaType == "image/svg+xml" || lower.endsWith(".svg")
                    if (isXhtml || isSvg) {
                        require(bytes.size <= 8 * 1024 * 1024) { "An EPUB text resource exceeds 8 MiB." }
                    }
                    val converted = when {
                        isXhtml -> sanitizeXhtml(bytes.inputStream(), true)
                        isSvg -> sanitizeXhtml(bytes.inputStream(), true)
                        else -> bytes
                    }
                    out.putNextEntry(ZipEntry(entry.name)); out.write(converted); out.closeEntry()
                }
            }
        }
    }

    fun isCurrentPrepared(file: File): Boolean = runCatching {
        ZipFile(file).use { it.comment == PREPARED_COMMENT }
    }.getOrDefault(false)

    private fun publicationMediaTypes(zip: ZipFile): Map<String, String> {
        val container = zip.getEntry("META-INF/container.xml") ?: return emptyMap()
        val rootFiles = zip.getInputStream(container).use { input ->
            Jsoup.parse(input, null, "", Parser.xmlParser()).select("rootfile[full-path]")
                .map { it.attr("full-path") }
        }
        return buildMap {
            rootFiles.forEach { opfPath ->
                if (!isSafeEntry(opfPath)) return@forEach
                val opf = zip.getEntry(opfPath) ?: return@forEach
                val base = opfPath.substringBeforeLast('/', "")
                val packageDocument = zip.getInputStream(opf).use { input -> Jsoup.parse(input, null, "", Parser.xmlParser()) }
                packageDocument.select("manifest item[href][media-type]").forEach { item ->
                    resolveArchivePath(base, item.attr("href"))?.let { path -> put(path, item.attr("media-type")) }
                }
            }
        }
    }

    private fun resolveArchivePath(base: String, href: String): String? = runCatching {
        val raw = href.substringBefore('#').substringBefore('?')
        require(raw.isNotBlank() && !raw.startsWith('/') && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw))
        val decoded = URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()).replace('\\', '/')
        val parts = ArrayDeque<String>()
        (base.split('/').filter { it.isNotEmpty() } + decoded.split('/')).forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> require(parts.isNotEmpty()).also { parts.removeLast() }
                else -> parts.addLast(part)
            }
        }
        parts.joinToString("/").also { require(isSafeEntry(it)) }
    }.getOrNull()

    fun decodeText(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_TEXT_BYTES) { "Text imports must be nonempty and at most 16 MiB." }
        val utf16le = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
        val utf16be = bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
        val charset = when { utf16le -> Charsets.UTF_16LE; utf16be -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val offset = when { utf16le || utf16be -> 2
            bytes.size >= 3 && bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> 3
            else -> 0 }
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        require(!text.contains('\u0000') && text.isNotBlank()) { "This file is not readable UTF-8/UTF-16 text." }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    fun textToEpub(text: String, title: String, id: String, destination: File) {
        val paragraphs = text.split(Regex("\n\\s*\n")).flatMap(::boundedParagraph)
        val chapters = mutableListOf<StringBuilder>()
        var chapter = StringBuilder()
        for (paragraph in paragraphs) {
            if (chapter.length > 100_000) { chapters += chapter; chapter = StringBuilder() }
            chapter.append("<p>").append(escape(paragraph).replace("\n", "<br/> ")).append("</p>\n")
        }
        if (chapter.isNotEmpty()) chapters += chapter
        require(chapters.isNotEmpty() && chapters.size <= 1000)
        ZipOutputStream(destination.outputStream().buffered()).use { out ->
            out.setComment(PREPARED_COMMENT)
            writeStoredMime(out)
            fun entry(name: String, content: String) { out.putNextEntry(ZipEntry(name)); out.write(content.toByteArray(Charsets.UTF_8)); out.closeEntry() }
            entry("META-INF/container.xml", """<?xml version="1.0" encoding="utf-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:sha256:$id</dc:identifier><dc:title>${escape(title)}</dc:title><dc:language>und</dc:language><meta property="dcterms:modified">2026-10-13T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${chapters.indices.joinToString("") { "<item id=\"c$it\" href=\"chapter-$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}</manifest><spine>${chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${escape(title)}</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol>${chapters.indices.joinToString("") { "<li><a href=\"chapter-$it.xhtml\">Part ${it + 1}</a></li>" }}</ol></nav></body></html>""")
            chapters.forEachIndexed { i, content -> entry("chapter-$i.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escape(title)} · Part ${i + 1}</title><meta http-equiv="Content-Security-Policy" content="$CSP"/></head><body><h1>${escape(title)} · Part ${i + 1}</h1>$content</body></html>""") }
        }
    }
    private fun boundedParagraph(value: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + 100_000, value.length)
            if (end < value.length && value[end - 1].isHighSurrogate()) end--
            chunks += value.substring(start, end)
            start = end
        }
        return chunks.ifEmpty { listOf("") }
    }
    private fun boundedRead(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun writeStoredMime(out: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val e = ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
        out.putNextEntry(e); out.write(bytes); out.closeEntry()
    }
}
