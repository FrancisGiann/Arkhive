package mihon.readingapp.books

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class BookPackagePolicyTest {
    @Test fun archivePathsRejectTraversalAndEncodedEscapes() {
        listOf("../book.xhtml", "a/../../book", "/etc/passwd", "C:/book", "a\\..\\book", "%2e%2e/book", "a/%2e%2e/book").forEach {
            assertFalse(BookPackagePolicy.isSafeEntry(it), it)
        }
        assertTrue(BookPackagePolicy.isSafeEntry("OEBPS/夜明け/chapter-1.xhtml"))
    }
    @Test fun preservesUnicodeAndRejectsBinaryInsteadOfReplacingBytes() {
        val s = "夜明け 🌙\nA quiet library."
        assertEquals(s, BookPackagePolicy.decodeText(s.toByteArray()))
        assertEquals(s, BookPackagePolicy.decodeText(byteArrayOf(0xFF.toByte(),0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
        assertThrows(Exception::class.java) { BookPackagePolicy.decodeText(byteArrayOf(0xFF.toByte(), 0, 0)) }
        assertThrows(Exception::class.java) { BookPackagePolicy.decodeText("bad\u0000text".toByteArray()) }
    }
    @Test fun textConversionBuildsSelfContainedPublicationWithoutInterpretingMarkup() {
        val dir = Files.createTempDirectory("arkhive-text-test").toFile()
        try {
            val file = File(dir, "book.epub")
            BookPackagePolicy.textToEpub("<script>unsafe()</script>\n\nA quiet library 🌙.", "A & B", "fixture", file)
            ZipFile(file).use { zip ->
                val mime = zip.getEntry("mimetype")
                assertEquals(0, mime.method)
                assertNotNull(zip.getEntry("META-INF/container.xml"))
                val text = zip.getInputStream(zip.getEntry("chapter-0.xhtml")).reader().readText()
                assertTrue(text.contains("&lt;script&gt;unsafe()&lt;/script&gt;"))
                assertTrue(text.contains("🌙"))
                assertFalse(text.contains("<script>"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun xhtmlRemovesExecutableAndExternalReferencesButRetainsBookSemantics() {
        val input = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Title</title></head><body><h1 id="chapter">Dawn</h1><p onclick="steal()">Text <em>emphasis</em></p><script src="https://evil.test/x.js"/><iframe srcdoc="evil"/><img src="cover.png"/><img src="file:///private/key"/><a href="javascript:evil()">Link</a></body></html>"""
        val result = BookPackagePolicy.sanitizeXhtml(input.byteInputStream(), true).toString(Charsets.UTF_8)
        assertFalse(result.contains("onclick"))
        assertFalse(result.contains("<script"))
        assertFalse(result.contains("<iframe"))
        assertFalse(result.contains("file:///private/key"))
        assertFalse(result.contains("javascript:evil"))
        assertTrue(result.contains("id=\"chapter\""))
        assertTrue(result.contains("<em>emphasis</em>"))
        assertTrue(result.contains("src=\"cover.png\""))
        assertTrue(result.contains("Content-Security-Policy"))
    }

    @Test fun manifestDeclaredHtmlBecomesWellFormedXhtmlAcrossLargeSpine() {
        val dir = Files.createTempDirectory("arkhive-xhtml-repair").toFile()
        try {
            val source = File(dir, "source.epub")
            val ready = File(dir, "ready.epub")
            ZipOutputStream(source.outputStream()).use { out ->
                fun entry(name: String, value: String, stored: Boolean = false) {
                    val bytes = value.toByteArray()
                    val item = ZipEntry(name)
                    if (stored) {
                        item.method = ZipEntry.STORED
                        item.size = bytes.size.toLong()
                        item.compressedSize = item.size
                        item.crc = CRC32().apply { update(bytes) }.value
                    }
                    out.putNextEntry(item); out.write(bytes); out.closeEntry()
                }
                entry("mimetype", "application/epub+zip", true)
                entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                entry("OPS/book.opf", """<package><manifest>${(0 until 636).joinToString("") { "<item id=\"c$it\" href=\"Text/ch-$it.html\" media-type=\"application/xhtml+xml\"/>" }}</manifest><spine>${(0 until 636).joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>""")
                repeat(636) { index ->
                    entry("OPS/Text/ch-$index.html", """<!doctype html><html><head><meta name="fixture"><title>Chapter $index</title></head><body><p>Original chapter $index&nbsp;</p><br><script>bad()</script></body></html>""")
                }
            }
            BookPackagePolicy.prepareEpub(source, ready)
            assertTrue(BookPackagePolicy.isCurrentPrepared(ready))
            ZipFile(ready).use { zip ->
                assertEquals(636, (0 until 636).count { zip.getEntry("OPS/Text/ch-$it.html") != null })
                val chapter = zip.getInputStream(zip.getEntry("OPS/Text/ch-4.html")).readBytes()
                assertDoesNotThrow { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(chapter.inputStream()) }
                val xhtml = chapter.toString(Charsets.UTF_8)
                assertTrue(xhtml.contains("Content-Security-Policy"))
                assertFalse(xhtml.contains("<script"))
                assertTrue(xhtml.contains("<meta name=\"fixture\""))
            }
        } finally { dir.deleteRecursively() }
    }
}
