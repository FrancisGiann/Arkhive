package mihon.readingapp.novels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.reader.automation.*
import kotlinx.coroutines.*
import mihon.app.di.appGraph
import mihon.readingapp.books.BookAutoController
import mihon.readingapp.design.Ink
import mihon.readingapp.design.SharedTextAppearancePanel
import mihon.readingapp.design.SharedTextAppearancePreview
import mihon.readingapp.settings.InkSettingsTheme
import org.jsoup.nodes.Entities
import mihon.readingapp.library.ReadingSessionMeter
import mihon.readingapp.library.ReadingRoom
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import kotlin.math.roundToInt

private data class NovelReturnPoint(val chapterId: String, val progression: Double)

/** Sanitized text only. WebView has no JavaScript, broker, file access or external navigation. */
class NovelReaderActivity : BaseActivity() {
    companion object {
        const val NOVEL_ID = "arkhive_novel_id"
        const val CHAPTER_ID = "arkhive_novel_chapter_id"
        fun intent(context: Context, novel: String, chapter: String? = null) = Intent(context, NovelReaderActivity::class.java)
            .putExtra(NOVEL_ID, novel).putExtra(CHAPTER_ID, chapter)
    }
    private val graph by lazy { applicationContext.appGraph }
    private val repository by lazy { graph.novelRepository }
    private val preferences by lazy { getSharedPreferences("arkhive_book_reader", Context.MODE_PRIVATE) }
    private lateinit var web: TextWebView
    private lateinit var footerView: ComposeView
    private lateinit var statusView: ComposeView
    private lateinit var readerRoot: FrameLayout
    private var novel by mutableStateOf<SavedNovel?>(null)
    private var chapters by mutableStateOf(emptyList<ChapterState>())
    private var current by mutableStateOf<ChapterState?>(null)
    private var ready by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var menu by mutableStateOf(true)
    private var contents by mutableStateOf(false)
    private var options by mutableStateOf(false)
    private var automation by mutableStateOf(false)
    private var font by mutableFloatStateOf(100f)
    private var dark by mutableStateOf(true)
    private var readingLayout by mutableStateOf("scroll")
    private var chapterPercent by mutableIntStateOf(0)
    private val pagedLayout get() = readingLayout == "pages"
    private var body by mutableStateOf("")
    private var headerHeightPx by mutableIntStateOf(0)
    private var footerHeightPx by mutableIntStateOf(0)
    private var restoredProgress = 0.0
    private var lastMeasuredProgress = 0.0
    private var expectedUrl = ""
    private var renderSerial = 0L
    private var viewportChangeSerial = 0L
    private var userGestureSerial = 0L
    private var pendingViewportProgress: Double? = null
    private var viewportReflowPending = false
    // Keep the position captured before the first chrome resize until the reader moves. A
    // quiet→menu round trip must use the same content anchor instead of deriving a new anchor
    // from Blink's intermediate column/scroll range.
    private var chromeViewportAnchor: Double? = null
    private var chromeScrollAnchor: Int? = null
    private var expectedRestorePosition: Pair<Int, Int>? = null
    private var expectedRestoreGesture = 0L
    private var lastPersisted: Pair<String, Int>? = null
    private var boundary = 0L
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private var returnPoint by mutableStateOf<NovelReturnPoint?>(null)
    private var pendingReturnCandidate: Pair<String, NovelReturnPoint>? = null
    private var pendingReturnConsumeChapter: String? = null
    private var typographyPreviewProgress: Double? = null
    private var typographyPreviewJob: Job? = null
    private var lineSpacing by mutableFloatStateOf(33f / 21f)
    private var pageMargin by mutableFloatStateOf(1f)
    private var appearanceVersion by mutableIntStateOf(0)
    private var automationStartJob: Job? = null
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val persistAfterScroll = Runnable {persist()}
    private var touchX = 0f
    private var touchY = 0f
    private var touchTime = 0L
    private var pageDragActive = false
    private var pageDragStartX = 0
    private var pageDragMoved = false
    private var pageDragAnimator: ValueAnimator? = null
    private var pageDragSettleTarget: Int? = null
    private var pageDragSettleCommits = false
    private var pageVelocityTracker: VelocityTracker? = null
    private val meter by lazy {ReadingSessionMeter(graph.libraryToolsStore,{!graph.basePreferences.incognitoMode.get()},{novel?.id?.let {"novel:$it"}})}
    private fun room()=novel?.id?.let {graph.libraryToolsStore.profile("novel:$it").room} ?: graph.libraryToolsStore.state.value.room
    private fun readerPalette() = room() ?: if(dark) ReadingRoom.MIDNIGHT_VIOLET else ReadingRoom.WARM_PAPER
    private fun startAutomationFromSheet() {
        automationStartJob?.cancel()
        automation = false
        // The settings sheet can be opened from either chrome state. A successful explicit
        // Start should always return to the quiet reader while retaining the viewport anchor.
        if (menu) toggleReaderChrome()
        automationStartJob = lifecycleScope.launch {
            val focused = withTimeoutOrNull(1500) {
                while (!hasWindowFocus()) delay(16)
                while (viewportReflowPending || !ready) delay(16)
                delay(100)
                true
            } == true
            if (focused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) {
                auto.start()
                if (!auto.state.running) automation = true
            }
        }
    }
    private val autoStepScroller by lazy { ReaderAutoStepScroller(this) }
    private val auto by lazy { BookAutoController(this, { boundary.takeIf { ready } },
        { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() && ready },
        { !pagedLayout }, { captured, pixels ->
            if (!ready || boundary != captured) ReaderAutoAdvanceResult.NOT_READY
            else advance(pixels ?: if (pagedLayout) pageStridePx() else (web.height * .75f).toInt(), animate = pixels == null)
        }, cancelStepAnimation = autoStepScroller::cancel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this); enableEdgeToEdge(); super.onCreate(null)
        font = preferences.getFloat("font", 100f).coerceIn(80f, 220f)
        dark = preferences.getBoolean("dark", true)
        lineSpacing = preferences.getFloat("line", 33f / 21f).coerceIn(1f, 2.5f)
        pageMargin = preferences.getFloat("margin", 1f).coerceIn(.5f, 2.5f)
        auto.bind("online-novel")
        // Keep the publication viewport stable while reader chrome appears and disappears.
        // Resizing a paginated WebView repaginates the document and changes the visible passage.
        // Chrome remains interactive in overlays above the invariant WebView bounds.
        val root = FrameLayout(this).apply { setBackgroundColor(readerPalette().paper) }
        readerRoot = root
        web = TextWebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.blockNetworkLoads = true; settings.blockNetworkImage = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.domStorageEnabled = false; settings.databaseEnabled = false
            settings.textZoom = 100; settings.setSupportZoom(false)
            setBackgroundColor(if(dark) 0xFF0F1428.toInt() else 0xFFF7F2E8.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val u = request.url
                    return if(u.scheme == "https" && u.host == "arkhive.local" && u.path == "/font/serif.ttf" && request.method == "GET")
                        WebResourceResponse("font/ttf", null, assets.open("readium/fonts/arkhive-noto-serif-regular.ttf"))
                    else WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if(body.isBlank() || current == null || url != expectedUrl) return
                    view.postVisualStateCallback(renderSerial, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if(requestId != renderSerial || url != expectedUrl) return
                            if (pagedLayout) {
                                val raw=(web.endX()*restoredProgress).toInt()
                                val stride=pageStridePx()
                                val snapped=((raw.toDouble()/stride).roundToInt()*stride).coerceIn(0,web.endX())
                                web.scrollTo(snapped,0)
                            } else web.scrollTo(0, (web.end() * restoredProgress).toInt())
                            ready = true; updateChapterPercent(); web.visibility = View.VISIBLE
                            completePendingReturnRestore()
                            scheduleNextChapterPreload()
                            if(hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) meter.resume()
                        }
                    })
                }
            }
            setOnScrollChangeListener { _, scrollX, scrollY, oldScrollX, oldScrollY ->
                if(ready && !viewportReflowPending && !pageDragActive) {
                    val expected=expectedRestorePosition
                    if(expected!=null && scrollX==expected.first && scrollY==expected.second) {
                        expectedRestorePosition=null
                    } else if((scrollX!=oldScrollX || scrollY!=oldScrollY) &&
                        (expected==null || userGestureSerial!=expectedRestoreGesture)) {
                        // A genuine page turn, vertical scroll, or auto step supersedes the
                        // chrome anchor. A restored scrollTo is recognized by its exact target.
                        clearChromeViewportAnchor()
                    }
                    updateChapterPercent()
                    progressHandler.removeCallbacks(persistAfterScroll)
                    progressHandler.postDelayed(persistAfterScroll,1500)
                }
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if(ready && ((right-left)!=(oldRight-oldLeft) || (bottom-top)!=(oldBottom-oldTop))) {
                    if (pageDragActive) cancelPageDrag()
                    // Capture progress before Blink recomputes its page/scroll range. A footer
                    // resize can otherwise leave the same X offset pointing at another column.
                    if(!viewportReflowPending) {
                        pendingViewportProgress=lastMeasuredProgress.coerceIn(0.0,1.0)
                        viewportReflowPending=true
                        progressHandler.removeCallbacks(persistAfterScroll)
                    }
                    val preserveProgress=pendingViewportProgress ?: lastMeasuredProgress
                    if(pagedLayout) {
                        // Reload the cached, sanitized chapter against the actual new viewport.
                        // Blink can acknowledge a resize before exposing its new column range.
                        restoredProgress=preserveProgress
                        render(preserveChromeAnchor=true)
                        return@addOnLayoutChangeListener
                    }
                    val requestedViewportChange=++viewportChangeSerial
                    val requestedGesture=userGestureSerial
                    val requestedRender = renderSerial
                    web.postVisualStateCallback(requestedViewportChange, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if(requestId!=requestedViewportChange || requestedRender!=renderSerial ||
                                requestedViewportChange!=viewportChangeSerial || !ready) return
                            if(requestedGesture==userGestureSerial && pendingViewportProgress!=null) restoreViewportProgress(preserveProgress)
                            else clearChromeViewportAnchor()
                            pendingViewportProgress=null
                            viewportReflowPending=false
                            updateChapterPercent()
                            progressHandler.removeCallbacks(persistAfterScroll)
                            progressHandler.postDelayed(persistAfterScroll,1500)
                        }
                    })
                }
            }
            setOnTouchListener { _, event ->
                val paged = pagedLayout
                var consumedTap = false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        userGestureSerial++
                    if (typographyPreviewProgress != null) settleTypographyPreviewForUserInput()
                        touchX = event.x; touchY = event.y; touchTime = event.eventTime
                        auto.touchDown()
                        if (paged) {
                            interruptPageDragForRetouch()
                            autoStepScroller.cancel()
                            progressHandler.removeCallbacks(persistAfterScroll)
                            pageDragActive = ready && !viewportReflowPending
                            pageDragStartX = web.scrollX
                            pageDragMoved = false
                            pageVelocityTracker?.recycle()
                            pageVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                            if (pageDragActive) meter.pause()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> if (paged && pageDragActive) {
                        pageVelocityTracker?.addMovement(event)
                        val dx = event.x - touchX
                        val dy = event.y - touchY
                        val slop = ViewConfiguration.get(this@NovelReaderActivity).scaledTouchSlop.toFloat()
                        if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                            pageDragMoved = true
                            val delta = -dx.roundToInt()
                            val minX = maxOf(0, pageDragStartX - web.width)
                            val maxX = minOf(web.endX(), pageDragStartX + web.width)
                            web.scrollTo((pageDragStartX + delta).coerceIn(minX, maxX), 0)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        auto.touchUp()
                        val dx = event.x - touchX
                        val dy = event.y - touchY
                        val slop = ViewConfiguration.get(this@NovelReaderActivity).scaledTouchSlop.toFloat()
                        pageVelocityTracker?.addMovement(event)
                        pageVelocityTracker?.computeCurrentVelocity(1000)
                        val velocityX = pageVelocityTracker?.xVelocity ?: 0f
                        if (paged && pageDragActive && pageDragMoved && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                            val stride = web.width.coerceAtLeast(1)
                            val flingThreshold = ViewConfiguration.get(this@NovelReaderActivity).scaledMinimumFlingVelocity * 2f
                            val flingAgreesWithRelease = kotlin.math.abs(dx) > slop && dx * velocityX > 0f
                            val shouldTurn = kotlin.math.abs(dx) >= stride * .22f ||
                                (kotlin.math.abs(velocityX) >= flingThreshold && flingAgreesWithRelease)
                            val direction = when {
                                kotlin.math.abs(dx) >= stride * .12f -> if (dx < 0f) 1 else -1
                                shouldTurn && velocityX < 0f -> 1
                                shouldTurn && velocityX > 0f -> -1
                                else -> 0
                            }
                            val target = if (shouldTurn && direction != 0) adjacentPageTarget(pageDragStartX, direction)
                            else pageDragStartX
                            settlePageDrag(target, commit = target != pageDragStartX)
                        } else {
                            if (paged && pageDragActive) cancelPageDrag()
                            if (event.eventTime - touchTime < 300 &&
                                kotlin.math.abs(dx) < 12 * resources.displayMetrics.density &&
                                kotlin.math.abs(dy) < 12 * resources.displayMetrics.density
                            ) {
                                when {
                                    paged && event.x < width * .12f -> advancePageWithinChapter(-1, animate = true)
                                    paged && event.x > width * .88f -> advancePageWithinChapter(1, animate = true)
                                    !paged && event.x < web.width * .12f -> {
                                        advance(-(web.height * .75f).toInt(),
                                            animate = ReaderAutoStepScroller.animationsAllowed(this@NovelReaderActivity))
                                        consumedTap = true
                                    }
                                    !paged && event.x > web.width * .88f -> {
                                        advance((web.height * .75f).toInt(),
                                            animate = ReaderAutoStepScroller.animationsAllowed(this@NovelReaderActivity))
                                        consumedTap = true
                                    }
                                    else -> { toggleReaderChrome(); consumedTap = true }
                                }
                            } else if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                                // Vertical WebView scrolling is handled by WebView itself.
                                if (!paged) clearChromeViewportAnchor()
                            }
                        }
                        if (!paged && consumedTap) cancelWebTouch(event)
                        pageVelocityTracker?.recycle()
                        pageVelocityTracker = null
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        auto.touchUp()
                        pageVelocityTracker?.recycle()
                        pageVelocityTracker = null
                        if (paged && pageDragActive) cancelPageDrag()
                    }
                }
                // Pages mode owns its touch stream so WebView cannot leave a half-column offset.
                // Continuous mode consumes completed edge-step/control-toggle taps; vertical scroll remains native.
                paged || consumedTap
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        statusView = ComposeView(this).apply { setContent { InkSettingsTheme { ReaderStatusOverlay() } } }
        root.addView(statusView, FrameLayout.LayoutParams(-1, -1))
        val headerView = ComposeView(this).apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val next = bottom - top
                if (headerHeightPx != next) headerHeightPx = next
            }
            setContent { InkSettingsTheme { Header() } }
        }
        root.addView(headerView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        footerView = ComposeView(this).apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val next = bottom - top
                if (footerHeightPx != next) footerHeightPx = next
            }
            setContent { InkSettingsTheme { Footer() } }
        }
        root.addView(footerView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        setContentView(root)
        lifecycleScope.launch {
            try {
                val id = intent.getStringExtra(NOVEL_ID) ?: error("No novel selected.")
                novel = repository.get(id) ?: error("This novel is no longer available.")
                val profile = graph.libraryToolsStore.profile("novel:$id")
                readingLayout = if (profile.layout == "pages") "pages" else "scroll"
                auto.bindSeries("novel:$id")
                graph.libraryToolsStore.profile("novel:$id").brightnessPercent?.let {percent->window.attributes=window.attributes.apply {screenBrightness=percent/100f}}
                chapters = repository.chapters(id)
                val requested = savedInstanceState?.getString(CHAPTER_ID) ?: intent.getStringExtra(CHAPTER_ID) ?: novel?.lastChapter
                val chapter = chapters.firstOrNull { it.id == requested } ?: chapters.firstOrNull() ?: error("No chapters available.")
                returnPoint = readReturnPoint(requireNotNull(novel).id)
                open(if(savedInstanceState?.containsKey("progression")==true && chapter.id == requested)
                    chapter.copy(progression=savedInstanceState.getDouble("progression")) else chapter)
            } catch(error: Exception) { message = error.message ?: "The novel could not be opened." }
        }
    }
    private fun open(chapter: ChapterState, deliberateJump: Boolean = false, returning: Boolean = false) {
        val previewAnchor = if (typographyPreviewProgress != null) commitTypographyPreviewAnchor() else null
        val prior = if (deliberateJump && ready && current?.id != chapter.id) {
            current?.let { NovelReturnPoint(it.id, (previewAnchor ?: progression()).coerceIn(0.0, 1.0)) }
        } else null
        preloadJob?.cancel(); preloadJob = null
        pendingReturnCandidate = null
        pendingReturnConsumeChapter = if (returning) chapter.id else null
        if (prior != null) pendingReturnCandidate = chapter.id to prior
        cancelPageDragBeforeLeaving()
        meter.pause()
        if (previewAnchor == null) persist()
        progressHandler.removeCallbacks(persistAfterScroll); loadJob?.cancel(); auto.pause(ReaderAutomationPause.CHAPTER_END)
        boundary++; current = chapter; lastMeasuredProgress=chapter.progression.coerceIn(0.0,1.0); ready = false; message = null; body = ""; web.visibility = View.INVISIBLE
        loadJob = lifecycleScope.launch {
            try {
                body = repository.text(requireNotNull(novel).id, chapter.id)
                restoredProgress = chapter.progression
                lastMeasuredProgress = restoredProgress.coerceIn(0.0,1.0)
                render()
            } catch(cancelled: CancellationException) { throw cancelled }
              catch(error: Exception) {
                  if (pendingReturnCandidate?.first == chapter.id) pendingReturnCandidate = null
                  if (pendingReturnConsumeChapter == chapter.id) pendingReturnConsumeChapter = null
                  message = error.message ?: "Chapter unavailable. Retry when connected."
              }
        }
    }
    private fun returnChapterPreference(novelId: String) = "novel-return:$novelId:chapter"
    private fun returnProgressPreference(novelId: String) = "novel-return:$novelId:progress"
    private fun readReturnPoint(novelId: String): NovelReturnPoint? {
        val chapterId = preferences.getString(returnChapterPreference(novelId), null) ?: return null
        val progress = preferences.getFloat(returnProgressPreference(novelId), Float.NaN).toDouble()
        return NovelReturnPoint(chapterId, progress).takeIf {
            it.progression.isFinite() && it.progression in 0.0..1.0
        }
    }
    private fun completePendingReturnRestore() {
        val chapterId = current?.id ?: return
        pendingReturnCandidate?.takeIf { it.first == chapterId }?.let { (_, point) ->
            val novelId = novel?.id ?: return
            if (!graph.basePreferences.incognitoMode.get()) {
                preferences.edit().putString(returnChapterPreference(novelId), point.chapterId)
                    .putFloat(returnProgressPreference(novelId), point.progression.toFloat()).apply()
            }
            returnPoint = point
            pendingReturnCandidate = null
        }
        if (pendingReturnConsumeChapter == chapterId) {
            if (!graph.basePreferences.incognitoMode.get()) {
                novel?.id?.let { id -> preferences.edit().remove(returnChapterPreference(id)).remove(returnProgressPreference(id)).apply() }
            }
            returnPoint = null
            pendingReturnConsumeChapter = null
        }
    }
    private fun canReturnToPriorPosition(): Boolean {
        val point = returnPoint ?: return false
        val here = current ?: return false
        if (chapters.none { it.id == point.chapterId }) return false
        return here.id != point.chapterId || kotlin.math.abs(progression() - point.progression) > .015
    }
    private fun returnToPriorPosition() {
        val point = returnPoint ?: return
        val chapter = chapters.firstOrNull { it.id == point.chapterId } ?: return
        auto.pause()
        options = false
        open(chapter.copy(progression = point.progression), returning = true)
    }
    private fun scheduleNextChapterPreload() {
        preloadJob?.cancel()
        if (graph.basePreferences.incognitoMode.get()) return
        val novelId = novel?.id ?: return
        val chapterId = current?.id ?: return
        if (!ready || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val capturedBoundary = boundary
        preloadJob = lifecycleScope.launch {
            delay(700)
            if (isActive && ready && boundary == capturedBoundary && current?.id == chapterId &&
                hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !graph.basePreferences.incognitoMode.get()) {
                repository.preloadNextText(novelId, chapterId)
            }
        }
    }
    private fun render(preserveChromeAnchor:Boolean=false) {
        if (pageDragActive) cancelPageDrag(resumeMeter = false)
        ready = false
        pendingViewportProgress=null
        viewportReflowPending=false
        if(!preserveChromeAnchor) {chromeViewportAnchor=null;chromeScrollAnchor=null}
        expectedRestorePosition=null
        expectedRestoreGesture=0L
        viewportChangeSerial++
        web.isHorizontalScrollBarEnabled = !pagedLayout
        web.isVerticalScrollBarEnabled = !pagedLayout
        val size = 21 * font/100f * resources.configuration.fontScale
        val line = lineSpacing
        val margin = 24 * pageMargin
        // The two side margins are added back as column gap so each page stride is exactly one
        // viewport. This keeps adjacent columns from bleeding into the reader's visible page.
        val layoutRules = if (pagedLayout) "html{height:100%;overflow-x:auto;overflow-y:hidden}body{box-sizing:border-box;height:100%;column-width:calc(100vw - ${margin * 2}px);column-gap:${margin * 2}px;column-fill:auto;overflow:visible;padding:28px ${margin}px 80px;font-family:ArkhiveSerif,serif;font-size:${size}px;line-height:$line;overflow-wrap:break-word}" else "html,body{min-height:100%;overflow-x:hidden}body{column-count:auto;column-width:auto;column-gap:normal;overflow-y:auto;padding:28px ${margin}px 80px;font-family:ArkhiveSerif,serif;font-size:${size}px;line-height:$line;overflow-wrap:break-word}"
        val palette=room() ?: if(dark) ReadingRoom.MIDNIGHT_VIOLET else ReadingRoom.WARM_PAPER
        if (::readerRoot.isInitialized) readerRoot.setBackgroundColor(palette.paper)
        val background = String.format("#%06x",palette.paper and 0xffffff)
        val text = String.format("#%06x",palette.text and 0xffffff)
        val title = Entities.escape(current?.name.orEmpty())
        val sourceName = runCatching { repository.sources.get(requireNotNull(novel).sourceId).name }.getOrElse { novel?.sourceId.orEmpty() }
        val markup = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; font-src https://arkhive.local"><style>@font-face{font-family:ArkhiveSerif;src:url('https://arkhive.local/font/serif.ttf')}html,body{background:$background;color:$text;margin:0;padding:0}$layoutRules h1{font-weight:400;font-size:1.52em;line-height:1.2}h2,h3{font-weight:400;line-height:1.3}p{margin:0 0 1.1em}pre{white-space:pre-wrap}.chapter-label{font-family:sans-serif;font-size:12px;letter-spacing:.14em;text-transform:uppercase;opacity:.6}hr{opacity:.2}</style></head><body><div class="chapter-label">${Entities.escape(sourceName)}</div><h1>$title</h1>$body</body></html>"""
        web.setBackgroundColor(android.graphics.Color.parseColor(background))
        expectedUrl = "https://arkhive.local/chapter/${current?.id}?render=${++renderSerial}"
        web.loadDataWithBaseURL(expectedUrl, markup, "text/html", "UTF-8", null)
    }
    private fun advance(pixels: Int, animate: Boolean): ReaderAutoAdvanceResult {
        if(!ready) return ReaderAutoAdvanceResult.NOT_READY
        if (pagedLayout) return advancePage(if(pixels<0) -1 else 1,animate)
        val before = web.scrollY
        val end = web.end()
        val next = (before + pixels).coerceIn(0, end)
        if(next == before) return ReaderAutoAdvanceResult.CHAPTER_END
        clearChromeViewportAnchor()
        if(animate) {
            autoStepScroller.scrollBy(web, 0, next - before)
        } else web.scrollTo(0, next)
        return if(next == end && pixels > 0 && !animate) ReaderAutoAdvanceResult.CHAPTER_END else ReaderAutoAdvanceResult.ADVANCED
    }
    private fun pageStridePx():Int {
        return web.width.coerceAtLeast(1)
    }
    /** Move to the adjacent page boundary from an aligned or restored partial-page offset. */
    private fun adjacentPageTarget(before:Int,direction:Int):Int {
        if(direction==0) return before.coerceIn(0,web.endX())
        val stride=pageStridePx()
        val position=before.toDouble()/stride
        val nearest=position.roundToInt()
        val aligned=kotlin.math.abs(before-nearest*stride)<=maxOf(2,(2*resources.displayMetrics.density).toInt())
        val targetPage=when {
            direction>0 -> if(aligned) nearest+1 else kotlin.math.floor(position).toInt()+1
            aligned -> nearest-1
            else -> kotlin.math.ceil(position).toInt()-1
        }
        val lastPage=kotlin.math.ceil(web.endX().toDouble()/stride).toInt()
        return (targetPage.coerceIn(0,lastPage)*stride).coerceAtMost(web.endX())
    }
    private fun advancePage(direction:Int,animate:Boolean):ReaderAutoAdvanceResult {
        if(!ready) return ReaderAutoAdvanceResult.NOT_READY
        val before=web.scrollX
        val next=adjacentPageTarget(before,direction)
        if(next==before) return ReaderAutoAdvanceResult.CHAPTER_END
        clearChromeViewportAnchor()
        if(animate) autoStepScroller.scrollBy(web,next-before,0) else web.scrollTo(next,0)
        return ReaderAutoAdvanceResult.ADVANCED
    }
    private fun advancePageWithinChapter(direction:Int,animate:Boolean):ReaderAutoAdvanceResult {
        if (!ready || !pagedLayout) return ReaderAutoAdvanceResult.NOT_READY
        val before = web.scrollX
        val target=adjacentPageTarget(before,direction)
        if (target == before) return ReaderAutoAdvanceResult.CHAPTER_END
        clearChromeViewportAnchor()
        if (animate) autoStepScroller.scrollBy(web, target - before, 0) else web.scrollTo(target, 0)
        return ReaderAutoAdvanceResult.ADVANCED
    }

    private fun settlePageDrag(target: Int, commit: Boolean) {
        if (!pageDragActive) return
        val from = web.scrollX
        val to = target.coerceIn(0, web.endX())
        pageDragSettleTarget = to
        pageDragSettleCommits = commit && to != pageDragStartX
        if (from == to || !ReaderAutoStepScroller.animationsAllowed(this)) {
            web.scrollTo(to, 0)
            finishPageDragState(pageDragSettleCommits)
            return
        }
        val animator = ValueAnimator.ofInt(from, to).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { web.scrollTo(it.animatedValue as Int, 0) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && pageDragAnimator === animation) finishPageDragState(pageDragSettleCommits)
                }
            })
        }
        pageDragAnimator = animator
        animator.start()
    }

    /** Cancel restores the exact pre-gesture scroll and leaves saved progress untouched. */
    private fun cancelPageDrag(resumeMeter: Boolean = true) {
        if (!pageDragActive) return
        pageDragAnimator?.cancel()
        pageDragAnimator = null
        pageDragSettleTarget = null
        pageDragSettleCommits = false
        web.scrollTo(pageDragStartX, 0)
        pageDragActive = false
        progressHandler.removeCallbacks(persistAfterScroll)
        if (resumeMeter && ready && hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) meter.resume()
    }

    private fun interruptPageDragForRetouch() {
        if (!pageDragActive) return
        pageDragAnimator?.cancel()
        pageDragAnimator = null
        pageDragSettleTarget = null
        pageDragSettleCommits = false
        web.scrollTo(pageDragStartX, 0)
        pageDragActive = false
        progressHandler.removeCallbacks(persistAfterScroll)
    }

    private fun finishPageDragState(committed: Boolean) {
        pageDragAnimator = null
        pageDragSettleTarget = null
        pageDragSettleCommits = false
        pageDragActive = false
        if (committed) {
            clearChromeViewportAnchor()
            updateChapterPercent()
            progressHandler.removeCallbacks(persistAfterScroll)
            persist()
        }
        if (ready && hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) meter.resume()
    }

    private fun cancelPageDragBeforeLeaving() {
        // A pending settle is transient. Chapter/lifecycle changes preserve the last committed
        // page, so cancellation cannot persist or complete the old gesture's target.
        cancelPageDrag(resumeMeter = false)
    }

    private fun canSwitchToContinuousScroll(): Boolean =
        ready && pagedLayout &&
            auto.systemAllowsContinuousMotion()

    private fun switchToContinuousScroll() {
        if (!canSwitchToContinuousScroll()) return
        val id = novel?.id ?: return
        val key = "novel:$id"
        persist()
        restoredProgress = current?.progression ?: 0.0
        readingLayout = "scroll"
        graph.libraryToolsStore.setProfile(key, graph.libraryToolsStore.profile(key).copy(layout = "scroll"))
        render()
        auto.update(auto.state.settings.copy(mode = ReaderAutomationMode.CONTINUOUS))
    }

    private fun selectReadingLayout(layout: String) {
        if (!ready || layout !in setOf("pages", "scroll") || layout == readingLayout) return
        val previewAnchor = commitTypographyPreviewAnchor()
        if (previewAnchor == null) persist()
        restoredProgress = previewAnchor ?: current?.progression ?: 0.0
        auto.pause()
        readingLayout = layout
        novel?.id?.let { id ->
            val key = "novel:$id"
            graph.libraryToolsStore.setProfile(key, graph.libraryToolsStore.profile(key).copy(layout = layout))
        }
        auto.update(auto.state.settings, persist = false)
        render()
    }

    private fun beginTypographyPreview() {
        if (typographyPreviewProgress == null) {
            auto.pause()
            typographyPreviewProgress = if (ready) progression() else current?.progression ?: 0.0
        }
    }
    private fun scheduleTypographyPreview() {
        typographyPreviewJob?.cancel()
        typographyPreviewJob = lifecycleScope.launch {
            delay(110)
            val anchor = typographyPreviewProgress ?: return@launch
            if (body.isNotBlank() && current != null) {
                restoredProgress = anchor.coerceIn(0.0, 1.0)
                render()
            }
        }
    }
    private fun persistAppearance() {
        preferences.edit().putFloat("font", font).putFloat("line", lineSpacing).putFloat("margin", pageMargin).apply()
    }
    private fun finishTypographyPreview() {
        if (commitTypographyPreviewAnchor() == null) return
        if (body.isNotBlank() && current != null) render()
    }
    private fun commitTypographyPreviewAnchor(): Double? {
        val anchor = typographyPreviewProgress ?: return null
        typographyPreviewJob?.cancel(); typographyPreviewJob = null
        persistAppearance()
        val normalized = anchor.coerceIn(0.0, 1.0)
        restoredProgress = normalized
        current?.let { chapter ->
            val updated = chapter.copy(progression = normalized)
            current = updated
            chapters = chapters.map { if (it.id == chapter.id) updated else it }
            if (!graph.basePreferences.incognitoMode.get()) {
                novel?.let { repository.saveProgress(it.id, chapter.id, normalized) }
                lastPersisted = chapter.id to (normalized * 10000).toInt()
            }
            lastMeasuredProgress = normalized
        }
        typographyPreviewProgress = null
        return normalized
    }
    private fun settleTypographyPreviewForUserInput() {
        commitTypographyPreviewAnchor() ?: return
        // The user's next touch owns movement; don't issue another reflow here.
    }
    private fun previewFontSize(value: Float) { beginTypographyPreview(); font = value; scheduleTypographyPreview() }
    private fun previewLineSpacing(value: Float) { beginTypographyPreview(); lineSpacing = value; scheduleTypographyPreview() }
    private fun previewPageMargin(value: Float) { beginTypographyPreview(); pageMargin = value; scheduleTypographyPreview() }
    private fun selectReadingRoom(value: ReadingRoom) {
        val selectedNovel = novel ?: return
        val previewAnchor = commitTypographyPreviewAnchor()
        val anchor = previewAnchor ?: if (ready) progression() else current?.progression ?: 0.0
        if (previewAnchor == null) persist()
        auto.pause()
        graph.libraryToolsStore.setProfile("novel:${selectedNovel.id}",
            graph.libraryToolsStore.profile("novel:${selectedNovel.id}").copy(room = value))
        appearanceVersion++
        restoredProgress = anchor.coerceIn(0.0, 1.0)
        if (body.isNotBlank()) render()
    }

    private fun progression(): Double {
        val range = if (pagedLayout) web.endX() else web.end()
        val offset = if (pagedLayout) web.scrollX else web.scrollY
        // A viewport resize can make a short chapter fit without moving the reader. Keep the
        // last meaningful locator instead of interpreting the new zero range as completion.
        if (range <= 0) return lastMeasuredProgress.coerceIn(0.0, 1.0)
        return (offset.toDouble() / range).coerceIn(0.0, 1.0).also {lastMeasuredProgress=it}
    }
    private fun updateChapterPercent() {
        if (!ready || current == null) return
        val next = (progression() * 100).toInt().coerceIn(0, 100)
        if (chapterPercent != next) chapterPercent = next
    }
    private fun cancelWebTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            web.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun toggleReaderChrome() {
        // Header and footer are overlays, so changing chrome never resizes or repaginates the
        // publication. Keep the current page/scroll offset and avoid a synthetic reflow wait.
        if (!viewportReflowPending) {
            chromeViewportAnchor = null
            chromeScrollAnchor = null
            expectedRestorePosition = null
            progressHandler.removeCallbacks(persistAfterScroll)
        }
        menu = !menu
        persist()
    }
    private fun clearChromeViewportAnchor() {
        chromeViewportAnchor=null
        chromeScrollAnchor=null
        expectedRestorePosition=null
        if(viewportReflowPending) pendingViewportProgress=null
    }
    private fun restoreViewportProgress(progress:Double) {
        val normalized=progress.coerceIn(0.0,1.0)
        if(pagedLayout) {
            val end=web.endX()
            val stride=pageStridePx()
            val raw=(end*normalized).roundToInt()
            val snapped=((raw.toDouble()/stride).roundToInt()*stride).coerceIn(0,end)
            expectedRestorePosition=snapped to 0
            expectedRestoreGesture=userGestureSerial
            web.scrollTo(snapped,0)
        } else {
            val restoredY=(chromeScrollAnchor ?: (web.end()*normalized).roundToInt()).coerceIn(0,web.end())
            expectedRestorePosition=0 to restoredY
            expectedRestoreGesture=userGestureSerial
            web.scrollTo(0,restoredY)
        }
    }
    private fun persist() {
        val n = novel ?: return; val c = current ?: return
        if(!ready || pageDragActive || graph.basePreferences.incognitoMode.get()) return
        // A real gesture can supersede a pending resize anchor. Wait for the settled WebView
        // geometry callback to measure that newer position rather than saving the old value.
        if(viewportReflowPending && pendingViewportProgress == null) return
        // While Blink recalculates a resized viewport, persist the pre-reflow anchor rather
        // than interpreting its temporary range/offset as new reading progress.
        val progression = when {
            viewportReflowPending -> pendingViewportProgress ?: return
            chromeViewportAnchor != null -> chromeViewportAnchor!!
            else -> progression()
        }
        chapters = chapters.map { if(it.id == c.id) it.copy(progression = progression) else it }
        current = c.copy(progression = progression)
        if(progression>=.999) meter.complete(c.id)
        val key = c.id to (progression*10000).toInt()
        if(lastPersisted != key) { lastPersisted=key;repository.saveProgress(n.id, c.id, progression) }
    }
    override fun onResume() { super.onResume();graph.readerForegroundSignal.enter(this);repository.readerForeground(true);if(ready && hasWindowFocus()) { meter.resume(); scheduleNextChapterPreload() } }
    override fun onPause() {
        cancelPageDragBeforeLeaving()
        graph.readerForegroundSignal.leave(this)
        graph.smartOfflineDispatcher.onReaderIdle()
        meter.pause()
        auto.pause(ReaderAutomationPause.BACKGROUND)
        val previewing = typographyPreviewProgress != null
        if (previewing) settleTypographyPreviewForUserInput() else persist()
        preloadJob?.cancel(); preloadJob = null
        progressHandler.removeCallbacks(persistAfterScroll)
        repository.readerForeground(false)
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if(!hasFocus) {cancelPageDragBeforeLeaving();preloadJob?.cancel();auto.pause(ReaderAutomationPause.BACKGROUND);meter.pause()} else if(ready) {meter.resume();scheduleNextChapterPreload()} }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // The visible strip and Auto read sheet contain Pause/Resume actions. Pausing on
            // their ACTION_DOWN can recompose Pause as Resume before ACTION_UP and restart.
            val touchesAutomationControls = automation ||
                (::footerView.isInitialized && menu && event.y >= footerView.top)
            if (!touchesAutomationControls) auto.touchDown()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) auto.touchUp()
        return super.dispatchTouchEvent(event)
    }
    override fun onSaveInstanceState(outState: Bundle) { cancelPageDragBeforeLeaving(); if(typographyPreviewProgress!=null) settleTypographyPreviewForUserInput() else persist(); outState.putString(CHAPTER_ID, current?.id); outState.putDouble("progression",current?.progression ?: 0.0);super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        cancelPageDragBeforeLeaving();loadJob?.cancel();preloadJob?.cancel();typographyPreviewJob?.cancel();progressHandler.removeCallbacks(persistAfterScroll); auto.pause(); web.stopLoading(); web.destroy(); super.onDestroy()
    }

    @Composable private fun Header() {
        val palette = remember(appearanceVersion, novel?.id, dark) { readerPalette() }
        val paper = Color(palette.paper)
        val ink = Color(palette.text)
        LaunchedEffect(palette.paper) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = paper.luminance() > .5f
                isAppearanceLightNavigationBars = paper.luminance() > .5f
            }
        }
        if(menu) Surface(Modifier.fillMaxWidth(), color=paper, contentColor=ink) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min=62.dp), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick={finish()}) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f), horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(novel?.detail?.name ?: "Opening novel…", maxLines=1, fontSize=14.sp,
                            overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(current?.name.orEmpty() + if(current?.downloaded == true) " · saved offline" else "",
                            color=ink.copy(alpha=.65f), maxLines=1, fontSize=11.sp,
                            overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (canReturnToPriorPosition()) TextButton(onClick = ::returnToPriorPosition,
                        colors = ButtonDefaults.textButtonColors(contentColor = ink)) { Text("Return", fontSize = 11.sp) }
                    TextButton(onClick={
                        if (typographyPreviewProgress != null) finishTypographyPreview()
                        options = true; auto.pause()
                    }, colors=ButtonDefaults.textButtonColors(contentColor=ink)) {
                        Text("Aa", fontFamily=androidx.compose.ui.text.font.FontFamily.Serif, fontSize=22.sp)
                    }
                }
                HorizontalDivider(color=ink.copy(alpha=.12f))
            }
        }
    }
    @Composable private fun ReaderStatusOverlay() {
        val state = novelReaderStatus(ready = ready, error = message != null)
        SideEffect { statusView.visibility = if (state == NovelReaderStatus.CONTENT) View.GONE else View.VISIBLE }
        if (state == NovelReaderStatus.CONTENT) return
        val palette = remember(appearanceVersion, novel?.id, dark) { readerPalette() }
        val ink = Color(palette.text)
        val paper = Color(palette.paper)
        val density = LocalDensity.current
        val topInset = with(density) { headerHeightPx.toDp() } + 16.dp
        val bottomInset = with(density) { footerHeightPx.toDp() } + 16.dp
        Box(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                shape = RoundedCornerShape(18.dp),
                color = paper,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    when (state) {
                        NovelReaderStatus.CONTENT -> Unit
                        NovelReaderStatus.LOADING -> {
                            CircularProgressIndicator()
                            Text("Opening chapter…", color = ink.copy(alpha = .75f), modifier = Modifier.padding(top = 12.dp))
                        }
                        NovelReaderStatus.ERROR -> {
                            Text(message ?: "Chapter unavailable.", color = ink)
                            TextButton(onClick = { current?.let(::open) }) { Text("Retry chapter") }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Footer() {
        val palette = remember(appearanceVersion, novel?.id, dark) { readerPalette() }
        val paper = Color(palette.paper)
        val ink = Color(palette.text)
        val footerSurface = Color(androidx.core.graphics.ColorUtils.blendARGB(palette.paper, palette.text, .08f))
        LaunchedEffect(palette.paper) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = paper.luminance() > .5f
                isAppearanceLightNavigationBars = paper.luminance() > .5f
            }
        }
        Surface(Modifier.fillMaxWidth(), color = paper, contentColor = ink) {
            Column(Modifier.fillMaxWidth()) {
                if (ready || body.isNotBlank()) {
                    if (menu) {
                        val index = chapters.indexOfFirst { it.id == current?.id }
                        Column(
                            Modifier.fillMaxWidth().background(paper)
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Chapter ${(index + 1).coerceAtLeast(0)} of ${chapters.size}", fontSize = 12.sp, color = ink.copy(alpha = .68f))
                                Text("$chapterPercent%", fontSize = 12.sp, color = ink.copy(alpha = .68f),
                                    modifier = Modifier.semantics { contentDescription = "Chapter progress, $chapterPercent percent" })
                            }
                            LinearProgressIndicator(
                                progress = { chapterPercent.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = ink.copy(alpha = .78f),
                                trackColor = ink.copy(alpha = .16f),
                                drawStopIndicator = {},
                            )
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(footerSurface).heightIn(min = 56.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                IconButton(enabled = ready && index > 0, onClick = { auto.pause(); open(chapters[index - 1], deliberateJump = true) }) {
                                    Icon(MaterialSymbols.Rounded.KeyboardArrowLeft, "Previous chapter")
                                }
                                TextButton(enabled = ready, onClick = { contents = true; auto.pause() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = ink)) {
                                    Icon(MaterialSymbols.AutoMirroredRounded.FormatListBulleted, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Contents", fontSize = 13.sp)
                                }
                                IconButton(
                                    enabled = ready,
                                    modifier = Modifier.clip(CircleShape)
                                        .background(if (current?.bookmark == true) ink.copy(alpha = .16f) else Color.Transparent)
                                        .semantics { stateDescription = if (current?.bookmark == true) "Bookmarked" else "Not bookmarked" },
                                    onClick = {
                                        auto.pause()
                                        val chapter = current ?: return@IconButton
                                        lifecycleScope.launch {
                                            repository.bookmark(chapter.id, !chapter.bookmark)
                                            current = chapter.copy(bookmark = !chapter.bookmark)
                                            chapters = chapters.map { if (it.id == chapter.id) current!! else it }
                                        }
                                    },
                                ) {
                                    Icon(
                                        MaterialSymbols.Rounded.Bookmark,
                                        if (current?.bookmark == true) "Remove bookmark" else "Bookmark",
                                        tint = if (current?.bookmark == true) ink else ink.copy(alpha = .72f),
                                    )
                                }
                                IconButton(enabled = ready && index >= 0 && index < chapters.lastIndex, onClick = {
                                    auto.pause(); open(chapters[index + 1], deliberateJump = true)
                                }) {
                                    Icon(MaterialSymbols.Rounded.KeyboardArrowRight, "Next chapter")
                                }
                            }
                            ReaderAutomationStripControl(
                                auto.state, ready, ::startAutomationFromSheet, { auto.pause() },
                                Modifier.fillMaxWidth(), statusColor = ink,
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().background(paper)
                                .heightIn(min = 20.dp).padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator(
                                progress = { chapterPercent.coerceIn(0, 100) / 100f },
                                modifier = Modifier.weight(1f).height(2.dp),
                                color = ink.copy(alpha = .78f),
                                trackColor = ink.copy(alpha = .16f),
                                drawStopIndicator = {},
                            )
                            Text("$chapterPercent%", fontSize = 10.sp, color = ink,
                                modifier = Modifier.semantics { contentDescription = "Chapter progress, $chapterPercent percent" })
                        }
                    }
                }
            }
        }
        if(automation) ModalBottomSheet(
            onDismissRequest={automation=false;auto.pause()},
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        ) {
            ReaderAutomationControls(auto.state, true, ready, ::startAutomationFromSheet, {auto.pause()},
                {auto.update(it)}, {auto.update(it,false)}, {auto.update(auto.state.settings)},
                onClose={automation=false},
                continuousUnavailableMessage="Use scrolling text with system motion enabled. Touch exploration disables continuous motion.",
                continuousTransitionDescription="Switch this novel to scrolling text at the current reading position, then start Continuous scroll.",
                onContinuousTransition=if(canSwitchToContinuousScroll()) ::switchToContinuousScroll else null,
                timedStepDescription=if(pagedLayout) "Turns one full page. Chapter boundaries pause playback." else "Moves three quarters of the visible screen. Chapter boundaries pause playback.")
        }
        if(options) AlertDialog(onDismissRequest={finishTypographyPreview();options=false},title={Text("Reading layout & appearance")},text={Column(Modifier.heightIn(max=440.dp)) {
            SharedTextAppearancePreview(font, lineSpacing, pageMargin, palette)
            Column(Modifier.heightIn(max=330.dp).verticalScroll(rememberScrollState())) {
                SharedTextAppearancePanel(
                    layout=readingLayout,
                    onLayoutSelected=::selectReadingLayout,
                    fontPercent=font,
                    onFontPreview=::previewFontSize,
                    onFontSettled=::finishTypographyPreview,
                    lineHeight=lineSpacing,
                    onLineHeightPreview=::previewLineSpacing,
                    onLineHeightSettled=::finishTypographyPreview,
                    margins=pageMargin,
                    onMarginsPreview=::previewPageMargin,
                    onMarginsSettled=::finishTypographyPreview,
                    effectiveRoom=palette,
                    onRoomSelected=::selectReadingRoom,
                    supportsLayout=true,
                )
                TextButton(onClick={finishTypographyPreview();options=false;automation=true;auto.pause()}, modifier=Modifier.fillMaxWidth()) {
                    Text("Auto read")
                }
            }
        }},confirmButton={TextButton(onClick={finishTypographyPreview();options=false}){Text("Done")}})
        if(contents) AlertDialog(onDismissRequest={contents=false},title={Text("Chapters")},text={LazyColumn {
            items(chapters,key={it.id}) {c->TextButton(onClick={contents=false;open(c, deliberateJump = true)}) {Text(c.name+if(c.downloaded) " · saved" else "")}}
        }},confirmButton={TextButton(onClick={contents=false}){Text("Close")}})
    }
    internal class TextWebView(context: Context) : WebView(context) {
        fun end() = (computeVerticalScrollRange()-height).coerceAtLeast(0)
        fun endX() = (computeHorizontalScrollRange()-width).coerceAtLeast(0)
    }
}
