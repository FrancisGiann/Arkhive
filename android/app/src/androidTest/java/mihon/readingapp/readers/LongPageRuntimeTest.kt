package mihon.readingapp.readers

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.cash.sqldelight.db.SqlDriver
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonStripImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.*
import mihon.app.di.appGraph
import mihon.data.database.DatabaseRepositoryImpl
import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoImages
import mihon.readingapp.fixtures.OriginalDemoSource
import mihon.readingapp.library.ReadingProfile
import mihon.readingapp.library.ReadingRoom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.UUID
import kotlin.time.Clock

/** Own extreme strips run through the real source, page loader, native activity and renderer. */
@RunWith(AndroidJUnit4::class)
class LongPageRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.appGraph
    private suspend fun fixture(
        mode: ReadingMode,
        sourceId: Long = OriginalDemoSource.MANGA_ID,
        sidePadding: Int = 0,
        disableZoomOut: Boolean = true,
        cropPaged: Boolean = false,
        cropWebtoon: Boolean = false,
        body: suspend (ActivityScenario<ReaderActivity>, Long) -> Unit,
    ) {
        val nonce = UUID.randomUUID().toString()
        val slug = if(sourceId==OriginalDemoSource.WEBTOON_ID) "rainlight-district" else "neon-courier"
        val chapterPath = "/$slug/chapter/1"
        val path = "$chapterPath?qa=$nonce"
        val urls = OriginalDemoCatalog.pageUrls(chapterPath).map { "$it&qa=$nonce" }
        val data = instrumentation.context.assets.open("reader/extreme-tall.png").use { it.readBytes() }
        val prefs = graph.readerPreferences
        val restores = mutableListOf<() -> Unit>()
        fun <T> temporarily(preference: tachiyomi.core.common.preference.Preference<T>, value: T) {
            val before = preference.get(); val existed = preference.isSet(); restores += { if(existed) preference.set(before) else preference.delete() }; preference.set(value)
        }
        temporarily(prefs.imageScaleType, SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        temporarily(prefs.smartLongPageFit, true); temporarily(prefs.webtoonSidePadding, sidePadding); temporarily(prefs.webtoonDisableZoomOut,disableZoomOut)
        temporarily(prefs.cropBordersWebtoon, cropWebtoon); temporarily(prefs.cropBorders, cropPaged)
        temporarily(prefs.dualPageSplitPaged, false); temporarily(prefs.dualPageSplitWebtoon, false)
        var driver: SqlDriver? = null; var inserted: Long? = null
        try {
            OriginalDemoSource.controlledPageLists[path] = urls
            OriginalDemoImages.controlledImage(urls.first(), data)
            lateinit var production: SqlDriver
            val migration = DatabaseRepositoryImpl(lazy { production })
            production = DatabaseBindings.providesSqlDriver(context, migration); driver = production
            val database = DatabaseBindings.providesDatabase(production)
            val mangas = MangaRepositoryImpl(database); val chapters = ChapterRepositoryImpl(database)
            val manga = NetworkToLocalManga(mangas)(Manga.create().copy(source = sourceId,
                url = "/$slug/long-qa-$nonce", title = "Arkhive original long-page QA $nonce", initialized = true,
                favoriteAt = Clock.System.now(), viewerFlags = mode.flagValue.toLong(), chapterFlags = Manga.CHAPTER_SORTING_NUMBER))
            inserted = manga.id
            val chapter = chapters.updateFromRemote(emptyList(), listOf(Chapter.create().copy(mangaId = manga.id,
                url = path, name = "Original extreme strip + normal pages", chapterNumber = 1.0, sourceOrder = 0, dateFetch = Clock.System.now())), emptyList()).single()
            ActivityScenario.launch<ReaderActivity>(ReaderActivity.newIntent(context, manga.id, chapter.id)).use { body(it, chapter.id) }
        } finally {
            OriginalDemoImages.controlledImage(urls.first(), null); OriginalDemoSource.controlledPageLists.remove(path)
            urls.forEach { runCatching { graph.chapterCache.getImageFile(it).delete() } }
            inserted?.let { id -> withContext(Dispatchers.IO) { driver?.execute(null, "DELETE FROM manga WHERE id=?", 1) { bindLong(0, id) }?.await() } }
            restores.asReversed().forEach { it() }; driver?.close()
        }
    }
    private fun assertReaderUsesFullContainerWidth(activity: ReaderActivity) {
        val window=activity.window.decorView
        val reader=activity.findViewById<View>(R.id.reader_container)
        val viewer=activity.findViewById<View>(R.id.viewer_container)
        assertTrue("Window must be laid out",window.width>0)
        assertTrue("Reader container must be laid out",reader.width>0)
        assertTrue("Viewer must be laid out",viewer.width>0)
        assertEquals("Reader must reach both horizontal window edges",window.width,reader.width)
        assertEquals("No app-owned horizontal inset may be added around the native viewer",reader.width,viewer.width)
        val windowPosition=IntArray(2);window.getLocationInWindow(windowPosition)
        val readerPosition=IntArray(2);reader.getLocationInWindow(readerPosition)
        val viewerPosition=IntArray(2);viewer.getLocationInWindow(viewerPosition)
        assertEquals(windowPosition[0],readerPosition[0])
        assertEquals(windowPosition[0]+window.width,readerPosition[0]+reader.width)
        assertEquals("Viewer starts at the reader's left edge",readerPosition[0],viewerPosition[0])
        assertEquals("Viewer reaches the reader's right edge",readerPosition[0]+reader.width,viewerPosition[0]+viewer.width)
    }
    @Test fun pagedVerticalStartsAtWidthAndStepsInsideImageBeforeNormalPage() = runBlocking {
        fixture(ReadingMode.VERTICAL) { reader, chapter ->
            await { var okay = false; reader.onActivity { a -> okay = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().any { it.page.index == 0 && it.isImageReadyForAutoScroll && it.isReadableLongPage } }; okay }
            var before = 0f
            reader.onActivity { a ->
                assertReaderUsesFullContainerWidth(a)
                val holder = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().first { it.page.index == 0 && it.isShown }
                val image = holder.all<SubsamplingScaleImageView>().first()
                assertEquals("Tall paged image view spans the full pager viewport",(a.viewModel.state.value.viewer as PagerViewer).pager.width,image.width)
                assertEquals(image.width.toFloat() / image.sWidth, image.scale, .05f)
                before = image.center!!.y
                (a.viewModel.state.value.viewer as PagerViewer).moveToNext()
                assertEquals(1, a.viewModel.state.value.currentPage)
            }
            await { var moved = false; reader.onActivity { a -> moved = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().first { it.page.index == 0 && it.isShown }.all<SubsamplingScaleImageView>().first().center!!.y > before + 100 }; moved }
            delay(1500)
            screenshot("Arkhive-tall-paged-width")
            reader.onActivity { a -> val image = a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().first { it.page.index == 0 && it.isShown }.all<SubsamplingScaleImageView>().first(); image.setScaleAndCenter(image.width.toFloat()/image.sWidth, PointF(image.sWidth/2f, image.sHeight.toFloat())) }
            delay(300)
            reader.onActivity { a -> (a.viewModel.state.value.viewer as PagerViewer).moveToNext() }
            await { var normal = false; reader.onActivity { a -> normal = a.viewModel.state.value.currentPage == 2 && a.findViewById<View>(android.R.id.content).all<PagerPageHolder>().any { it.page.index == 1 && it.isShown && it.isImageReadyForAutoScroll && !it.isReadableLongPage } }; normal }
        }
    }
    @Test fun continuousLongImageUsesWidthAndBoundedRegionsWhileScrolling() = runBlocking {
        fixture(ReadingMode.WEBTOON, sourceId=OriginalDemoSource.WEBTOON_ID, cropWebtoon=true) { reader, _ ->
            await { var ready = false; reader.onActivity { a -> ready = a.findViewById<View>(android.R.id.content).all<WebtoonStripImageView>().any { it.isShown && it.hasDecodedImage && it.height > 50000 } }; ready }
            reader.onActivity { a ->
                val viewer = a.viewModel.state.value.viewer as WebtoonViewer
                assertReaderUsesFullContainerWidth(a)
                val tile = viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height > 50000 }
                assertEquals(viewer.recycler.width, tile.width)
                assertTrue("The live webtoon renderer keeps the selected crop preference",viewer.config.imageCropBorders)
                assertTrue(tile.cachedTileCount < 12); assertTrue(tile.cachedBitmapBytes <= WebtoonStripImageView.MAX_CACHE_BYTES)
                viewer.recycler.scrollBy(0, 5200)
            }
            await { var ready = false; reader.onActivity { a -> ready = a.findViewById<View>(android.R.id.content).all<WebtoonStripImageView>().any { it.isShown && it.hasDecodedImage && it.height > 50000 } }; ready }
            delay(350)
            screenshot("Arkhive-tall-continuous-regions", checkCoverage=true)
            reader.onActivity { a -> val tile = a.findViewById<View>(android.R.id.content).all<WebtoonStripImageView>().first { it.isShown && it.height > 50000 }; assertTrue(tile.cachedBitmapBytes <= WebtoonStripImageView.MAX_CACHE_BYTES); assertTrue(tile.cachedTileCount < 16) }
        }
    }

    @Test fun visibleFullWidthActionRemovesInsetsAndSurvivesReaderRecreation() = runBlocking {
        fixture(
            mode=ReadingMode.WEBTOON,
            sourceId=OriginalDemoSource.WEBTOON_ID,
            sidePadding=12,
            disableZoomOut=false,
            cropWebtoon=true,
        ) { reader, chapter ->
            val device=UiDevice.getInstance(instrumentation)
            val toolsBefore=graph.libraryToolsStore.state.value
            var profileKey:String?=null
            var oldProfile:ReadingProfile?=null
            try {
            var initialTileDiagnostic="not observed"
            try { await("initial webtoon tile decoded") {
                var ready=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tiles=viewer?.recycler?.all<WebtoonStripImageView>().orEmpty()
                    val tile=tiles.firstOrNull { it.isShown && it.height>50000 }
                    initialTileDiagnostic="chapter=${activity.viewModel.state.value.currentChapter?.chapter?.id},"+
                        "page=${activity.viewModel.state.value.currentPage},viewer=${viewer?.recycler?.width}x${viewer?.recycler?.height},"+
                        "tiles=${tiles.size},visible=${tile?.isShown},decoded=${tile?.hasDecodedImage},tile=${tile?.width}x${tile?.height},"+
                        "offset=${viewer?.recycler?.computeVerticalScrollOffset()},crop=${viewer?.config?.imageCropBorders}"
                    ready=tiles.any { child ->
                            child.isShown && child.hasDecodedImage && child.height>50000
                    }
                }
                ready
            } } catch(failure:AssertionError) {
                throw AssertionError("${failure.message}; $initialTileDiagnostic",failure)
            }
            reader.onActivity { activity ->
                assertReaderUsesFullContainerWidth(activity)
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                assertTrue("Fixture starts with measurable internal side padding",viewer.recycler.sidePaddingPixels>0)
                assertTrue("Crop remains a distinct user choice",viewer.config.imageCropBorders)
                val tile=viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height>50000 }
                assertTrue("Configured internal padding narrows the image before choosing full width",tile.width<viewer.recycler.width)
                // Simulate a user zoom-in before opening the reader controls. The width action
                // must not reset that zoom when it only raises the minimum fit scale.
                viewer.recycler.onScale(1.5f)
                activity.showMenu()
            }
            openReaderSettings(device,reader)
            revealFullWidthAction(device)
            requireNotNull(device.wait(Until.findObject(By.text("Use full available width")),10_000)).click()
            await("selected full-width setting reaches the active Webtoon viewer") {
                var applied=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tile=viewer?.getView()?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.height>50000 }
                    applied=viewer!=null && viewer.recycler.sidePaddingPixels==0 &&
                        activity.appGraph.readerPreferences.webtoonSidePadding.get()==0 &&
                        activity.appGraph.readerPreferences.webtoonDisableZoomOut.get() &&
                        viewer.config.imageCropBorders && tile?.width==viewer.recycler.width &&
                        kotlin.math.abs((viewer.recycler.scaleX)-1.5f)<0.05f
                }
                applied
            }
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Use full available width")),5_000))
            reader.onActivity { activity ->
                activity.hideMenu()
                assertReaderUsesFullContainerWidth(activity)
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                val tile=viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height>50000 }
                assertEquals("Full-width image uses the whole live RecyclerView width",viewer.recycler.width,tile.width)
                assertTrue("Full width does not clear the user's crop choice",viewer.config.imageCropBorders)
                assertEquals("Opening the reader settings must preserve the user's zoom",1.5f,viewer.recycler.scaleX,0.05f)
                assertEquals(chapter,activity.viewModel.state.value.currentChapter?.chapter?.id)
                val mangaId=requireNotNull(activity.viewModel.manga?.id)
                profileKey="manga:$mangaId"
                oldProfile=toolsBefore.profiles[profileKey!!]
                val profile=oldProfile ?: activity.appGraph.libraryToolsStore.profile(profileKey!!)
                activity.appGraph.libraryToolsStore.setProfile(profileKey!!,profile.copy(room=ReadingRoom.WARM_PAPER))
            }

            // A fresh Activity must read the persisted width-fit choice and restore actual image geometry.
            reader.recreate()
            await("recreated Webtoon viewer restores the selected full-width geometry") {
                var restored=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tile=viewer?.getView()?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.hasDecodedImage && it.height>50000 }
                    restored=viewer!=null && activity.viewModel.state.value.currentChapter?.chapter?.id==chapter &&
                        activity.appGraph.readerPreferences.webtoonSidePadding.get()==0 &&
                        activity.appGraph.readerPreferences.webtoonDisableZoomOut.get() &&
                        viewer.recycler.width==activity.findViewById<View>(R.id.reader_container).width &&
                        viewer.config.imageCropBorders && tile?.width==viewer.recycler.width
                }
                restored
            }
            reader.onActivity { activity ->
                assertReaderUsesFullContainerWidth(activity)
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                assertEquals("Reader profile application does not alter persisted full width",0,viewer.recycler.sidePaddingPixels)
                assertTrue(viewer.config.imageCropBorders)
                viewer.recycler.onScale(1.5f)
                activity.showMenu()
            }
            openReaderSettings(device,reader)
            revealFullWidthAction(device)
            requireNotNull(device.wait(Until.findObject(By.text("Use full available width")),10_000))
            device.pressBack()
            reader.onActivity { activity ->
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                activity.hideMenu()
                assertReaderUsesFullContainerWidth(activity)
                assertEquals("Opening controls after applying the series profile must preserve zoom",1.5f,viewer.recycler.scaleX,0.05f)
                assertTrue("Applying the series profile preserves the selected crop state",viewer.config.imageCropBorders)
            }
            } finally {
                profileKey?.let { key -> graph.libraryToolsStore.update { state ->
                    if(oldProfile==null) state.copy(profiles=state.profiles-key)
                    else state.copy(profiles=state.profiles+(key to oldProfile!!))
                } }
            }
        }
    }

    @Test fun previouslySavedFullWidthPreferencesRemainEdgeToEdgeAfterUpgradeAndRecreation() = runBlocking {
        // In 0.3.2 these persisted settings were the user's full-width choice. The old
        // unconditional 20dp Activity margin kept the page inset despite those settings.
        fixture(
            mode=ReadingMode.WEBTOON,
            sourceId=OriginalDemoSource.WEBTOON_ID,
            sidePadding=0,
            disableZoomOut=true,
            cropWebtoon=true,
        ) { reader, chapter ->
            await("legacy full-width webtoon image decoded") {
                var ready=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    ready=viewer?.getView()?.all<WebtoonStripImageView>()?.any { it.isShown && it.hasDecodedImage && it.height>50000 }==true
                }
                ready
            }
            reader.onActivity { activity ->
                val viewer=activity.viewModel.state.value.viewer as WebtoonViewer
                val prefs=activity.appGraph.readerPreferences
                assertTrue("The historical full-width preference is present",prefs.webtoonSidePadding.isSet())
                assertTrue("The historical no-zoom-out preference is present",prefs.webtoonDisableZoomOut.isSet())
                assertEquals(0,prefs.webtoonSidePadding.get())
                assertTrue(prefs.webtoonDisableZoomOut.get())
                assertReaderUsesFullContainerWidth(activity)
                val tile=viewer.getView().all<WebtoonStripImageView>().first { it.isShown && it.height>50000 }
                assertEquals("Previously saved full-width preference fills the native viewport",viewer.recycler.width,tile.width)
                assertTrue("Saved image-crop choice remains independent",viewer.config.imageCropBorders)
                assertEquals(chapter,activity.viewModel.state.value.currentChapter?.chapter?.id)
                viewer.recycler.scrollBy(0,1800)
            }
            await("legacy preference fixture advances within the same chapter") {
                var advanced=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    advanced=viewer!=null && viewer.recycler.computeVerticalScrollOffset()>0 &&
                        activity.viewModel.state.value.currentChapter?.chapter?.id==chapter
                }
                advanced
            }
            var savedOffset=0
            var savedPage=0
            reader.onActivity { activity ->
                val state=activity.viewModel.state.value
                savedOffset=(state.viewer as WebtoonViewer).recycler.computeVerticalScrollOffset()
                savedPage=state.currentPage
            }
            reader.recreate()
            // The existing WebtoonViewer restores the chapter/logical page through moveToPage,
            // which starts a tall page at its top; intra-page pixel offset is not persisted across
            // destroying the Activity. The fit preference is persisted independently.
            var lastRestoreDiagnostic="not observed"
            try { await("recreated reader restores historical full-width preferences and logical page") {
                var restored=false
                reader.onActivity { activity ->
                    val viewer=activity.viewModel.state.value.viewer as? WebtoonViewer
                    val tile=viewer?.getView()?.all<WebtoonStripImageView>()?.firstOrNull { it.isShown && it.hasDecodedImage && it.height>50000 }
                    lastRestoreDiagnostic="chapter=${activity.viewModel.state.value.currentChapter?.chapter?.id},page=${activity.viewModel.state.value.currentPage},"+
                        "viewer=${viewer?.recycler?.width}x${viewer?.recycler?.height},reader=${activity.findViewById<View>(R.id.reader_container).width},"+
                        "sidePadding=${viewer?.recycler?.sidePaddingPixels},tile=${tile?.width}x${tile?.height},"+
                        "offset=${viewer?.recycler?.computeVerticalScrollOffset()},savedOffset=$savedOffset,"+
                        "crop=${viewer?.config?.imageCropBorders},prefs=${activity.appGraph.readerPreferences.webtoonSidePadding.get()}/"+
                        "${activity.appGraph.readerPreferences.webtoonDisableZoomOut.get()}"
                    restored=viewer!=null &&
                        activity.viewModel.state.value.currentChapter?.chapter?.id==chapter &&
                        activity.viewModel.state.value.currentPage==savedPage &&
                        activity.appGraph.readerPreferences.webtoonSidePadding.get()==0 &&
                        activity.appGraph.readerPreferences.webtoonDisableZoomOut.get() &&
                        viewer.config.imageCropBorders && tile?.width==viewer.recycler.width
                }
                restored
            } } catch(failure:AssertionError) {
                throw AssertionError("${failure.message}; $lastRestoreDiagnostic",failure)
            }
            reader.onActivity { activity ->
                assertReaderUsesFullContainerWidth(activity)
                assertEquals("Upgrade compatibility must retain the selected internal padding",0,
                    (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.sidePaddingPixels)
                assertEquals("Upgrade compatibility must not rewrite the legacy preference",0,
                    activity.appGraph.readerPreferences.webtoonSidePadding.get())
                assertEquals("Upgrade compatibility retains the active logical page",savedPage,activity.viewModel.state.value.currentPage)
            }
        }
    }

    private fun screenshot(name: String, checkCoverage: Boolean=false) {
        val dir = File(context.getExternalFilesDir(null), "arkhive-qa").apply { mkdirs() }
        val file=File(dir,"$name.png");assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(file))
        if(checkCoverage) android.graphics.BitmapFactory.decodeFile(file.path).let { bitmap ->
            try { val samples=(120 until bitmap.height-150 step 150).map {bitmap.getPixel(bitmap.width/2,it) and 0x00ffffff}
                assertTrue("Visible long-page pixels must cover the viewport after decoding, not just old tile readiness",samples.count {it!=0}.toDouble()/samples.size>.8)
            } finally {bitmap.recycle()}
        }
    }
    private fun revealFullWidthAction(device:UiDevice) {
        // Settings opens on the reader's Reading mode tab, but explicitly select it so this
        // remains stable if the dialog preserves its last-used tab across future versions.
        device.wait(Until.findObject(By.text("Reading mode")),5_000)?.click()
        val action=By.text("Use full available width")
        // At large text sizes a swipe can carry the action through the viewport between
        // accessibility snapshots. Scroll the actual vertical body in smaller steps and
        // wait for each result instead of immediately sending another fling.
        fun body()=device.findObjects(By.scrollable(true))
            .lastOrNull { it.visibleBounds.height()>device.displayHeight/3 }
        for(direction in listOf(Direction.DOWN,Direction.UP)) {
            var previous=""
            var unchanged=0
            for(attempt in 0 until 20) {
                if(device.wait(Until.findObject(action),800)!=null) return
                val scroll=body() ?: break
                val signature=scroll.findObjects(By.clazz("android.widget.TextView"))
                    .take(4).joinToString { "${it.text}@${it.visibleBounds}" }
                unchanged=if(signature==previous) unchanged+1 else 0
                if(unchanged>=2) break
                previous=signature
                // Compose's virtual ScrollView can return false despite a successful move.
                // Observe the settled visible rows rather than treating that event result as
                // an end-of-list signal. Slow short moves avoid flinging past the action.
                scroll.scroll(direction,.2f,600)
            }
        }
        if(device.findObject(By.text("Use full available width"))==null) {
            val hierarchy=File(context.cacheDir,"full-width-settings-hierarchy.xml")
            device.dumpWindowHierarchy(hierarchy)
            val visibleState=runCatching { hierarchy.readText().take(8000) }.getOrDefault("hierarchy unavailable")
            throw AssertionError("Full-width action is not reachable from the Reading mode settings tab: $visibleState")
        }
    }
    private suspend fun openReaderSettings(device:UiDevice,scenario:ActivityScenario<ReaderActivity>) {
        // The storyboard reader menu intentionally groups the legacy settings bar under
        // Reader tools. Use that visible route instead of searching for a hidden Settings icon.
        val settings=findStableClickable(device,listOf(By.desc("Settings"),By.descContains("Settings")),1_500)
        if(settings!=null) {
            settings.first.click()
            return
        }
        requireStableClickable(device,By.text("⋮"),"reader menu",scenario).click()
        requireStableClickable(device,By.text("Reader tools"),"Reader tools menu item",scenario).click()
        requireStableClickable(device,By.desc("Settings"),"Settings",scenario).click()
    }
    private suspend fun requireStableClickable(
        device:UiDevice,
        selector:androidx.test.uiautomator.BySelector,
        label:String,
        scenario:ActivityScenario<ReaderActivity>,
    ):androidx.test.uiautomator.UiObject2 = findStableClickable(device,listOf(selector))?.first
        ?: run {
            val hierarchy=File(context.cacheDir,"full-width-reader-$label-hierarchy.xml")
            val screenshot=File(context.cacheDir,"full-width-reader-$label-missing.png")
            device.dumpWindowHierarchy(hierarchy)
            device.takeScreenshot(screenshot)
            var readerState="activity unavailable"
            runCatching { scenario.onActivity { activity ->
                val state=activity.viewModel.state.value
                val viewer=state.viewer as? WebtoonViewer
                readerState="menu=${state.menuVisible},dialog=${state.dialog},chapter=${state.currentChapter?.chapter?.id},"+
                    "page=${state.currentPage},viewer=${state.viewer?.javaClass?.simpleName},"+
                    "width=${viewer?.recycler?.width},height=${viewer?.recycler?.height},"+
                    "offset=${viewer?.recycler?.computeVerticalScrollOffset()},crop=${viewer?.config?.imageCropBorders},"+
                    "focus=${activity.window.decorView.hasWindowFocus()}"
            } }
            val visible=runCatching { hierarchy.readText().take(8000) }.getOrDefault("hierarchy unavailable")
            throw AssertionError("Visible $label button stayed absent/unstable; $readerState; " +
                "hierarchy=$visible; screenshot=${screenshot.absolutePath}")
        }
    private suspend fun findStableClickable(
        device:UiDevice,
        selectors:List<androidx.test.uiautomator.BySelector>,
        timeoutMs:Long=8_000,
    ):Pair<androidx.test.uiautomator.UiObject2,String>? {
        var lastBounds:String?=null
        var stableObservations=0
        var diagnostic="label not found"
        var result:Pair<androidx.test.uiautomator.UiObject2,String>?=null
        withTimeoutOrNull(timeoutMs) {
            while(result==null) {
                for(selector in selectors) {
                    val labelNode=device.findObject(selector)
                    val row=runCatching { labelNode?.let(::clickableReaderAncestor) }.getOrNull()
                    diagnostic="label=${automationNodeDiagnostic(labelNode)},row=${automationNodeDiagnostic(row)}"
                    val bounds=runCatching {
                        row?.takeIf { it.isEnabled && it.isClickable }?.visibleBounds
                            ?.takeIf { it.width()>0 }?.flattenToString()
                    }.getOrNull()
                    if(row!=null && bounds!=null) {
                        if(bounds==lastBounds) stableObservations++ else stableObservations=0
                        lastBounds=bounds
                        if(stableObservations>=2) result=row to diagnostic
                    } else {
                        lastBounds=null
                        stableObservations=0
                    }
                    if(result!=null) break
                }
                if(result==null) delay(100)
            }
        }
        return result
    }
    private fun clickableReaderAncestor(label:androidx.test.uiautomator.UiObject2):androidx.test.uiautomator.UiObject2? {
        var node:androidx.test.uiautomator.UiObject2?=label
        repeat(8) {
            val current=node ?: return null
            if(current.isClickable) return current
            node=current.parent
        }
        return null
    }
    private fun automationNodeDiagnostic(node:androidx.test.uiautomator.UiObject2?):String=runCatching {
        node?.let { "enabled=${it.isEnabled},clickable=${it.isClickable},bounds=${it.visibleBounds.flattenToString()}" } ?: "missing"
    }.getOrDefault("stale")
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(100) }
    private suspend fun await(stage:String, timeoutMs:Long=25_000, condition:() -> Boolean) {
        try { withTimeout(timeoutMs) { while(!condition()) delay(100) } }
        catch(error:TimeoutCancellationException) { throw AssertionError("Timed out at $stage",error) }
    }
    private inline fun <reified T : View> View.all(): List<T> { val out = mutableListOf<T>(); val pending = java.util.ArrayDeque<View>(); pending.add(this); while(pending.isNotEmpty()) { val v = pending.removeFirst(); if(v is T) out += v; if(v is ViewGroup) for(i in 0 until v.childCount) pending.add(v.getChildAt(i)) }; return out }
}
