package mihon.readingapp.compat

import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.appGraph
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OriginalDemoReaderContractTest {
    @Test
    fun originalCatalogToImagesSupportsPagedAndVerticalShapesWithoutInternet() = runBlocking {
        for (source in OriginalDemoSource.createAll()) {
            val catalog = source.getPopularManga(1)
            assertEquals(2, catalog.mangas.size)
            assertFalse(catalog.hasNextPage)
            assertFalse(source.supportsLatest)
            assertTrue(source.getSearchManga(1, "absent", FilterList()).mangas.isEmpty())
            assertTrue(source.getPopularManga(2).mangas.isEmpty())
            for (manga in catalog.mangas) {
                val update = source.getMangaUpdate(manga, emptyList(), true, true)
                assertEquals(3, update.chapters.size)
                val pages = source.getPageList(update.chapters.first())
                assertEquals((0..5).toList(), pages.map { it.index })
                source.getImage(pages.first()).use { response ->
                    assertTrue(response.isSuccessful)
                    val bytes = response.body.bytes()
                    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
                    assertEquals(720, dimensions.outWidth)
                    assertEquals(if (source.id == OriginalDemoSource.MANGA_ID) 1080 else 2160, dimensions.outHeight)
                }
                source.client.newCall(Request.Builder().url(manga.thumbnail_url!!).build()).execute().use { response ->
                    val bytes = response.body.bytes()
                    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
                    assertEquals(480, dimensions.outWidth)
                    assertEquals(720, dimensions.outHeight)
                }
            }
        }
    }

    @Test
    fun offlineImageClientRejectsEveryOutsideHost() {
        val source = OriginalDemoSource(OriginalDemoCatalog.Format.MANGA)
        assertThrows(IOException::class.java) {
            source.client.newCall(Request.Builder().url("https://outside.invalid/page.png").build()).execute().close()
        }
    }

    @Test
    fun retainedReaderPageLoaderProducesStreamsAndReopensFromDiskCacheWithoutSourceCalls() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check("readingapp" in context.packageName.lowercase())
        val isolatedDirectory = File(context.cacheDir, "readingapp-reader-contract-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(context) {
            override fun getCacheDir(): File = isolatedDirectory
        }
        val cache = ChapterCache(isolatedContext, context.appGraph.json)
        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + Dispatchers.IO)
        try {
            for (source in OriginalDemoSource.createAll()) {
                val manga = source.getPopularManga(1).mangas.first()
                val sourceChapter = source.getMangaUpdate(manga, emptyList(), true, true).chapters.first()
                val chapter = Chapter.create().copy(
                    mangaId = -source.id,
                    url = sourceChapter.url,
                    name = sourceChapter.name,
                    chapterNumber = sourceChapter.chapter_number.toDouble(),
                )
                val readerChapter = ReaderChapter(chapter)
                val loader = HttpPageLoader(readerChapter, source, cache, appScope) { true }
                try {
                    val pages = loader.getPages()
                    readerChapter.state = ReaderChapter.State.Loaded(pages)
                    assertEquals((0..5).toList(), pages.map { it.index })
                    pages.forEach { awaitReady(it) }
                    assertStreamDimensions(pages.first(), source.id)
                    assertTrue(pages.all { cache.isImageInCache(requireNotNull(it.imageUrl)) })
                } finally {
                    // This is the actual reader's page-list persistence path, not a test-written cache entry.
                    loader.recycle()
                }
                withTimeout(10_000) { appJob.children.toList().joinAll() }

                val reopenedChapter = ReaderChapter(chapter)
                val cacheOnlySource = object : HttpSource() {
                    override val id = source.id
                    override val name = source.name
                    override val lang = source.lang
                    override val supportsLatest = false
                    override val baseUrl = source.baseUrl
                    override val client: OkHttpClient
                        get() = error("Cached reader reopen must not request a source image")

                    override suspend fun getPageList(chapter: SChapter): List<Page> =
                        error("Cached reader reopen must not request a source chapter")
                }
                val reopenedLoader = HttpPageLoader(reopenedChapter, cacheOnlySource, cache, appScope) { true }
                try {
                    val reopenedPages = reopenedLoader.getPages()
                    reopenedChapter.state = ReaderChapter.State.Loaded(reopenedPages)
                    reopenedPages.forEach { awaitReady(it) }
                    assertEquals(6, reopenedPages.size)
                    assertStreamDimensions(reopenedPages.first(), source.id)
                } finally {
                    reopenedLoader.recycle()
                }
                withTimeout(10_000) { appJob.children.toList().joinAll() }
            }
        } finally {
            appJob.cancel()
            appJob.join()
            isolatedDirectory.deleteRecursively()
        }
    }

    private suspend fun awaitReady(page: ReaderPage) {
        withTimeout(20_000) {
            page.statusFlow.first { state ->
                if (state is Page.State.Error) throw AssertionError("Retained reader loader failed", state.error)
                state == Page.State.Ready
            }
        }
    }

    private fun assertStreamDimensions(page: ReaderPage, sourceId: Long) {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireNotNull(page.stream).invoke().use { stream -> BitmapFactory.decodeStream(stream, null, dimensions) }
        assertEquals(720, dimensions.outWidth)
        assertEquals(if (sourceId == OriginalDemoSource.MANGA_ID) 1080 else 2160, dimensions.outHeight)
    }
}
