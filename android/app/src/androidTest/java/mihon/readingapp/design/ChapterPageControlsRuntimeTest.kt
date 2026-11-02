package mihon.readingapp.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.readingapp.library.chapterPageWindow
import mihon.readingapp.settings.InkSettingsTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChapterPageControlsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val uiVariant get() = InstrumentationRegistry.getArguments().getString("arkhive_ui_variant", "revised")

    @Before
    fun requireExplicitUiVariant() {
        assertTrue("Pass arkhive_ui_variant=baseline or revised", uiVariant in setOf("baseline", "revised"))
    }

    @Test
    fun narrowLargeTextKeepsBothActionsReachableAndPagesBounded() {
        val measuredHeight = AtomicInteger()
        val scenario = launchPager(
            widthDp = 320,
            fontScale = 1.3f,
            itemCount = 201,
            onCurrent = true,
            onNextUnread = true,
            measuredHeight = measuredHeight,
        )
        try {
            val pageOne = pageDescription(1, 3, "1 to 100 of 201")
            val pageButton = requireNotNull(device.wait(Until.findObject(By.desc(pageOne)), 15_000))
            logMeasuredHeight(widthDp = 320, fontScale = 1.3f, itemCount = 201, optionalActions = 2, measuredHeight = measuredHeight)
            if (uiVariant == "revised") {
                assertTrue("Compact pager stays within 80dp at 320dp/1.3x", measuredHeight.get() / context.resources.displayMetrics.density <= 80f)
            }
            assertTrue("Page jump target remains at least 48dp", pageButton.visibleBounds.height() >= dpToPx(48))
            assertInsideViewport(pageButton, 320)
            assertTrue("First page has a disabled Previous action", !requireNotNull(device.findObject(By.desc("Previous page"))).isEnabled)
            assertTrue("Next page is enabled on the first page", requireNotNull(device.findObject(By.desc("Next page"))).isEnabled)

            if (uiVariant == "revised") {
                val menuButton = requireNotNull(device.findObject(By.desc("More chapter actions")))
                assertTrue("Narrow fallback keeps a 48dp actions target", menuButton.visibleBounds.width() >= dpToPx(48))
                menuButton.click()
            } else {
                assertTrue("Baseline keeps Current directly visible", device.hasObject(By.text("Current")))
                assertTrue("Baseline keeps Next unread directly visible", device.hasObject(By.text("Next unread")))
            }
            requireNotNull(device.wait(Until.findObject(By.text("Current")), 5_000)).click()
            assertNotNull("Current callback remains wired through overflow", device.wait(Until.findObject(By.text("Current selected")), 5_000))
            if (uiVariant == "revised") requireNotNull(device.findObject(By.desc("More chapter actions"))).click()
            requireNotNull(device.wait(Until.findObject(By.text("Next unread")), 5_000)).click()
            assertNotNull("Next unread callback remains wired through overflow", device.wait(Until.findObject(By.text("Next unread selected")), 5_000))
            assertTrue("Capture the narrow shared pager with both actions", device.takeScreenshot(File(context.cacheDir, "qa-chapter-pager-both-320dp-1.3x-$uiVariant.png")))

            requireNotNull(device.findObject(By.desc("Next page"))).click()
            assertNotNull("Next reaches page two", device.wait(Until.findObject(By.desc(pageDescription(2, 3, "101 to 200 of 201"))), 5_000))
            requireNotNull(device.findObject(By.desc("Next page"))).click()
            val pageThree = pageDescription(3, 3, "201 to 201 of 201")
            assertNotNull("Next reaches the final one-item page", device.wait(Until.findObject(By.desc(pageThree)), 5_000))
            assertFalse("Final page disables Next", requireNotNull(device.findObject(By.desc("Next page"))).isEnabled)
            requireNotNull(device.findObject(By.desc("Previous page"))).click()
            val pageTwo = pageDescription(2, 3, "101 to 200 of 201")
            assertNotNull("Previous returns to page two", device.wait(Until.findObject(By.desc(pageTwo)), 5_000))

            requireNotNull(device.findObject(By.desc(pageTwo))).click()
            setSearchQuery("single-result")
            requireNotNull(device.findObject(By.text("Done"))).click()
            val singlePage = pageDescription(1, 1, "1 to 1 of 1", "single-result")
            assertNotNull("Single result has a reachable one-page state", device.wait(Until.findObject(By.desc(singlePage)), 5_000))
            assertFalse("Single page disables Previous", requireNotNull(device.findObject(By.desc("Previous page"))).isEnabled)
            assertFalse("Single page disables Next", requireNotNull(device.findObject(By.desc("Next page"))).isEnabled)

            requireNotNull(device.findObject(By.desc(singlePage))).click()
            setSearchQuery("no-results")
            requireNotNull(device.findObject(By.text("Done"))).click()
            val noMatches = By.desc(
                if (uiVariant == "revised") "No matches for no-results. Search or jump to a chapter."
                else "No matches. Search or jump to a chapter.",
            )
            assertNotNull("Empty search keeps the find action visible", device.wait(Until.findObject(noMatches), 5_000))
            requireNotNull(device.findObject(noMatches)).click()
            assertNotNull("Find reopens from the no-match state", device.wait(Until.findObject(By.text("Find or jump to chapter")), 5_000))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun doubleFontScaleEmptyListWithoutOptionalActionsKeepsFindReachable() {
        val measuredHeight = AtomicInteger()
        val scenario = launchPager(
            widthDp = 320,
            fontScale = 2f,
            itemCount = 0,
            onCurrent = false,
            onNextUnread = false,
            measuredHeight = measuredHeight,
        )
        try {
            val noChapters = By.desc("No chapters. Find chapters or search.")
            val pageButton = requireNotNull(device.wait(Until.findObject(noChapters), 15_000))
            logMeasuredHeight(widthDp = 320, fontScale = 2f, itemCount = 0, optionalActions = 0, measuredHeight = measuredHeight)
            if (uiVariant == "revised") {
                assertTrue("Double-font pager remains compact at 320dp", measuredHeight.get() / context.resources.displayMetrics.density <= 96f)
            }
            assertTrue("Find target remains at least 48dp", pageButton.visibleBounds.height() >= dpToPx(48))
            assertInsideViewport(pageButton, 320)
            assertFalse("Empty list disables Previous", requireNotNull(device.findObject(By.desc("Previous page"))).isEnabled)
            assertFalse("Empty list disables Next", requireNotNull(device.findObject(By.desc("Next page"))).isEnabled)
            assertFalse("No-callback layout does not add a dead overflow button", device.hasObject(By.desc("More chapter actions")))
            assertTrue("Capture the empty-list large-text pager", device.takeScreenshot(File(context.cacheDir, "qa-chapter-pager-empty-320dp-2.0x-$uiVariant.png")))

            pageButton.click()
            assertNotNull("Find dialog opens from the empty list at 2.0x", device.wait(Until.findObject(By.text("Find or jump to chapter")), 5_000))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun singleNextUnreadActionUsesInlineSpaceWhenItFits() {
        val measuredHeight = AtomicInteger()
        val scenario = launchPager(
            widthDp = 420,
            fontScale = 1f,
            itemCount = 7313,
            onCurrent = false,
            onNextUnread = true,
            measuredHeight = measuredHeight,
        )
        try {
            assertNotNull(
                "Large chapter totals stay accessible in the compact page control",
                device.wait(Until.findObject(By.desc(pageDescription(1, 74, "1 to 100 of 7313"))), 15_000),
            )
            logMeasuredHeight(widthDp = 420, fontScale = 1f, itemCount = 7313, optionalActions = 1, measuredHeight = measuredHeight)
            val moreButton = device.findObject(By.desc("More chapter actions"))
            if (uiVariant == "revised" && moreButton != null) {
                moreButton.click()
                assertNotNull("Constrained host retains Next unread in overflow", device.wait(Until.findObject(By.text("Next unread")), 5_000))
            } else {
                assertNotNull("Common Next unread action stays visibly labeled inline", device.wait(Until.findObject(By.text("Next unread")), 15_000))
                assertFalse("A fitting action does not leave a redundant More button", device.hasObject(By.desc("More chapter actions")))
            }
            if (uiVariant == "revised") {
                assertTrue("Single-action toolbar stays below 64dp", measuredHeight.get() / context.resources.displayMetrics.density <= 64f)
            }
            requireNotNull(device.findObject(By.text("Next unread"))).click()
            assertNotNull("Inline Next unread callback remains wired", device.wait(Until.findObject(By.text("Next unread selected")), 5_000))
        } finally {
            scenario.close()
        }
    }

    private fun launchPager(
        widthDp: Int,
        fontScale: Float,
        itemCount: Int,
        onCurrent: Boolean,
        onNextUnread: Boolean,
        measuredHeight: AtomicInteger,
    ): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContentView(ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val density = LocalDensity.current
                    InkSettingsTheme {
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
                                    PagerFixture(itemCount, onCurrent, onNextUnread, measuredHeight)
                                }
                            }
                        }
                    }
                }
            })
        }
        return scenario
    }

    @androidx.compose.runtime.Composable
    private fun PagerFixture(
        itemCount: Int,
        onCurrent: Boolean,
        onNextUnread: Boolean,
        measuredHeight: AtomicInteger,
    ) {
        var query by remember { mutableStateOf("") }
        var pageIndex by remember { mutableIntStateOf(0) }
        var actionMessage by remember { mutableStateOf("") }
        val filteredCount = when (query) {
            "single-result" -> 1
            "no-results" -> 0
            else -> itemCount
        }
        val window = chapterPageWindow(filteredCount, pageIndex)
        val currentAction: (() -> Unit)? = if (onCurrent) ({ actionMessage = "Current selected" }) else null
        val unreadAction: (() -> Unit)? = if (onNextUnread) ({ actionMessage = "Next unread selected" }) else null

        Column(Modifier.fillMaxWidth()) {
            ChapterPageControls(
                window = window,
                totalItems = filteredCount,
                query = query,
                onQueryChange = { query = it; pageIndex = 0 },
                onPageChange = { pageIndex = it },
                onCurrentPage = currentAction,
                onNextUnreadPage = unreadAction,
                modifier = Modifier.onSizeChanged { measuredHeight.set(it.height) },
            )
            if (actionMessage.isNotBlank()) androidx.compose.material3.Text(actionMessage)
        }
    }

    private fun setSearchQuery(query: String) {
        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
        assertTrue("Find dialog exposes a search input", fields.size >= 2)
        fields[0].text = query
        device.pressBack()
    }

    private fun pageDescription(page: Int, pages: Int, chapterRange: String, query: String? = null): String =
        if (uiVariant == "revised") {
            "Page $page of $pages. Chapters $chapterRange. " +
                (query?.let { "Search filter: $it. " } ?: "") +
                "Find or jump to a chapter or search."
        } else {
            "Page $page of $pages. Find or jump to a chapter or search."
        }

    private fun dpToPx(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun logMeasuredHeight(
        widthDp: Int,
        fontScale: Float,
        itemCount: Int,
        optionalActions: Int,
        measuredHeight: AtomicInteger,
    ) {
        val heightDp = measuredHeight.get() / context.resources.displayMetrics.density
        android.util.Log.i(
            "ChapterPageControlsRuntime",
            "variant=$uiVariant widthDp=$widthDp fontScale=$fontScale items=$itemCount optionalActions=$optionalActions heightDp=$heightDp",
        )
    }

    private fun assertInsideViewport(target: androidx.test.uiautomator.UiObject2, widthDp: Int) {
        val viewportWidthPx = dpToPx(widthDp)
        val viewportLeftPx = (device.displayWidth - viewportWidthPx) / 2
        assertTrue("Control stays inside the configured width", target.visibleBounds.left >= viewportLeftPx)
        assertTrue("Control does not overflow the configured width", target.visibleBounds.right <= viewportLeftPx + viewportWidthPx)
    }
}
