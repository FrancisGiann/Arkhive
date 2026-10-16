package mihon.readingapp.books

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.app.di.appGraph
import mihon.domain.book.LocalBookFormat
import mihon.domain.book.BookBookmark
import mihon.readingapp.books.pdf.IsolatedPdfClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LocalBookRuntimeTest {
    private class BookScrollProbe {
        val scrollY = AtomicInteger(-1)
        val targetIdentity = AtomicInteger(0)
        val contentHeight = AtomicInteger(-1)
        val targetHeight = AtomicInteger(-1)
        @Volatile var visible = false
        @Volatile var canScroll = false
        @Volatile var focused = false
        @Volatile var lifecycle = "unknown"
        @Volatile var resource = "unknown"
        @Volatile var mode = "unknown"
        @Volatile var running = false
        @Volatile var controllerMessage = "unknown"
        @Volatile var bounds = "unknown"
        var observer: ViewTreeObserver? = null
        var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
        var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var snapshot: (() -> Unit)? = null
        private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var refreshTask: Runnable? = null

        fun diagnostic(): String =
            "target=${targetIdentity.get()},visible=$visible,scrollY=${scrollY.get()}," +
                "contentHeight=${contentHeight.get()},targetHeight=${targetHeight.get()},canScroll=$canScroll," +
                "resource=$resource,mode=$mode,running=$running,message=$controllerMessage," +
                "focus=$focused,lifecycle=$lifecycle,bounds=$bounds"

        fun dispose() {
            stopRefreshing()
            observer?.takeIf { it.isAlive }?.let { live ->
                scrollListener?.let(live::removeOnScrollChangedListener)
                layoutListener?.let(live::removeOnGlobalLayoutListener)
            }
            observer = null
            scrollListener = null
            layoutListener = null
            snapshot = null
        }

        /** Test-only bounded refresh for state transitions that need not relayout Compose. */
        fun refreshFor(durationMs: Long) {
            refreshHandler.post {
                stopRefreshing()
                val deadline = android.os.SystemClock.uptimeMillis() + durationMs
                lateinit var task: Runnable
                task = Runnable {
                    snapshot?.invoke()
                    if (android.os.SystemClock.uptimeMillis() < deadline) {
                        refreshHandler.postDelayed(task, 50)
                    } else {
                        refreshTask = null
                    }
                }
                refreshTask = task
                task.run()
            }
        }

        fun stopRefreshing() {
            refreshTask?.let(refreshHandler::removeCallbacks)
            refreshTask = null
        }
    }

    private var savedIncognito = false
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    @Before fun normalReadingForPersistenceCases() {
        savedIncognito = graph.basePreferences.incognitoMode.get()
        graph.basePreferences.incognitoMode.set(false)
    }
    @After fun restoreIncognitoPreference() {
        graph.basePreferences.incognitoMode.set(savedIncognito)
        // Finish the private preference write before the instrumentation process exits.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().commit()
    }
    private fun fixture(extension: String): File = File(File(context.cacheDir, "original-book-fixtures").apply { mkdirs() }, "original-${UUID.randomUUID()}.$extension")
    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.bookfixtures", file)
    private suspend fun cleanup(id: String) {
        val book = graph.localBookRepository.get(id) ?: return
        graph.localBookRepository.remove(id)
        val dir = graph.localBookImporter.directory
        setOf(book.assetFile, book.originalFile, book.coverFile).filterNotNull().forEach { File(dir, it).delete() }
    }
    @Test fun contentUriTextImportsOfflineAndDuplicatePreservesProgress() = runBlocking {
        val file = fixture("txt")
        val text = "An original quiet archive 🌙.\n\n" + "A self-authored paragraph for offline reading.\n\n".repeat(50)
        file.writeText(text)
        var id: String? = null
        try {
            val imported = graph.localBookImporter.import(uri(file)).getOrThrow()
            id = imported.book.id
            assertEquals(LocalBookFormat.TEXT, imported.book.format)
            assertEquals(text, File(graph.localBookImporter.directory, imported.book.originalFile).readText())
            val marker = """{"href":"chapter-0.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4}}"""
            graph.localBookRepository.saveProgress(imported.book.id, marker, .4, System.currentTimeMillis())
            val duplicate = graph.localBookImporter.import(uri(file)).getOrThrow()
            assertTrue(duplicate.alreadyImported)
            assertEquals(id, duplicate.book.id)
            assertEquals(marker, duplicate.book.locator)
            assertEquals(.4, duplicate.book.progression, .00001)
            val previousIncognito = graph.basePreferences.incognitoMode.get()
            try {
                graph.basePreferences.incognitoMode.set(true)
                ActivityScenario.launch<BookReaderActivity>(android.content.Intent(context, BookReaderActivity::class.java)
                    .putExtra(BookReaderActivity.BOOK_ID, imported.book.id)).use { reader ->
                    awaitRenderedText(reader, "An original quiet archive")
                }
                delay(700)
                assertEquals("Incognito book reading must retain the prior stored locator", marker,
                    graph.localBookRepository.get(imported.book.id)?.locator)
            } finally { graph.basePreferences.incognitoMode.set(previousIncognito) }
            file.delete() // Original URI is now unreadable; the private copy remains usable.
            val publication = LocalEpubEngine(context).open(graph.localBookImporter.assetFile(imported.book))
            try { assertTrue(publication.readingOrder.isNotEmpty()) } finally { publication.close() }
        } finally { id?.let { cleanup(it) }; file.delete() }
        Unit
    }
    @Test fun invalidTextImportDoesNotCommitAnAssetOrLibraryRow() = runBlocking {
        val before = graph.localBookImporter.directory.listFiles().orEmpty().map { it.name }.toSet()
        val file = fixture("txt"); file.writeBytes(byteArrayOf(0xFF.toByte(), 0, 0))
        try {
            assertTrue(graph.localBookImporter.import(uri(file)).isFailure)
            assertEquals(before, graph.localBookImporter.directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally { file.delete() }
    }
    @Test fun importedEpubExposesItsAuthoredSynopsisAndSubjectTags() = runBlocking {
        val file=fixture("epub")
        val synopsis="A quiet, original story about a lantern keeper and an imagined city."
        val tags=listOf("historical fiction","quiet fantasy")
        writeOriginalEpub(file,metadataDescription=synopsis,metadataSubjects=tags)
        var id:String?=null
        try {
            val book=graph.localBookImporter.import(uri(file)).getOrThrow().book
            id=book.id
            val publication=LocalEpubEngine(context).open(graph.localBookImporter.ensureCurrentAsset(book))
            try {
                assertEquals("Book detail reads the actual EPUB description",synopsis,publication.metadata.description)
                assertEquals("Book detail reads the actual EPUB subject names",tags,publication.metadata.subjects.map { it.name })
            } finally {publication.close()}
        } finally {id?.let { cleanup(it) };file.delete()}
    }
    @Test fun legacyPreparedEpubRepairsFromPrivateOriginalWithoutLosingReadingState() = runBlocking {
        val file = fixture("epub"); writeOriginalEpub(file)
        var id: String? = null
        try {
            val book = graph.localBookImporter.import(uri(file)).getOrThrow().book; id = book.id
            val original = File(graph.localBookImporter.directory, book.originalFile)
            val asset = graph.localBookImporter.assetFile(book)
            val originalDigest = sha256(original)
            val locator = """{"href":"two.xhtml","type":"application/xhtml+xml","locations":{"progression":0.6}}"""
            graph.localBookRepository.saveProgress(book.id, locator, .6, System.currentTimeMillis())
            graph.localBookRepository.addBookmark(BookBookmark("repair-${book.id}", book.id, locator, "Repair marker", System.currentTimeMillis()))
            rewriteWithoutComment(asset)
            assertFalse(BookPackagePolicy.isCurrentPrepared(asset))

            val repaired = graph.localBookImporter.ensureCurrentAsset(requireNotNull(graph.localBookRepository.get(book.id)))
            assertEquals(asset, repaired)
            assertTrue(BookPackagePolicy.isCurrentPrepared(repaired))
            assertEquals(originalDigest, sha256(original))
            assertEquals(locator, graph.localBookRepository.get(book.id)?.locator)
            assertEquals(.6, graph.localBookRepository.get(book.id)?.progression ?: -1.0, .00001)
            assertEquals("Repair marker", graph.localBookRepository.bookmarks(book.id).single().label)
            LocalEpubEngine(context).open(repaired).also { publication ->
                try { assertEquals(2, publication.readingOrder.size) } finally { publication.close() }
            }
        } finally { id?.let { cleanup(it) }; file.delete() }
        Unit
    }
    @Test fun isolatedPdfRendersRealPagesAndFreshActivityRestoresPosition() = runBlocking {
        val file = fixture("pdf")
        val doc = PdfDocument()
        try {
            for (index in 0..2) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(600, 800, index).create())
                val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
                page.canvas.drawText("Original Arkhive PDF · page ${index + 1}", 40f, 100f, paint)
                paint.color = if (index == 0) Color.MAGENTA else Color.CYAN
                page.canvas.drawRect(50f, 180f, 550f, 650f, paint)
                doc.finishPage(page)
            }
            file.outputStream().use(doc::writeTo)
        } finally { doc.close() }
        var id: String? = null
        var reader: ActivityScenario<BookReaderActivity>? = null
        try {
            val book = graph.localBookImporter.import(uri(file)).getOrThrow().book; id = book.id
            assertEquals(LocalBookFormat.PDF, book.format)
            val pdf = IsolatedPdfClient.open(context, graph.localBookImporter.assetFile(book))
            try {
                assertNotEquals(Process.myUid(), pdf.rendererUid)
                assertEquals(3, pdf.pageCount)
                val page = pdf.render(1, 600)
                val bitmap = android.graphics.BitmapFactory.decodeFile(page.path)
                assertEquals(600, bitmap.width); assertEquals(800, bitmap.height)
                assertEquals(Color.CYAN, bitmap.getPixel(300, 400)); bitmap.recycle(); page.delete()
            } finally { pdf.close() }
            val device = UiDevice.getInstance(instrumentation)
            val intent = android.content.Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, id)
            reader = ActivityScenario.launch(intent)
            assertTrue(device.wait(Until.hasObject(By.text("Page 1 of 3")), 15_000))
            awaitPdfImage(reader!!)
            requireNotNull(device.wait(Until.findObject(By.desc("Next")), 15_000)).click()
            assertTrue(device.wait(Until.hasObject(By.text("Page 2 of 3")), 15_000))
            awaitPdfImage(reader!!)
            reader.close(); reader = null
            withTimeout(10_000) { while (graph.localBookRepository.get(book.id)?.locator?.let { JSONObject(it).optInt("page") } != 1) delay(100) }
            file.delete()
            reader = ActivityScenario.launch(intent)
            assertTrue(device.wait(Until.hasObject(By.text("Page 2 of 3")), 15_000))
            awaitPdfImage(reader!!)
            requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("Go to page")),10_000)).click()
            val pageField=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10_000))
            pageField.text="3"
            requireNotNull(device.wait(Until.findObject(By.text("Go")),10_000)).click()
            assertTrue("Go to page renders the requested authored PDF page",device.wait(Until.hasObject(By.text("Page 3 of 3")),15_000))
            awaitPdfImage(reader!!)
            requireNotNull(device.wait(Until.findObject(By.text("Return")),10_000)).click()
            assertTrue("PDF Return restores the original page",device.wait(Until.hasObject(By.text("Page 2 of 3")),15_000))
            awaitPdfImage(reader!!)
            assertTrue("Successful PDF Return consumes its one saved point",device.wait(Until.gone(By.text("Return")),5_000))
            requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("Auto read")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("Start auto read")),10_000)).click()
            val pdfPause = requireNotNull(device.wait(Until.findObject(By.desc("Pause auto read")),10_000)) {
                "Running PDF Auto read should keep a compact accessible Pause control"
            }
            assertNotNull("The quiet PDF strip should retain page metadata",device.wait(Until.findObject(By.text("Page 2 / 3")),5_000))
            assertTrue("The compact PDF Pause control should remain at the bottom of the page: ${pdfPause.visibleBounds}",
                pdfPause.visibleBounds.bottom > device.displayHeight * 3 / 4)
            pdfPause.click()
            assertTrue("Pausing PDF automation should remove its running strip",device.wait(Until.gone(By.desc("Pause auto read")),5_000))
        } finally { reader?.close(); id?.let { cleanup(it) }; file.delete() }
    }

    private fun rewriteWithoutComment(file: File) {
        val temp = File(file.parentFile, "${file.name}.legacy")
        ZipFile(file).use { input ->
            ZipOutputStream(temp.outputStream()).use { output ->
                input.entries().toList().forEach { old ->
                    val bytes = input.getInputStream(old).use { it.readBytes() }
                    val entry = ZipEntry(old.name)
                    if (old.method == ZipEntry.STORED) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong(); entry.compressedSize = entry.size
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    output.putNextEntry(entry); output.write(bytes); output.closeEntry()
                }
            }
        }
        assertTrue(temp.renameTo(file) || run { temp.copyTo(file, overwrite = true); temp.delete(); true })
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun explicitChapterLocatorOpensActualTextAndIncognitoKeepsPriorPosition() = runBlocking {
        val file=fixture("epub");writeOriginalEpub(file)
        var id:String?=null
        val priorIncognito=graph.basePreferences.incognitoMode.get()
        try {
            val book=graph.localBookImporter.import(uri(file)).getOrThrow().book;id=book.id
            val publication=LocalEpubEngine(context).open(graph.localBookImporter.assetFile(book))
            val locators=try {publication.readingOrder.take(2).map {checkNotNull(publication.locatorFromLink(it)).toJSON().toString()}} finally {publication.close()}
            graph.localBookRepository.saveProgress(book.id,locators[0],.1,System.currentTimeMillis())
            graph.basePreferences.incognitoMode.set(true)
            val intent=android.content.Intent(context,BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID,book.id)
                .putExtra(BookReaderActivity.START_LOCATOR,locators[1])
            ActivityScenario.launch<BookReaderActivity>(intent).use {reader -> awaitRenderedText(reader,"Original two chapter")}
            delay(500)
            assertEquals(locators[0],graph.localBookRepository.get(book.id)?.locator)
            graph.basePreferences.incognitoMode.set(false)
            ActivityScenario.launch<BookReaderActivity>(intent).use {reader -> awaitRenderedText(reader,"Original two chapter")}
            withTimeout(10_000) {while(graph.localBookRepository.get(book.id)?.locator?.contains("two.xhtml")!=true) delay(100)}
        } finally {graph.basePreferences.incognitoMode.set(priorIncognito);id?.let{cleanup(it)};file.delete()}
    }
    @Test fun epubTwoSpineResourcesRemainReadableAndRestoreALocator() = runBlocking {
        val file = fixture("epub"); writeOriginalEpub(file)
        var id: String? = null
        var reader: ActivityScenario<BookReaderActivity>? = null
        try {
            val book = graph.localBookImporter.import(uri(file)).getOrThrow().book; id = book.id
            assertEquals("The Quiet Archive", book.title)
            assertEquals("Original Arkhive fixture", book.author)
            val p = LocalEpubEngine(context).open(graph.localBookImporter.assetFile(book))
            val secondLocator = try {
                assertEquals(2, p.readingOrder.size)
                requireNotNull(p.locatorFromLink(p.readingOrder[1]))
            } finally { p.close() }
            val intent = android.content.Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, id)
            reader = ActivityScenario.launch(intent)
            lateinit var navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment
            withTimeout(20_000) {
                while (true) {
                    var ready = false
                    reader!!.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                        if (fragment is org.readium.r2.navigator.epub.EpubNavigatorFragment && fragment.view != null) {
                            navigator = fragment; ready = true
                        }
                    }
                    if (ready) break
                    delay(100)
                }
            }
            awaitRenderedText(reader!!, "Original one chapter")
            // A real rendered heading must follow the approved regular serif hierarchy,
            // even when the imported document uses an unstyled HTML h1.
            withTimeout(10_000) {
                while (true) {
                    val raw = withContext(Dispatchers.Main) { navigator.evaluateJavascript("""
                        JSON.stringify({
                          styled: !!document.getElementById('arkhive-reading-typography'),
                          weight: getComputedStyle(document.querySelector('h1')).fontWeight,
                          indent: getComputedStyle(document.querySelector('p')).textIndent,
                          bodySize: getComputedStyle(document.body).fontSize,
                          paragraphSize: getComputedStyle(document.querySelector('p')).fontSize,
                          headingSize: getComputedStyle(document.querySelector('h1')).fontSize,
                          faces: Array.from(document.fonts).map(f => ({family:f.family, status:f.status})),
                          fontRules: Array.from(document.styleSheets).flatMap(s => {try { return Array.from(s.cssRules).filter(r => r.type === 5 && r.cssText.includes('Arkhive')).map(r => r.cssText); } catch(e) { return []; }}),
                          resources: performance.getEntriesByType('resource').filter(r => r.name.includes('arkhive-type')).map(r => ({url:r.name, status:r.responseStatus})),
                          serifLoaded: Array.from(document.fonts).some(f => f.family.includes('Arkhive Serif') && f.status === 'loaded')
                        })
                    """.trimIndent()) }
                    android.util.Log.i("ArkhiveTypographyTest", raw ?: "no rendered style response")
                    val rendered = runCatching {
                        when(val value = org.json.JSONTokener(raw ?: "null").nextValue()) {
                            is JSONObject -> value
                            is String -> JSONObject(value)
                            else -> null
                        }
                    }.getOrNull()
                    if (rendered?.optBoolean("styled") == true && rendered.optString("weight") == "400" &&
                        rendered.optString("indent") == "0px" && rendered.optBoolean("serifLoaded") &&
                        rendered.optString("paragraphSize") == rendered.optString("bodySize") &&
                        kotlin.math.abs(rendered.optString("headingSize").removeSuffix("px").toDouble() /
                            rendered.optString("bodySize").removeSuffix("px").toDouble() - 32.0/21.0) < .001) break
                    delay(100)
                }
            }
            // Reflowable prose must not sit underneath visible reader chrome.
            withTimeout(5_000) {
                val device = UiDevice.getInstance(instrumentation)
                while (true) {
                    var viewport: android.graphics.Rect? = null
                    reader!!.onActivity { activity ->
                        viewport = android.graphics.Rect().also { activity.findViewById<android.view.View>(eu.kanade.tachiyomi.R.id.book_reader_content).getGlobalVisibleRect(it) }
                    }
                    val top = device.findObject(By.text("The Quiet Archive"))?.visibleBounds
                    val bottom = device.findObject(By.desc("Next"))?.visibleBounds
                    if (top != null && bottom != null && viewport!!.top >= top.bottom && viewport!!.bottom <= bottom.top) break
                    delay(100)
                }
            }
            // Actual edge tap must advance a scrolling text viewport, not a horizontal paged API.
            fun scrollY(): Int {
                var y = -1
                reader!!.onActivity { activity ->
                    y = activity.window.decorView.descendants().filterIsInstance<android.webkit.WebView>()
                        .first { it.isShown && it.getGlobalVisibleRect(android.graphics.Rect()) }.scrollY
                }
                return y
            }
            val beforeTap = scrollY()
            val device = UiDevice.getInstance(instrumentation)
            device.click(device.displayWidth * 9 / 10, device.displayHeight / 2)
            withTimeout(5_000) { while (scrollY() <= beforeTap) delay(100) }
            // Actual independent Readium navigator over imported assets, not a mocked locator store.
            reader!!.onActivity { navigator.go(secondLocator, false) }
            awaitRenderedText(reader!!, "Original two chapter")
            withTimeout(15_000) { while (graph.localBookRepository.get(book.id)?.locator?.contains("two.xhtml") != true) delay(100) }
            reader.close(); reader = null; file.delete()
            reader = ActivityScenario.launch(intent)
            withTimeout(15_000) {
                while (true) {
                    var restored = false
                    reader!!.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                        if (fragment is org.readium.r2.navigator.epub.EpubNavigatorFragment && fragment.view != null)
                            restored = fragment.currentLocator.value.href.toString().contains("two.xhtml")
                    }
                    if (restored) break
                    delay(100)
                }
            }
            awaitRenderedText(reader!!, "Original two chapter")
        } finally { reader?.close(); id?.let { cleanup(it) }; file.delete() }
    }

    @Test fun sameResourceEpubFragmentJumpIsNotSuppressedAndOffersReturn() = runBlocking {
        val file=fixture("epub");writeOriginalEpub(file,includeNavigation=true)
        var id:String?=null;var reader:ActivityScenario<BookReaderActivity>?=null
        val oldPrivate=graph.basePreferences.incognitoMode.get()
        val preferences=context.getSharedPreferences("arkhive_book_reader",0)
        val oldScrollPresent=preferences.contains("scroll");val oldScroll=preferences.getBoolean("scroll",true)
        var returnKey:String?=null;var hadReturn=false;var oldReturn:String?=null
        val device=UiDevice.getInstance(instrumentation)
        suspend fun awaitFragmentStage(stage:String,expected:String) {
            try {
                awaitRenderedText(requireNotNull(reader),expected)
            } catch(error:TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for same-resource EPUB stage '$stage' (expected rendered text '$expected')",error)
            }
        }
        try {
            val book=graph.localBookImporter.import(uri(file)).getOrThrow().book;id=book.id
            val publication=LocalEpubEngine(context).open(graph.localBookImporter.assetFile(book))
            val (sentinel,tocLinkJson,targetJson)=try {
                fun flatten(links:List<org.readium.r2.shared.publication.Link>):List<org.readium.r2.shared.publication.Link> =
                    links.flatMap { listOf(it)+flatten(it.children) }
                val link=flatten(publication.tableOfContents).firstOrNull {
                    android.net.Uri.parse(it.href.toString()).fragment=="same-resource-section"
                } ?: error("Authored EPUB3 TOC is missing the same-resource section: ${publication.tableOfContents}")
                val target=requireNotNull(publication.locatorFromLink(link))
                val prior=requireNotNull(publication.locatorFromLink(publication.readingOrder[1]))
                Triple(prior.toJSON().toString(),link.toJSON().toString(),target.toJSON().toString())
            } finally {publication.close()}
            returnKey="book-return:${book.id}"
            hadReturn=preferences.contains(returnKey);oldReturn=preferences.getString(returnKey,null)
            preferences.edit().putBoolean("scroll",true).putString(returnKey,sentinel).commit()
            graph.basePreferences.incognitoMode.set(true)
            val intent=android.content.Intent(context,BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID,book.id)
            reader=ActivityScenario.launch(intent)
            awaitFragmentStage("initial selected spine HTML and reader render","Original one chapter")
            lateinit var prior:String
            lateinit var navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment
            reader!!.onActivity {activity->
                navigator=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                    as org.readium.r2.navigator.epub.EpubNavigatorFragment
                prior=navigator.currentLocator.value.toJSON().toString()
            }
            suspend fun sectionProbe():JSONObject {
                val evaluated=CompletableDeferred<String?>()
                reader!!.onActivity {activity->
                    val web=bookAutoReadTarget(activity)
                    if(web==null) evaluated.complete(null) else web.evaluateJavascript(
                        """JSON.stringify((()=>{const e=document.getElementById('same-resource-section');const r=e?e.getBoundingClientRect():null;return {href:location.href,found:!!e,top:r?r.top:-1,bottom:r?r.bottom:-1,height:innerHeight,visible:!!e&&r.bottom>0&&r.top<innerHeight,scrollX,scrollY,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight}})())""",
                    ) {value->evaluated.complete(value)}
                }
                val raw=withTimeout(2_500){evaluated.await()}
                return when(val decoded=org.json.JSONTokener(raw?:"null").nextValue()) {
                    is JSONObject->decoded
                    is String->JSONObject(decoded)
                    else->error("No rendered same-resource anchor in the selected visible Readium WebView: $raw")
                }
            }
            val beforeSection=try {
                sectionProbe()
            } catch(error:TimeoutCancellationException) {
                throw AssertionError("Timed out probing the initial selected EPUB WebView DOM before TOC navigation",error)
            }
            assertTrue("The authored anchor starts outside the actual reader viewport: $beforeSection",
                beforeSection.has("top") && !beforeSection.optBoolean("visible"))
            reader!!.onActivity {activity->
                val target=requireNotNull(org.readium.r2.shared.publication.Locator.fromJSON(JSONObject(targetJson)))
                val matches=activity.javaClass.getDeclaredMethod(
                    "locatorMatches",org.readium.r2.shared.publication.Locator::class.java,String::class.java,
                ).apply {isAccessible=true}
                assertFalse("A same-resource fragment is a distinct deliberate target without scalar coordinates",
                    matches.invoke(activity,navigator.currentLocator.value,target.toJSON().toString()) as Boolean)
                val fragmentTarget=activity.javaClass.getDeclaredMethod("fragmentOnlyReturnTarget",String::class.java)
                    .apply {isAccessible=true}.invoke(activity,targetJson)
                assertNotNull("Readium fragment-only TOC locators remain available to Return settlement",fragmentTarget)
                assertEquals("The parsed settlement target retains the authored element ID","same-resource-section",
                    fragmentTarget!!.javaClass.getDeclaredField("elementId").apply {isAccessible=true}.get(fragmentTarget))
                activity.javaClass.getDeclaredMethod("navigateToEpubLocator",org.readium.r2.shared.publication.Locator::class.java)
                    .apply {isAccessible=true}.invoke(activity,target)
            }
            var latestLocator=""
            fun pendingReturnState():String {
                var state="unavailable"
                reader!!.onActivity {activity->
                    fun field(name:String):Any? {
                        val direct=runCatching {activity.javaClass.getDeclaredField(name)}.getOrNull()
                        val reflected=direct ?: runCatching {activity.javaClass.getDeclaredField("${name}\$delegate")}.getOrNull()
                            ?: return null
                        reflected.isAccessible=true
                        val value=reflected.get(activity)
                        return (value as? androidx.compose.runtime.State<*>)?.value ?: value
                    }
                    state="returnLocator=${field("returnLocatorJson")},pendingCandidate=${field("pendingReturnCandidate")},"+
                        "pendingConsume=${field("pendingReturnConsume")},targetMatch=${runCatching {
                            activity.javaClass.getDeclaredMethod(
                                "locatorMatches",org.readium.r2.shared.publication.Locator::class.java,String::class.java,
                            ).apply {isAccessible=true}.invoke(activity,navigator.currentLocator.value,targetJson)
                        }.getOrNull()}"
                }
                return state
            }
            try {
                withTimeout(10_000) {
                    while(true) {
                        var reached=false
                        reader!!.onActivity {activity->
                            val navigator=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                                as org.readium.r2.navigator.epub.EpubNavigatorFragment
                            val actual=navigator.currentLocator.value
                            latestLocator=actual.toJSON().toString()
                        }
                        val section=sectionProbe()
                        reached=section.optBoolean("visible") &&
                            kotlin.math.abs(section.optDouble("top")-beforeSection.optDouble("top"))>100
                        if(reached) break
                        delay(100)
                    }
                }
            } catch(error:TimeoutCancellationException) {
                val visibleReader=bookReaderDiagnosticWithDom(requireNotNull(reader),navigator)
                val selectedSection=runCatching {sectionProbe().toString()}.getOrElse {"probe error: ${it.message}"}
                throw AssertionError("Timed out waiting for the actual selected-reader DOM to move to the TOC section; " +
                    "link=$tocLinkJson target=$targetJson current=$latestLocator before=$beforeSection " +
                    "selectedSection=$selectedSection visibleReader=$visibleReader returnState=${pendingReturnState()}",error)
            }
            val afterSection=sectionProbe()
            assertTrue("The same-resource jump moves the actual authored section into the reader viewport: before=$beforeSection after=$afterSection",
                afterSection.optBoolean("visible") && kotlin.math.abs(afterSection.optDouble("top")-beforeSection.optDouble("top"))>100)
            try {
                withTimeout(10_000) {while(!device.hasObject(By.text("Return"))) delay(100)}
            } catch(error:TimeoutCancellationException) {
                val visibleReader=bookReaderDiagnosticWithDom(requireNotNull(reader),navigator)
                throw AssertionError("The TOC section reached the actual selected reader viewport but the one-slot Return action did not settle; "+
                    "target=$targetJson section=$afterSection current=${navigator.currentLocator.value.toJSON()} " +
                    "visibleReader=$visibleReader returnState=${pendingReturnState()}",error)
            }
            var committedReturn:String?=null
            reader!!.onActivity {activity->
                val field=activity.javaClass.getDeclaredField("returnLocatorJson\$delegate").apply {isAccessible=true}
                committedReturn=(field.get(activity) as androidx.compose.runtime.State<*>).value as? String
            }
            assertEquals("A rendered same-resource fragment jump commits the captured point immediately before it, not the old sentinel",
                prior,committedReturn)
            assertEquals("A private EPUB jump leaves the pre-existing normal-mode Return sentinel untouched",sentinel,preferences.getString(returnKey,null))
            requireNotNull(device.wait(Until.findObject(By.text("Return")),10_000)).click()
            val returnedSection=try {
                withTimeout(10_000) {
                    var latest=sectionProbe()
                    while(latest.optBoolean("visible") || kotlin.math.abs(latest.optDouble("top")-beforeSection.optDouble("top"))>100) {
                        delay(100);latest=sectionProbe()
                    }
                    latest
                }
            } catch(error:TimeoutCancellationException) {
                val current=navigator.currentLocator.value.toJSON()
                val selectedSection=runCatching {sectionProbe().toString()}.getOrElse {"probe error: ${it.message}"}
                val visibleReader=bookReaderDiagnosticWithDom(requireNotNull(reader),navigator)
                throw AssertionError("Timed out waiting for actual selected-reader DOM to restore the prior same-resource viewport; "+
                    "prior=$prior target=$targetJson before=$beforeSection current=$current selectedSection=$selectedSection "+
                    "visibleReader=$visibleReader returnState=${pendingReturnState()}",error)
            }
            assertTrue("Private Return restores the actual same-resource section viewport: before=$beforeSection after=$returnedSection target=$targetJson",
                !returnedSection.optBoolean("visible") && kotlin.math.abs(returnedSection.optDouble("top")-beforeSection.optDouble("top"))<=100)
            withTimeout(10_000) {while(device.hasObject(By.text("Return"))) delay(50)}
            assertEquals("A private Return consumes only its in-memory point and preserves normal-mode history",sentinel,preferences.getString(returnKey,null))
        } finally {
            reader?.close()
            graph.basePreferences.incognitoMode.set(oldPrivate)
            preferences.edit().apply {
                if(oldScrollPresent) putBoolean("scroll",oldScroll) else remove("scroll")
                returnKey?.let {if(hadReturn) putString(it,oldReturn) else remove(it)}
            }.commit()
            id?.let { cleanup(it) };file.delete()
        }
    }

    @Test fun deliberateEpubSpineJumpOffersOneReturnAndConsumesItAfterRestore() = runBlocking {
        val file = fixture("epub"); writeOriginalEpub(file)
        var id: String? = null
        var reader: ActivityScenario<BookReaderActivity>? = null
        try {
            val book = graph.localBookImporter.import(uri(file)).getOrThrow().book; id = book.id
            val publication = LocalEpubEngine(context).open(graph.localBookImporter.assetFile(book))
            val second = try {
                requireNotNull(publication.locatorFromLink(publication.readingOrder[1]))
            } finally { publication.close() }
            val intent = android.content.Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, book.id)
            reader = ActivityScenario.launch(intent)
            awaitRenderedText(requireNotNull(reader), "Original one chapter")
            val prior = withContext(Dispatchers.Main) {
                var value = ""
                reader!!.onActivity { activity ->
                    val navigator = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                        as org.readium.r2.navigator.epub.EpubNavigatorFragment
                    value = navigator.currentLocator.value.toJSON().toString()
                    val actual = navigator.currentLocator.value
                    val progress = actual.locations.progression
                    assertNotNull("Authored EPUB locator includes a continuous resource offset", progress)
                    val actualProgress = requireNotNull(progress)
                    val changedOffset = if (actualProgress < .5) actualProgress + .2 else actualProgress - .2
                    val syntheticBucket = 37
                    val sameBucketCurrent = JSONObject(value).put("locations", JSONObject(value).getJSONObject("locations")
                        .put("position", syntheticBucket).put("progression", actualProgress)).toString()
                    val sameBucketDifferentOffset = JSONObject(sameBucketCurrent).put("locations",
                        JSONObject(sameBucketCurrent).getJSONObject("locations").put("position", syntheticBucket)
                            .put("progression", changedOffset)).toString()
                    val actualWithSyntheticBucket=requireNotNull(org.readium.r2.shared.publication.Locator.fromJSON(JSONObject(sameBucketCurrent)))
                    val locatorMatches = activity.javaClass.getDeclaredMethod(
                        "locatorMatches", org.readium.r2.shared.publication.Locator::class.java, String::class.java,
                    ).apply { isAccessible = true }
                    assertFalse("A same discrete bucket with a different actual EPUB offset is not the same Return target",
                        locatorMatches.invoke(activity, actualWithSyntheticBucket, sameBucketDifferentOffset) as Boolean)
                    val navigate = activity.javaClass.getDeclaredMethod(
                        "navigateToEpubLocator", org.readium.r2.shared.publication.Locator::class.java,
                    ).apply { isAccessible = true }
                    navigate.invoke(activity, second)
                }
                value
            }
            awaitRenderedText(requireNotNull(reader), "Original two chapter")
            val preferences = context.getSharedPreferences("arkhive_book_reader", 0)
            val key = "book-return:${book.id}"
            withTimeout(10_000) { while (preferences.getString(key, null) == null) delay(50) }
            assertEquals("Only the successful deliberate jump stores its prior locator", prior, preferences.getString(key, null))

            val device = UiDevice.getInstance(instrumentation)
            requireNotNull(device.wait(Until.findObject(By.text("Return")), 10_000)).click()
            awaitRenderedText(requireNotNull(reader), "Original one chapter")
            withTimeout(10_000) { while (preferences.contains(key)) delay(50) }
            assertTrue("Successful Return consumes the one saved point", device.wait(Until.gone(By.text("Return")), 5_000))
        } finally { reader?.close(); id?.let { cleanup(it) }; file.delete() }
    }

    @Test fun epubContentTapsAndNativePagesGesturesStayWithinSpineResource() = runBlocking {
        val file=fixture("epub");writeOriginalEpub(file)
        val graph=context.appGraph
        val prefs=context.getSharedPreferences("arkhive_book_reader",0)
        val hadScroll=prefs.contains("scroll");val oldScroll=prefs.getBoolean("scroll",true)
        var id:String?=null;var reader:ActivityScenario<BookReaderActivity>?=null
        var profileKey:String?=null;var hadPriorProfile=false;var priorProfile=mihon.readingapp.library.ReadingProfile()
        val device=UiDevice.getInstance(instrumentation)
        val inputTimeline=JSONArray()
        val inputTimelineFile=File(context.cacheDir,"qa-0.4.2-epub-input-timeline-17.json")
        inputTimelineFile.delete()
        val inputTimelineStarted=android.os.SystemClock.uptimeMillis()
        fun persistInputTimeline() {
            inputTimelineFile.writeText(inputTimeline.toString(2))
        }
        fun recordInputEvent(stage:String,event:JSONObject) {
            inputTimeline.put(JSONObject().put("kind","input").put("stage",stage)
                .put("elapsedMs",android.os.SystemClock.uptimeMillis()-inputTimelineStarted).put("event",event))
            persistInputTimeline()
        }
        suspend fun fragment(scenario:ActivityScenario<BookReaderActivity>):org.readium.r2.navigator.epub.EpubNavigatorFragment {
            var result:org.readium.r2.navigator.epub.EpubNavigatorFragment?=null
            withTimeout(20_000) {while(result==null) {
                scenario.onActivity {activity->result=activity.supportFragmentManager.findFragmentByTag("arkhive_epub") as? org.readium.r2.navigator.epub.EpubNavigatorFragment}
                if(result==null) delay(100)
            }}
            return requireNotNull(result)
        }
        suspend fun tapRatio(scenario:ActivityScenario<BookReaderActivity>,ratio:Float) {
            val target=IntArray(2)
            scenario.onActivity {activity->val web=requireNotNull(bookAutoReadTarget(activity));web.getLocationOnScreen(target)
                target[0]+=(web.width*ratio).toInt();target[1]+=web.height/2}
            device.click(target[0],target[1])
        }
        fun injectScreenTouch(action:Int,downTime:Long,eventTime:Long,x:Float,y:Float) {
            val properties=arrayOf(MotionEvent.PointerProperties().apply {
                this.id=0
                this.toolType=MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates=arrayOf(MotionEvent.PointerCoords().apply {
                this.x=x
                this.y=y
                pressure=1f
                size=1f
            })
            val event=MotionEvent.obtain(downTime,eventTime,action,1,properties,coordinates,0,0,
                1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try {
                recordInputEvent("held-pages",JSONObject().put("action",action).put("downTime",downTime)
                    .put("eventTime",eventTime).put("x",x).put("y",y).put("source",event.source)
                    .put("toolType",event.getToolType(0)).put("pressure",event.getPressure(0)))
                assertTrue("UiAutomation accepts touchscreen action=$action at $x,$y source=${event.source}",
                    instrumentation.uiAutomation.injectInputEvent(event,true))
            } finally { event.recycle() }
        }
        suspend fun awaitReadiumPagerSettled(stage:String,navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment) {
            var previous:String?=null
            var stable=0
            val scrollStateField=try {
                androidx.viewpager.widget.ViewPager::class.java.getDeclaredField("mScrollState").apply {isAccessible=true}
            } catch(error:ReflectiveOperationException) {
                throw AssertionError("Pinned AndroidX ViewPager mScrollState is required to prove EPUB pager idle after $stage",error)
            }
            try {
                withTimeout(8_000) {
                    while(stable<3) {
                        var signature=""
                        var pagerIdle=false
                        reader!!.onActivity {activity->
                            val pub=navigator.publicationView
                            // Readium 3.4.0's EpubNavigatorFragment uses R2RTLViewPager, a classic
                            // AndroidX ViewPager (not RecyclerView/ViewPager2). Require a real pager
                            // and read its pinned private state so an empty/mismatched hierarchy
                            // cannot make the settle check pass vacuously.
                            val pagers=pub.descendants().filterIsInstance<androidx.viewpager.widget.ViewPager>().toList()
                            check(pagers.isNotEmpty()) {
                                "Readium EPUB publication view has no AndroidX ViewPager after $stage: ${pub.javaClass.name}"
                            }
                            val pagerStates=pagers.map {pager->
                                try { scrollStateField.getInt(pager) }
                                catch(error:ReflectiveOperationException) {
                                    throw AssertionError("Unable to read pinned ViewPager scroll state after $stage (${pager.javaClass.name})",error)
                                }
                            }
                            pagerIdle=pagerStates.all {it==androidx.viewpager.widget.ViewPager.SCROLL_STATE_IDLE}
                            val pagerSignature=pagers.mapIndexed {index,pager->
                                "${pager.javaClass.name}:${pagerStates[index]}:${pager.currentItem}:${pager.scrollX}"
                            }.joinToString("|")
                            val webViews=pub.descendants().filterIsInstance<android.webkit.WebView>()
                                .filter {it.isShown && it.url?.contains("one.xhtml")==true}
                                .joinToString {"${System.identityHashCode(it)}:${it.scrollX},${it.scrollY},${it.translationX},${it.isPressed}"}
                            val location=navigator.currentLocator.value
                            signature=listOf(location.href.toString(),location.locations.progression,
                                pub.scrollX,pub.scrollY,pub.translationX,pub.isPressed,pagerSignature,webViews).joinToString("|")
                        }
                        if(pagerIdle && signature==previous) stable++ else stable=0
                        previous=signature
                        delay(100)
                    }
                }
            } catch(error:TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for Readium pager/content to settle after $stage: "+
                    bookReaderDiagnosticWithDom(requireNotNull(reader),navigator)+"; timeline=${inputTimelineFile.absolutePath}",error)
            }
        }
        suspend fun domReadiness(navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment):JSONObject {
            val raw=withContext(Dispatchers.Main) { navigator.evaluateJavascript("""
                (()=>{
                  const prose=document.querySelector('p');
                  const root=document.documentElement;
                  return JSON.stringify({href:location.href,readyState:document.readyState,
                    fontsStatus:document.fonts?document.fonts.status:'unsupported',
                    computedProse:prose?getComputedStyle(prose).fontSize:null,
                    viewportWidth:innerWidth,viewportHeight:innerHeight,
                    scrollWidth:root?.scrollWidth??null,scrollHeight:root?.scrollHeight??null,
                    windowScrollX:window.scrollX,documentScrollX:root?.scrollLeft??null,
                    visibleParagraph:(()=>{const p=Array.from(document.querySelectorAll('p')).find(e=>{const r=e.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth});return p?.innerText?.slice(0,100)||null})()});
                })()
            """.trimIndent()) }
            val decoded=runCatching { org.json.JSONTokener(raw?:"null").nextValue() }.getOrNull()
            return when(decoded) {
                is JSONObject->decoded
                is String->runCatching { JSONObject(decoded) }.getOrElse { JSONObject().put("decodeError",it.toString()).put("raw",raw) }
                else->JSONObject().put("decodeError","No DOM readiness sample").put("raw",raw)
            }
        }
        suspend fun captureInputSnapshot(stage:String,navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment):JSONObject {
            val locator=navigator.currentLocator.value
            val ui=JSONObject()
            reader!!.onActivity {activity->
                val pub=navigator.publicationView
                fun privateInt(name:String)=runCatching {
                    activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.getInt(activity)
                }.getOrNull()
                val pagerArray=JSONArray()
                val scrollStateField=runCatching {
                    androidx.viewpager.widget.ViewPager::class.java.getDeclaredField("mScrollState").apply {isAccessible=true}
                }.getOrNull()
                val pagers=pub.descendants().filterIsInstance<androidx.viewpager.widget.ViewPager>().toList()
                pagers.forEach {pager->pagerArray.put(JSONObject().put("class",pager.javaClass.name)
                    .put("scrollState",scrollStateField?.let {field->runCatching {field.getInt(pager)}.getOrNull()})
                    .put("currentItem",pager.currentItem).put("scrollX",pager.scrollX)
                    .put("translationX",pager.translationX).put("width",pager.width))}
                val webArray=JSONArray()
                pub.descendants().filterIsInstance<android.webkit.WebView>().forEach {web->
                    val bounds=android.graphics.Rect();val visible=web.getGlobalVisibleRect(bounds)
                    webArray.put(JSONObject().put("url",web.url).put("shown",web.isShown).put("selected",web.isSelected)
                        .put("visible",visible).put("bounds","${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                        .put("scrollX",web.scrollX).put("scrollY",web.scrollY).put("translationX",web.translationX)
                        .put("width",web.width).put("height",web.height).put("contentHeight",web.contentHeight))
                }
                ui.put("pageInResource",privateInt("pageInResource"))
                    .put("pagesInResource",privateInt("pagesInResource"))
                    .put("publicationView",JSONObject().put("class",pub.javaClass.name).put("width",pub.width)
                        .put("height",pub.height).put("scrollX",pub.scrollX).put("scrollY",pub.scrollY)
                        .put("translationX",pub.translationX).put("windowFocus",activity.hasWindowFocus()))
                    .put("pagers",pagerArray).put("webViews",webArray)
            }
            val entry=JSONObject().put("kind","snapshot").put("stage",stage)
                .put("elapsedMs",android.os.SystemClock.uptimeMillis()-inputTimelineStarted)
                .put("href",locator.href.toString()).put("progression",locator.locations.progression)
                .put("totalProgression",locator.locations.totalProgression).put("locator",locator.toString())
                .put("ui",ui).put("dom",domReadiness(navigator))
            inputTimeline.put(entry)
            persistInputTimeline()
            return entry
        }
        suspend fun recordUiInputSample(stage:String,navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment) {
            val locator=navigator.currentLocator.value
            val ui=JSONObject()
            reader!!.onActivity {activity->
                val pub=navigator.publicationView
                val page=runCatching {activity.javaClass.getDeclaredField("pageInResource").apply {isAccessible=true}.getInt(activity)}.getOrNull()
                val count=runCatching {activity.javaClass.getDeclaredField("pagesInResource").apply {isAccessible=true}.getInt(activity)}.getOrNull()
                val pagers=pub.descendants().filterIsInstance<androidx.viewpager.widget.ViewPager>().map {pager->
                    val state=runCatching {androidx.viewpager.widget.ViewPager::class.java
                        .getDeclaredField("mScrollState").apply {isAccessible=true}.getInt(pager)}.getOrNull()
                    "${pager.javaClass.simpleName}:$state:${pager.currentItem}:${pager.scrollX}:${pager.translationX}"
                }.joinToString("|")
                val active=pub.descendants().filterIsInstance<android.webkit.WebView>().filter {it.isShown && it.url?.contains("one.xhtml")==true}
                    .joinToString("|") {"${System.identityHashCode(it)}:${it.url}:${it.scrollX}:${it.translationX}:${it.width}x${it.height}"}
                ui.put("pageInResource",page).put("pagesInResource",count).put("pagers",pagers).put("activeWebViews",active)
            }
            inputTimeline.put(JSONObject().put("kind","transition-sample").put("stage",stage)
                .put("elapsedMs",android.os.SystemClock.uptimeMillis()-inputTimelineStarted)
                .put("href",locator.href.toString()).put("progression",locator.locations.progression)
                .put("totalProgression",locator.locations.totalProgression).put("ui",ui))
            persistInputTimeline()
        }
        suspend fun waitForPage(stage:String,navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment,
            href:String,progress:Double,forward:Boolean):JSONObject {
            var previousProgress=Double.NaN
            var latest:JSONObject?=null
            val crossing=withTimeoutOrNull(8_000) {
                while(true) {
                    val location=navigator.currentLocator.value
                    val current=location.locations.progression?:0.0
                    if(previousProgress.isNaN() || kotlin.math.abs(current-previousProgress)>.0001) {
                        recordUiInputSample("$stage.progress=${"%.6f".format(java.util.Locale.US,current)}",navigator)
                        previousProgress=current
                    }
                    latest=JSONObject().put("href",location.href.toString()).put("progression",current)
                    if(location.href.toString().substringBefore('#')==href &&
                        if(forward) current>progress+.002 else current<progress-.002) {
                        return@withTimeoutOrNull captureInputSnapshot("$stage.locator-crossing",navigator)
                    }
                    delay(50)
                }
                null
            }
            if(crossing==null) {
                latest=captureInputSnapshot("$stage.timeout",navigator)
                throw AssertionError("Timed out waiting for $stage to cross ${if(forward) "forward" else "back"} from $href@$progress; latest=$latest; timeline=${inputTimelineFile.absolutePath}")
            }
            return crossing
        }
        suspend fun awaitRenderedDomSettled(stage:String,navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment):JSONObject {
            var previous:String?=null
            var stable=0
            var latest=JSONObject()
            try {
                withTimeout(10_000) {
                    while(stable<3) {
                        latest=domReadiness(navigator)
                        val href=latest.optString("href")
                        val prose=latest.optString("computedProse")
                        val width=latest.optInt("scrollWidth",0);val height=latest.optInt("scrollHeight",0)
                        val viewWidth=latest.optInt("viewportWidth",0);val viewHeight=latest.optInt("viewportHeight",0)
                        val ready=href.contains("one.xhtml") && latest.optString("readyState")=="complete" &&
                            latest.optString("fontsStatus")=="loaded" && prose.endsWith("px") &&
                            width>0 && height>0 && viewWidth>0 && viewHeight>0
                        val signature=listOf(href,prose,width,height,viewWidth,viewHeight,
                            latest.optDouble("windowScrollX"),latest.optDouble("documentScrollX")).joinToString("|")
                        if(ready && signature==previous) stable++ else stable=0
                        previous=signature
                        if(stable<3) delay(100)
                    }
                }
            } catch(error:TimeoutCancellationException) {
                inputTimeline.put(JSONObject().put("kind","readiness-timeout").put("stage",stage)
                    .put("elapsedMs",android.os.SystemClock.uptimeMillis()-inputTimelineStarted).put("dom",latest))
                persistInputTimeline()
                throw AssertionError("DOM/font/layout did not reach a stable ready state after $stage: $latest; timeline=${inputTimelineFile.absolutePath}",error)
            }
            return latest
        }
        fun domReadySignature(dom:JSONObject):String = listOf(
            dom.optString("href"),dom.optString("readyState"),dom.optString("fontsStatus"),
            dom.optString("computedProse"),dom.optInt("scrollWidth",0),dom.optInt("scrollHeight",0),
            dom.optInt("viewportWidth",0),dom.optInt("viewportHeight",0),
            dom.optDouble("windowScrollX"),dom.optDouble("documentScrollX"),
        ).joinToString("|")
        fun assertSnapshotMatchesRenderedDom(stage:String,stable:JSONObject,snapshot:JSONObject) {
            val sampled=snapshot.optJSONObject("dom")?:JSONObject()
            assertEquals("$stage snapshot must still match the ready document/font/geometry sample; snapshot=$sampled timeline=${inputTimelineFile.absolutePath}",
                domReadySignature(stable),domReadySignature(sampled))
        }
        suspend fun settlePageMove(stage:String,navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment,
            baseline:Double,forward:Boolean):JSONObject {
            awaitReadiumPagerSettled(stage,navigator)
            val dom=awaitRenderedDomSettled(stage,navigator)
            val settled=captureInputSnapshot("$stage.settled",navigator)
            assertSnapshotMatchesRenderedDom(stage,dom,settled)
            val finalProgress=settled.optDouble("progression",Double.NaN)
            assertTrue("$stage must remain on the committed page after pager, DOM, and font readiness settle; baseline=$baseline final=$finalProgress dom=$dom timeline=${inputTimelineFile.absolutePath}",
                if(forward) finalProgress>baseline+.002 else finalProgress<baseline-.002)
            return settled
        }
        try {
            val imported=graph.localBookImporter.import(uri(file)).getOrThrow().book;id=imported.id
            profileKey="book:${imported.id}"
            graph.libraryToolsStore.state.value.profiles[profileKey!!]?.let {hadPriorProfile=true;priorProfile=it}
            val intent=android.content.Intent(context,BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID,imported.id)

            for(layout in listOf("pages","scroll")) {
                graph.libraryToolsStore.setProfile(profileKey!!,priorProfile.copy(layout=layout))
                reader=ActivityScenario.launch(intent)
                awaitRenderedText(requireNotNull(reader),"Original one chapter")
                val navigator=fragment(requireNotNull(reader))
                val firstHref=navigator.currentLocator.value.href.toString().substringBefore('#')
                assertTrue("Fixture opens the first resource",firstHref.contains("one.xhtml"))

                suspend fun awaitStage(stage:String,timeoutMs:Long=10_000,condition:suspend ()->Boolean) {
                    try {
                        withTimeout(timeoutMs) { while(!condition()) delay(50) }
                    } catch(error:TimeoutCancellationException) {
                        val diagnostic=bookReaderDiagnosticWithDom(requireNotNull(reader),navigator)
                        throw AssertionError("Timed out at EPUB stage '$stage': $diagnostic",error)
                    }
                }

                if(layout=="pages") {
                    // Start at a stable middle page, then exercise the actual Readium view tree
                    // with a held pointer sequence before releasing or cancelling it.
                    awaitStage("initial EPUB Pages navigator ready",20_000) {
                        val ui=reader!!.let {scenario->var ready=false;scenario.onActivity {ready=bookUiState(it)?.ready==true};ready}
                        var pageCount=0
                        reader!!.onActivity {activity->pageCount=activity.javaClass.getDeclaredField("pagesInResource").apply {isAccessible=true}.getInt(activity)}
                        ui && pageCount>1 && navigator.currentLocator.value.href.toString().substringBefore('#')==firstHref
                    }
                    var setupAdvanceAccepted=false
                    reader!!.onActivity { setupAdvanceAccepted=navigator.goForward(false) }
                    if(!setupAdvanceAccepted) throw AssertionError(
                        "Readium rejected the direct Pages setup advance after its ready/page-count gate: "+
                            bookReaderDiagnosticWithDom(requireNotNull(reader),navigator),
                    )
                    awaitStage("initial Pages setup advance produces a locator",8_000) {
                        (navigator.currentLocator.value.locations.progression?:0.0)>.002
                    }
                    awaitReadiumPagerSettled("initial Pages setup advance",navigator)
                    val setupDom=awaitRenderedDomSettled("initial Pages setup advance",navigator)
                    assertSnapshotMatchesRenderedDom("initial Pages setup advance",setupDom,
                        captureInputSnapshot("initial-pages.after-setup",navigator))
                    tapRatio(requireNotNull(reader),.50f)
                    assertTrue("The EPUB pager probe runs with fullscreen chrome hidden",device.wait(Until.gone(By.text("Aa")),3_000))
                    // Hiding chrome changes the publication viewport. Do not take swipe origins
                    // until the native pager, active document, fonts, and rendered geometry agree.
                    awaitReadiumPagerSettled("initial Pages viewport after hiding chrome",navigator)
                    val viewportDom=awaitRenderedDomSettled("initial Pages viewport after hiding chrome",navigator)
                    assertSnapshotMatchesRenderedDom("initial Pages viewport after hiding chrome",viewportDom,
                        captureInputSnapshot("initial-pages.hidden-chrome-ready",navigator))
                    // Exercise a real input-device swipe on a fresh, ready pager before the
                    // synthetic held/cancel stream below. This separates Readium's native
                    // gesture routing from any state left by test-generated MotionEvents.
                    var webBounds=android.graphics.Rect()
                    reader!!.onActivity {activity->requireNotNull(bookAutoReadTarget(activity)).getGlobalVisibleRect(webBounds)}
                    val freshForwardBaseline=captureInputSnapshot("fresh-forward.before",navigator)
                    val freshForwardBefore=freshForwardBaseline.optDouble("progression",Double.NaN)
                    recordInputEvent("fresh-forward",JSONObject().put("action","UiDevice.swipe")
                        .put("fromX",webBounds.right-webBounds.width()/10).put("toX",webBounds.left+webBounds.width()/10)
                        .put("y",webBounds.centerY()).put("steps",16).put("source",InputDevice.SOURCE_TOUCHSCREEN)
                        .put("toolType",MotionEvent.TOOL_TYPE_FINGER))
                    device.swipe(webBounds.right-webBounds.width()/10,webBounds.centerY(),webBounds.left+webBounds.width()/10,webBounds.centerY(),16)
                    captureInputSnapshot("fresh-forward.immediate",navigator)
                    waitForPage("fresh-forward",navigator,firstHref,freshForwardBefore,true)
                    settlePageMove("fresh native Pages forward swipe",navigator,freshForwardBefore,true)
                    reader!!.onActivity {activity->requireNotNull(bookAutoReadTarget(activity)).getGlobalVisibleRect(webBounds)}
                    val freshBackBaseline=captureInputSnapshot("fresh-back.before",navigator)
                    val freshBackBefore=freshBackBaseline.optDouble("progression",Double.NaN)
                    recordInputEvent("fresh-back",JSONObject().put("action","UiDevice.swipe")
                        .put("fromX",webBounds.left+webBounds.width()/10).put("toX",webBounds.right-webBounds.width()/10)
                        .put("y",webBounds.centerY()).put("steps",16).put("source",InputDevice.SOURCE_TOUCHSCREEN)
                        .put("toolType",MotionEvent.TOOL_TYPE_FINGER))
                    device.swipe(webBounds.left+webBounds.width()/10,webBounds.centerY(),webBounds.right-webBounds.width()/10,webBounds.centerY(),16)
                    captureInputSnapshot("fresh-back.immediate",navigator)
                    waitForPage("fresh-back",navigator,firstHref,freshBackBefore,false)
                    settlePageMove("fresh native Pages backward swipe",navigator,freshBackBefore,false)
                    val start=navigator.currentLocator.value
                    var startTotalProgress=0.0
                    reader!!.onActivity {activity->startTotalProgress=bookProgress(activity)}
                    awaitStage("persisted EPUB Pages position before held drag",8_000) {
                        kotlin.math.abs((graph.localBookRepository.get(imported.id)?.progression ?: -1.0)-startTotalProgress)<=.03
                    }
                    val savedBeforeDrag=graph.localBookRepository.get(imported.id)?.progression ?: error("EPUB position was not saved before drag")
                    suspend fun currentResourceColumnOffset():JSONObject {
                        val raw=withContext(Dispatchers.Main) {navigator.evaluateJavascript("""
                            (()=>{
                              const paragraphs=Array.from(document.querySelectorAll('p'));
                              const index=paragraphs.findIndex(p=>{const r=p.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth});
                              const paragraph=paragraphs[index];
                              return JSON.stringify({href:location.href,windowScrollX:window.scrollX,
                                documentScrollX:document.documentElement.scrollLeft,paragraphIndex:index,
                                paragraphText:paragraph?.textContent?.slice(0,100)||'',
                                paragraphRects:paragraph?Array.from(paragraph.getClientRects()).map(r=>({left:r.left,right:r.right,top:r.top,bottom:r.bottom})):[]});
                            })()
                        """.trimIndent())}
                        return when(val decoded=org.json.JSONTokener(raw?:"null").nextValue()) {
                            is JSONObject->decoded
                            is String->JSONObject(decoded)
                            else->error("Readium did not return its current-resource column offset: $raw")
                        }
                    }
                    val beforeDomOffset=currentResourceColumnOffset()
                    captureInputSnapshot("held-drag.before",navigator)
                    var beforeFrames=emptyMap<Int,Pair<android.graphics.Rect,Pair<Int,Float>>>()
                    var heldGestureDownTime=0L
                    var downX=0f;var moveX=0f;var screenY=0f
                    reader!!.onActivity {activity->
                        val pub=navigator.publicationView
                        val origin=IntArray(2)
                        pub.getLocationOnScreen(origin)
                        downX=origin[0]+pub.width*.85f
                        moveX=origin[0]+pub.width*.20f
                        screenY=origin[1]+pub.height*.5f
                        beforeFrames=pub.descendants().filterIsInstance<android.webkit.WebView>()
                            .filter {it.isShown && it.url?.contains("one.xhtml")==true}.associate {
                            val bounds=android.graphics.Rect();it.getGlobalVisibleRect(bounds)
                            System.identityHashCode(it) to (bounds to (it.scrollX to it.translationX))
                        }
                    }
                    heldGestureDownTime=android.os.SystemClock.uptimeMillis()
                    injectScreenTouch(MotionEvent.ACTION_DOWN,heldGestureDownTime,heldGestureDownTime,downX,screenY)
                    delay(90)
                    injectScreenTouch(MotionEvent.ACTION_MOVE,heldGestureDownTime,android.os.SystemClock.uptimeMillis(),moveX,screenY)
                    delay(120)
                    var afterFrames=emptyMap<Int,Pair<android.graphics.Rect,Pair<Int,Float>>>()
                    reader!!.onActivity {activity->afterFrames=navigator.publicationView.descendants()
                        .filterIsInstance<android.webkit.WebView>().filter {it.isShown && it.url?.contains("one.xhtml")==true}.associate {
                            val bounds=android.graphics.Rect();it.getGlobalVisibleRect(bounds)
                            System.identityHashCode(it) to (bounds to (it.scrollX to it.translationX))
                        }}
                    val afterDomOffset=currentResourceColumnOffset()
                    captureInputSnapshot("held-drag.held",navigator)
                    assertEquals("Held EPUB motion is measured only on the original spine-resource WebView",1,beforeFrames.size)
                    assertEquals("Held EPUB motion remains on the same spine-resource WebView",beforeFrames.keys,afterFrames.keys)
                    assertEquals("The EPUB held-drag locator remains in the original spine resource",
                        firstHref,navigator.currentLocator.value.href.toString().substringBefore('#'))
                    assertTrue("The held-drag DOM offset probe starts in one.xhtml: $beforeDomOffset",
                        beforeDomOffset.optString("href").substringBefore('#').endsWith("one.xhtml"))
                    assertEquals("The held-drag DOM offset probe remains in one.xhtml",
                        beforeDomOffset.optString("href").substringBefore('#'),afterDomOffset.optString("href").substringBefore('#'))
                    val beforeFrame=beforeFrames.values.single()
                    val afterFrame=afterFrames.values.single()
                    val webContentOffsetMoved=kotlin.math.abs(afterFrame.second.first-beforeFrame.second.first)>12
                    val domWindowOffsetMoved=kotlin.math.abs(afterDomOffset.optDouble("windowScrollX")-beforeDomOffset.optDouble("windowScrollX"))>12 ||
                        kotlin.math.abs(afterDomOffset.optDouble("documentScrollX")-beforeDomOffset.optDouble("documentScrollX"))>12
                    val beforeRects=beforeDomOffset.optJSONArray("paragraphRects")?:org.json.JSONArray()
                    val afterRects=afterDomOffset.optJSONArray("paragraphRects")?:org.json.JSONArray()
                    val sameParagraph=beforeDomOffset.optInt("paragraphIndex",-1)>=0 &&
                        beforeDomOffset.optInt("paragraphIndex",-1)==afterDomOffset.optInt("paragraphIndex",-2)
                    val paragraphFragmentMoved=sameParagraph && beforeRects.length()>0 && afterRects.length()>0 &&
                        kotlin.math.abs((afterRects.optJSONObject(0)?.optDouble("left")?:0.0)-
                            (beforeRects.optJSONObject(0)?.optDouble("left")?:0.0))>12
                    val contentColumnMoved=webContentOffsetMoved||domWindowOffsetMoved||paragraphFragmentMoved
                    assertTrue("Readium Pages finger-follow must move content columns inside the original one.xhtml resource while held; outer frame before=$beforeFrame after=$afterFrame, internal WebView offset=$webContentOffsetMoved, DOM before=$beforeDomOffset after=$afterDomOffset",
                        contentColumnMoved)
                    assertTrue("Capture EPUB neighbor-page hold sent through the Android input dispatcher",
                        device.takeScreenshot(File(context.cacheDir,"qa-0.4.2-epub-held-partial-page-window-input-14.png")))
                    injectScreenTouch(MotionEvent.ACTION_CANCEL,heldGestureDownTime,android.os.SystemClock.uptimeMillis(),moveX,screenY)
                    awaitReadiumPagerSettled("window-dispatched ACTION_CANCEL",navigator)
                    awaitRenderedDomSettled("window-dispatched ACTION_CANCEL",navigator)
                    val afterCancel=navigator.currentLocator.value
                    captureInputSnapshot("held-drag.after-cancel-settled",navigator)
                    assertEquals("Readium cancel keeps the same resource",start.href.toString().substringBefore('#'),afterCancel.href.toString().substringBefore('#'))
                    assertEquals("Readium cancel restores the logical page position",start.locations.progression?:0.0,
                        afterCancel.locations.progression?:0.0,.03)
                    assertEquals("A held/cancelled EPUB drag does not persist transient reading progress",savedBeforeDrag,
                        graph.localBookRepository.get(imported.id)?.progression ?: -1.0,.01)

                    // The gesture stream must leave the native pager responsive after cancel too.
                    reader!!.onActivity {activity->requireNotNull(bookAutoReadTarget(activity)).getGlobalVisibleRect(webBounds)}
                    val postCancelForwardBaseline=captureInputSnapshot("post-cancel-forward.before",navigator)
                    val postCancelForwardBefore=postCancelForwardBaseline.optDouble("progression",Double.NaN)
                    recordInputEvent("post-cancel-forward",JSONObject().put("action","UiDevice.swipe")
                        .put("fromX",webBounds.right-webBounds.width()/10).put("toX",webBounds.left+webBounds.width()/10)
                        .put("y",webBounds.centerY()).put("steps",16).put("source",InputDevice.SOURCE_TOUCHSCREEN)
                        .put("toolType",MotionEvent.TOOL_TYPE_FINGER))
                    device.swipe(webBounds.right-webBounds.width()/10,webBounds.centerY(),webBounds.left+webBounds.width()/10,webBounds.centerY(),16)
                    captureInputSnapshot("post-cancel-forward.immediate",navigator)
                    waitForPage("post-cancel-forward",navigator,firstHref,postCancelForwardBefore,true)
                    settlePageMove("native Pages forward swipe after cancel",navigator,postCancelForwardBefore,true)
                    reader!!.onActivity {activity->requireNotNull(bookAutoReadTarget(activity)).getGlobalVisibleRect(webBounds)}
                    val postCancelBackBaseline=captureInputSnapshot("post-cancel-back.before",navigator)
                    val postCancelBackBefore=postCancelBackBaseline.optDouble("progression",Double.NaN)
                    recordInputEvent("post-cancel-back",JSONObject().put("action","UiDevice.swipe")
                        .put("fromX",webBounds.left+webBounds.width()/10).put("toX",webBounds.right-webBounds.width()/10)
                        .put("y",webBounds.centerY()).put("steps",16).put("source",InputDevice.SOURCE_TOUCHSCREEN)
                        .put("toolType",MotionEvent.TOOL_TYPE_FINGER))
                    device.swipe(webBounds.left+webBounds.width()/10,webBounds.centerY(),webBounds.right-webBounds.width()/10,webBounds.centerY(),16)
                    captureInputSnapshot("post-cancel-back.immediate",navigator)
                    waitForPage("post-cancel-back",navigator,firstHref,postCancelBackBefore,false)
                    settlePageMove("native Pages backward swipe after cancel",navigator,postCancelBackBefore,false)

                }

                // Drive to the final location inside the first spine item. Taps at the center,
                // near-center-right, and narrow page edge must never open the second item.
                if(layout=="scroll") {
                    reader!!.onActivity {activity->requireNotNull(bookAutoReadTarget(activity)).scrollTo(0,Int.MAX_VALUE)}
                    awaitStage("Continuous EPUB reaches first spine-resource end",8_000) {
                        val href=navigator.currentLocator.value.href.toString().substringBefore('#')
                        var physicallyAtEnd=false
                        reader!!.onActivity {activity->
                            val web=bookAutoReadTarget(activity)
                            physicallyAtEnd=web!=null && web.contentHeight>web.height && !web.canScrollVertically(1)
                        }
                        href==firstHref && physicallyAtEnd
                    }
                } else {
                    awaitStage("Pages reaches first spine-resource end without crossing it",20_000) {
                        var page=0;var count=0
                        reader!!.onActivity {activity->
                            page=activity.javaClass.getDeclaredField("pageInResource").apply {isAccessible=true}.getInt(activity)
                            count=activity.javaClass.getDeclaredField("pagesInResource").apply {isAccessible=true}.getInt(activity)
                        }
                        if(count>0 && page>=count-1) true else {
                            tapRatio(requireNotNull(reader),.95f);delay(90);false
                        }
                    }
                }
                val boundaryHref=navigator.currentLocator.value.href.toString().substringBefore('#')
                assertTrue("Reader stays in the first spine resource before boundary taps",boundaryHref.contains("one.xhtml"))
                tapRatio(requireNotNull(reader),.50f)
                tapRatio(requireNotNull(reader),.75f)
                tapRatio(requireNotNull(reader),.95f)
                delay(250)
                assertEquals("Center/near-center-right/edge content taps cannot change spine resources in $layout mode",
                    boundaryHref,navigator.currentLocator.value.href.toString().substringBefore('#'))
                reader!!.close();reader=null
            }
        } finally {
            reader?.close();id?.let {cleanup(it)}
            profileKey?.let {key->graph.libraryToolsStore.update {state->state.copy(profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key)}}
            prefs.edit().apply {if(hadScroll) putBoolean("scroll",oldScroll) else remove("scroll")}.commit()
            file.delete()
        }
    }

    @Test fun epubFixedPublisherFontSizeReflowsInPagesAndScrollAndRestoresPosition() = runBlocking {
        val file=fixture("epub");writeOriginalEpub(file,fixedPublisherSizes=true)
        val sourceXhtml=ZipFile(file).use { archive ->
            archive.getInputStream(requireNotNull(archive.getEntry("one.xhtml"))).bufferedReader().use { it.readText() }
        }
        assertTrue("The source archive contains the fixed-size publisher stylesheet",
            sourceXhtml.contains("body{font-size:14px}p{font-size:14px}.fixed-size{font-size:14px}"))
        assertTrue("The source archive contains fixed-size inline prose and a fixture marker",
            sourceXhtml.contains("class=\"fixed-size\" style=\"font-size:14px\""))
        val readerPrefs=context.getSharedPreferences("arkhive_book_reader",0)
        val hadFont=readerPrefs.contains("font");val oldFont=readerPrefs.getFloat("font",100f)
        val hadLine=readerPrefs.contains("line");val oldLine=readerPrefs.getFloat("line",33f/21f)
        val hadMargin=readerPrefs.contains("margin");val oldMargin=readerPrefs.getFloat("margin",1f)
        val hadDark=readerPrefs.contains("dark");val oldDark=readerPrefs.getBoolean("dark",true)
        val hadScroll=readerPrefs.contains("scroll");val oldScroll=readerPrefs.getBoolean("scroll",true)
        val graph=context.appGraph
        var id:String?=null;var reader:ActivityScenario<BookReaderActivity>?=null
        var profileKey:String?=null;var hadPriorProfile=false;var priorProfile=mihon.readingapp.library.ReadingProfile()
        val device=UiDevice.getInstance(instrumentation)
        suspend fun openNavigator(scenario:ActivityScenario<BookReaderActivity>):org.readium.r2.navigator.epub.EpubNavigatorFragment {
            var value:org.readium.r2.navigator.epub.EpubNavigatorFragment?=null
            withTimeout(20_000) {
                while(value==null) {
                    scenario.onActivity {activity->value=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                        as? org.readium.r2.navigator.epub.EpubNavigatorFragment}
                    if(value==null) delay(100)
                }
            }
            return requireNotNull(value)
        }
        suspend fun metrics(navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment):JSONObject {
            val raw=withContext(Dispatchers.Main) { navigator.evaluateJavascript("""
                JSON.stringify({
                  href: location.href,
                  paper: getComputedStyle(document.body).backgroundColor,
                  htmlPaper: getComputedStyle(document.documentElement).backgroundColor,
                  readyState: document.readyState,
                  title: document.title,
                  bodyMarker: (document.body?.innerText || '').slice(0,220),
                  body: getComputedStyle(document.body).fontSize,
                  prose: getComputedStyle(document.querySelector('p')).fontSize,
                  proseLineHeight: getComputedStyle(document.querySelector('p')).lineHeight,
                  bodyPaddingLeft: getComputedStyle(document.body).paddingLeft,
                  heading: getComputedStyle(document.querySelector('h1')).fontSize,
                  visibleParagraph: Array.from(document.querySelectorAll('p')).findIndex(p => {
                    const r = p.getBoundingClientRect();
                    return r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
                  }),
                  visibleParagraphText: (()=>{const p=Array.from(document.querySelectorAll('p')).find(p=>{const r=p.getBoundingClientRect();return r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth});return p?.innerText||null})(),
                  visibleParagraphTop: (()=>{const p=Array.from(document.querySelectorAll('p')).find(p=>{const r=p.getBoundingClientRect();return r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth});return p?p.getBoundingClientRect().top:null})(),
                  paragraphCount: document.querySelectorAll('p').length,
                  viewportHeight: window.innerHeight,
                  documentScrollY: window.scrollY,
                  documentScrollX: window.scrollX,
                  documentScrollWidth: document.documentElement.scrollWidth,
                  documentScrollHeight: document.documentElement.scrollHeight,
                  firstClass: document.querySelector('p').className,
                  firstParagraph: document.querySelector('p').innerText.slice(0,180),
                  sourceInline: document.querySelector('p').getAttribute('style'),
                  styleTags: Array.from(document.querySelectorAll('style')).map(s=>s.textContent),
                  styleSheetRules: Array.from(document.styleSheets).map(s=>{
                    try { return Array.from(s.cssRules).map(r=>r.cssText); }
                    catch(e) { return ['CSSOM_ERROR:' + String(e)]; }
                  })
                })
            """.trimIndent()) }
            val decoded=org.json.JSONTokener(raw?:"null").nextValue()
            val rendered=when(decoded) {is JSONObject->decoded;is String->JSONObject(decoded);else->error("No computed EPUB typography: $raw")}
            val runtime=withContext(Dispatchers.Main) {
                val locator=navigator.currentLocator.value
                val webViews=org.json.JSONArray()
                val activity= navigator.requireActivity()
                val rootField=activity.javaClass.getDeclaredField("readerRoot").apply {isAccessible=true}
                val readerRoot=rootField.get(activity) as View
                fun colorValue(view:View):String?=(view.background as? ColorDrawable)?.color?.let {String.format("#%08X",it)}
                navigator.publicationView.descendants().filterIsInstance<android.webkit.WebView>().forEach {web->
                    val bounds=android.graphics.Rect();web.getGlobalVisibleRect(bounds)
                    webViews.put(JSONObject().put("url",web.url).put("selected",web.isSelected).put("shown",web.isShown)
                        .put("scrollX",web.scrollX).put("scrollY",web.scrollY).put("width",web.width).put("height",web.height)
                        .put("visibleBounds","${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                        .put("background",colorValue(web)?:JSONObject.NULL))
                }
                JSONObject().put("locator",locator.toString()).put("href",locator.href.toString())
                    .put("progression",locator.locations.progression).put("totalProgression",locator.locations.totalProgression)
                    .put("readerRootBackground",colorValue(readerRoot)?:JSONObject.NULL)
                    .put("readerRootSize","${readerRoot.width}x${readerRoot.height}")
                    .put("webViews",webViews)
            }
            return rendered.put("runtime",runtime)
        }
        suspend fun awaitMetrics(navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment,
            previous:Double?=null):JSONObject {
            var latest=JSONObject()
            val result=withTimeoutOrNull(10_000) {
                latest=metrics(navigator)
                while(true) {
                    val size=latest.optString("prose").removeSuffix("px").toDoubleOrNull()?:0.0
                    if(size>0 && (previous==null || size>previous*1.5)) return@withTimeoutOrNull latest
                    delay(100);latest=metrics(navigator)
                }
                null
            }
            return result ?: throw AssertionError("Timed out waiting for rendered EPUB font metrics (previous=$previous): $latest")
        }
        suspend fun awaitSettledMetrics(navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment,
            stage:String):JSONObject {
            var previousSignature:String?=null
            var stableObservations=0
            var latest=JSONObject()
            val result=withTimeoutOrNull(20_000) {
                // Let Readium's preference collector and reflow lifecycle finish first.
                delay(1_200)
                while(true) {
                    latest=metrics(navigator)
                    val runtime=latest.optJSONObject("runtime")?:JSONObject()
                    val views=runtime.optJSONArray("webViews")?:org.json.JSONArray()
                    val viewSignature=(0 until views.length()).joinToString("|") {index->
                        val view=views.optJSONObject(index)?:JSONObject()
                        listOf(view.optString("url"),view.optInt("scrollX"),view.optInt("scrollY"),
                            view.optInt("width"),view.optInt("height")).joinToString(":")
                    }
                    val signature=listOf(latest.optString("href"),latest.optString("prose"),latest.optString("heading"),
                        latest.optInt("visibleParagraph"),latest.optString("visibleParagraphTop"),
                        latest.optString("documentScrollX"),latest.optString("documentScrollY"),
                        runtime.optString("progression"),viewSignature).joinToString(";")
                    if(signature==previousSignature) stableObservations++ else stableObservations=0
                    if(stableObservations>=3) return@withTimeoutOrNull latest
                    previousSignature=signature
                    delay(180)
                }
                null
            }
            return result ?: throw AssertionError("Timed out waiting for EPUB $stage layout/locator to settle: $latest")
        }
        fun saveMetrics(layout:String,phase:String,metrics:JSONObject) {
            File(context.cacheDir,"qa-0.4.2-epub-font-$layout-$phase-continuous-anchor-09.json").writeText(
                JSONObject().put("layout",layout).put("phase",phase)
                    .put("archiveResource","one.xhtml")
                    .put("archiveSha256",sha256(file))
                    .put("archiveCss","body,p,.fixed-size each specify font-size:14px")
                    .put("archiveInline","p.fixed-size style=font-size:14px")
                    .put("rendered",metrics).toString(2),
            )
        }
        fun invokeFontAction(scenario:ActivityScenario<BookReaderActivity>,name:String,percent:Float?=null) {
            scenario.onActivity {activity->
                val method=if(percent==null) activity.javaClass.getDeclaredMethod(name)
                    else activity.javaClass.getDeclaredMethod(name,Float::class.javaPrimitiveType!!)
                method.isAccessible=true
                if(percent==null) method.invoke(activity) else method.invoke(activity,percent)
            }
        }
        fun raceTypographyWithPaperAndLayout(scenario:ActivityScenario<BookReaderActivity>, originalLayout:String) {
            scenario.onActivity { activity ->
                val roomType = mihon.readingapp.library.ReadingRoom::class.java
                val selectRoom = activity.javaClass.getDeclaredMethod("selectReadingRoom", roomType).apply { isAccessible = true }
                selectRoom.invoke(activity, mihon.readingapp.library.ReadingRoom.WARM_PAPER)
                selectRoom.invoke(activity, mihon.readingapp.library.ReadingRoom.MIDNIGHT_VIOLET)
                selectRoom.invoke(activity, mihon.readingapp.library.ReadingRoom.INK_BLACK)
                val layoutMethod = activity.javaClass.getDeclaredMethod("selectReadingLayout", Boolean::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
                val originalScroll = originalLayout == "scroll"
                layoutMethod.invoke(activity, !originalScroll)
                layoutMethod.invoke(activity, originalScroll)
            }
        }
        fun readerField(scenario:ActivityScenario<BookReaderActivity>,name:String):Any? {
            var result:Any?=null
            scenario.onActivity {activity->result=activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(activity)}
            return result
        }
        suspend fun waitForFontPreviewToFinish(scenario:ActivityScenario<BookReaderActivity>,timeoutMs:Long=6_000) {
            withTimeout(timeoutMs) {while(readerField(scenario,"fontPreviewOrigin")!=null) delay(30)}
        }
        suspend fun tapReaderRatio(scenario:ActivityScenario<BookReaderActivity>,ratio:Float) {
            val target=IntArray(2)
            scenario.onActivity {activity->
                val web=requireNotNull(bookAutoReadTarget(activity))
                web.getLocationOnScreen(target)
                target[0]+=(web.width*ratio).toInt();target[1]+=web.height/2
            }
            device.click(target[0],target[1])
        }
        try {
            readerPrefs.edit().putFloat("font",100f).commit()
            val imported=graph.localBookImporter.import(uri(file)).getOrThrow().book;id=imported.id
            profileKey="book:${imported.id}"
            graph.libraryToolsStore.state.value.profiles[profileKey!!]?.let {hadPriorProfile=true;priorProfile=it}
            val intent=android.content.Intent(context,BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID,imported.id)

            for(layout in listOf("pages","scroll")) {
                graph.libraryToolsStore.setProfile(profileKey!!,priorProfile.copy(layout=layout,room=mihon.readingapp.library.ReadingRoom.INK_BLACK))
                readerPrefs.edit().putFloat("font",100f).putFloat("line",33f/21f).putFloat("margin",1f).commit()
                reader=ActivityScenario.launch(intent)
                awaitRenderedText(requireNotNull(reader),"Original one chapter")
                val navigator=openNavigator(requireNotNull(reader))
                val initialMetrics=awaitSettledMetrics(navigator,"initial $layout typography")
                saveMetrics(layout,"initial",initialMetrics)
                assertTrue("Capture the effective selected-title paper before evaluating CSS transparency",
                    device.takeScreenshot(File(context.cacheDir,"qa-0.4.3-epub-room-initial-$layout.png")))
                assertTrue("The rendered DOM is the fixed-publisher fixture's first spine resource: $initialMetrics",
                    initialMetrics.optString("href").contains("one.xhtml") &&
                        initialMetrics.optString("firstParagraph").contains("Original paragraph 1"))
                assertEquals("Ink Black is painted by the observed EPUB HTML canvas; full CSS, native view and WebView layers: $initialMetrics",
                    "rgb(0, 0, 0)",initialMetrics.optString("htmlPaper"))
                assertTrue("The EPUB body may remain transparent only over the confirmed black HTML canvas: $initialMetrics",
                    initialMetrics.optString("paper")=="rgba(0, 0, 0, 0)" || initialMetrics.optString("paper")=="rgb(0, 0, 0)")
                assertEquals("The same Ink Black selection is carried by the native reader root: $initialMetrics",
                    "#FF000000",initialMetrics.optJSONObject("runtime")?.optString("readerRootBackground"))
                assertTrue("Readium preserves the authored fixed-size fixture class among any generated classes: $initialMetrics",
                    initialMetrics.optString("firstClass").split(Regex("\\s+")).contains("fixed-size"))
                val initialProse=initialMetrics.optString("prose").removeSuffix("px").toDouble()
                val initialHeading=initialMetrics.optString("heading").removeSuffix("px").toDouble()
                assertTrue("Authored heading hierarchy remains proportional while prose is scaled: $initialMetrics",
                    initialHeading/initialProse in 1.4..1.7)
                val startHref=navigator.currentLocator.value.href.toString().substringBefore('#')
                val startProgress=navigator.currentLocator.value.locations.progression?:0.0

                requireNotNull(device.wait(Until.findObject(By.desc("Next")),10_000)).click()
                try {
                    withTimeout(8_000) {
                        while((navigator.currentLocator.value.locations.progression?:0.0)<=startProgress+.002) delay(100)
                    }
                } catch(error:TimeoutCancellationException) {
                    throw AssertionError("Timed out waiting for visible EPUB Next to advance from $startHref at $startProgress: "+
                        bookReaderDiagnosticWithDom(requireNotNull(reader),navigator),error)
                }
                val beforeMetrics=awaitSettledMetrics(navigator,"$layout pre-reflow")
                saveMetrics(layout,"before-reflow",beforeMetrics)
                val beforeReflow=navigator.currentLocator.value
                assertEquals("Manual page/screen movement stays in the resource before text reflow",startHref,
                    beforeReflow.href.toString().substringBefore('#'))
                val beforeVisibleParagraph=beforeMetrics.optInt("visibleParagraph",-1)
                assertTrue("A real authored prose paragraph is visible before font reflow: $beforeMetrics",beforeVisibleParagraph>=0)
                requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
                val slider=requireNotNull(device.wait(Until.findObject(By.desc("Text size")),10_000))
                val bounds=slider.visibleBounds
                device.swipe(bounds.left+(bounds.width()*.14f).toInt(),bounds.centerY(),
                    bounds.left+(bounds.width()*.82f).toInt(),bounds.centerY(),14)
                val preview=awaitMetrics(navigator,initialProse)
                val previewProse=preview.optString("prose").removeSuffix("px").toDouble()
                assertTrue("Slider preview changes rendered prose size before confirmation: $initialMetrics → $preview",previewProse>initialProse*1.5)
                fun finiteCssPixels(value: JSONObject, key: String, stage: String): Double {
                    val raw=value.optString(key)
                    val parsed=raw.removeSuffix("px").toDoubleOrNull()
                    assertNotNull("$stage must expose numeric CSS pixels for $key: $value",parsed)
                    val number=requireNotNull(parsed)
                    assertTrue("$stage $key must be finite and positive: $value",number.isFinite() && number>0.0)
                    return number
                }
                val beforeLineMetrics=metrics(navigator)
                val beforeLineHeight=finiteCssPixels(beforeLineMetrics,"proseLineHeight","EPUB line-height before preview")
                invokeFontAction(requireNotNull(reader), "previewLineHeight", 2.2f)
                var linePreview = beforeLineMetrics
                withTimeout(8_000) {
                    while (true) {
                        linePreview=metrics(navigator)
                        val value=linePreview.optString("proseLineHeight").removeSuffix("px").toDoubleOrNull()
                        if(value!=null && value.isFinite() && value>=beforeLineHeight*1.2) break
                        delay(100)
                    }
                }
                val afterLineHeight=finiteCssPixels(linePreview,"proseLineHeight","EPUB line-height preview")
                assertTrue("Line spacing must increase computed prose leading meaningfully: before=$beforeLineHeight after=$afterLineHeight",afterLineHeight>=beforeLineHeight*1.2)
                assertNotNull("The paper-matched live sample stays visible while lower controls are adjusted",
                    device.wait(Until.findObject(By.textContains("Quiet pages.")), 5_000))
                val beforeMargins=finiteCssPixels(linePreview,"bodyPaddingLeft","EPUB page margin before preview")
                invokeFontAction(requireNotNull(reader), "previewMargins", 2f)
                var marginPreview = linePreview
                withTimeout(8_000) {
                    while (true) {
                        marginPreview=metrics(navigator)
                        val value=marginPreview.optString("bodyPaddingLeft").removeSuffix("px").toDoubleOrNull()
                        if(value!=null && value.isFinite() && value>=beforeMargins+8.0) break
                        delay(100)
                    }
                }
                val afterMargins=finiteCssPixels(marginPreview,"bodyPaddingLeft","EPUB page margin preview")
                assertTrue("Page margins must increase the rendered inset meaningfully: before=$beforeMargins after=$afterMargins",afterMargins>=beforeMargins+8.0)
                assertTrue("Save the fixed live-preview sample with rendered typography controls",
                    device.takeScreenshot(File(context.cacheDir,"qa-0.4.3-epub-appearance-preview-$layout.png")))
                raceTypographyWithPaperAndLayout(requireNotNull(reader), layout)
                invokeFontAction(requireNotNull(reader), "finishFontSizePreview")
                waitForFontPreviewToFinish(requireNotNull(reader))
                val restoredTypography = awaitSettledMetrics(navigator, "$layout line and margin preview anchor restore")
                val restoredRawHref=restoredTypography.optString("href").substringBefore('#')
                assertEquals("Live typography controls preserve the exact EPUB resource path; raw hrefs=$startHref → $restoredRawHref",
                    epubResourcePath(startHref),epubResourcePath(restoredRawHref))
                requireNotNull(device.wait(Until.findObject(By.text("Done")),10_000)).click()
                var beforeCenterTap=awaitSettledMetrics(navigator,"$layout before centered chrome tap")
                assertTrue("Centered chrome tap begins at a nonzero reading position: $beforeCenterTap",
                    (beforeCenterTap.optJSONObject("runtime")?.optDouble("progression",0.0) ?: 0.0) > .001)
                repeat(2) { toggleIndex ->
                    var menuBefore=true
                    reader!!.onActivity { activity -> menuBefore=bookUiState(activity)?.menu ?: true }
                    val expectedMenu=!menuBefore
                    tapReaderRatio(requireNotNull(reader),.50f)
                    withTimeout(5_000) {
                        while(true) {
                            var currentMenu=menuBefore
                            reader!!.onActivity { activity -> currentMenu=bookUiState(activity)?.menu ?: menuBefore }
                            if(currentMenu==expectedMenu) break
                            delay(50)
                        }
                    }
                    val afterCenterTap=awaitSettledMetrics(navigator,"$layout centered chrome toggle $toggleIndex")
                    val beforeRuntime=beforeCenterTap.optJSONObject("runtime") ?: JSONObject()
                    val afterRuntime=afterCenterTap.optJSONObject("runtime") ?: JSONObject()
                    assertEquals("A centered chrome toggle stays in the same EPUB resource in $layout",
                        beforeCenterTap.optString("href"),afterCenterTap.optString("href"))
                    assertEquals("A centered chrome toggle preserves the exact Readium locator in $layout",
                        beforeRuntime.optString("locator"),afterRuntime.optString("locator"))
                    assertEquals("A centered chrome toggle preserves the exact visible paragraph in $layout",
                        beforeCenterTap.optString("visibleParagraphText"),afterCenterTap.optString("visibleParagraphText"))
                    assertEquals("A centered chrome toggle preserves the paragraph containing the viewport in $layout",
                        beforeCenterTap.optInt("visibleParagraph",-1),afterCenterTap.optInt("visibleParagraph",-1))
                    beforeCenterTap=afterCenterTap
                }
                val larger=awaitSettledMetrics(navigator,"$layout post-confirm reflow")
                saveMetrics(layout,"larger-settled",larger)
                val largerProse=larger.optString("prose").removeSuffix("px").toDouble()
                val largerHeading=larger.optString("heading").removeSuffix("px").toDouble()
                assertTrue("Readium font preference must change computed prose size for fixed-size source CSS: $initialMetrics → $larger",
                    largerProse>initialProse*1.5)
                assertTrue("Heading sizes follow the same base scale while preserving relative hierarchy: $larger",
                    largerHeading/largerProse in 1.4..1.7)
                val afterVisibleParagraph=larger.optInt("visibleParagraph",-1)
                assertTrue("Font reflow keeps the visible text anchor within one authored paragraph in $layout mode: before=$beforeVisibleParagraph after=$afterVisibleParagraph, beforeMetrics=$beforeMetrics, afterMetrics=$larger",
                    afterVisibleParagraph>=0 && kotlin.math.abs(beforeVisibleParagraph-afterVisibleParagraph)<=1)
                assertTrue("The user-selected EPUB font preference is saved after a live slider change",
                    readerPrefs.getFloat("font",100f)>170f)
                val afterReflow=navigator.currentLocator.value
                assertEquals("Font reflow preserves the logical chapter",beforeReflow.href.toString().substringBefore('#'),
                    afterReflow.href.toString().substringBefore('#'))
                assertTrue("Font reflow preserves the nearby logical reading position",
                    kotlin.math.abs((beforeReflow.locations.progression?:0.0)-(afterReflow.locations.progression?:0.0))<.16)
                reader!!.close();reader=null
                delay(350)

                reader=ActivityScenario.launch(intent)
                awaitRenderedText(requireNotNull(reader),"Original one chapter")
                val reopened=openNavigator(requireNotNull(reader))
                val restored=awaitSettledMetrics(reopened,"reopened $layout typography and locator")
                saveMetrics(layout,"reopened",restored)
                assertEquals("Reopened Ink Black is painted by the EPUB HTML canvas; full CSS, native view and WebView layers: $restored",
                    "rgb(0, 0, 0)",restored.optString("htmlPaper"))
                assertTrue("The reopened EPUB body is transparent only over the confirmed black HTML canvas: $restored",
                    restored.optString("paper")=="rgba(0, 0, 0, 0)" || restored.optString("paper")=="rgb(0, 0, 0)")
                assertEquals("The reopened native reader root retains the selected Ink Black room: $restored",
                    "#FF000000",restored.optJSONObject("runtime")?.optString("readerRootBackground"))
                val restoredProse=restored.optString("prose").removeSuffix("px").toDouble()
                assertEquals("Reopened EPUB prose retains the saved font size",largerProse,restoredProse,largerProse*.04)
                val reopenedVisibleParagraph=restored.optInt("visibleParagraph",-1)
                assertTrue("Reopening restores the visible prose anchor within one paragraph: expected=$afterVisibleParagraph actual=$reopenedVisibleParagraph",
                    reopenedVisibleParagraph>=0 && kotlin.math.abs(afterVisibleParagraph-reopenedVisibleParagraph)<=1)
                assertEquals("Reopened EPUB retains its logical chapter",afterReflow.href.toString().substringBefore('#'),
                    reopened.currentLocator.value.href.toString().substringBefore('#'))
                assertTrue("Reopened EPUB restores a nearby logical position",
                    kotlin.math.abs((afterReflow.locations.progression?:0.0)-(reopened.currentLocator.value.locations.progression?:0.0))<.16)
                assertTrue("Save synthetic fixture typography evidence for Pages/Continuous layout=$layout",
                    device.takeScreenshot(File(context.cacheDir,"qa-0.4.2-epub-font-$layout-settled-continuous-anchor-09.png")))
                assertTrue("Capture the self-authored reader after reopening its per-title paper",
                    device.takeScreenshot(File(context.cacheDir,"qa-0.4.3-epub-room-reopen-$layout.png")))

                if(layout=="scroll") {
                    // Exercise the same-resource target path used by Contents and bookmarks while
                    // a font restore job is active; a stale restore must not undo that jump.
                    withContext(Dispatchers.Main) {
                        reopened.evaluateJavascript("""
                            (() => {
                                const passage = document.querySelectorAll('p')[6];
                                if (!passage) return 'missing-authored-passage';
                                passage.scrollIntoView({ block: 'start' });
                                return 'scrolled-to-authored-passage-6';
                            })()
                        """.trimIndent())
                    }
                    val beforeSameResourceJump=awaitSettledMetrics(reopened,"same-resource font restore origin")
                    assertTrue("Same-resource jump begins from a distinct visible passage: $beforeSameResourceJump",
                        beforeSameResourceJump.optInt("visibleParagraph",-1)>=3)
                    var sameResourceTarget:org.readium.r2.shared.publication.Locator?=null
                    reader!!.onActivity {activity->
                        val pub=activity.javaClass.getDeclaredField("publication").apply {isAccessible=true}.get(activity)
                            as org.readium.r2.shared.publication.Publication
                        sameResourceTarget=pub.locatorFromLink(pub.readingOrder.first())
                    }
                    val target=requireNotNull(sameResourceTarget) { "The fixture exposes its first Contents target" }
                    // Run the same production callback used by the visible Contents row before the
                    // restore coroutine can resume on the main dispatcher. This exercises the
                    // narrow race without depending on UiAutomator idle timing.
                    reader!!.onActivity {activity->
                        val preview=activity.javaClass.getDeclaredMethod("previewFontSize",Float::class.javaPrimitiveType!!).apply {isAccessible=true}
                        val finish=activity.javaClass.getDeclaredMethod("finishFontSizePreview").apply {isAccessible=true}
                        val navigate=activity.javaClass.getDeclaredMethod("navigateToEpubLocator",org.readium.r2.shared.publication.Locator::class.java).apply {isAccessible=true}
                        preview.invoke(activity,80f)
                        finish.invoke(activity)
                        assertNotNull("A confirmed font change guards its reading passage before restore",
                            activity.javaClass.getDeclaredField("fontPreviewOrigin").apply {isAccessible=true}.get(activity))
                        navigate.invoke(activity,target)
                    }
                    assertNull("Selecting an explicit same-resource Contents target cancels the stale font restore",
                        readerField(requireNotNull(reader),"fontPreviewOrigin"))
                    val afterSameResourceJump=reopened.currentLocator.value
                    assertTrue("The explicit Contents target stays in the same chapter",
                        sameResourceHref(startHref,afterSameResourceJump.href.toString()))
                    withTimeout(5_000) {
                        while((reopened.currentLocator.value.locations.progression?:1.0)>.03) delay(50)
                    }
                    delay(700)
                    assertTrue("A cancelled font restore cannot overwrite a deliberate same-resource jump",
                        (reopened.currentLocator.value.locations.progression?:1.0)<=.03)

                    reader!!.close();reader=null
                    delay(250)
                    reader=ActivityScenario.launch(intent)
                    awaitRenderedText(requireNotNull(reader),"Original one chapter")
                    val afterJumpReopen=openNavigator(requireNotNull(reader))
                    val jumpMetrics=awaitSettledMetrics(afterJumpReopen,"same-resource jump reopen")
                    assertTrue("Reopening keeps the deliberate same-resource jump near its target: $jumpMetrics",
                        jumpMetrics.optInt("visibleParagraph",-1) in 0..1 &&
                            (afterJumpReopen.currentLocator.value.locations.progression?:1.0)<=.03)

                    // Background during the live preview itself. onPause must retain the origin
                    // and restore it when this same activity returns, without resuming auto-read.
                    val sameActivityBeforeBackground=openNavigator(requireNotNull(reader))
                    val anchorBeforeBackground=awaitSettledMetrics(sameActivityBeforeBackground,"before same-activity background")
                    val paragraphBeforeBackground=anchorBeforeBackground.optInt("visibleParagraph",-1)
                    assertTrue("Background test records the actual visible passage before reflow: $anchorBeforeBackground",paragraphBeforeBackground>=0)
                    invokeFontAction(requireNotNull(reader),"previewFontSize",220f)
                    assertNotNull("Background test starts with a pending font anchor",readerField(requireNotNull(reader),"fontPreviewOrigin"))
                    reader!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    assertEquals("Backgrounding commits the selected font size",220f,readerPrefs.getFloat("font",100f),.1f)
                    assertNotNull("Backgrounding retains a guarded locator until the same activity restores it",
                        readerField(requireNotNull(reader),"fontPreviewOrigin"))
                    assertEquals("Backgrounding leaves a pending anchor restore",true,
                        readerField(requireNotNull(reader),"fontPreviewRestorePending"))
                    reader!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    waitForFontPreviewToFinish(requireNotNull(reader),15_000)
                    val sameActivityAfterBackground=awaitSettledMetrics(sameActivityBeforeBackground,"same-activity foreground anchor restore")
                    assertTrue("Returning to the same activity restores the visible passage within one paragraph: before=$paragraphBeforeBackground after=$sameActivityAfterBackground",
                        sameActivityAfterBackground.optInt("visibleParagraph",-1)>=0 &&
                            kotlin.math.abs(paragraphBeforeBackground-sameActivityAfterBackground.optInt("visibleParagraph",-1))<=1)
                    assertTrue("Returning to the same activity restores the chapter and nearby logical position",
                        sameResourceHref(sameActivityBeforeBackground.currentLocator.value.href.toString(),
                            sameActivityAfterBackground.optString("href")) &&
                            (sameActivityBeforeBackground.currentLocator.value.locations.progression?:1.0)<=.03)
                    val resumedAutomation=bookAutomationState(requireNotNull(reader))
                    assertNotNull("The EPUB automation controller remains available after foregrounding",resumedAutomation)
                    assertFalse("Font restoration does not resume auto-read after backgrounding",
                        requireNotNull(resumedAutomation).running)
                    reader!!.close();reader=null
                    delay(250)
                    reader=ActivityScenario.launch(intent)
                    awaitRenderedText(requireNotNull(reader),"Original one chapter")
                    val afterBackground=openNavigator(requireNotNull(reader))
                    val backgroundMetrics=awaitSettledMetrics(afterBackground,"font preview background restore")
                    assertTrue("Background cancellation restores the committed visible paragraph: $backgroundMetrics",
                        backgroundMetrics.optInt("visibleParagraph",-1) in 0..1 &&
                            (afterBackground.currentLocator.value.locations.progression?:1.0)<=.03)
                    assertTrue("The confirmed font setting survives backgrounding and reopen",
                        backgroundMetrics.optString("prose").removeSuffix("px").toDouble()>initialProse*1.8)

                    // The second fixture resource is intentionally short enough not to scroll.
                    // Reflow restoration must settle at y=0 instead of timing out for lack of range.
                    var secondLinkTitle="Chapter 2"
                    reader!!.onActivity {activity->
                        val pub=activity.javaClass.getDeclaredField("publication").apply {isAccessible=true}.get(activity)
                            as org.readium.r2.shared.publication.Publication
                        secondLinkTitle=pub.readingOrder[1].title?.takeIf {it.isNotBlank()} ?: "Chapter 2"
                    }
                    requireNotNull(device.wait(Until.findObject(By.text("Contents")),5_000)).click()
                    requireNotNull(device.wait(Until.findObject(By.text(secondLinkTitle)),5_000)).click()
                    awaitRenderedText(requireNotNull(reader),"Original two chapter")
                    invokeFontAction(requireNotNull(reader),"previewFontSize",140f)
                    val shortReflowStarted=android.os.SystemClock.uptimeMillis()
                    invokeFontAction(requireNotNull(reader),"finishFontSizePreview")
                    waitForFontPreviewToFinish(requireNotNull(reader))
                    val shortMetrics=awaitSettledMetrics(afterBackground,"short-resource font reflow")
                    val shortRuntime=shortMetrics.optJSONObject("runtime")
                    val shortViews=shortRuntime?.optJSONArray("webViews")
                    val shortView=(0 until (shortViews?.length()?:0)).mapNotNull {shortViews?.optJSONObject(it)}
                        .firstOrNull {it.optBoolean("shown") && it.optString("url").contains("two.xhtml")}
                    assertTrue("Short EPUB resources restore at the top and finish without a timeout: $shortMetrics",
                        shortMetrics.optString("href").contains("two.xhtml") &&
                            shortMetrics.optDouble("documentScrollY",-1.0)==0.0 &&
                            shortMetrics.optDouble("documentScrollHeight",Int.MAX_VALUE.toDouble())<=shortMetrics.optDouble("viewportHeight",0.0) &&
                            (shortView?.optInt("scrollY",-1)?:-1)==0 &&
                            android.os.SystemClock.uptimeMillis()-shortReflowStarted<5_000L)
                }
                reader!!.close();reader=null
            }
        } finally {
            reader?.close()
            id?.let {cleanup(it)}
            profileKey?.let {key->graph.libraryToolsStore.update {state->state.copy(profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key)}}
            readerPrefs.edit().apply {
                if(hadFont) putFloat("font",oldFont) else remove("font")
                if(hadLine) putFloat("line",oldLine) else remove("line")
                if(hadMargin) putFloat("margin",oldMargin) else remove("margin")
                if(hadDark) putBoolean("dark",oldDark) else remove("dark")
                if(hadScroll) putBoolean("scroll",oldScroll) else remove("scroll")
            }.commit()
            file.delete()
        }
    }

    @Test fun epubReadingLayoutPagesAndVerticalScrollPreserveLocatorAndProfile(): Unit = runBlocking {
        val file=fixture("epub");writeOriginalEpub(file)
        val bookPreferences=context.getSharedPreferences("arkhive_book_reader",0)
        val oldScrollPresent=bookPreferences.contains("scroll");val oldScroll=bookPreferences.getBoolean("scroll",true)
        val automationPreferences=context.getSharedPreferences("arkhive_book_automation",0)
        val automationKeys=setOf("EPUB_false_mode","EPUB_false_speed","EPUB_false_interval","EPUB_true_mode","EPUB_true_speed","EPUB_true_interval")
        val previousAutomation=automationPreferences.all.filterKeys {it in automationKeys}
        val oldIdleTimeout=Configurator.getInstance().waitForIdleTimeout
        val device=UiDevice.getInstance(instrumentation)
        var id:String?=null
        var reader:ActivityScenario<BookReaderActivity>?=null
        var profileKey:String?=null
        var hadPriorProfile=false
        var priorProfile=mihon.readingapp.library.ReadingProfile()
        try {
            Configurator.getInstance().waitForIdleTimeout=0L
            bookPreferences.edit().putBoolean("scroll",false).commit()
            val book=graph.localBookImporter.import(uri(file)).getOrThrow().book;id=book.id
            profileKey="book:${book.id}"
            graph.libraryToolsStore.state.value.profiles[profileKey!!]?.let {hadPriorProfile=true;priorProfile=it}
            reader=ActivityScenario.launch(android.content.Intent(context,BookReaderActivity::class.java)
                .putExtra(BookReaderActivity.BOOK_ID,book.id))
            awaitRenderedText(requireNotNull(reader),"Original one chapter")
            var navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment?=null
            awaitBookStage("manual EPUB layout navigator bind",requireNotNull(reader)) {
                reader!!.onActivity {activity->navigator=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                    as? org.readium.r2.navigator.epub.EpubNavigatorFragment}
                navigator!=null
            }
            var initialTotalProgress=0.0
            reader!!.onActivity {initialTotalProgress=bookProgress(it)}
            assertBookPercentAtBottom(device,"${(initialTotalProgress*100).toInt()}%")
            tapBookCenter(requireNotNull(reader))
            awaitBookStage("quiet EPUB full-book percentage",requireNotNull(reader)) {
                var quiet=false
                reader!!.onActivity {activity->
                    quiet=bookUiState(activity)?.menu==false && bookAutoReadTarget(activity)!=null
                }
                quiet
            }
            assertFalse("Quiet EPUB state has no permanent Auto read status text",device.hasObject(By.textContains("Auto read")))
            assertFalse("Quiet EPUB state has no permanent play or pause control",
                device.hasObject(By.desc("Resume auto read")) || device.hasObject(By.desc("Pause auto read")))
            var quietTextBounds = android.graphics.Rect()
            reader!!.onActivity { activity ->
                requireNotNull(bookAutoReadTarget(activity)).getGlobalVisibleRect(quietTextBounds)
            }
            var quietTotalProgress=0.0
            reader!!.onActivity {quietTotalProgress=bookProgress(it)}
            assertEquals("Showing the thin quiet progress row preserves the EPUB reading position",
                initialTotalProgress,quietTotalProgress,.02)
            val quietPercent="${(quietTotalProgress*100).toInt()}%"
            val quietPercentLabel=requireNotNull(device.wait(Until.findObject(By.text(quietPercent)),5_000)) {
                "Quiet EPUB state keeps the small full-book percentage"
            }
            assertTrue("The thin EPUB progress footer is below, not over, the text: text=$quietTextBounds,percent=${quietPercentLabel.visibleBounds}",
                quietTextBounds.bottom <= quietPercentLabel.visibleBounds.top)
            assertBookPercentAtBottom(device,quietPercent)
            assertTrue("Save the self-authored EPUB quiet progress indicator for visual review",
                device.takeScreenshot(File(context.cacheDir,"qa-epub-quiet-percent.png")))
            tapBookCenter(requireNotNull(reader))
            awaitBookStage("EPUB controls after quiet progress check",requireNotNull(reader)) {
                var visible=false
                reader!!.onActivity {activity->visible=bookUiState(activity)?.menu==true}
                visible
            }
            assertNotNull("Center tap reveals accessible Resume after the manual pause",device.wait(Until.findObject(By.desc("Resume auto read")),5_000))
            var initialHref="";var initialProgress=0.0
            reader!!.onActivity {
                initialHref=requireNotNull(navigator).currentLocator.value.href.toString().substringBefore('#')
                initialProgress=navigator!!.currentLocator.value.locations.progression?:0.0
            }
            requireNotNull(device.wait(Until.findObject(By.desc("Next")),10_000)).click()
            awaitBookStage("paged layout initial progress",requireNotNull(reader)) {
                var changed=false
                reader!!.onActivity {activity->val locator=requireNotNull(navigator).currentLocator.value
                    changed=locator.href.toString().substringBefore('#')==initialHref && (locator.locations.progression?:0.0)>initialProgress+.005}
                changed
            }
            var pagedBookProgress=0.0
            reader!!.onActivity {pagedBookProgress=bookProgress(it)}
            assertTrue("The first EPUB spine resource advances the whole-book percentage in Pages mode: $pagedBookProgress",
                pagedBookProgress>initialTotalProgress && pagedBookProgress in 0.0..1.0)
            assertBookPercentAtBottom(device,"${(pagedBookProgress*100).toInt()}%")
            awaitBookStage("initial EPUB Pages geometry",requireNotNull(reader)) {
                var paginated=false
                reader!!.onActivity {activity->
                    val web=bookAutoReadTarget(activity)
                    paginated=bookReaderScrolling(activity)==false && web!=null &&
                        (web.canScrollHorizontally(1) || web.canScrollHorizontally(-1))
                }
                paginated
            }
            var pagedProgress=0.0
            reader!!.onActivity {pagedProgress=requireNotNull(navigator).currentLocator.value.locations.progression?:0.0}

            requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
            assertNotNull("EPUB appearance controls must expose Pages and Vertical scroll",device.wait(Until.findObject(By.textContains("Vertical scroll")),10_000))
            requireNotNull(device.wait(Until.findObject(By.textContains("Vertical scroll")),10_000)).click()
            awaitBookStage("manual EPUB vertical layout selection",requireNotNull(reader)) {
                var locatorMatches=false;var verticallyScrollable=false
                reader!!.onActivity {activity->
                    val locator=requireNotNull(navigator).currentLocator.value
                    locatorMatches=locator.href.toString().substringBefore('#')==initialHref &&
                        kotlin.math.abs((locator.locations.progression?:0.0)-pagedProgress)<.08
                    verticallyScrollable=bookAutoReadTarget(activity)?.canScrollVertically(1)==true
                }
                graph.libraryToolsStore.profile(profileKey!!).layout=="scroll" && locatorMatches && verticallyScrollable
            }
            requireNotNull(device.wait(Until.findObject(By.text("Done")),10_000)).click()
            var beforeY=-1
            reader!!.onActivity {activity->beforeY=requireNotNull(bookAutoReadTarget(activity)).scrollY}
            requireNotNull(device.wait(Until.findObject(By.desc("Next")),10_000)).click()
            awaitBookStage("vertical EPUB manual screen advance",requireNotNull(reader)) {
                var advanced=false
                reader!!.onActivity {activity->advanced=bookAutoReadTarget(activity)?.let {it.scrollY>beforeY}?:false}
                advanced
            }
            awaitBookStage("vertical EPUB locator progression catches up",requireNotNull(reader)) {
                var progressed=false
                reader!!.onActivity {activity->
                    val locator=requireNotNull(navigator).currentLocator.value
                    progressed=locator.href.toString().substringBefore('#')==initialHref &&
                        (locator.locations.progression?:0.0)>pagedProgress+.005 &&
                        bookAutoReadTarget(activity)?.scrollY?.let {it>beforeY}==true
                }
                progressed
            }
            var verticalBookProgress=0.0
            reader!!.onActivity {verticalBookProgress=bookProgress(it)}
            assertTrue("The first EPUB spine resource advances the same whole-book percentage in Continuous mode: pages=$pagedBookProgress continuous=$verticalBookProgress",
                verticalBookProgress>pagedBookProgress && verticalBookProgress in 0.0..1.0)
            assertBookPercentAtBottom(device,"${(verticalBookProgress*100).toInt()}%")
            var verticalProgress=0.0
            reader!!.onActivity {verticalProgress=requireNotNull(navigator).currentLocator.value.locations.progression?:0.0}

            requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.textContains("Pages")),10_000)).click()
            awaitBookStage("manual EPUB Pages reflow",requireNotNull(reader)) {
                var locatorMatches=false
                reader!!.onActivity {activity->
                    val locator=requireNotNull(navigator).currentLocator.value
                    val web=bookAutoReadTarget(activity)
                    locatorMatches=locator.href.toString().substringBefore('#')==initialHref &&
                        kotlin.math.abs((locator.locations.progression?:0.0)-verticalProgress)<.08 &&
                        bookReaderScrolling(activity)==false && web!=null &&
                        (web.canScrollHorizontally(1) || web.canScrollHorizontally(-1))
                }
                graph.libraryToolsStore.profile(profileKey!!).layout=="pages" && locatorMatches
            }
            assertTrue("Switching the manual layout must not discard the current text position: paged=$pagedProgress vertical=$verticalProgress",
                verticalProgress>pagedProgress)
            var reflowedProgress=0.0
            reader!!.onActivity {reflowedProgress=requireNotNull(navigator).currentLocator.value.locations.progression?:0.0}
            assertEquals("Pages reflow should retain the text anchor within its containing page: vertical=$verticalProgress paged=$reflowedProgress",
                verticalProgress,reflowedProgress,.08)
            requireNotNull(device.wait(Until.findObject(By.text("Done")),10_000)).click()
            // Closing the appearance sheet can trigger one more Readium reflow. Capture the
            // comparison point only after the selected WebView and locator stop changing.
            var settledPage:Pair<Double,Int>?=null
            var stablePageObservations=0
            awaitBookStage("paged EPUB locator settles after closing appearance controls",requireNotNull(reader)) {
                var snapshot:Pair<Double,Int>?=null
                reader!!.onActivity {activity->
                    val locator=requireNotNull(navigator).currentLocator.value
                    val web=bookAutoReadTarget(activity)
                    if(locator.href.toString().substringBefore('#')==initialHref &&
                        web!=null && (web.canScrollHorizontally(1) || web.canScrollHorizontally(-1))) {
                        snapshot=(locator.locations.progression?:0.0) to web.scrollX
                    }
                }
                if(snapshot!=null && snapshot==settledPage) stablePageObservations++ else stablePageObservations=0
                settledPage=snapshot
                stablePageObservations>=2
            }
            val beforePage=requireNotNull(settledPage).first
            requireNotNull(device.wait(Until.findObject(By.desc("Next")),10_000)).click()
            awaitBookStage("paged EPUB manual page advance",requireNotNull(reader)) {
                var progressed=false
                reader!!.onActivity {activity->val locator=requireNotNull(navigator).currentLocator.value
                    progressed=locator.href.toString().substringBefore('#')==initialHref &&
                        (locator.locations.progression?:0.0)>beforePage+.005}
                progressed
            }
            var savedHref="";var savedProgress=0.0
            reader!!.onActivity {
                val locator=requireNotNull(navigator).currentLocator.value
                savedHref=locator.href.toString().substringBefore('#')
                savedProgress=locator.locations.progression?:0.0
            }
            reader!!.close();reader=null
            reader=ActivityScenario.launch(android.content.Intent(context,BookReaderActivity::class.java)
                .putExtra(BookReaderActivity.BOOK_ID,book.id))
            awaitRenderedText(requireNotNull(reader),"Original one chapter")
            var reopenedNavigator:org.readium.r2.navigator.epub.EpubNavigatorFragment?=null
            awaitBookStage("manual EPUB restored locator bind",requireNotNull(reader)) {
                reader!!.onActivity {activity->reopenedNavigator=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                    as? org.readium.r2.navigator.epub.EpubNavigatorFragment}
                reopenedNavigator!=null
            }
            reader!!.onActivity {
                val restored=requireNotNull(reopenedNavigator).currentLocator.value
                assertEquals("Manual EPUB layout reopen must retain the selected resource",savedHref,restored.href.toString().substringBefore('#'))
                assertEquals("Manual EPUB layout reopen must retain the reading position",savedProgress,restored.locations.progression?:0.0,.08)
            }
            assertEquals("EPUB manual layout must persist as a per-book profile","pages",graph.libraryToolsStore.profile(profileKey!!).layout)
            requireNotNull(device.wait(Until.findObject(By.text("Contents")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("Chapter 2")),10_000)).click()
            awaitBookStage("explicit EPUB Contents selection reaches the later spine resource",requireNotNull(reader)) {
                var reached=false
                reader!!.onActivity {activity->reached=requireNotNull(reopenedNavigator).currentLocator.value.href.toString().contains("two.xhtml")}
                reached
            }
            var laterSpineStart=0.0
            reader!!.onActivity {laterSpineStart=bookProgress(it)}
            assertTrue("The later EPUB spine begins at its actual position-weighted book boundary: $laterSpineStart",
                laterSpineStart in .05..0.95)
            requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.textContains("Vertical scroll")),10_000)).click()
            awaitBookStage("later EPUB spine switched to continuous layout",requireNotNull(reader)) {
                var selected=false
                reader!!.onActivity {activity->selected=bookReaderScrolling(activity)==true &&
                    bookAutoReadTarget(activity)?.canScrollVertically(1)==true}
                selected
            }
            requireNotNull(device.wait(Until.findObject(By.text("Done")),10_000)).click()
            var webWidth=0;var webHeight=0;var initialScrollY=0
            var initialContentHeight=0;var initialWebScale=1f
            reader!!.onActivity {activity->requireNotNull(bookAutoReadTarget(activity)).also {web->
                val webGeometry=IntArray(2)
                web.getLocationOnScreen(webGeometry);webWidth=web.width;webHeight=web.height
                initialScrollY=web.scrollY;initialContentHeight=web.contentHeight;initialWebScale=web.scale
            }}
            val estimatedMaxScrollY=(initialContentHeight.toDouble()*initialWebScale).toLong()-webHeight
            val remainingScrollY=(estimatedMaxScrollY-initialScrollY).coerceAtLeast(0L)
            val minimumSwipeAdvance=maxOf(1,(webHeight*.4f).toInt())
            val traversalSwipeLimit=(kotlin.math.ceil(remainingScrollY.toDouble()/minimumSwipeAdvance).toInt()+2)
                .coerceIn(1,128)
            for(attempt in 0 until traversalSwipeLimit) {
                var scrollable=false
                var beforeScrollY=-1
                reader!!.onActivity {activity->bookAutoReadTarget(activity)?.let {web->
                    scrollable=web.canScrollVertically(1);beforeScrollY=web.scrollY
                }}
                if(!scrollable) break
                var geometryX=0;var geometryY=0
                reader!!.onActivity {activity->bookAutoReadTarget(activity)?.let {web->
                    val webGeometry=IntArray(2);web.getLocationOnScreen(webGeometry)
                    geometryX=webGeometry[0];geometryY=webGeometry[1]
                }}
                val x=geometryX+webWidth/2
                val startY=geometryY+(webHeight*.78f).toInt()
                val endY=geometryY+(webHeight*.22f).toInt()
                assertTrue("Later-spine source-content swipe $attempt is delivered",device.swipe(x,startY,x,endY,10))
                awaitBookStage("later-spine content swipe $attempt moves its active WebView",requireNotNull(reader),2_000) {
                    var moved=false
                    reader!!.onActivity {activity->val web=bookAutoReadTarget(activity)
                        moved=web!=null && web.scrollY>beforeScrollY}
                    moved
                }
            }
            awaitBookStage("later EPUB spine reaches its rendered near-end position after at most $traversalSwipeLimit geometry-bounded swipes",requireNotNull(reader),15_000) {
                var nearEnd=false
                reader!!.onActivity {activity->
                    val web=bookAutoReadTarget(activity)
                    nearEnd=web!=null && !web.canScrollVertically(1) && bookProgress(activity)>=.85 &&
                        requireNotNull(reopenedNavigator).currentLocator.value.href.toString().contains("two.xhtml")
                }
                nearEnd
            }
            var laterSpineEnd=0.0
            reader!!.onActivity {laterSpineEnd=bookProgress(it)}
            assertTrue("Later-spine whole-book progress advances near the end in Continuous mode: start=$laterSpineStart end=$laterSpineEnd",
                laterSpineEnd>laterSpineStart && laterSpineEnd in .85..1.0)
            assertTrue("The later-spine rendered footer percentage advances: start=$laterSpineStart end=$laterSpineEnd",
                (laterSpineEnd*100).toInt()>(laterSpineStart*100).toInt())
            assertBookPercentAtBottom(device,"${(laterSpineEnd*100).toInt()}%")
        } finally {
            reader?.close()
            id?.let {cleanup(it)}
            profileKey?.let {key->graph.libraryToolsStore.update {state->
                state.copy(profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key)
            }}
            bookPreferences.edit().also {edit->
                if(oldScrollPresent) edit.putBoolean("scroll",oldScroll) else edit.remove("scroll")
            }.commit()
            automationPreferences.edit().apply {
                automationKeys.forEach(::remove)
                previousAutomation.forEach {(key,value)->when(value) {
                    is String->putString(key,value)
                    is Int->putInt(key,value)
                    is Long->putLong(key,value)
                    is Boolean->putBoolean(key,value)
                    is Float->putFloat(key,value)
                    else->Unit
                }}
            }.commit()
            Configurator.getInstance().waitForIdleTimeout=oldIdleTimeout
            file.delete()
        }
    }

    @Test fun singleSpineEpubProgressAdvancesInPagesAndContinuousModes(): Unit = runBlocking {
        val file=fixture("epub");writeOriginalEpub(file,chapterCount=1)
        val bookPreferences=context.getSharedPreferences("arkhive_book_reader",0)
        val oldScrollPresent=bookPreferences.contains("scroll");val oldScroll=bookPreferences.getBoolean("scroll",true)
        val oldIdleTimeout=Configurator.getInstance().waitForIdleTimeout
        val device=UiDevice.getInstance(instrumentation)
        var id:String?=null
        var reader:ActivityScenario<BookReaderActivity>?=null
        var profileKey:String?=null
        var hadPriorProfile=false
        var priorProfile=mihon.readingapp.library.ReadingProfile()
        try {
            Configurator.getInstance().waitForIdleTimeout=0L
            bookPreferences.edit().putBoolean("scroll",false).commit()
            val book=graph.localBookImporter.import(uri(file)).getOrThrow().book;id=book.id
            profileKey="book:${book.id}"
            graph.libraryToolsStore.state.value.profiles[profileKey!!]?.let {hadPriorProfile=true;priorProfile=it}
            reader=ActivityScenario.launch(android.content.Intent(context,BookReaderActivity::class.java)
                .putExtra(BookReaderActivity.BOOK_ID,book.id))
            awaitRenderedText(requireNotNull(reader),"Original one chapter")
            var navigator:org.readium.r2.navigator.epub.EpubNavigatorFragment?=null
            awaitBookStage("single-spine EPUB navigator bind",requireNotNull(reader)) {
                reader!!.onActivity {activity->navigator=activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                    as? org.readium.r2.navigator.epub.EpubNavigatorFragment}
                navigator!=null
            }
            awaitBookStage("single-spine Pages position service ready",requireNotNull(reader)) {
                var ready=false
                reader!!.onActivity {activity->val web=bookAutoReadTarget(activity)
                    ready=web!=null && bookUiState(activity)?.ready==true &&
                        (web.canScrollHorizontally(1) || web.canScrollHorizontally(-1))}
                ready
            }
            var start=0.0
            reader!!.onActivity {start=bookProgress(it)}
            assertEquals("A single-spine EPUB starts at the beginning",0.0,start,.02)
            requireNotNull(device.wait(Until.findObject(By.desc("Next")),10_000)).click()
            awaitBookStage("single-spine Pages advances whole-book progress",requireNotNull(reader)) {
                var progressed=false
                reader!!.onActivity {activity->progressed=bookProgress(activity)>start+.005 &&
                    (requireNotNull(navigator).currentLocator.value.locations.progression?:0.0)>.005}
                progressed
            }
            var pagesProgress=0.0
            reader!!.onActivity {pagesProgress=bookProgress(it)}
            assertTrue("Pages percentage is bounded and nonzero for a one-resource publication: $pagesProgress",
                pagesProgress in .0..1.0 && pagesProgress>0.0)
            assertTrue("One-spine Pages footer moves beyond 0%",(pagesProgress*100).toInt()>0)
            assertBookPercentAtBottom(device,"${(pagesProgress*100).toInt()}%")

            requireNotNull(device.wait(Until.findObject(By.text("Aa")),10_000)).click()
            requireNotNull(device.wait(Until.findObject(By.textContains("Vertical scroll")),10_000)).click()
            awaitBookStage("single-spine Continuous layout selected",requireNotNull(reader)) {
                var selected=false
                reader!!.onActivity {activity->selected=bookReaderScrolling(activity)==true &&
                    bookAutoReadTarget(activity)?.canScrollVertically(1)==true}
                selected
            }
            requireNotNull(device.wait(Until.findObject(By.text("Done")),10_000)).click()
            var beforeY=-1
            reader!!.onActivity {beforeY=requireNotNull(bookAutoReadTarget(it)).scrollY}
            requireNotNull(device.wait(Until.findObject(By.desc("Next")),10_000)).click()
            awaitBookStage("single-spine Continuous advances whole-book progress",requireNotNull(reader)) {
                var progressed=false
                reader!!.onActivity {activity->val web=bookAutoReadTarget(activity)
                    progressed=web!=null && web.scrollY>beforeY && bookProgress(activity)>pagesProgress+.005 &&
                        (requireNotNull(navigator).currentLocator.value.locations.progression?:0.0)>.01}
                progressed
            }
            var continuousProgress=0.0
            reader!!.onActivity {continuousProgress=bookProgress(it)}
            assertTrue("Continuous percentage remains bounded and increases for a one-resource publication: $continuousProgress",
                continuousProgress in 0.0..1.0 && continuousProgress>pagesProgress)
            assertTrue("One-spine Continuous footer advances beyond the Pages percentage: pages=$pagesProgress continuous=$continuousProgress",
                (continuousProgress*100).toInt()>(pagesProgress*100).toInt())
            assertBookPercentAtBottom(device,"${(continuousProgress*100).toInt()}%")
        } finally {
            reader?.close()
            id?.let {cleanup(it)}
            profileKey?.let {key->graph.libraryToolsStore.update {state->
                state.copy(profiles=if(hadPriorProfile) state.profiles+(key to priorProfile) else state.profiles-key)
            }}
            bookPreferences.edit().also {edit->if(oldScrollPresent) edit.putBoolean("scroll",oldScroll) else edit.remove("scroll")}.commit()
            Configurator.getInstance().waitForIdleTimeout=oldIdleTimeout
            file.delete()
        }
    }

    @Test fun pagedEpubContinuousControlRebindsToScrollAndStartsMoving(): Unit = runBlocking {
        val file = fixture("epub")
        writeOriginalEpub(file)
        val bookPreferences = context.getSharedPreferences("arkhive_book_reader", 0)
        val previousScrollPresent = bookPreferences.contains("scroll")
        val previousScroll = bookPreferences.getBoolean("scroll", true)
        val automationPreferences = context.getSharedPreferences("arkhive_book_automation", 0)
        val automationKeys = setOf(
            "EPUB_false_mode", "EPUB_false_speed", "EPUB_false_interval",
            "EPUB_true_mode", "EPUB_true_speed", "EPUB_true_interval",
        )
        val previousAutomationSettings = automationPreferences.all.filterKeys { it in automationKeys }
        val previousTools = graph.libraryToolsStore.state.value
        val previousReducedMotion = graph.uiPreferences.arkhiveReducedMotion.get()
        val previousIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        val device = UiDevice.getInstance(instrumentation)
        var id: String? = null
        var reader: ActivityScenario<BookReaderActivity>? = null
        var probe: BookScrollProbe? = null
        try {
            // Active continuous scrolling intentionally prevents an idle frame; keep UI queries
            // responsive. UIAutomator's default idle wait consumes the entire chapter while
            // Readium emits scroll/accessibility updates, so active state is sampled through
            // callbacks installed before Start; UIAutomator remains for actual visible controls.
            Configurator.getInstance().waitForIdleTimeout = 0L
            graph.uiPreferences.arkhiveReducedMotion.set(false)
            bookPreferences.edit().putBoolean("scroll", false).commit()
            automationPreferences.edit()
                .putString("EPUB_false_mode", "TIMED_STEP").putInt("EPUB_false_speed", 60).putInt("EPUB_false_interval", 8)
                .putString("EPUB_true_mode", "TIMED_STEP").putInt("EPUB_true_speed", 60).putInt("EPUB_true_interval", 8)
                .commit()
            val book = graph.localBookImporter.import(uri(file)).getOrThrow().book
            id = book.id
            val intent = android.content.Intent(context, BookReaderActivity::class.java)
                .putExtra(BookReaderActivity.BOOK_ID, book.id)
            reader = ActivityScenario.launch(intent)
            awaitRenderedText(requireNotNull(reader), "Original one chapter")

            var navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment? = null
            awaitBookStage("initial EPUB navigator bind", requireNotNull(reader)) {
                    reader!!.onActivity { activity ->
                        navigator = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                            as? org.readium.r2.navigator.epub.EpubNavigatorFragment
                    }
                    navigator != null
            }
            // Establish a nonzero real page position through the paged reader's edge-tap control.
            var initialProgress = 0.0
            reader!!.onActivity {
                initialProgress = requireNotNull(navigator).currentLocator.value.locations.progression ?: 0.0
            }
            requireNotNull(device.wait(Until.findObject(By.desc("Next")), 10_000)).click()
            var originalHref = ""
            var originalProgress = 0.0
            awaitBookStage("paged EPUB advances from its initial location", requireNotNull(reader)) {
                    reader!!.onActivity {
                        val location = requireNotNull(navigator).currentLocator.value
                        originalHref = location.href.toString().substringBefore('#')
                        originalProgress = location.locations.progression ?: 0.0
                    }
                    originalProgress > initialProgress + .005
            }

            requireNotNull(device.wait(Until.findObject(By.text("Aa")), 10_000)).click()
            visibleReaderOption(device, "Auto read").click()
            val (continuousRow, rowBeforeTap) = awaitStableClickableModeRow(requireNotNull(reader), "Continuous scroll")
            assertTrue("Paginated reflowable EPUB should offer an explicit scroll-text transition: $rowBeforeTap",
                continuousRow.isEnabled && continuousRow.isClickable)
            continuousRow.click()
            val speedLabel = device.wait(Until.findObject(By.textContains("Scroll speed ·")), 10_000)
            if (speedLabel == null) {
                var diagnostic = "activity unavailable"
                reader!!.onActivity { activity -> diagnostic = bookReaderDiagnostic(activity) }
                val profile = graph.libraryToolsStore.profile("book:${book.id}")
                val row = device.findObject(By.text("Continuous scroll"))
                val rowAfterTap = runCatching { row?.let(::clickableAutomationAncestor) }.getOrNull()
                throw AssertionError(
                    "Continuous mode selection did not commit after the EPUB rebind; initialRow={$rowBeforeTap}," +
                        "currentRow=${automationNodeDiagnostic(row, rowAfterTap)}," +
                        "scrollPreference=${bookPreferences.getBoolean("scroll", true)},profileLayout=${profile.layout}," +
                        "persistedMode=${automationPreferences.getString("EPUB_true_mode", null)}," +
                        "reducedMotion=${graph.uiPreferences.arkhiveReducedMotion.get()},$diagnostic",
                )
            }

            var reboundHref = ""
            var reboundProgress = 0.0
            awaitBookStage("Continuous EPUB rebind retains the current resource and position", requireNotNull(reader)) {
                    reader!!.onActivity {
                        val location = requireNotNull(navigator).currentLocator.value
                        reboundHref = location.href.toString().substringBefore('#')
                        reboundProgress = location.locations.progression ?: 0.0
                    }
                    reboundHref == originalHref && reboundProgress >= originalProgress - .15
            }
            assertEquals("The same spine resource should remain open after reflow", originalHref, reboundHref)
            assertTrue("Reflow should keep the saved position instead of returning to the start",
                reboundProgress >= originalProgress - .15)

            // Attach observers before playback. Continuous Readium scrolling emits accessibility
            // updates that can make ActivityScenario.onActivity wait until the entire spine ends;
            // the probe samples the selected production WebView only from UI-thread scroll/layout
            // callbacks and lets the test thread inspect atomics without synchronizing on UI idle.
            probe = observeBook(requireNotNull(reader))
            probe!!.refreshFor(2_500)
            requireNotNull(device.wait(Until.findObject(By.text("Start auto read")), 10_000)).click()
            awaitBookProbe("visible Start enters playback", requireNotNull(probe), 10_000) {
                it.running && it.targetIdentity.get() != 0 && it.visible
            }
            var startTargetIdentity = probe!!.targetIdentity.get()
            var startScrollY = probe!!.scrollY.get()
            var movingScrollY = startScrollY
            awaitBookProbe("visible Start dismisses the sheet and exposes the reflowed WebView", probe!!, 8_000) {
                it.targetIdentity.get() != 0 && it.visible && it.scrollY.get() >= 0
            }
            startTargetIdentity = probe!!.targetIdentity.get()
            startScrollY = probe!!.scrollY.get()
            movingScrollY = startScrollY
            val movementObserved = withTimeoutOrNull(8_000) {
                while (true) {
                    val identity = probe!!.targetIdentity.get()
                    val scrollY = probe!!.scrollY.get()
                    if (identity != 0 && scrollY >= 0) {
                        if (identity != startTargetIdentity) {
                            // Readium may replace its visible WebView during the chrome/reflow
                            // transition. Measure only movement on the currently selected target.
                            startTargetIdentity = identity
                            startScrollY = scrollY
                            movingScrollY = scrollY
                        } else {
                            movingScrollY = scrollY
                        }
                        if (movingScrollY > startScrollY + 40) break
                    }
                    delay(50)
                }
                true
            } == true
            assertTrue(
                "Visible Start should move the reflowed EPUB text by more than 40px on one selected target: ${probe!!.diagnostic()}",
                movementObserved && movingScrollY > startScrollY + 40,
            )
            val activeProgress=device.wait(Until.findObject(By.text(Pattern.compile("^\\d{1,3}%$"))),5_000)
            assertNotNull("Full-book percentage should remain visible during EPUB playback",activeProgress)
            assertTrue("Full-book percentage should stay at the bottom during playback: ${activeProgress!!.visibleBounds}",
                activeProgress.visibleBounds.bottom>device.displayHeight*3/4)
            awaitBookProbe("playback continues with only the compact full-book percentage visible", probe!!, 5_000) {
                it.running
            }
            assertFalse("Quiet EPUB playback has no permanent status text",device.hasObject(By.textContains("Auto read")))
            assertFalse("Quiet EPUB playback has no permanent play or pause button",
                device.hasObject(By.desc("Resume auto read")) || device.hasObject(By.desc("Pause auto read")))
            val activePercent=requireNotNull(device.findObject(By.text(Pattern.compile("^\\d{1,3}%$"))))
            probe!!.refreshFor(2_500)
            val visibleTextBounds = android.graphics.Rect.unflattenFromString(probe!!.bounds)
            assertNotNull("The quiet EPUB footer should leave a visible text viewport: ${probe!!.diagnostic()}", visibleTextBounds)
            assertTrue("The thin progress footer stays below reader text: text=$visibleTextBounds,percent=${activePercent.visibleBounds}",
                requireNotNull(visibleTextBounds).bottom <= activePercent.visibleBounds.top)
            assertTrue("Save the self-authored EPUB continuous progress indicator for visual review",
                device.takeScreenshot(File(context.cacheDir,"qa-epub-continuous-percent.png")))
            tapBookCenter(requireNotNull(reader))
            awaitBookProbe("center tap pauses playback and reveals accessible controls", probe!!, 5_000) {
                !it.running && it.targetIdentity.get() != 0 && it.visible
            }
            assertNotNull("Paused EPUB controls expose Resume after center tap",device.wait(Until.findObject(By.desc("Resume auto read")),5_000))
            val pausedScrollY = probe!!.scrollY.get()
            val pausedTargetIdentity = probe!!.targetIdentity.get()
            delay(650)
            assertEquals("Readium's selected WebView must remain the paused viewport: ${probe!!.diagnostic()}",
                pausedTargetIdentity, probe!!.targetIdentity.get())
            assertEquals("Paused Auto read must stop moving the reflowable page: ${probe!!.diagnostic()}",
                pausedScrollY, probe!!.scrollY.get())
        } finally {
            probe?.let { current -> runCatching { instrumentation.runOnMainSync { current.dispose() } } }
            Configurator.getInstance().waitForIdleTimeout = previousIdleTimeout
            reader?.close()
            id?.let { cleanup(it) }
            file.delete()
            graph.libraryToolsStore.update { previousTools }
            graph.uiPreferences.arkhiveReducedMotion.set(previousReducedMotion)
            bookPreferences.edit().apply {
                if (previousScrollPresent) putBoolean("scroll", previousScroll) else remove("scroll")
            }.commit()
            automationPreferences.edit().apply {
                automationKeys.forEach(::remove)
                previousAutomationSettings.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Float -> putFloat(key, value)
                        else -> Unit
                    }
                }
            }.commit()
        }
    }

    private suspend fun awaitBookStage(
        stage: String,
        scenario: ActivityScenario<BookReaderActivity>,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(100) }
        } catch (error: TimeoutCancellationException) {
            var details = "activity unavailable"
            scenario.onActivity { details = bookReaderDiagnostic(it) }
            throw AssertionError("Timed out at $stage: $details", error)
        }
    }

    private suspend fun awaitStableClickableModeRow(
        scenario: ActivityScenario<BookReaderActivity>,
        label: String,
    ): Pair<UiObject2, String> {
        val device = UiDevice.getInstance(instrumentation)
        var lastBounds: String? = null
        var stableObservations = 0
        var lastDiagnostic = "label not found"
        var result: Pair<UiObject2, String>? = null
        withTimeoutOrNull(10_000) {
            while (result == null) {
                val labelNode = device.findObject(By.text(label))
                val row = runCatching { labelNode?.let(::clickableAutomationAncestor) }.getOrNull()
                lastDiagnostic = automationNodeDiagnostic(labelNode, row)
                val bounds = runCatching {
                    row?.takeIf { it.isEnabled && it.isClickable }
                        ?.visibleBounds?.takeIf { it.width() > 0 }?.flattenToString()
                }.getOrNull()
                if (row != null && bounds != null) {
                    if (bounds == lastBounds) stableObservations++ else stableObservations = 0
                    lastBounds = bounds
                    if (stableObservations >= 2) result = row to lastDiagnostic
                } else {
                    lastBounds = null
                    stableObservations = 0
                }
                if (result == null) delay(100)
            }
        }
        if (result != null) return result!!
        var productState = "activity unavailable"
        scenario.onActivity { activity ->
            val ui = bookUiState(activity)
            productState = "uiReady=${ui?.ready},ui=$ui,controller=${bookAutomationState(activity)}," +
                "windowFocus=${activity.hasWindowFocus()},lifecycle=${activity.lifecycle.currentState}"
        }
        throw AssertionError("Continuous scroll row did not become enabled, clickable, and stable: " +
            "$lastDiagnostic; $productState")
    }

    private fun clickableAutomationAncestor(label: UiObject2): UiObject2? {
        var node: UiObject2? = label
        repeat(8) {
            val current = node ?: return null
            if (current.isClickable) return current
            node = current.parent
        }
        return null
    }

    private fun automationNodeDiagnostic(label: UiObject2?, row: UiObject2?): String =
        "label=${describeAutomationNode(label)},row=${describeAutomationNode(row)}"

    private fun describeAutomationNode(node: UiObject2?): String = runCatching { node?.let {
        "enabled=${it.isEnabled},clickable=${it.isClickable},checkable=${it.isCheckable},checked=${it.isChecked}," +
            "bounds=${it.visibleBounds.flattenToString()}"
    } ?: "missing" }.getOrDefault("stale")

    private suspend fun awaitBookProbe(
        stage: String,
        probe: BookScrollProbe,
        timeoutMs: Long,
        condition: (BookScrollProbe) -> Boolean,
    ) {
        val reached = withTimeoutOrNull(timeoutMs) {
            while (!condition(probe)) delay(50)
            true
        } == true
        assertTrue("Timed out at $stage: ${probe.diagnostic()}", reached)
    }

    private fun observeBook(scenario: ActivityScenario<BookReaderActivity>): BookScrollProbe {
        val probe = BookScrollProbe()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val controller = bookAutoController(activity)
            fun capture() {
                val web = bookAutoReadTarget(activity)
                val bounds = android.graphics.Rect()
                val state = controller?.state
                val navigator = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
                    as? org.readium.r2.navigator.epub.EpubNavigatorFragment
                val href = navigator?.currentLocator?.value?.href?.toString()
                probe.targetIdentity.set(web?.let { System.identityHashCode(it) } ?: 0)
                probe.scrollY.set(web?.scrollY ?: -1)
                probe.contentHeight.set(web?.contentHeight ?: -1)
                probe.targetHeight.set(web?.height ?: -1)
                probe.visible = web?.getGlobalVisibleRect(bounds) == true
                probe.canScroll = web?.canScrollVertically(1) == true
                probe.focused = activity.hasWindowFocus()
                probe.lifecycle = activity.lifecycle.currentState.name
                probe.resource = href?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')?.take(80) ?: "unknown"
                probe.mode = state?.settings?.mode?.name ?: "unavailable"
                probe.running = state?.running == true
                probe.controllerMessage = state?.message?.take(100) ?: "unavailable"
                probe.bounds = if (web == null) "none" else bounds.flattenToString()
            }
            val observer = decor.viewTreeObserver
            probe.snapshot = { capture() }
            if (observer.isAlive) {
                val scroll = ViewTreeObserver.OnScrollChangedListener { capture() }
                val layout = ViewTreeObserver.OnGlobalLayoutListener { capture() }
                observer.addOnScrollChangedListener(scroll)
                observer.addOnGlobalLayoutListener(layout)
                probe.observer = observer
                probe.scrollListener = scroll
                probe.layoutListener = layout
            }
            capture()
        }
        return probe
    }

    private fun bookReaderDiagnostic(activity: BookReaderActivity): String {
        val navigator = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
            as? org.readium.r2.navigator.epub.EpubNavigatorFragment
        val location = navigator?.currentLocator?.value
        val webViews = navigator?.publicationView?.descendants()?.filterIsInstance<android.webkit.WebView>()?.toList().orEmpty()
        val web = bookAutoReadTarget(activity)
        fun resourceName(value: String?): String? = value?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')?.take(80)
        fun describe(view: android.webkit.WebView): String {
            val bounds = android.graphics.Rect()
            val visible = view.getGlobalVisibleRect(bounds)
            return "id=${System.identityHashCode(view)},selected=${view === web},shown=${view.isShown},visibility=${view.visibility}," +
                "alpha=${view.alpha},attached=${view.isAttachedToWindow},resource=${resourceName(view.url)}," +
                "progress=${view.progress},scrollX=${view.scrollX},canScrollLeft=${view.canScrollHorizontally(-1)},"+
                "canScrollRight=${view.canScrollHorizontally(1)},scrollY=${view.scrollY},contentHeight=${view.contentHeight}," +
                "size=${view.width}x${view.height},canScrollY=${view.canScrollVertically(1)}," +
                "visible=$visible,bounds=${bounds.flattenToString()}"
        }
        val scrolling=bookReaderScrolling(activity)
        val pageIndex=runCatching {activity.javaClass.getDeclaredField("pageInResource").apply {isAccessible=true}.getInt(activity)}.getOrNull()
        val pageCount=runCatching {activity.javaClass.getDeclaredField("pagesInResource").apply {isAccessible=true}.getInt(activity)}.getOrNull()
        val root = activity.findViewById<View>(eu.kanade.tachiyomi.R.id.book_reader_content)
        return "resource=${resourceName(location?.href?.toString())},progress=${location?.locations?.progression}," +
            "controller=${bookAutomationState(activity)},ui=${bookUiDiagnostic(activity)}," +
            "scrolling=$scrolling,page=$pageIndex/$pageCount,"+
            "selectedTarget=${web?.let(::describe)}," +
            "publicationWebViews=${webViews.joinToString(prefix="[",postfix="]",transform=::describe)}," +
            "root=${root?.width}x${root?.height},windowFocus=${activity.hasWindowFocus()}," +
            "lifecycle=${activity.lifecycle.currentState}"
    }

    private suspend fun bookReaderDiagnosticWithDom(
        scenario: ActivityScenario<BookReaderActivity>,
        navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment,
    ): String {
        val dom = CompletableDeferred<String>()
        var product = "activity unavailable"
        scenario.onActivity { activity ->
            product = bookReaderDiagnostic(activity)
            val web = bookAutoReadTarget(activity)
            if (web == null) {
                dom.complete("no visible publication WebView")
            } else {
                web.evaluateJavascript(
                    """JSON.stringify({href:location.href,readyState:document.readyState,title:document.title,
                        bodyText:(document.body?.innerText||'').slice(0,240),paragraphCount:document.querySelectorAll('p').length,
                        firstParagraph:document.querySelector('p')?.innerText?.slice(0,180),
                        firstClass:document.querySelector('p')?.className,
                        firstInlineStyle:document.querySelector('p')?.getAttribute('style'),
                        computedFont:document.querySelector('p')?getComputedStyle(document.querySelector('p')).fontSize:null,
                        styleTags:Array.from(document.querySelectorAll('style')).map(s=>s.textContent.slice(0,180)),
                        ruleProbe:Array.from(document.styleSheets).map(s=>{try{return Array.from(s.cssRules).map(r=>r.cssText).slice(0,8)}catch(e){return 'CSSOM_ERROR:'+String(e)}})})""".trimIndent(),
                ) { value -> dom.complete(value ?: "null DOM probe") }
            }
        }
        val sampledDom = withTimeoutOrNull(2_500) { dom.await() } ?: "DOM probe timed out"
        val location = navigator.currentLocator.value
        return "$product,locator=${location.href} progress=${location.locations.progression},dom=$sampledDom"
    }

    /** Mirrors BookReaderActivity's publication-view selection so the assertion samples the view
     * that the production Continuous callback actually scrolls, rather than another attached
     * Readium page or an unrelated WebView elsewhere in the activity. */
    private fun bookAutoReadTarget(activity: BookReaderActivity): android.webkit.WebView? {
        val navigator = activity.supportFragmentManager.findFragmentByTag("arkhive_epub")
            as? org.readium.r2.navigator.epub.EpubNavigatorFragment ?: return null
        val root = activity.findViewById<View>(eu.kanade.tachiyomi.R.id.book_reader_content) ?: return null
        return navigator.publicationView.descendants().filterIsInstance<android.webkit.WebView>()
            .firstOrNull { web ->
                if (!web.isShown) return@firstOrNull false
                val bounds = android.graphics.Rect()
                web.getGlobalVisibleRect(bounds) && bounds.width() >= root.width * 9 / 10
            }
    }

    private fun bookAutoController(activity: BookReaderActivity): BookAutoController? = runCatching {
        val field = activity.javaClass.declaredFields.first { it.name == "auto\$delegate" }
        field.isAccessible = true
        (field.get(activity) as Lazy<*>).value as BookAutoController
    }.getOrNull()

    private fun bookAutomationState(activity: BookReaderActivity): eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? =
        bookAutoController(activity)?.state

    private fun bookReaderScrolling(activity: BookReaderActivity): Boolean? = runCatching {
        val field=activity.javaClass.declaredFields.firstOrNull {it.name=="scrolling"}
            ?: activity.javaClass.getDeclaredField("scrolling\$delegate")
        field.isAccessible=true
        when(val value=field.get(activity)) {
            is Boolean -> value
            is androidx.compose.runtime.State<*> -> value.value as? Boolean
            else -> null
        }
    }.getOrNull()

    private fun sameResourceHref(locatorHref:String,renderedHref:String):Boolean {
        fun path(value:String)=android.net.Uri.parse(value).pathSegments.flatMap {it.split('/')}
            .filter(String::isNotEmpty).joinToString("/")
        val locatorPath=path(locatorHref)
        val renderedPath=path(renderedHref)
        return locatorPath.isNotBlank() && renderedPath.isNotBlank() &&
            (locatorPath==renderedPath || renderedPath.endsWith("/$locatorPath"))
    }

    private fun bookAutomationState(scenario: ActivityScenario<BookReaderActivity>): eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? {
        var state: eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationUiState? = null
        scenario.onActivity { activity -> state = bookAutomationState(activity) }
        return state
    }

    private fun bookUiState(activity: BookReaderActivity): BookReaderUi? = runCatching {
        val field = activity.javaClass.declaredFields.first { it.name == "ui\$delegate" }
        field.isAccessible = true
        (field.get(activity) as? androidx.compose.runtime.State<*>)?.value as? BookReaderUi
    }.getOrNull()

    private fun bookProgress(activity: BookReaderActivity): Double = runCatching {
        val field=activity.javaClass.getDeclaredField("lastProgress").apply {isAccessible=true}
        field.getDouble(activity)
    }.getOrDefault(0.0)

    private fun bookUiDiagnostic(activity: BookReaderActivity): String =
        bookUiState(activity)?.let { "uiReady=${it.ready},ui=$it" } ?: "ui=unavailable"

    private fun assertBookPercentAtBottom(device:UiDevice,expected:String) {
        val label=requireNotNull(device.wait(Until.findObject(By.text(expected)),10_000)) {
            "Expected the full-book progress label $expected"
        }
        assertTrue("Full-book percentage should remain at the bottom of the reader: ${label.visibleBounds}",
            label.visibleBounds.bottom>device.displayHeight*3/4)
    }

    private suspend fun visibleReaderOption(device: UiDevice, label: String): UiObject2 {
        repeat(8) {
            device.findObject(By.text(label))?.let { return it }
            val scroller = device.findObject(By.scrollable(true))
                ?: throw AssertionError("Reader option '$label' is absent and the sheet has no scrollable region")
            scroller.scroll(Direction.DOWN, .65f)
            delay(150)
        }
        return device.findObject(By.text(label))
            ?: throw AssertionError("Reader option '$label' was not reachable after bounded sheet scrolling")
    }

    private fun tapBookCenter(scenario:ActivityScenario<BookReaderActivity>) {
        val point=IntArray(2)
        instrumentation.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<BookReaderActivity>().single()
            val web=requireNotNull(bookAutoReadTarget(activity))
            web.getLocationOnScreen(point)
            point[0]+=web.width/2
            point[1]+=web.height/2
        }
        UiDevice.getInstance(instrumentation).click(point[0],point[1])
    }

    private fun android.view.View.descendants(): Sequence<android.view.View> = sequence {
        yield(this@descendants)
        if (this@descendants is android.view.ViewGroup)
            for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }
    private suspend fun awaitPdfImage(reader: ActivityScenario<BookReaderActivity>) = withTimeout(15_000) {
        while (true) {
            var ready = false
            reader.onActivity { activity ->
                ready = activity.window.decorView.descendants().any {
                    it is com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView &&
                        it.isShown && it.isReady && it.sWidth > 0 && it.sHeight > 0
                }
            }
            if (ready) break
            delay(100)
        }
    }
    private suspend fun awaitRenderedText(reader: ActivityScenario<BookReaderActivity>, expected: String) = withTimeout(20_000) {
        while (true) {
            val result = CompletableDeferred<String>()
            reader.onActivity { activity ->
                val web = activity.window.decorView.descendants().filterIsInstance<android.webkit.WebView>()
                    .firstOrNull {
                        val visible = android.graphics.Rect()
                        it.isShown && it.width > 0 && it.progress == 100 && it.getGlobalVisibleRect(visible) &&
                            visible.width() >= it.width * 9 / 10 && visible.height() > 0
                    }
                if (web == null) result.complete("")
                else web.evaluateJavascript("typeof readium !== 'undefined' && document.body && getComputedStyle(document.body).color !== 'rgb(0, 0, 0)' ? document.body.innerText : ''") { encoded ->
                    result.complete(runCatching { org.json.JSONArray("[$encoded]").getString(0) }.getOrDefault(""))
                }
            }
            if (withTimeout(3_000) { result.await() }.contains(expected)) break
            delay(100)
        }
    }
    private fun epubResourcePath(raw:String):List<String> =
        android.net.Uri.parse(raw).encodedPath.orEmpty().split('/').filter {it.isNotEmpty()}.map {android.net.Uri.decode(it)}
    private fun writeOriginalEpub(file: File, fixedPublisherSizes:Boolean=false, chapterCount:Int=2, includeNavigation:Boolean=false,
        metadataDescription:String?=null, metadataSubjects:List<String> = emptyList()) {
        require(chapterCount>0)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String, stored: Boolean = false) {
                val b = text.toByteArray()
                val e = ZipEntry(name)
                if (stored) { e.method = ZipEntry.STORED; e.size = b.size.toLong(); e.crc = CRC32().apply { update(b) }.value }
                zip.putNextEntry(e); zip.write(b); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip", true)
            put("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val chapters=(0 until chapterCount).map {index->when(index) {0->"one";1->"two";else->"chapter${index+1}"}}
            val manifest=chapters.joinToString("") {name->"<item id=\"$name\" href=\"$name.xhtml\" media-type=\"application/xhtml+xml\"/>"}+
                if(includeNavigation) "<item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/>" else ""
            val spine=chapters.joinToString("") {name->"<itemref idref=\"$name\"/>"}
            val packageVersion=if(includeNavigation) "3.0" else "2.0"
            val description=metadataDescription?.let {"<dc:description>$it</dc:description>"}.orEmpty()
            val subjects=metadataSubjects.joinToString("") {"<dc:subject>$it</dc:subject>"}
            put("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="$packageVersion" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>The Quiet Archive</dc:title><dc:creator>Original Arkhive fixture</dc:creator><dc:language>en</dc:language>$description$subjects</metadata><manifest>$manifest</manifest><spine>$spine</spine></package>""")
            if(includeNavigation) put("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc" id="toc"><ol><li><a href="one.xhtml#same-resource-section">Same-resource section</a></li><li><a href="two.xhtml">Second chapter</a></li></ol></nav></body></html>""")
            for (name in chapters) {
                val publisherStyle=if(fixedPublisherSizes) "<style>body{font-size:14px}p{font-size:14px}.fixed-size{font-size:14px}</style>" else ""
                val paragraphs=if(fixedPublisherSizes && name==chapters.last()) {
                    "<p class=\"fixed-size\" style=\"font-size:14px\">Short last chapter.</p>"
                } else (1..80).joinToString("") { if(fixedPublisherSizes) "<p class=\"fixed-size\" style=\"font-size:14px\">Original paragraph $it. A quiet archive keeps the reader's place. This is self-authored fixture text.</p>" else "<p>Original paragraph $it. A quiet archive keeps the reader's place. This is self-authored fixture text.</p>" }
                val section=if(name=="one") "<h2 id=\"same-resource-section\">Original same-resource section</h2>" else ""
                val chapterBody=if(name=="one" && !fixedPublisherSizes)
                    paragraphs.replace("<p>Original paragraph 40.","$section<p>Original paragraph 40.")
                else paragraphs
                put("$name.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Original $name</title>$publisherStyle</head><body><h1>Original $name chapter</h1>$chapterBody</body></html>""")
            }
        }
    }
}
