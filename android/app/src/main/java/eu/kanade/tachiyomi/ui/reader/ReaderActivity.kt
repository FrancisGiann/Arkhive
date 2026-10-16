// Modified for the private ReadingApp prototype; see READINGAPP-PROTOTYPE.md.
package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationController
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationControls
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationStripControl
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationPause
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

class ReaderActivity : BaseActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    companion object {
        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    @Inject private lateinit var readerPreferences: ReaderPreferences

    @Inject private lateinit var preferences: BasePreferences

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel> { graph.viewModelFactory }
    private var assistUrl: String? = null
    private var automationStartJob: kotlinx.coroutines.Job? = null
    private var quietReaderVisible = false
    private var quietReaderTop = 0
    private var quietReaderBottom = 0
    private var quietReaderTopVisible = false
    private var quietReaderBottomVisible = false
    private var quietReaderBackgroundVisible = false
    private var quietReaderBackground: Int? = null
    private var themeCanvasBackground: Int = android.graphics.Color.BLACK
    private var activeCompletionChapterId: Long? = null
    private var activeCompletionPageNumber: Int? = null
    private var activeCompletionPage: ReaderPage? = null
    private var chapterCompletionJob: kotlinx.coroutines.Job? = null
    private val completionForegroundFocused = MutableStateFlow(false)

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val readerAutomation by lazy {
        ReaderAutomationController(
            context = this,
            viewer = { viewModel.state.value.viewer },
            chapterId = { viewModel.state.value.currentChapter?.chapter?.id },
            readerActive = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() },
        )
    }

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }
    private val readingMeter by lazy {mihon.readingapp.library.ReadingSessionMeter(graph.libraryToolsStore,
        {!preferences.incognitoMode.get()},{viewModel.manga?.id?.let {"manga:$it"}})}

    private var loadingIndicator: ReaderProgressIndicator? = null

    var isScrollingThroughPages = false
        private set

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        NotificationReceiver.dismissNotification(
            this,
            viewModel.mangaId.hashCode(),
            Notifications.ID_NEW_CHAPTERS,
        )

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setInitialChapterError)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        var autoOptionsVisible by remember { mutableStateOf(false) }
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber.collectAsState()
        val settingsviewModel = remember {
            ReaderSettingsViewModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
                preferences = readerPreferences,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.menuVisible && showPageNumber) {
                ReaderPageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }

            ContentOverlay(state = state)

            AppBars(state = state, onAuto = { readerAutomation.pause(); autoOptionsVisible = true })
        }

        if (autoOptionsVisible) ModalBottomSheet(
            onDismissRequest = { autoOptionsVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ReaderAutomationControls(
                state = readerAutomation.uiState,
                menuVisible = true,
                canStart = state.currentChapter != null && state.viewer != null,
                onStart = {
                    automationStartJob?.cancel(); autoOptionsVisible = false
                    automationStartJob = lifecycleScope.launch {
                        val focused = kotlinx.coroutines.withTimeoutOrNull(1500) {
                            while (!hasWindowFocus()) kotlinx.coroutines.delay(16)
                            true
                        } == true
                        if (focused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) {
                            readerAutomation.start()
                            if (readerAutomation.uiState.running) hideMenu() else autoOptionsVisible = true
                        }
                    }
                },
                onPause = { readerAutomation.pause() },
                onSettingsChange = readerAutomation::updateSettings,
                onSettingsPreview = readerAutomation::previewSettings,
                onSettingsChangeFinished = { readerAutomation.updateSettings(readerAutomation.uiState.settings) },
                onClose = { autoOptionsVisible = false },
                continuousTransitionDescription = "Switch this manga to vertical scrolling at the current page, then start Continuous scroll.",
                onContinuousTransition = if (state.viewer is PagerViewer && readerAutomation.systemAllowsContinuousMotion()) {
                    ::switchToContinuousAutoRead
                } else null,
            )
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    viewModel = settingsviewModel,
                )
            }
            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsviewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        if (!readerPreferences.showReadingMode.get()) {
                            menuToggleToast = toast(stringRes)
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsviewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                )
            }
            null -> {}
        }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        readerAutomation.destroy()
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    override fun onPause() {
        graph.readerForegroundSignal.leave(this)
        graph.smartOfflineDispatcher.onReaderIdle()
        readingMeter.pause()
        automationStartJob?.cancel()
        readerAutomation.pause(ReaderAutomationPause.BACKGROUND)
        viewModel.saveHistory()
        super.onPause()
        updateCompletionForeground()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        graph.readerForegroundSignal.enter(this)
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
        updateCompletionForeground()
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateCompletionForeground()
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
            if(viewModel.state.value.viewer!=null) readingMeter.resume()
        } else {
            readerAutomation.pause(ReaderAutomationPause.BACKGROUND)
            readingMeter.pause()
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) readerAutomation.onTouchStarted()
        return try {
            val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
            handled || super.dispatchKeyEvent(event)
        } finally {
            if (event.action == KeyEvent.ACTION_UP) readerAutomation.onTouchEnded()
        }
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        readerAutomation.pause()
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) readerAutomation.onTouchStarted()
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                readerAutomation.onTouchEnded()
            }
        }
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    private fun updateQuietReaderBounds() {
        val params = binding.viewerContainer.layoutParams as android.widget.FrameLayout.LayoutParams
        val view = viewModel.state.value.viewer?.getView()
        val top = if(quietReaderVisible && quietReaderTopVisible) (quietReaderTop-(view?.paddingTop?:0)).coerceAtLeast(0) else 0
        val bottom = if(quietReaderVisible && quietReaderBottomVisible) (quietReaderBottom-(view?.paddingBottom?:0)).coerceAtLeast(0) else 0
        // The viewer already applies the user's pager/webtoon padding. Keep its outer viewport
        // edge-to-edge; controls should only change the vertical bounds, never add a second inset.
        val side = 0
        if(params.topMargin!=top || params.bottomMargin!=bottom || params.leftMargin!=side || params.rightMargin!=side) {
            params.topMargin=top;params.bottomMargin=bottom;params.leftMargin=side;params.rightMargin=side
            binding.viewerContainer.layoutParams=params
        }
        if(quietReaderBackgroundVisible) {
            if(quietReaderBackground==null) quietReaderBackground=(binding.readerContainer.background as? android.graphics.drawable.ColorDrawable)?.color?:android.graphics.Color.BLACK
            val room=viewModel.manga?.id?.let {graph.libraryToolsStore.profile("manga:$it").room} ?: graph.libraryToolsStore.state.value.room
            binding.readerContainer.setBackgroundColor(room?.paper ?: themeCanvasBackground)
        } else quietReaderBackground?.let {binding.readerContainer.setBackgroundColor(it);quietReaderBackground=null}
    }

    @Composable
    fun AppBars(state: ReaderViewModel.State, onAuto:()->Unit) {
        var tools by remember { mutableStateOf(false) }
        val controlsVisible = state.menuVisible && !tools
        val currentThemeCanvasBackground = MaterialTheme.colorScheme.background.toArgb()
        androidx.compose.runtime.SideEffect {
            themeCanvasBackground = currentThemeCanvasBackground
            quietReaderVisible = controlsVisible
            quietReaderTopVisible = controlsVisible
            quietReaderBottomVisible = controlsVisible
            quietReaderBackgroundVisible = controlsVisible
            updateQuietReaderBounds()
        }
        if(!tools) {
            if(state.menuVisible) mihon.readingapp.design.StoryboardReaderBars(
                title=state.manga?.title.orEmpty().substringBefore(" · Original demo"),
                chapter=state.currentChapter?.chapter?.name.orEmpty().substringBefore(" · Original demo"),
                strip=state.viewer is WebtoonViewer,rtl=state.viewer is R2LPagerViewer,
                page=state.currentPage,total=state.totalPages,bookmarked=state.bookmarked,
                nextChapter=state.viewerChapters?.nextChapter!=null,previousChapter=state.viewerChapters?.prevChapter!=null,
                onBack=onBackPressedDispatcher::onBackPressed,
                onForward={ readerAutomation.pause();val chapter=state.currentChapter?.chapter?.id
                    if(chapter!=null) state.viewer?.autoAdvanceWithinChapter(chapter,null) },
                onBackward={readerAutomation.pause();moveToPageIndex((state.currentPage-2).coerceAtLeast(0))},
                onMode=viewModel::openReadingModeSelectDialog,onTools={tools=true},onBookmark=viewModel::toggleChapterBookmark,onAuto=onAuto,
                onNextChapter=::loadNextChapter,onPreviousChapter=::loadPreviousChapter,
                onTopSize={quietReaderTop=it;updateQuietReaderBounds()},onBottomSize={quietReaderBottom=it;updateQuietReaderBounds()},
                autoReadControl={
                    ReaderAutomationStripControl(
                        state=readerAutomation.uiState,
                        canStart=state.currentChapter!=null && state.viewer!=null,
                        onStart={readerAutomation.start();if(readerAutomation.uiState.running) hideMenu()},
                        onPause={readerAutomation.pause()},
                        modifier=Modifier.fillMaxWidth(),
                    )
                })
            return
        }
        Box(Modifier.fillMaxSize()) {
            TextButton(onClick={tools=false},modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=72.dp,end=12.dp)) {Text("Simple controls")}
        }

        val isHttpSource = state.source is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())
        val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon

        val verticalNavigatorModes by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigator = verticalNavigatorModes.contains(
            ReadingMode.fromPreference(viewModel.getMangaReadingMode()),
        )
        val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()
        val verticalNavigatorHeight by readerPreferences.verticalNavigatorHeight.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            bookmarked = state.bookmarked,
            onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            chapterNavigatorType = if (!verticalNavigator) {
                if (state.viewer is R2LPagerViewer) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (verticalNavigatorOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            verticalNavigatorHeight = verticalNavigatorHeight / 100f,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },
            onPageIndexChangeFinished = {
                isScrollingThroughPages = false
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = viewModel::openSettingsDialog,
        )
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        if (visible) readerAutomation.pause()
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        readerAutomation.pause(ReaderAutomationPause.VIEWER_CHANGED)
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        viewModel.manga?.id?.let {readerAutomation.bindSeries("manga:$it")}
        readerAutomation.bindReadingMode(viewModel.getMangaReadingMode())
        config?.applySeriesProfile()
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        if (readerPreferences.showReadingMode.get()) {
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        readerAutomation.onChapterSelected(viewerChapters.currChapter.chapter.id)
        binding.readerContainer.removeView(loadingIndicator)
        viewModel.state.value.viewer?.setChapters(viewerChapters)

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        activeCompletionChapterId = page.chapter.chapter.id
        activeCompletionPageNumber = page.number
        activeCompletionPage = page
        chapterCompletionJob?.cancel()
        chapterCompletionJob = null
        // Update the selected chapter/page synchronously before observing Ready. A page can
        // already be decoded when selected, and a collector started first could complete the
        // previous chapter while the ViewModel still reports its old active page.
        viewModel.onPageSelected(page)
        if(hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) readingMeter.resume()
        if(page.index==page.chapter.pages?.lastIndex) {
            val selectedChapterId = page.chapter.chapter.id
            val selectedPageNumber = page.number
            chapterCompletionJob = lifecycleScope.launch {
                // Transport can succeed while image decoding fails. Wait for the viewer's
                // decoded-ready signal and foreground visibility so a broken or backgrounded
                // image cannot mark a chapter complete. Both are event-driven StateFlows: if
                // decode finishes in the background, foreground/focus re-arms completion.
                combine(page.renderStateFlow, completionForegroundFocused) { renderState, foreground ->
                    renderState == ReaderPage.RenderState.READY && foreground
                }.first { it }
                val current = viewModel.state.value
                if(page.status == Page.State.Ready &&
                    page.renderState == ReaderPage.RenderState.READY &&
                    page.index == page.chapter.pages?.lastIndex &&
                    activeCompletionPage === page &&
                    activeCompletionChapterId == selectedChapterId &&
                    activeCompletionPageNumber == selectedPageNumber &&
                    current.currentChapter?.chapter?.id == selectedChapterId &&
                    current.currentPage == selectedPageNumber &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    hasWindowFocus()
                ) {
                    readingMeter.complete(page.chapter.chapter.id.toString())
                }
            }
        }
        readerAutomation.onChapterSelected(page.chapter.chapter.id)
    }

    private fun updateCompletionForeground() {
        completionForegroundFocused.value = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()
    }

    fun pauseReaderAutomation() {
        readerAutomation.pause()
    }

    fun shouldAnimateReaderAutomationStep(): Boolean =
        readerAutomation.systemAllowsContinuousMotion() && readerPreferences.pageTransitions.get()

    private fun switchToContinuousAutoRead() {
        val settings = readerAutomation.uiState.settings.copy(mode = eu.kanade.tachiyomi.ui.reader.automation.ReaderAutomationMode.CONTINUOUS)
        readerAutomation.prepareSettingsForReadingMode(ReadingMode.WEBTOON.flagValue, settings)
        viewModel.setMangaReadingMode(ReadingMode.WEBTOON)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        viewModel.openPageDialog(page)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launch(Dispatchers.IO) { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(baseContext.readerBackgroundColor(theme))
                }
                .launchIn(lifecycleScope)

            preferences.displayProfile.changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                ImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            val selected=viewModel.manga?.id?.let {graph.libraryToolsStore.profile("manga:$it").brightnessPercent} ?: value
            // Calculate and set reader brightness.
            val readerBrightness = when {
                selected > 0 -> {
                    selected / 100f
                }
                selected < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(selected)
        }
        fun applySeriesProfile() {
            setCustomBrightnessValue(if(readerPreferences.customBrightness.get()) readerPreferences.customBrightnessValue.get() else 0)
            val room=viewModel.manga?.id?.let {graph.libraryToolsStore.profile("manga:$it").room} ?: graph.libraryToolsStore.state.value.room
            if(room!=null) {binding.readerContainer.setBackgroundColor(room.paper);binding.viewerContainer.setBackgroundColor(room.paper)}
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
