package mihon.readingapp.books

import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.ui.main.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BookImportCoordinatorRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph
    private fun fixture() = File(File(context.cacheDir, "original-book-fixtures").apply { mkdirs() }, "coordinator-${UUID.randomUUID()}.epub")
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.bookfixtures", file)

    @Test fun progressBlocksRapidRepeatsDetectsDuplicateAndRetriesFailure(): Unit = runBlocking {
        val file = fixture()
        val coordinator = graph.bookImportCoordinator
        val importedIds = mutableSetOf<String>()
        coordinator.dismiss()
        try {
            writeLargeOriginalEpub(file)
            val validBytes = file.readBytes()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(validBytes).joinToString("") { "%02x".format(it) }
            graph.localBookRepository.getByFingerprint(fingerprint)?.let { removeBook(it.id) }
            val selected = uri(file)
            val before = coordinator.state.value
            assertTrue(coordinator.start(listOf(selected)))
            assertFalse("A rapid second selection must not start another import", coordinator.start(listOf(selected)))
            val first = awaitCompleteAfter(before)
            assertEquals("Import failures: ${first.failures}", 1, first.books.size)
            assertFalse(first.books.single().alreadyImported)
            assertTrue(first.failures.isEmpty())
            importedIds += first.books.single().id

            coordinator.dismiss()
            val duplicateBefore = coordinator.state.value
            assertTrue(coordinator.start(listOf(selected)))
            val duplicate = awaitCompleteAfter(duplicateBefore)
            assertEquals(1, duplicate.books.size)
            assertTrue("The same bytes must open the existing row", duplicate.books.single().alreadyImported)
            assertEquals(first.books.single().id, duplicate.books.single().id)

            coordinator.dismiss()
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val failedBefore = coordinator.state.value
            assertTrue(coordinator.start(listOf(selected)))
            val failed = awaitCompleteAfter(failedBefore)
            assertTrue(failed.books.isEmpty())
            assertEquals(1, failed.failures.size)

            file.writeBytes(validBytes)
            val retryBefore = coordinator.state.value
            assertTrue(coordinator.retry())
            val retry = awaitCompleteAfter(retryBefore)
            assertEquals(1, retry.books.size)
            importedIds += retry.books.single().id
            assertTrue(retry.books.single().alreadyImported)
            assertEquals(first.books.single().id, retry.books.single().id)
        } finally {
            coordinator.dismiss()
            importedIds.forEach { removeBook(it) }
            file.delete()
        }
    }

    @Test fun completedImportStaysVisibleAcrossNavigationAndOpensExistingBook():Unit=runBlocking {
        val file=fixture();val coordinator=graph.bookImportCoordinator
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var id:String?=null
        fun click(label:String) {requireNotNull(device.wait(Until.findObject(By.text(label)),10000)) {"Missing $label"}.click()}
        try {
            writeLargeOriginalEpub(file)
            val bytes=file.readBytes();val fingerprint=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            graph.localBookRepository.getByFingerprint(fingerprint)?.let {removeBook(it.id)}
            ActivityScenario.launch(MainActivity::class.java).use {
                click("Library");coordinator.dismiss();val before=coordinator.state.value
                assertTrue(coordinator.start(listOf(uri(file))));val first=awaitCompleteAfter(before)
                assertTrue(first.failures.toString(),first.failures.isEmpty());id=first.books.single().id
                assertNotNull(device.wait(Until.findObject(By.text("IMPORT COMPLETE")),10000))
                click("Browse");click("Library")
                assertNotNull(device.wait(Until.findObject(By.text("IMPORT COMPLETE")),10000))
                assertNotNull(device.wait(Until.findObject(By.text("Open book")),10000))
                coordinator.dismiss();val previous=coordinator.state.value;assertTrue(coordinator.start(listOf(uri(file))))
                val duplicate=awaitCompleteAfter(previous);assertTrue(duplicate.books.single().alreadyImported)
                assertNotNull(device.wait(Until.findObject(By.text("ALREADY IN YOUR LIBRARY")),10000))
                val dir=File(context.getExternalFilesDir(null),"arkhive-qa").apply {mkdirs()}
                device.takeScreenshot(File(dir,"Arkhive-import-existing.png"))
                click("Open existing book")
                assertNotNull(device.wait(Until.findObject(By.text("Original Import Progress Fixture")),10000))
                device.pressBack();click("Done");assertTrue(device.wait(Until.gone(By.text("ALREADY IN YOUR LIBRARY")),10000))
            }
        } finally {coordinator.dismiss();id?.let {removeBook(it)};file.delete()}
    }

    private suspend fun awaitCompleteAfter(previous: BookImportState): BookImportState.Complete = withTimeout(60_000) {
        while (coordinatorState() == previous) delay(10)
        var completed: BookImportState.Complete? = null
        while (completed == null) {
            val state = coordinatorState()
            if (state is BookImportState.Complete) completed = state else delay(20)
        }
        completed
    }

    private fun coordinatorState() = graph.bookImportCoordinator.state.value

    private suspend fun removeBook(id: String) {
        graph.localBookRepository.get(id)?.let { book ->
            graph.localBookRepository.remove(id)
            setOf(book.assetFile, book.originalFile, book.coverFile).filterNotNull()
                .forEach { File(graph.localBookImporter.directory, it).delete() }
        }
        graph.libraryShelfMembership.restoreBook(id)
    }

    private fun writeLargeOriginalEpub(file: File) {
        val random = Random(75)
        val prose = CharArray(2 * 1024 * 1024) { ('a'.code + random.nextInt(26)).toChar() }.concatToString()
        ZipOutputStream(file.outputStream()).use { out ->
            fun entry(name: String, value: String, stored: Boolean = false) {
                val bytes = value.toByteArray()
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = bytes.size.toLong(); e.compressedSize = e.size
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(e); out.write(bytes); out.closeEntry()
            }
            entry("mimetype", "application/epub+zip", true)
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:53d692db-c77e-41c9-9fc2-46cf454cc3cc</dc:identifier><dc:title>Original Import Progress Fixture</dc:title><dc:creator>Arkhive authored fixture</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""")
            entry("chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><meta name="fixture"/><title>Progress</title></head><body><p>$prose</p></body></html>""")
        }
    }
}
