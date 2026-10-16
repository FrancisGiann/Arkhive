@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package mihon.readingapp.books

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoAdvanceResult
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationControls
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationStripControl
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationMode
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutoStepScroller
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationPause
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.domain.book.BookBookmark
import mihon.domain.book.LocalBook
import mihon.domain.book.LocalBookFormat
import mihon.readingapp.books.pdf.IsolatedPdfClient
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import mihon.readingapp.library.ReadingSessionMeter
import mihon.readingapp.library.ReadingRoom
import mihon.readingapp.design.SharedTextAppearancePanel
import mihon.readingapp.design.SharedTextAppearancePreview

private data class PendingBookReturn(
    val targetLocatorJson: String?,
    val targetPdfPage: Int?,
    val priorLocatorJson: String,
)
private data class PendingReturnConsume(val targetLocatorJson: String? = null, val targetPdfPage: Int? = null)
private data class FragmentOnlyReturnTarget(val href: String, val elementId: String)

data class BookReaderUi(
    val title: String = "Opening book…", val subtitle: String = "", val ready: Boolean = false,
    val error: String? = null, val menu: Boolean = true, val options: Boolean = false,
    val autoOptions: Boolean = false,
    val contents: Boolean = false, val bookmarks: Boolean = false, val jump: Boolean = false,
)

/** Restore from durable asset locators; never retain a Publication across a killed process. */
class BookReaderActivity : BaseActivity() {
    companion object {
        const val BOOK_ID = "arkhive_book_id"
        const val START_LOCATOR = "arkhive_start_locator"
        private const val BASE_READIUM_TEXT_SCALE = 1.3125
    }
    private val graph by lazy { applicationContext.appGraph }
    private val prefs by lazy { getSharedPreferences("arkhive_book_reader", Context.MODE_PRIVATE) }
    private lateinit var container: FrameLayout
    private lateinit var readerRoot: FrameLayout
    private var safeTop = 0
    private var safeBottom = 0
    private var safeLeft = 0
    private var safeRight = 0
    private var topChromeHeight = 0
    private var bottomChromeHeight = 0
    private var quietStripHeight = 0
    private var book: LocalBook? = null
    private var publication: Publication? = null
    private var epubBookProgress: EpubBookProgress? = null
    private var epub: EpubNavigatorFragment? = null
    private var pdf: IsolatedPdfClient? = null
    private var pdfImage: SubsamplingScaleImageView? = null
    private var renderedPdf: File? = null
    private var renderJob: Job? = null
    private var saveJob: Job? = null
    private var autoStartJob: Job? = null
    private var lastLocator: String? = null
    private var lastProgress = 0.0
    private var fontPreviewOrigin: Locator? = null
    private var fontPreviewGeneration = 0L
    private var fontRestoreJob: Job? = null
    private var typographyPreviewJob: Job? = null
    private var fontPreviewRestorePending = false
    private var returnLocatorJson by mutableStateOf<String?>(null)
    private var pendingReturnCandidate by mutableStateOf<PendingBookReturn?>(null)
    private var pendingReturnConsume by mutableStateOf<PendingReturnConsume?>(null)
    private var returnSettlementJob: Job? = null
    private var returnSettlementInputSerial = 0L
    private var fontGestureStart: PointF? = null
    private var boundarySerial = 0L
    private var currentHref: String? = null
    private var lastTapRatio = 0.5f
    private var displayedBookPercent by mutableIntStateOf(0)
    private var pageInResource = 0
    private var pagesInResource = 0
    private var edgePageTurnPending = false
    private var edgePageTurnToken = 0L
    private var pdfPage by mutableIntStateOf(0)
    private var pdfPages by mutableIntStateOf(0)
    private var ui by mutableStateOf(BookReaderUi())
    private var fontPercent by mutableDoubleStateOf(100.0)
    private var lineHeight by mutableDoubleStateOf(33.0/21.0)
    private var margin by mutableDoubleStateOf(1.0)
    private var scrolling by mutableStateOf(true)
    private var darkPaper by mutableStateOf(true)
    private var appearanceVersion by mutableIntStateOf(0)
    private var bookmarkItems by mutableStateOf(emptyList<BookBookmark>())
    private val meter by lazy {ReadingSessionMeter(graph.libraryToolsStore,{!graph.basePreferences.incognitoMode.get()},{book?.id?.let {"book:$it"}})}
    private fun room()=book?.id?.let {graph.libraryToolsStore.profile("book:$it").room} ?: graph.libraryToolsStore.state.value.room
    private fun effectiveReaderRoom() = room() ?: if (darkPaper) ReadingRoom.MIDNIGHT_VIOLET else ReadingRoom.WARM_PAPER
    private val autoStepScroller by lazy { ReaderAutoStepScroller(this) }
    private val auto by lazy { BookAutoController(this, { if (ui.ready) boundarySerial else null },
        { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() },
        { book?.format != LocalBookFormat.PDF && scrolling && publication?.metadata?.layout != Layout.FIXED },
        ::advanceAutomatically, boundaryMessage = {
            if (book?.format == LocalBookFormat.PDF) "Book complete. Choose another book yourself."
            else "Chapter complete. Choose the next chapter yourself."
        }, cancelStepAnimation = autoStepScroller::cancel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        enableEdgeToEdge()
        // Skip Fragment restoration; load the saved locator after opening the private asset instead.
        super.onCreate(null)
        fontPercent = prefs.getFloat("font", 100f).toDouble().coerceIn(80.0, 220.0)
        lineHeight = prefs.getFloat("line", (33f/21f)).toDouble().coerceIn(1.0, 2.5)
        margin = prefs.getFloat("margin", 1f).toDouble().coerceIn(0.5, 2.5)
        scrolling = prefs.getBoolean("scroll", true)
        darkPaper = prefs.getBoolean("dark", true)
        val root = FrameLayout(this)
        readerRoot = root
        container = FrameLayout(this).apply { id = R.id.book_reader_content }
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        val overlay = ComposeView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            safeTop = safe.top; safeBottom = safe.bottom; safeLeft = safe.left; safeRight = safe.right
            updateReaderViewport()
            insets
        }
        ViewCompat.requestApplyInsets(root)
        overlay.setComposeContent { Chrome() }
        lifecycleScope.launch {
            try {
                val id = intent.getStringExtra(BOOK_ID) ?: error("Choose a book from your Library.")
                val selected = withContext(Dispatchers.IO) { graph.localBookRepository.get(id) }
                    ?: error("This book is no longer in your Library.")
                book = selected; lastLocator = selected.locator; lastProgress = selected.progression
                returnLocatorJson = prefs.getString(returnPreferenceKey(selected.id), null)?.takeIf(::isValidReturnLocator)
                val profile=graph.libraryToolsStore.profile("book:${selected.id}")
                profile.layout?.let {scrolling=it=="scroll"}
                profile.brightnessPercent?.let {percent->window.attributes=window.attributes.apply {screenBrightness=percent/100f}}
                auto.bindSeries("book:${selected.id}")
                ui = ui.copy(title = selected.title)
                val asset = graph.localBookImporter.ensureCurrentAsset(selected)
                val initial = intent.getStringExtra(START_LOCATOR) ?: selected.locator
                if (selected.format == LocalBookFormat.PDF) openPdf(asset, initial) else openEpub(asset, initial)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                ui = ui.copy(error = error.message ?: "This book could not be opened.", ready = false)
            }
        }
    }

    private suspend fun openEpub(file: File, saved: String?) {
        val (pub, progress) = withContext(Dispatchers.IO) {
            val opened = LocalEpubEngine(this@BookReaderActivity).open(file)
            val positionGroups = opened.positionsByReadingOrder()
            val bookProgress = if (positionGroups.size == opened.readingOrder.size) {
                var positionOffset = 0
                val resources = opened.readingOrder.mapIndexed { index, link ->
                    val positions = positionGroups[index]
                    val count = positions.size
                    val firstPosition = positions.firstOrNull()?.locations?.position
                    val lastPosition = positions.lastOrNull()?.locations?.position
                    val areSequential = positions.withIndex().all { (withinResource, locator) ->
                        locator.locations.position == positionOffset + withinResource + 1
                    }
                    EpubProgressResource(link.href.toString(), count, firstPosition, lastPosition, areSequential)
                        .also { positionOffset += count }
                }
                EpubBookProgress.fromReadingOrder(resources)
            } else {
                EpubBookProgress.fromReadingOrder(emptyList())
            }
            opened to bookProgress
        }
        publication = pub
        epubBookProgress = progress
        val initial = saved?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val factory = EpubNavigatorFactory(pub)
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(initial,
            initialPreferences = readerPreferences(),
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    pageInResource = pageIndex; pagesInResource = totalPages
                    edgePageTurnToken++
                    edgePageTurnPending = false
                }
                override fun onPageLoaded() { applyReadingTypography() }
            }, configuration = EpubNavigatorFragment.Configuration {
                disablePageTurnsWhileScrolling = true
                servedAssets = listOf("readium/fonts/arkhive-.*")
                readiumCssRsProperties = RsProperties(pageGutter = Length.Px(31.0))
                addFontFamilyDeclaration(FontFamily("Arkhive Serif"), alternates = listOf(FontFamily.SERIF)) {
                    addFontFace { addSource("readium/fonts/arkhive-noto-serif-regular.ttf") }
                }
                addFontFamilyDeclaration(FontFamily("Arkhive Sans"), alternates = listOf(FontFamily.SANS_SERIF)) {
                    addFontFace { addSource("readium/fonts/arkhive-noto-sans-regular.ttf") }
                }
            })
        val navigator = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        epub = navigator
        supportFragmentManager.commit { setReorderingAllowed(true); replace(container.id, navigator, "arkhive_epub") }
        navigator.viewLifecycleOwnerLiveData.observe(this) { owner ->
            if (owner == null) return@observe
            navigator.publicationView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> hardenWebViews(view) }
            hardenWebViews(navigator.publicationView)
            navigator.addInputListener(object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    val width = navigator.publicationView.width.coerceAtLeast(1)
                    val ratio = event.point.x / width
                    val pagedEpub = !scrolling || pub.metadata.layout == Layout.FIXED
                    val rtl = navigator.settings.value.readingProgression == ReadingProgression.RTL
                    if (pagedEpub && ratio < 0.12f) turnPageWithinResource(forward = rtl)
                    else if (pagedEpub && ratio > 0.88f) turnPageWithinResource(forward = !rtl)
                    else ui = ui.copy(menu = !ui.menu)
                    return true
                }
            })
            owner.lifecycleScope.launch {
                navigator.currentLocator.collect { locator ->
                    val href = locator.href.toString().substringBefore('#')
                    val previewOrigin = fontPreviewOrigin
                    if (previewOrigin != null && !sameFontResource(previewOrigin.href.toString(), locator.href.toString())) {
                        cancelFontPreviewForUserInput()
                    }
                    if (fontPreviewOrigin != null) return@collect
                    if (currentHref != href) {
                        if (currentHref != null) auto.pause(ReaderAutomationPause.CHAPTER_END)
                        edgePageTurnToken++
                        edgePageTurnPending = false
                        currentHref = href; boundarySerial++; applyReadingTypography()
                    }
                    lastLocator = locator.toJSON().toString()
                    lastProgress = publicationProgress(locator) ?: 0.0
                    completePendingEpubReturnRestore(locator)
                    val percent=(lastProgress*100).toInt().coerceIn(0,100)
                    if(displayedBookPercent!=percent) displayedBookPercent=percent
                    ui = ui.copy(ready = true, subtitle = "${(lastProgress * 100).toInt()}% · ${locator.title ?: "Position ${locator.locations.position ?: 1}"}")
                    if(hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) meter.resume()
                    if((locator.locations.progression ?: 0.0)>=.999) meter.complete(href)
                    persistSoon()
                }
            }
            auto.bind("EPUB_${scrolling}")
        }
    }

    private fun turnPageWithinResource(forward: Boolean) {
        cancelFontPreviewForUserInput()
        val navigator = epub ?: return
        if (edgePageTurnPending || pagesInResource <= 0) return
        val targetPage = pageInResource + if (forward) 1 else -1
        if (targetPage !in 0 until pagesInResource) return
        val token = ++edgePageTurnToken
        edgePageTurnPending = true
        val moved = if (forward) navigator.goForward(ReaderAutoStepScroller.animationsAllowed(this))
        else navigator.goBackward(ReaderAutoStepScroller.animationsAllowed(this))
        if (!moved) {
            if (edgePageTurnToken == token) edgePageTurnPending = false
            return
        }
        window.decorView.postDelayed({
            if (edgePageTurnToken == token) edgePageTurnPending = false
        }, 900L)
    }

    private suspend fun openPdf(file: File, saved: String?) {
        pdf = IsolatedPdfClient.open(this, file)
        pdfPages = requireNotNull(pdf).pageCount
        val location = saved?.let { runCatching { JSONObject(it) }.getOrNull() }
        pdfPage = (location?.optInt("page", 0) ?: 0).coerceIn(0, pdfPages - 1)
        val view = SubsamplingScaleImageView(this).apply {
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setOnClickListener {
                when {
                    lastTapRatio < 0.3f -> manualBackward()
                    lastTapRatio > 0.7f -> manualForward()
                    else -> ui = ui.copy(menu = !ui.menu)
                }
            }
            setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                override fun onScaleChanged(newScale: Float, origin: Int) { savePdfLocation() }
                override fun onCenterChanged(newCenter: PointF?, origin: Int) { savePdfLocation() }
            })
        }
        pdfImage = view; container.addView(view, FrameLayout.LayoutParams(-1, -1))
        auto.bind("PDF")
        renderPdf(location)
    }

    private fun renderPdf(restore: JSONObject? = null, pausePlayback: Boolean = true) {
        if (pausePlayback) auto.pause()
        renderJob?.cancel()
        ui = ui.copy(ready = false, subtitle = "Page ${pdfPage + 1} of $pdfPages")
        val requested = pdfPage
        renderJob = lifecycleScope.launch {
            try {
                val image = requireNotNull(pdf).render(requested, resources.displayMetrics.widthPixels.coerceAtMost(1600))
                if (requested != pdfPage) { image.delete(); return@launch }
                val previous = renderedPdf
                renderedPdf = image
                pdfImage?.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        val view = pdfImage ?: return
                        restore?.let {
                            val zoom = it.optDouble("zoom", 1.0).coerceIn(1.0, 8.0).toFloat()
                            val x = it.optDouble("x", 0.5).coerceIn(0.0, 1.0).toFloat()
                            val y = it.optDouble("y", 0.5).coerceIn(0.0, 1.0).toFloat()
                            view.setScaleAndCenter(view.minScale * zoom, PointF(x * view.sWidth, y * view.sHeight))
                        }
                        previous?.delete()
                        ui = ui.copy(ready = true)
                        if(hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) meter.resume()
                        if(pdfPage==pdfPages-1) meter.complete("pdf:${pdfPage}")
                        savePdfLocation()
                        completePendingPdfReturnRestore()
                    }
                    override fun onImageLoadError(e: Exception?) { ui = ui.copy(error = "The PDF page could not be displayed.", ready = false) }
                })
                pdfImage?.setImage(ImageSource.uri(this@BookReaderActivity, android.net.Uri.fromFile(image)))
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                ui = ui.copy(error = "The PDF renderer stopped. Reopen this book to try again.", ready = false)
            }
        }
    }

    private fun readerPreferences(): EpubPreferences {
        val selectedRoom = effectiveReaderRoom()
        return EpubPreferences(
            // Readium's fontSize is a scale (1.0 = 100%). Preserve the 21px app baseline
            // without overriding Readium CSS's fixed base font-size variable.
            fontSize = fontPercent.coerceIn(80.0, 220.0) / 100.0 * BASE_READIUM_TEXT_SCALE,
            lineHeight = lineHeight, pageMargins = margin,
            fontFamily = FontFamily("Arkhive Serif"), scroll = scrolling, publisherStyles = false,
            paragraphIndent = 0.0, paragraphSpacing = .95,
            backgroundColor = org.readium.r2.navigator.preferences.Color(selectedRoom.paper),
            textColor = org.readium.r2.navigator.preferences.Color(selectedRoom.text),
            theme = if (selectedRoom == ReadingRoom.WARM_PAPER) Theme.LIGHT else Theme.DARK,
        )
    }
    private fun applyReadingTypography() {
        if (publication?.metadata?.layout == Layout.FIXED) return
        val navigator = epub ?: return
        lifecycleScope.launch {
            runCatching { navigator.evaluateJavascript(InkBookTypography.script) }
                .onFailure { android.util.Log.w("Arkhive", "Reading stylesheet could not be applied", it) }
        }
    }
    private fun applyPreferences() {
        typographyPreviewJob?.cancel(); typographyPreviewJob = null
        auto.pause(); epub?.submitPreferences(readerPreferences())
        val selected=book?.id?.let {"book:$it"}
        val profile=selected?.let {graph.libraryToolsStore.profile(it)}
        val editor=prefs.edit().putFloat("font", fontPercent.toFloat()).putFloat("line", lineHeight.toFloat())
            .putFloat("margin", margin.toFloat()).putBoolean("dark", darkPaper)
        if (profile != null && selected != null) {
            graph.libraryToolsStore.setProfile(selected, profile.copy(
                layout = profile.layout?.let { if (scrolling) "scroll" else "pages" },
            ))
            if (profile.layout == null) editor.putBoolean("scroll", scrolling)
        } else editor.putBoolean("scroll",scrolling)
        editor.apply()
        appearanceVersion++
        if (book?.format != LocalBookFormat.PDF) auto.bind("EPUB_$scrolling")
    }

    private fun previewFontSize(value: Float) {
        beginTypographyPreview()
        fontPercent = value.toDouble()
        scheduleTypographyPreview()
    }

    private fun previewLineHeight(value: Float) { beginTypographyPreview(); lineHeight = value.toDouble(); scheduleTypographyPreview() }
    private fun previewMargins(value: Float) { beginTypographyPreview(); margin = value.toDouble(); scheduleTypographyPreview() }

    private fun beginTypographyPreview() {
        val navigator = epub ?: return
        if (book?.format != LocalBookFormat.EPUB || publication?.metadata?.layout == Layout.FIXED) return
        auto.pause()
        if (fontPreviewOrigin == null) fontPreviewOrigin = navigator.currentLocator.value
        fontPreviewGeneration++
        fontRestoreJob?.cancel(); fontRestoreJob = null
        fontPreviewRestorePending = false
    }

    private fun scheduleTypographyPreview() {
        typographyPreviewJob?.cancel()
        typographyPreviewJob = lifecycleScope.launch {
            delay(70)
            if (fontPreviewOrigin != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                epub?.submitPreferences(readerPreferences())
            }
        }
    }

    private fun finishFontSizePreview() {
        applyPreferences()
        val origin = fontPreviewOrigin ?: return
        val navigator = epub ?: return
        val generation = fontPreviewGeneration
        launchFontAnchorRestore(origin, navigator, generation, readerPreferences())
    }

    private fun preserveTypographyAnchorForPreferenceChange(): Locator? {
        val origin = fontPreviewOrigin
        typographyPreviewJob?.cancel(); typographyPreviewJob = null
        fontRestoreJob?.cancel(); fontRestoreJob = null
        fontPreviewGeneration++
        fontPreviewRestorePending = false
        return origin
    }

    private fun restoreTypographyAnchorAfterPreferenceChange(origin: Locator?) {
        if (origin == null) return
        val navigator = epub ?: return
        launchFontAnchorRestore(origin, navigator, fontPreviewGeneration, readerPreferences())
    }

    private fun launchFontAnchorRestore(
        origin: Locator,
        navigator: EpubNavigatorFragment,
        generation: Long,
        expectedPreferences: EpubPreferences,
    ) {
        if (generation != fontPreviewGeneration || fontPreviewOrigin != origin) return
        fontRestoreJob?.cancel()
        fontPreviewRestorePending = false
        fontRestoreJob = lifecycleScope.launch {
            try {
                val reflowSettled = awaitFontReflowSettled(navigator, origin, expectedPreferences, generation)
                if (generation != fontPreviewGeneration || fontPreviewOrigin != origin) return@launch
                if (!reflowSettled) {
                    deferFontAnchorRestore(origin, generation, "EPUB font reflow did not settle")
                    return@launch
                }
                if (!sameFontResource(navigator.currentLocator.value.href.toString(), origin.href.toString())) {
                    cancelFontPreviewForUserInput()
                    return@launch
                }
                val moved = navigator.go(origin, false)
                val anchorRestored = if (scrolling) awaitFontAnchorRestored(navigator, origin, generation)
                    else awaitPagedLocatorRestored(navigator, origin, generation, expectedPreferences)
                if (!moved || !anchorRestored) {
                    deferFontAnchorRestore(origin, generation, "EPUB did not settle at its saved locator after font reflow")
                    return@launch
                }
                if (generation != fontPreviewGeneration || fontPreviewOrigin != origin) return@launch

                fontPreviewOrigin = null
                fontPreviewRestorePending = false
                fontRestoreJob = null
                val restored = navigator.currentLocator.value
                lastLocator = restored.toJSON().toString()
                lastProgress = publicationProgress(restored) ?: lastProgress
                displayedBookPercent = (lastProgress * 100).toInt().coerceIn(0, 100)
                persistSoon()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                deferFontAnchorRestore(origin, generation, "EPUB font anchor restore failed", error)
            }
        }
    }

    private fun deferFontAnchorRestore(origin: Locator, generation: Long, message: String, error: Throwable? = null) {
        if (generation != fontPreviewGeneration || fontPreviewOrigin != origin) return
        if (error == null) android.util.Log.w("Arkhive", message) else android.util.Log.w("Arkhive", message, error)
        fontRestoreJob = null
        fontPreviewRestorePending = true
    }

    private fun resumePendingFontAnchorRestore() {
        val origin = fontPreviewOrigin ?: return
        if (!ui.ready || !fontPreviewRestorePending || fontRestoreJob?.isActive == true ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !hasWindowFocus()) return
        val navigator = epub ?: return
        launchFontAnchorRestore(origin, navigator, fontPreviewGeneration, readerPreferences())
    }

    private suspend fun awaitFontReflowSettled(
        navigator: EpubNavigatorFragment,
        origin: Locator,
        expectedPreferences: EpubPreferences,
        generation: Long,
    ): Boolean {
        var previous: String? = null
        var stableObservations = 0
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (generation != fontPreviewGeneration || fontPreviewOrigin == null) return false
            if (sameTypographySettings(navigator, expectedPreferences)) {
                val web = visibleWebView(navigator.publicationView)
                val raw = try {
                    navigator.evaluateJavascript("""
                        JSON.stringify({href:location.href,font:getComputedStyle(document.querySelector('p')||document.body).fontSize,
                          line:getComputedStyle(document.querySelector('p')||document.body).lineHeight,
                          marginLeft:getComputedStyle(document.body).paddingLeft,marginRight:getComputedStyle(document.body).paddingRight,
                          width:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight,
                          viewport:innerHeight,scrollY:scrollY})
                    """.trimIndent())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val dom = decodeFontProbe(raw)
                val href = dom?.optString("href", "")
                val fontPx = dom?.optString("font", "")?.removeSuffix("px")?.toDoubleOrNull() ?: 0.0
                val linePx = dom?.optString("line", "")?.removeSuffix("px")?.toDoubleOrNull() ?: 0.0
                val marginLeftPx = dom?.optString("marginLeft", "")?.removeSuffix("px")?.toDoubleOrNull() ?: -1.0
                val marginRightPx = dom?.optString("marginRight", "")?.removeSuffix("px")?.toDoubleOrNull() ?: -1.0
                val width = dom?.optDouble("width", 0.0) ?: 0.0
                val height = dom?.optDouble("height", 0.0) ?: 0.0
                val viewport = dom?.optDouble("viewport", 0.0) ?: 0.0
                val scrollY = dom?.optDouble("scrollY", -1.0) ?: -1.0
                val loadedWeb = web?.takeIf { it.progress == 100 && it.width > 0 && it.height > 0 }
                val valid = loadedWeb != null &&
                    sameFontResource(origin.href.toString(), href.orEmpty()) && fontPx > 0.0 && linePx > 0.0 &&
                    marginLeftPx >= 0.0 && marginRightPx >= 0.0 &&
                    sameFontResource(origin.href.toString(), navigator.currentLocator.value.href.toString()) &&
                    width > 0.0 && height > 0.0 && viewport > 0.0 && scrollY >= 0.0
                val signature = if (valid) {
                    val currentWeb = requireNotNull(loadedWeb)
                    "$href|$fontPx|$linePx|$marginLeftPx|$marginRightPx|$width|$height|$viewport|$scrollY|${currentWeb.width}|${currentWeb.height}|${currentWeb.contentHeight}|${currentWeb.scrollY}"
                } else null
                if (signature != null && signature == previous) stableObservations++ else stableObservations = 0
                if (stableObservations >= 2) return true
                previous = signature
            } else {
                previous = null
                stableObservations = 0
            }
            delay(120)
        }
        return false
    }

    private suspend fun awaitFontAnchorRestored(
        navigator: EpubNavigatorFragment,
        origin: Locator,
        generation: Long,
    ): Boolean {
        var previous: String? = null
        var stableObservations = 0
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (generation != fontPreviewGeneration || fontPreviewOrigin == null) return false
            val raw = try {
                navigator.evaluateJavascript("""
                    JSON.stringify({href:location.href,y:scrollY,height:document.documentElement.scrollHeight,
                      viewport:innerHeight,content:document.body?.innerText?.slice(0,80)})
                """.trimIndent())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val sample = decodeFontProbe(raw)
            val href = sample?.optString("href", "")?.substringBefore('#')
            val height = sample?.optDouble("height", 0.0) ?: 0.0
            val viewport = sample?.optDouble("viewport", 0.0) ?: 0.0
            val y = sample?.optDouble("y", -1.0) ?: -1.0
            val content = sample?.optString("content", "").orEmpty().trim()
            val maxScroll = (height - viewport).coerceAtLeast(0.0)
            val tolerance = maxOf(48.0, viewport * .10)
            val hrefMatches = sameFontResource(origin.href.toString(), href.orEmpty()) &&
                sameFontResource(origin.href.toString(), navigator.currentLocator.value.href.toString())
            val validLayout = content.isNotEmpty() && height > 0.0 && viewport > 0.0 && y >= 0.0 && y <= maxScroll + tolerance
            val targetY = when {
                maxScroll <= 0.0 -> 0.0
                origin.locations.progression != null ->
                    (origin.locations.progression!!.coerceIn(0.0, 1.0) * height).coerceIn(0.0, maxScroll)
                else -> Double.NaN
            }
            val positionMatches = validLayout && !targetY.isNaN() && kotlin.math.abs(y - targetY) <= tolerance
            val signature = if (hrefMatches && positionMatches) "$href|$height|$viewport|$y" else null
            if (signature != null && signature == previous) stableObservations++ else stableObservations = 0
            if (stableObservations >= 2) return true
            previous = signature
            delay(120)
        }
        return false
    }

    private suspend fun awaitPagedLocatorRestored(
        navigator: EpubNavigatorFragment,
        origin: Locator,
        generation: Long,
        expectedPreferences: EpubPreferences,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        var stable = 0
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (generation != fontPreviewGeneration || fontPreviewOrigin == null) return false
            val restored = locatorMatches(navigator.currentLocator.value, origin.toJSON().toString())
            stable = if (restored && sameTypographySettings(navigator, expectedPreferences)) stable + 1 else 0
            if (stable >= 2) return true
            delay(100)
        }
        return false
    }

    private fun sameTypographySettings(navigator: EpubNavigatorFragment, expected: EpubPreferences): Boolean {
        val applied = navigator.settings.value
        fun matches(actual: Double?, target: Double?): Boolean =
            target == null || actual != null && kotlin.math.abs(actual - target) < .0001
        return matches(applied.fontSize, expected.fontSize) &&
            matches(applied.lineHeight, expected.lineHeight) &&
            matches(applied.pageMargins, expected.pageMargins)
    }

    private fun decodeFontProbe(raw: String?): JSONObject? = runCatching {
        when (val value = org.json.JSONTokener(raw ?: "null").nextValue()) {
            is JSONObject -> value
            is String -> JSONObject(value)
            else -> null
        }
    }.getOrNull()

    private fun sameFontResource(locatorHref: String, renderedHref: String): Boolean {
        fun path(value: String): String = android.net.Uri.parse(value).pathSegments
            .flatMap { it.split('/') }
            .filter(String::isNotEmpty)
            .joinToString("/")
        val locatorPath = path(locatorHref)
        val renderedPath = path(renderedHref)
        return locatorPath.isNotBlank() && renderedPath.isNotBlank() &&
            (renderedPath == locatorPath || renderedPath.endsWith("/$locatorPath"))
    }

    private fun returnPreferenceKey(bookId: String) = "book-return:$bookId"
    private fun fragmentOnlyReturnTarget(raw: String?): FragmentOnlyReturnTarget? {
        val json = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
        val locations = json.optJSONObject("locations") ?: return null
        if (listOf("position", "progression", "totalProgression").any { key ->
                locations.has(key) && !locations.isNull(key)
            }) return null
        // Readium's Locations.fromJSON consumes `fragments` through its remove=true JSON helper.
        val fromLocations = locations.optJSONArray("fragments")?.let { fragments ->
            (0 until fragments.length()).mapNotNull { fragments.optString(it).takeIf(String::isNotBlank) }.firstOrNull()
        }
        val locator = Locator.fromJSON(json) ?: return null
        val fromHref = android.net.Uri.parse(locator.href.toString()).fragment
        val elementId = fromLocations ?: fromHref ?: return null
        return elementId.takeIf(String::isNotBlank)?.let { FragmentOnlyReturnTarget(locator.href.toString(), it) }
    }

    private fun sameExactPackageResource(firstHref: String, secondHref: String): Boolean {
        fun path(value: String) = android.net.Uri.parse(value).pathSegments.filter(String::isNotBlank)
        val firstPath = path(firstHref)
        return firstPath.isNotEmpty() && firstPath == path(secondHref)
    }

    private fun visibleWebViewsForResource(view: View, expectedHref: String): List<WebView> {
        val found = mutableListOf<WebView>()
        fun visit(candidate: View) {
            if (candidate is WebView && candidate.isShown && sameExactPackageResource(expectedHref, candidate.url.orEmpty())) {
                val visibleBounds = android.graphics.Rect()
                if (container.width > 0 && candidate.getGlobalVisibleRect(visibleBounds) &&
                    visibleBounds.width() >= container.width * 9 / 10 && visibleBounds.height() > 0
                ) found += candidate
            }
            if (candidate is ViewGroup) for (index in 0 until candidate.childCount) visit(candidate.getChildAt(index))
        }
        visit(view)
        return found.take(2)
    }

    private suspend fun fragmentTargetVisible(
        navigator: EpubNavigatorFragment,
        target: FragmentOnlyReturnTarget,
        expectedBookId: String,
    ): Boolean {
        if (book?.id != expectedBookId || epub !== navigator || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val actual = navigator.currentLocator.value
        if (!sameExactPackageResource(target.href, actual.href.toString())) return false
        for (web in visibleWebViewsForResource(navigator.publicationView, target.href)) {
            if (book?.id != expectedBookId || epub !== navigator ||
                !sameExactPackageResource(target.href, web.url.orEmpty())
            ) return false
            val callback = CompletableDeferred<String?>()
            val quotedId = JSONObject.quote(target.elementId)
            val script = """JSON.stringify((()=>{const e=document.getElementById($quotedId);const r=e?e.getBoundingClientRect():null;return {href:location.href,found:!!e,left:r?r.left:-1,right:r?r.right:-1,top:r?r.top:-1,bottom:r?r.bottom:-1,width:innerWidth,height:innerHeight,visible:!!e&&r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight}})())"""
            web.evaluateJavascript(script) { raw -> if (!callback.isCompleted) callback.complete(raw) }
            val raw = kotlinx.coroutines.withTimeoutOrNull(350) { callback.await() } ?: continue
            val probe = decodeFontProbe(raw) ?: continue
            if (book?.id == expectedBookId && epub === navigator &&
                sameExactPackageResource(target.href, probe.optString("href")) &&
                probe.optBoolean("found") && probe.optBoolean("visible") &&
                probe.optDouble("width", 0.0) > 0.0 && probe.optDouble("height", 0.0) > 0.0
            ) return true
        }
        return false
    }

    private fun isValidReturnLocator(raw: String): Boolean = runCatching {
        when (book?.format) {
            LocalBookFormat.PDF -> JSONObject(raw).optInt("page", -1) >= 0
            else -> Locator.fromJSON(JSONObject(raw)) != null
        }
    }.getOrDefault(false)

    private fun locatorMatches(actual: Locator, expectedJson: String): Boolean = runCatching {
        val expected = Locator.fromJSON(JSONObject(expectedJson)) ?: return false
        if (!sameFontResource(expected.href.toString(), actual.href.toString())) return false
        val expectedHrefFragment=android.net.Uri.parse(expected.href.toString()).fragment
        val actualHrefFragment=android.net.Uri.parse(actual.href.toString()).fragment
        if ((expectedHrefFragment!=null || actualHrefFragment!=null) && expectedHrefFragment!=actualHrefFragment) return false
        val expectedFragments=JSONObject(expectedJson).optJSONObject("locations")?.optJSONArray("fragments")
        val actualFragments=JSONObject(actual.toJSON().toString()).optJSONObject("locations")?.optJSONArray("fragments")
        fun fragmentList(value:org.json.JSONArray?):List<String> = value?.let {array->
            (0 until array.length()).mapNotNull {index->array.optString(index).takeIf(String::isNotBlank)}
        }.orEmpty()
        val expectedFragmentList=fragmentList(expectedFragments)
        val actualFragmentList=fragmentList(actualFragments)
        if ((expectedFragmentList.isNotEmpty() || actualFragmentList.isNotEmpty()) && expectedFragmentList!=actualFragmentList) return false
        val expectedPosition = expected.locations.position
        val actualPosition = actual.locations.position
        val expectedProgression = expected.locations.progression
        val actualProgression = actual.locations.progression
        val expectedTotalProgression = expected.locations.totalProgression
        val actualTotalProgression = actual.locations.totalProgression
        when {
            expectedProgression != null && actualProgression != null ->
                kotlin.math.abs(expectedProgression - actualProgression) <= if (scrolling) .02 else .04
            expectedTotalProgression != null && actualTotalProgression != null ->
                kotlin.math.abs(expectedTotalProgression - actualTotalProgression) <= if (scrolling) .02 else .04
            expectedPosition != null && actualPosition != null -> actualPosition == expectedPosition
            else -> expected.href.toString() == actual.href.toString() && expectedFragmentList==actualFragmentList
        }
    }.getOrDefault(false)

    private fun completePendingEpubReturnRestore(actual: Locator) {
        val pending = pendingReturnCandidate
        if (pendingReturnCandidate === pending && pending?.targetLocatorJson?.let { locatorMatches(actual, it) } == true) {
            val selected = book ?: return
            if (!graph.basePreferences.incognitoMode.get()) {
                prefs.edit().putString(returnPreferenceKey(selected.id), pending.priorLocatorJson).apply()
            }
            returnLocatorJson = pending.priorLocatorJson
            pendingReturnCandidate = null
            returnSettlementJob?.cancel(); returnSettlementJob = null
        }
        val consume = pendingReturnConsume
        if (pendingReturnConsume === consume && consume?.targetLocatorJson?.let { locatorMatches(actual, it) } == true) {
            if (!graph.basePreferences.incognitoMode.get()) {
                book?.let { prefs.edit().remove(returnPreferenceKey(it.id)).apply() }
            }
            returnLocatorJson = null
            pendingReturnConsume = null
            returnSettlementJob?.cancel(); returnSettlementJob = null
        }
    }

    private fun completePendingPdfReturnRestore() {
        val pending = pendingReturnCandidate
        if (pending?.targetPdfPage == pdfPage) {
            if (!graph.basePreferences.incognitoMode.get()) {
                book?.let { prefs.edit().putString(returnPreferenceKey(it.id), pending.priorLocatorJson).apply() }
            }
            returnLocatorJson = pending.priorLocatorJson
            pendingReturnCandidate = null
            returnSettlementJob?.cancel(); returnSettlementJob = null
        }
        val consume = pendingReturnConsume
        if (consume?.targetPdfPage == pdfPage) {
            if (!graph.basePreferences.incognitoMode.get()) {
                book?.let { prefs.edit().remove(returnPreferenceKey(it.id)).apply() }
            }
            returnLocatorJson = null
            pendingReturnConsume = null
            returnSettlementJob?.cancel(); returnSettlementJob = null
        }
    }

    private fun watchReturnSettlement() {
        returnSettlementJob?.cancel()
        val candidate = pendingReturnCandidate
        val consume = pendingReturnConsume
        val target = fragmentOnlyReturnTarget(candidate?.targetLocatorJson ?: consume?.targetLocatorJson)
        val expectedBookId = book?.id
        val inputSerial = returnSettlementInputSerial
        returnSettlementJob = lifecycleScope.launch {
            val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
            if (target != null && expectedBookId != null && (candidate != null || consume != null)) {
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    if (book?.id != expectedBookId || returnSettlementInputSerial != inputSerial ||
                        (candidate != null && pendingReturnCandidate !== candidate) ||
                        (consume != null && pendingReturnConsume !== consume) ||
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) break
                    val navigator = epub
                    val actual = navigator?.currentLocator?.value
                    if (navigator != null && actual != null &&
                        sameExactPackageResource(target.href, actual.href.toString()) &&
                        fragmentTargetVisible(navigator, target, expectedBookId)
                    ) {
                            if (android.os.SystemClock.uptimeMillis() >= deadline ||
                                book?.id != expectedBookId || epub !== navigator ||
                                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                returnSettlementInputSerial != inputSerial ||
                                (candidate != null && pendingReturnCandidate !== candidate) ||
                                (consume != null && pendingReturnConsume !== consume) ||
                                !sameExactPackageResource(target.href, navigator.currentLocator.value.href.toString())
                            ) break
                            if (candidate != null && pendingReturnCandidate === candidate) {
                                if (!graph.basePreferences.incognitoMode.get()) {
                                    prefs.edit().putString(returnPreferenceKey(expectedBookId), candidate.priorLocatorJson).apply()
                                }
                                returnLocatorJson = candidate.priorLocatorJson
                                pendingReturnCandidate = null
                                returnSettlementJob = null
                                return@launch
                            }
                            if (consume != null && pendingReturnConsume === consume) {
                                if (!graph.basePreferences.incognitoMode.get()) {
                                    prefs.edit().remove(returnPreferenceKey(expectedBookId)).apply()
                                }
                                returnLocatorJson = null
                                pendingReturnConsume = null
                                returnSettlementJob = null
                                return@launch
                            }
                    }
                    delay(100)
                }
            } else {
                delay(8_000)
            }
            if (pendingReturnCandidate === candidate) pendingReturnCandidate = null
            if (pendingReturnConsume === consume) pendingReturnConsume = null
            returnSettlementJob = null
        }
    }

    private fun canReturnToPriorPosition(): Boolean {
        if (pendingReturnCandidate != null || pendingReturnConsume != null) return false
        val saved = returnLocatorJson ?: return false
        return when (book?.format) {
            LocalBookFormat.PDF -> runCatching {
                val target = JSONObject(saved)
                val here = JSONObject(lastLocator ?: "{}")
                val page = target.optInt("page", -1)
                page in 0 until pdfPages && (page != here.optInt("page", pdfPage) ||
                    kotlin.math.abs(target.optDouble("x", .5) - here.optDouble("x", .5)) > .02 ||
                    kotlin.math.abs(target.optDouble("y", .5) - here.optDouble("y", .5)) > .02 ||
                    kotlin.math.abs(target.optDouble("zoom", 1.0) - here.optDouble("zoom", 1.0)) > .02)
            }.getOrDefault(false)
            else -> epub?.currentLocator?.value?.let { current -> !locatorMatches(current, saved) } ?: false
        }
    }

    private fun returnToPriorPosition() {
        if (pendingReturnCandidate != null || pendingReturnConsume != null) return
        val saved = returnLocatorJson ?: return
        cancelFontPreviewForUserInput()
        auto.pause()
        when (book?.format) {
            LocalBookFormat.PDF -> {
                val target = runCatching { JSONObject(saved) }.getOrNull() ?: return
                val page = target.optInt("page", -1).takeIf { it in 0 until pdfPages } ?: return
                pendingReturnCandidate = null
                pendingReturnConsume = PendingReturnConsume(targetPdfPage = page)
                pdfPage = page
                renderPdf(target)
            }
            else -> {
                val target = runCatching { Locator.fromJSON(JSONObject(saved)) }.getOrNull() ?: return
                val navigator = epub ?: return
                pendingReturnCandidate = null
                pendingReturnConsume = PendingReturnConsume(targetLocatorJson = target.toJSON().toString())
                if (!navigator.go(target, false)) { pendingReturnConsume = null; return }
            }
        }
        ui = ui.copy(options = false)
        watchReturnSettlement()
    }

    private fun jumpToPdfPage(page: Int) {
        if (page !in 0 until pdfPages || page == pdfPage) return
        savePdfLocation()
        val prior = lastLocator ?: JSONObject().put("page", pdfPage).toString()
        pendingReturnCandidate = PendingBookReturn(targetLocatorJson = null, targetPdfPage = page, priorLocatorJson = prior)
        pendingReturnConsume = null
        pdfPage = page
        renderPdf()
        ui = ui.copy(jump = false)
        watchReturnSettlement()
    }

    private fun navigateToEpubLocator(locator: Locator) {
        cancelFontPreviewForUserInput()
        auto.pause()
        val navigator = epub ?: return
        val prior = navigator.currentLocator.value
        val target = locator.toJSON().toString()
        if (locatorMatches(prior, target)) return
        pendingReturnCandidate = PendingBookReturn(targetLocatorJson = target, targetPdfPage = null,
            priorLocatorJson = prior.toJSON().toString())
        pendingReturnConsume = null
        if (!navigator.go(locator, false)) { pendingReturnCandidate = null; return }
        watchReturnSettlement()
    }

    private fun cancelFontPreviewForUserInput() {
        if (fontPreviewOrigin == null && typographyPreviewJob == null) return
        val hadPreview = fontPreviewOrigin != null
        typographyPreviewJob?.cancel(); typographyPreviewJob = null
        fontPreviewGeneration++
        fontRestoreJob?.cancel()
        fontRestoreJob = null
        fontPreviewOrigin = null
        fontPreviewRestorePending = false
        fontGestureStart = null
        if (hadPreview) {
            epub?.submitPreferences(readerPreferences())
            prefs.edit().putFloat("font", fontPercent.toFloat()).putFloat("line", lineHeight.toFloat())
                .putFloat("margin", margin.toFloat()).putBoolean("dark", darkPaper).apply()
        }
        // Let the navigator's next locator emission persist the user's movement or target.
        saveJob?.cancel()
        saveJob = null
    }

    private fun preserveFontPreviewOnBackground() {
        val origin = fontPreviewOrigin ?: return
        typographyPreviewJob?.cancel(); typographyPreviewJob = null
        epub?.submitPreferences(readerPreferences())
        fontPreviewGeneration++
        fontRestoreJob?.cancel()
        fontRestoreJob = null
        fontPreviewRestorePending = true
        lastLocator = origin.toJSON().toString()
        lastProgress = publicationProgress(origin) ?: lastProgress
        fontGestureStart = null
        prefs.edit().putFloat("font", fontPercent.toFloat()).putFloat("line", lineHeight.toFloat())
            .putFloat("margin", margin.toFloat()).putBoolean("dark", darkPaper).apply()
        saveJob?.cancel()
        saveJob = null
        persist()
    }

    private fun selectReadingLayout(useScrolling: Boolean) {
        if (!ui.ready || book?.format != LocalBookFormat.EPUB || publication?.metadata?.layout == Layout.FIXED || scrolling == useScrolling) return
        val anchor = preserveTypographyAnchorForPreferenceChange()
        if (anchor == null) persist()
        scrolling = useScrolling
        val selected = book?.id ?: return
        val key = "book:$selected"
        graph.libraryToolsStore.setProfile(key, graph.libraryToolsStore.profile(key).copy(layout = if (useScrolling) "scroll" else "pages"))
        applyPreferences()
        restoreTypographyAnchorAfterPreferenceChange(anchor)
    }

    private fun selectReadingRoom(selectedRoom: ReadingRoom) {
        val id = book?.id ?: return
        val anchor = preserveTypographyAnchorForPreferenceChange()
        auto.pause()
        darkPaper = selectedRoom != ReadingRoom.WARM_PAPER
        graph.libraryToolsStore.setProfile("book:$id", graph.libraryToolsStore.profile("book:$id").copy(room = selectedRoom))
        appearanceVersion++
        applyPreferences()
        restoreTypographyAnchorAfterPreferenceChange(anchor)
    }
    private fun savePdfLocation() {
        val view = pdfImage ?: return
        if (!view.isReady) return
        val center = view.center ?: return
        lastLocator = JSONObject().put("page", pdfPage).put("zoom", (view.scale / view.minScale).toDouble())
            .put("x", (center.x / view.sWidth).toDouble()).put("y", (center.y / view.sHeight).toDouble()).toString()
        lastProgress = if (pdfPages <= 1) 1.0 else pdfPage.toDouble() / (pdfPages - 1)
        persistSoon()
    }
    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = lifecycleScope.launch { delay(300); persist() }
    }

    private fun publicationProgress(locator: Locator): Double? {
        val native = locator.locations.totalProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }
        if (book?.format != LocalBookFormat.EPUB) return native
        return epubBookProgress?.progression(
            href = locator.href.toString(),
            nativeTotalProgression = locator.locations.totalProgression,
            position = locator.locations.position,
            resourceProgression = locator.locations.progression,
        ) ?: native
    }

    private fun persist() {
        if (graph.basePreferences.incognitoMode.get()) return
        val selected = book ?: return
        val locator = lastLocator ?: return
        graph.bookProgressWriter.save(selected.id, locator, lastProgress)
    }
    private fun manualForward() {
        cancelFontPreviewForUserInput()
        auto.pause()
        if (book?.format == LocalBookFormat.PDF) { if (pdfPage + 1 < pdfPages) { pdfPage++; renderPdf() } }
        else if (scrolling && publication?.metadata?.layout != Layout.FIXED) manualTextStep(forward = true)
        else epub?.goForward(ReaderAutoStepScroller.animationsAllowed(this))
    }
    private fun manualBackward() {
        cancelFontPreviewForUserInput()
        auto.pause()
        if (book?.format == LocalBookFormat.PDF) { if (pdfPage > 0) { pdfPage--; renderPdf() } }
        else if (scrolling && publication?.metadata?.layout != Layout.FIXED) manualTextStep(forward = false)
        else epub?.goBackward(ReaderAutoStepScroller.animationsAllowed(this))
    }
    private fun manualTextStep(forward: Boolean) {
        val navigator = epub ?: return
        val web = visibleWebView(navigator.publicationView) ?: return
        val direction = if (forward) 1 else -1
        if (web.canScrollVertically(direction)) {
            autoStepScroller.scrollBy(web, 0, direction * (web.height * .75f).toInt())
            return
        }
        // A manual action may cross the spine boundary; automation remains in its captured resource.
        val pub = publication ?: return
        val index = pub.readingOrder.indexOfFirst { it.href.toString().substringBefore('#') == currentHref }
        val next = index + direction
        if (index < 0 || next !in pub.readingOrder.indices) return
        pub.locatorFromLink(pub.readingOrder[next])?.let { locator ->
            val target = if (forward) locator else locator.copy(locations = locator.locations.copy(progression = 1.0))
            navigator.go(target, false)
        }
    }
    private fun advanceAutomatically(scope: Long, pixels: Int?): ReaderAutoAdvanceResult {
        if (!ui.ready || scope != boundarySerial) return ReaderAutoAdvanceResult.NOT_READY
        if (book?.format == LocalBookFormat.PDF) {
            if (pixels != null) return ReaderAutoAdvanceResult.UNSUPPORTED
            if (pdfPage + 1 >= pdfPages) return ReaderAutoAdvanceResult.CHAPTER_END
            pdfPage++; renderPdfAutomatic(); return ReaderAutoAdvanceResult.ADVANCED
        }
        val navigator = epub ?: return ReaderAutoAdvanceResult.NOT_READY
        if (!scrolling) {
            if (pixels != null) return ReaderAutoAdvanceResult.UNSUPPORTED
            if (pagesInResource <= 0) return ReaderAutoAdvanceResult.NOT_READY
            if (pageInResource + 1 >= pagesInResource) return ReaderAutoAdvanceResult.CHAPTER_END
            return if (navigator.goForward(ReaderAutoStepScroller.animationsAllowed(this))) ReaderAutoAdvanceResult.ADVANCED else ReaderAutoAdvanceResult.CHAPTER_END
        }
        val web = visibleWebView(navigator.publicationView) ?: return ReaderAutoAdvanceResult.NOT_READY
        if (web.progress < 100 || web.height <= 0) return ReaderAutoAdvanceResult.NOT_READY
        if (!web.canScrollVertically(1)) return ReaderAutoAdvanceResult.CHAPTER_END
        val distance = pixels ?: (web.height * 0.75f).toInt()
        if (pixels == null) autoStepScroller.scrollBy(web, 0, distance) else web.scrollBy(0, distance)
        return ReaderAutoAdvanceResult.ADVANCED
    }
    private fun renderPdfAutomatic() {
        // Rendering is asynchronous; pause/resume is explicit if a timer fires before the next page is ready.
        renderPdf(pausePlayback = false)
        ui = ui.copy(menu = false)
    }
    private fun hardenWebViews(view: View) {
        if (view is WebView) {
            view.settings.blockNetworkLoads = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) hardenWebViews(view.getChildAt(i))
    }
    private fun visibleWebView(view: View): WebView? {
        if (view is WebView && view.isShown) {
            val rect = android.graphics.Rect()
            if (view.getGlobalVisibleRect(rect) && rect.width() >= container.width * 9 / 10) return view
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) visibleWebView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun addBookmark() { lifecycleScope.launch {
        val selected = book ?: return@launch
        val locator = lastLocator ?: return@launch
        graph.localBookRepository.addBookmark(BookBookmark(UUID.randomUUID().toString(), selected.id, locator,
            if (selected.format == LocalBookFormat.PDF) "Page ${pdfPage + 1}" else ui.subtitle, System.currentTimeMillis()))
        Toast.makeText(this@BookReaderActivity, "Bookmark saved", Toast.LENGTH_SHORT).show()
    } }
    private fun showBookmarks() { lifecycleScope.launch {
        val selected = book ?: return@launch
        bookmarkItems = graph.localBookRepository.bookmarks(selected.id); ui = ui.copy(bookmarks = true)
    } }
    private fun goBookmark(mark: BookBookmark) {
        cancelFontPreviewForUserInput()
        auto.pause()
        if (book?.format == LocalBookFormat.PDF) { val j = JSONObject(mark.locator); pdfPage = j.optInt("page", 0).coerceIn(0, pdfPages - 1); renderPdf(j) }
        else Locator.fromJSON(JSONObject(mark.locator))?.let(::navigateToEpubLocator)
        ui = ui.copy(bookmarks = false)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (pendingReturnCandidate != null || pendingReturnConsume != null) returnSettlementInputSerial++
            fontGestureStart = if (fontPreviewOrigin != null && !ui.options && !ui.autoOptions) {
                PointF(event.rawX, event.rawY)
            } else null
            // A later user touch cancels a deferred Start; it must never auto-resume after an interruption.
            if (autoStartJob?.isActive == true) autoStartJob?.cancel()
            if (::container.isInitialized) lastTapRatio = event.x / container.width.coerceAtLeast(1)
            val quietStripMinimum = (56 * resources.displayMetrics.density).toInt() + safeBottom
            // EPUB now has no control in its quiet progress row, so every touch should pause
            // playback. Keep the running PDF strip's Pause button tappable without treating
            // its own ACTION_DOWN as a second pause gesture.
            val quietPdfControl = !ui.menu && ui.ready &&
                book?.format == LocalBookFormat.PDF && auto.state.running &&
                event.y.toInt() >= readerRoot.height - maxOf(quietStripHeight, quietStripMinimum)
            val shownAutoControls = ui.ready && auto.state.running &&
                (ui.autoOptions || (ui.menu &&
                    event.y.toInt() >= readerRoot.height - maxOf(bottomChromeHeight, quietStripMinimum)))
            if (!quietPdfControl && !shownAutoControls) auto.touchDown()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val start = fontGestureStart
            if (start != null && fontPreviewOrigin != null) {
                val dx = event.rawX - start.x
                val dy = event.rawY - start.y
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                if (dx * dx + dy * dy >= slop * slop) cancelFontPreviewForUserInput()
            }
        }
        return try { super.dispatchTouchEvent(event) } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                fontGestureStart = null
                auto.touchUp()
            }
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END ->
                    cancelFontPreviewForUserInput()
            }
            auto.touchDown()
        }
        return try { super.dispatchKeyEvent(event) } finally { if (event.action == KeyEvent.ACTION_UP) auto.touchUp() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { auto.pause(ReaderAutomationPause.BACKGROUND); meter.pause() }
        else {
            if (ui.ready) meter.resume()
            resumePendingFontAnchorRestore()
        }
    }
    override fun onResume() {
        super.onResume()
        graph.readerForegroundSignal.enter(this)
        if (ui.ready && hasWindowFocus()) meter.resume()
        resumePendingFontAnchorRestore()
    }
    override fun onPause() {
        graph.readerForegroundSignal.leave(this)
        graph.smartOfflineDispatcher.onReaderIdle()
        meter.pause()
        autoStartJob?.cancel()
        auto.pause(ReaderAutomationPause.BACKGROUND)
        saveJob?.cancel()
        preserveFontPreviewOnBackground()
        // A short disk flush runs off the UI thread and completes even if this activity closes.
        persist()
        super.onPause()
    }
    override fun onDestroy() {
        auto.pause(ReaderAutomationPause.BACKGROUND)
        typographyPreviewJob?.cancel(); returnSettlementJob?.cancel()
        pdfImage?.recycle(); renderedPdf?.delete()
        super.onDestroy()
        graph.bookProgressWriter.release(pdf, publication)
    }

    /** EPUB chrome overlays a stable safe-area viewport so toggling it cannot repaginate text. */
    private fun updateReaderViewport() {
        if (!::container.isInitialized) return
        val params = container.layoutParams as FrameLayout.LayoutParams
        val stableTextViewport = book?.format == LocalBookFormat.EPUB || book?.format == LocalBookFormat.TEXT
        val showQuietStrip = !ui.menu && ui.ready &&
            (stableTextViewport || (book?.format == LocalBookFormat.PDF && auto.state.running))
        val quietStripMinimum = ((if (stableTextViewport) 24 else 56) * resources.displayMetrics.density).toInt() + safeBottom
        val top = if (stableTextViewport) safeTop else if (ui.menu) maxOf(safeTop, topChromeHeight) else safeTop
        val bottom = if (stableTextViewport) safeBottom else when {
            ui.menu -> maxOf(safeBottom, bottomChromeHeight)
            showQuietStrip -> maxOf(safeBottom, quietStripHeight, quietStripMinimum)
            else -> safeBottom
        }
        if (params.topMargin != top || params.bottomMargin != bottom ||
            params.leftMargin != safeLeft || params.rightMargin != safeRight
        ) {
            params.topMargin = top
            params.bottomMargin = bottom
            params.leftMargin = safeLeft
            params.rightMargin = safeRight
            container.layoutParams = params
        }
    }

    private fun startAutoFromControls() {
        cancelFontPreviewForUserInput()
        autoStartJob?.cancel()
        val requestedHref = currentHref
        val requestedBoundary = boundarySerial
        ui = ui.copy(autoOptions = false, options = false, menu = false)
        autoStartJob = lifecycleScope.launch {
            // A dialog owns window focus. Dismiss it before honoring this explicit Start gesture.
            val focused = kotlinx.coroutines.withTimeoutOrNull(1500) {
                while (!hasWindowFocus()) delay(16)
                true
            } == true
            val settled = when {
                !focused -> false
                book?.format == LocalBookFormat.EPUB -> awaitStableEpubViewport(requestedHref, requestedBoundary)
                else -> { delay(100); true }
            }
            val sameEpubPosition = book?.format != LocalBookFormat.EPUB ||
                (requestedHref == currentHref && requestedBoundary == boundarySerial)
            if (settled && sameEpubPosition && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) {
                auto.start()
            }
            if (!auto.state.running) ui = ui.copy(menu = true, autoOptions = true)
        }
    }

    /** Wait for Readium's post-chrome-change viewport to be ready and stable before starting playback. */
    private suspend fun awaitStableEpubViewport(expectedHref: String?, expectedBoundary: Long): Boolean {
        val navigator = epub ?: return false
        return kotlinx.coroutines.withTimeoutOrNull(5_000) {
            var previousLayout: List<Int>? = null
            var stableSamples = 0
            var ready = false
            while (!ready) {
                if (currentHref != expectedHref || boundarySerial != expectedBoundary) return@withTimeoutOrNull false
                var sampled = false
                if (ui.ready && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) {
                    val web = visibleWebView(navigator.publicationView)
                    val visible = android.graphics.Rect()
                    if (web != null && web.progress >= 100 && web.width > 0 && web.height > 0 && web.getGlobalVisibleRect(visible)) {
                        val layout = listOf(
                            System.identityHashCode(web), web.width, web.height, web.contentHeight,
                            web.scrollX, web.scrollY,
                            visible.left, visible.top, visible.right, visible.bottom,
                            container.width, container.height,
                        )
                        stableSamples = if (layout == previousLayout) stableSamples + 1 else 1
                        previousLayout = layout
                        ready = stableSamples >= 5
                        sampled = true
                    }
                }
                if (!sampled) { previousLayout = null; stableSamples = 0 }
                if (!ready) delay(50)
            }
            ready
        } == true
    }

    @Composable private fun AutoControls(menuVisible: Boolean, modifier: Modifier = Modifier) {
        ReaderAutomationControls(auto.state, menuVisible, ui.ready && publication?.metadata?.layout != Layout.FIXED,
            onStart = ::startAutoFromControls,
            onPause = { auto.pause() }, onSettingsChange = { auto.update(it) }, onSettingsPreview = { auto.update(it, false) },
            onSettingsChangeFinished = { auto.update(auto.state.settings) }, modifier = modifier,
            onClose = { ui = ui.copy(autoOptions = false) },
            continuousUnavailableMessage = "Continuous scroll requires reflowable EPUB or text with Scroll text enabled. System motion must be enabled; touch exploration pauses it.",
            continuousTransitionDescription = "Switch this book to scrolling text at the current reading position, then start Continuous scroll.",
            onContinuousTransition = if (canSwitchToContinuousText()) ::switchToContinuousText else null,
            timedStepDescription = "Advances one page or three quarters of a scrolling text screen. EPUB stops at the current chapter; PDF stops at the last page.")
    }

    private fun canSwitchToContinuousText(): Boolean =
        ui.ready && book?.format == LocalBookFormat.EPUB && !scrolling && publication?.metadata?.layout != Layout.FIXED &&
            auto.systemAllowsContinuousMotion()

    private fun switchToContinuousText() {
        if (!canSwitchToContinuousText()) return
        val anchor = preserveTypographyAnchorForPreferenceChange()
        val selected = auto.state.settings.copy(mode = ReaderAutomationMode.CONTINUOUS)
        auto.prepareSettingsForMode("EPUB_true", selected)
        val id = book?.id ?: return
        val key = "book:$id"
        graph.libraryToolsStore.setProfile(key, graph.libraryToolsStore.profile(key).copy(layout = "scroll"))
        scrolling = true
        applyPreferences()
        restoreTypographyAnchorAfterPreferenceChange(anchor)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Chrome() {
        val selectedRoom = remember(appearanceVersion, book?.id, darkPaper) { effectiveReaderRoom() }
        val paper = Color(selectedRoom.paper)
        val ink = Color(selectedRoom.text)
        val lightSystemBarIcons = selectedRoom == ReadingRoom.WARM_PAPER
        val footerSurface = Color(ColorUtils.blendARGB(selectedRoom.paper, selectedRoom.text, 0.08f))
        val appAccentCandidate = MaterialTheme.colorScheme.primary
        fun readableAccent(surface: Color): Color {
            val accentLuminance = appAccentCandidate.luminance()
            val surfaceLuminance = surface.luminance()
            val lighter = maxOf(accentLuminance, surfaceLuminance)
            val darker = minOf(accentLuminance, surfaceLuminance)
            return if ((lighter + .05f) / (darker + .05f) >= 4.5f) appAccentCandidate else ink
        }
        val appAccent = readableAccent(paper)
        val footerAccent = readableAccent(footerSurface)
        SideEffect {
            readerRoot.setBackgroundColor(selectedRoom?.paper ?: if (darkPaper) 0xFF121722.toInt() else 0xFFF7F7F1.toInt())
            WindowInsetsControllerCompat(window, readerRoot).apply {
                isAppearanceLightStatusBars = lightSystemBarIcons
                isAppearanceLightNavigationBars = lightSystemBarIcons
            }
            updateReaderViewport()
        }
        Box(Modifier.fillMaxSize()) {
            if (ui.error != null) Surface(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp)) { Text("Could not open book", style = MaterialTheme.typography.titleLarge); Text(ui.error!!)
                    TextButton(onClick = { finish() }) { Text("Back to Library") } }
            } else if (!ui.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (ui.menu) {
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged {
                    topChromeHeight = it.height; updateReaderViewport()
                }, color = paper, contentColor = ink) {
                    Column(Modifier.statusBarsPadding().padding(start=24.dp,top=20.dp,end=24.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack,"Back") }
                            Text(ui.title, Modifier.weight(1f).padding(horizontal=8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize=14.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                            if (pendingReturnCandidate == null && pendingReturnConsume == null && canReturnToPriorPosition()) {
                                TextButton(onClick = ::returnToPriorPosition) { Text("Return", fontSize = 11.sp) }
                            }
                            TextButton(onClick = { auto.pause(); ui = ui.copy(options = true) }, colors = ButtonDefaults.textButtonColors(contentColor = appAccent)) {
                                Text("Aa",fontFamily=ComposeFontFamily.Serif,fontSize=22.sp)
                            }
                        }
                        HorizontalDivider(color=ink.copy(alpha=.12f))
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {
                    bottomChromeHeight = it.height; updateReaderViewport()
                }, color = paper, contentColor = ink) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=31.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(if(book?.format==LocalBookFormat.PDF) ui.subtitle else {
                                val count=publication?.readingOrder?.size?:0
                                val current=publication?.readingOrder?.indexOfFirst{it.href.toString().substringBefore('#')==currentHref}?:-1
                                if(current>=0 && count>0) "${current+1} of $count chapters" else ui.subtitle
                            },fontSize=12.sp,color=ink.copy(alpha=.65f))
                            if(book?.format!=LocalBookFormat.PDF) Text("$displayedBookPercent%",fontSize=12.sp,color=ink.copy(alpha=.65f))
                        }
                        LinearProgressIndicator(progress={lastProgress.toFloat().coerceIn(0f,1f)},
                            modifier=Modifier.fillMaxWidth().height(3.dp),color=appAccent,trackColor=appAccent.copy(alpha=.18f),drawStopIndicator={})
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(footerSurface),
                            horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            IconButton(enabled=ui.ready,onClick=::manualBackward) { Icon(MaterialSymbols.Rounded.KeyboardArrowLeft,"Previous") }
                            TextButton(enabled=ui.ready,onClick={auto.pause();ui=ui.copy(contents=book?.format!=LocalBookFormat.PDF,jump=book?.format==LocalBookFormat.PDF)},colors=ButtonDefaults.textButtonColors(contentColor=ink)) {
                                Icon(MaterialSymbols.AutoMirroredRounded.FormatListBulleted,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(book?.format==LocalBookFormat.PDF) "Page" else "Contents",fontSize=13.sp)
                            }
                            IconButton(enabled=ui.ready,onClick=::addBookmark) {Icon(MaterialSymbols.Rounded.Bookmark,"Bookmark")}
                            IconButton(enabled=ui.ready,onClick=::manualForward) {Icon(MaterialSymbols.Rounded.KeyboardArrowRight,"Next")}
                        }
                        if (book?.format == LocalBookFormat.EPUB && publication?.metadata?.layout != Layout.FIXED) {
                            ReaderAutomationStripControl(
                                auto.state,
                                canStart = ui.ready && publication?.metadata?.layout != Layout.FIXED,
                                onStart = ::startAutoFromControls,
                                onPause = { auto.pause() },
                                modifier = Modifier.fillMaxWidth(),
                                statusColor = ink,
                            )
                        }
                    }
                }
            }
            if (!ui.menu && ui.ready &&
                (book?.format == LocalBookFormat.EPUB || (book?.format == LocalBookFormat.PDF && auto.state.running))) {
                Surface(
                    modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {
                        quietStripHeight = it.height
                        updateReaderViewport()
                    },
                    color=if (book?.format == LocalBookFormat.EPUB) paper else footerSurface.copy(alpha=.96f),
                    contentColor=ink,
                ) {
                    if (book?.format == LocalBookFormat.EPUB) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min=20.dp).padding(horizontal=16.dp, vertical=3.dp),
                            horizontalArrangement=Arrangement.spacedBy(10.dp),
                            verticalAlignment=Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator(
                                progress={lastProgress.toFloat().coerceIn(0f,1f)},
                                modifier=Modifier.weight(1f).height(2.dp),
                                color=footerAccent,
                                trackColor=footerAccent.copy(alpha=.18f),
                                drawStopIndicator={},
                            )
                            Text(
                                "$displayedBookPercent%",
                                Modifier.semantics { contentDescription = "Book progress, $displayedBookPercent percent" },
                                fontSize=10.sp,
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min=56.dp).padding(horizontal=12.dp),
                            horizontalArrangement=Arrangement.spacedBy(8.dp),
                            verticalAlignment=Alignment.CenterVertically,
                        ) {
                            ReaderAutomationStripControl(
                                auto.state,
                                canStart = false,
                                onStart = ::startAutoFromControls,
                                onPause = { auto.pause() },
                                modifier = Modifier.weight(1f),
                                statusColor = ink,
                            )
                            Text("Page ${pdfPage + 1} / $pdfPages", fontSize=12.sp)
                        }
                    }
                }
            }
        }
        if (ui.autoOptions) ModalBottomSheet(
            onDismissRequest = { ui = ui.copy(autoOptions = false); auto.pause() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AutoControls(true)
        }
        if (ui.options) AlertDialog(onDismissRequest = { finishFontSizePreview(); ui = ui.copy(options = false) }, title = { Text("Reader") }, text = {
            val supportsAppearance = book?.format != LocalBookFormat.PDF && publication?.metadata?.layout != Layout.FIXED
            Column(Modifier.heightIn(max = 440.dp)) {
                if (supportsAppearance) SharedTextAppearancePreview(
                    fontPercent = fontPercent.toFloat(), lineHeight = lineHeight.toFloat(),
                    margins = margin.toFloat(), effectiveRoom = effectiveReaderRoom(),
                )
                Column(Modifier.heightIn(max = if (supportsAppearance) 330.dp else 440.dp).verticalScroll(rememberScrollState())) {
                    if (supportsAppearance) {
                    SharedTextAppearancePanel(
                        layout = if (scrolling) "scroll" else "pages",
                        onLayoutSelected = { selectReadingLayout(it == "scroll") },
                        fontPercent = fontPercent.toFloat(), onFontPreview = ::previewFontSize,
                        onFontSettled = ::finishFontSizePreview,
                        lineHeight = lineHeight.toFloat(), onLineHeightPreview = ::previewLineHeight,
                        onLineHeightSettled = ::finishFontSizePreview,
                        margins = margin.toFloat(), onMarginsPreview = ::previewMargins,
                        onMarginsSettled = ::finishFontSizePreview,
                        effectiveRoom = effectiveReaderRoom(), onRoomSelected = ::selectReadingRoom,
                        supportsLayout = true,
                    )
                    } else if (book?.format == LocalBookFormat.PDF) Text("Pinch to zoom and drag the PDF page. Tap the edges to turn pages and the center to show controls.")
                    else Text("Fixed-layout EPUB uses manual page turns. Its page design stays fixed.")
                    if (book?.format == LocalBookFormat.PDF) TextButton(
                        onClick = { ui = ui.copy(options = false, jump = true) }, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Go to page") }
                    TextButton(onClick = { finishFontSizePreview(); ui = ui.copy(options = false, autoOptions = true) }) { Text("Auto read") }
                    TextButton(onClick = { finishFontSizePreview(); showBookmarks() }) { Text("Saved bookmarks") }
                }
            }
        }, confirmButton = { TextButton(onClick = { finishFontSizePreview(); ui = ui.copy(options = false) }) { Text("Done") } })
        if (ui.contents) AlertDialog(onDismissRequest = { ui = ui.copy(contents = false) }, title = { Text("Contents") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                val links = publication?.tableOfContents?.takeIf { it.isNotEmpty() } ?: publication?.readingOrder.orEmpty()
                fun flatten(items: List<Link>): List<Link> {
                    val pending = ArrayDeque(items); val result = mutableListOf<Link>()
                    while (pending.isNotEmpty() && result.size < 300) {
                        val link = pending.removeFirst(); result += link
                        link.children.asReversed().forEach { pending.addFirst(it) }
                    }
                    return result
                }
                flatten(links).forEachIndexed { index, link -> TextButton(onClick = { publication?.locatorFromLink(link)?.let(::navigateToEpubLocator); ui = ui.copy(contents = false) }) { Text(link.title ?: "Chapter ${index + 1}") } }
            }
        }, confirmButton = { TextButton(onClick = { ui = ui.copy(contents = false) }) { Text("Close") } })
        if (ui.bookmarks) AlertDialog(onDismissRequest = { ui = ui.copy(bookmarks = false) }, title = { Text("Bookmarks") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (bookmarkItems.isEmpty()) Text("No bookmarks yet")
                bookmarkItems.forEach { mark -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { goBookmark(mark) }, modifier = Modifier.weight(1f)) { Text(mark.label) }
                    TextButton(onClick = { lifecycleScope.launch {
                        graph.localBookRepository.removeBookmark(mark.id); bookmarkItems = bookmarkItems.filterNot { it.id == mark.id }
                    } }) { Text("Remove") }
                } }
            }
        }, confirmButton = { TextButton(onClick = { ui = ui.copy(bookmarks = false) }) { Text("Close") } })
        if (ui.jump) {
            var pageText by remember { mutableStateOf((pdfPage + 1).toString()) }
            AlertDialog(onDismissRequest = { ui = ui.copy(jump = false) }, title = { Text("Go to page · 1–$pdfPages") },
                text = { OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Page number") }) },
                confirmButton = { TextButton(enabled = pageText.toIntOrNull() in 1..pdfPages, onClick = { jumpToPdfPage(pageText.toInt().coerceIn(1, pdfPages) - 1) }) { Text("Go") } })
        }
    }
}
