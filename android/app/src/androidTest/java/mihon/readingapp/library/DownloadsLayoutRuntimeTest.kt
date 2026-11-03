package mihon.readingapp.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.readingapp.design.Ink
import mihon.readingapp.settings.InkSettingsTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DownloadsLayoutRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun compactQueueAndSmartControlsFitNarrowLargeTextWithoutRevealingHiddenTitles() {
        listOf(1.3f, 2.0f).forEach { fontScale ->
            val pauses = AtomicInteger()
            var scenario: ActivityScenario<MainActivity>? = null
            try {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                scenario.onActivity { activity ->
                    activity.setContentView(ComposeView(activity).apply {
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                        setContent {
                            val baseDensity = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
                                InkSettingsTheme {
                                    Box(Modifier.fillMaxSize().background(Ink.Canvas), contentAlignment = Alignment.TopCenter) {
                                        Column(
                                            Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                                        ) {
                                            DownloadsQueueSummary(
                                                visibleCount = 0,
                                                hasHiddenQueue = true,
                                                visibleMangaCount = 0,
                                                visibleNovelCount = 0,
                                                mangaRunning = true,
                                                novelsPaused = false,
                                                actionState = DownloadsActionPolicy.resolve(
                                                    mangaQueueCount = 1,
                                                    mangaRunning = true,
                                                    novelQueueCount = 0,
                                                    novelsPaused = false,
                                                    smartRulesEnabled = false,
                                                    smartPaused = false,
                                                    smartRunning = false,
                                                ),
                                                onPause = { pauses.incrementAndGet() },
                                                onResume = {},
                                            )
                                            var expanded by remember { mutableStateOf(false) }
                                            var cleanupEnabled by remember { mutableStateOf(false) }
                                            androidx.compose.material3.Text(
                                                "Toggle cleanup fixture",
                                                Modifier.clickable { cleanupEnabled = !cleanupEnabled },
                                            )
                                            SmartOfflineSummary(
                                                savedCount = 0,
                                                status = "Smart enabled · waiting",
                                                currentChapter = null,
                                                message = null,
                                                expanded = expanded,
                                                onToggleExpanded = { expanded = !expanded },
                                                blockedOrigin = "https://cdn.fixture.invalid:9443",
                                                onReviewOrigin = {},
                                                cleanupEnabled = cleanupEnabled,
                                                cleanupMessage = null,
                                                onCleanup = {},
                                                onConfigureCleanup = {},
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    })
                }

                val pause = device.wait(Until.findObject(By.desc("Pause all downloads")), 5_000)
                assertNotNull("Global control remains available for work hidden by filters at font scale $fontScale", pause)
                assertTrue("Pause target meets a 48dp touch target", requireNotNull(pause).visibleBounds.width() >= (48 * context.resources.displayMetrics.density).toInt())
                assertNotNull("Hidden-only queue state stays generic", device.findObject(By.text("Some queued chapters are hidden by Library filters.")))
                assertFalse("A hidden title is never rendered", device.hasObject(By.textContains("Private hidden title")))
                assertNotNull("Exact blocked origin is visible before review", device.findObject(By.text("Blocked origin: https://cdn.fixture.invalid:9443")))

                android.util.Log.i("DownloadsLayout", "READY fontScale=$fontScale")
                val scaleLabel = if (fontScale == 1.3f) "1.3x" else "2.0x"
                assertTrue(
                    "Capture compact Downloads controls at 320dp and $scaleLabel",
                    device.takeScreenshot(File(context.cacheDir, "qa-downloads-320dp-$scaleLabel.png")),
                )
                requireNotNull(pause).click()
                assertTrue("Pause action invokes its callback", pauses.get() == 1)
                val details = device.findObject(By.desc("Show Smart Offline details"))
                assertNotNull("Smart Offline details can be expanded", details)
                requireNotNull(details).click()
                assertNotNull("Cleanup-off state has a readable configuration action", device.wait(Until.findObject(By.text("Set up Smart cleanup")), 5_000))
                assertNotNull("Cleanup eligibility is explained when expanded", device.findObject(By.textContains("completed, unbookmarked Smart-owned copies")))
                device.findObject(By.text("Toggle cleanup fixture")).click()
                assertNotNull("Cleanup-on state keeps the eligibility explanation visible", device.wait(Until.findObject(By.text("Clean eligible read copies")), 5_000))
                assertNotNull("Cleanup-on criteria remain visible before the action", device.findObject(By.textContains("completed, unbookmarked Smart-owned copies")))
            } finally {
                scenario?.close()
            }
        }
    }

    @Test fun emptyQueueExplainsMixedGlobalPauseAndResumeTargets() {
        val pauses = AtomicInteger()
        val resumes = AtomicInteger()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                    setContent {
                        InkSettingsTheme {
                            DownloadsQueueSummary(
                                visibleCount = 0,
                                hasHiddenQueue = false,
                                visibleMangaCount = 0,
                                visibleNovelCount = 0,
                                mangaRunning = false,
                                novelsPaused = true,
                                actionState = DownloadsActionPolicy.resolve(
                                    mangaQueueCount = 0,
                                    mangaRunning = false,
                                    novelQueueCount = 0,
                                    novelsPaused = true,
                                    smartRulesEnabled = true,
                                    smartPaused = false,
                                    smartRunning = false,
                                ),
                                onPause = { pauses.incrementAndGet() },
                                onResume = { resumes.incrementAndGet() },
                            )
                        }
                    }
                })
            }
            assertNotNull(device.wait(Until.findObject(By.text("No visible chapters queued")), 5_000))
            assertNotNull(device.findObject(By.textContains("Novel downloads paused")))
            assertNotNull(device.findObject(By.textContains("Smart enabled · waiting")))
            assertNotNull(device.findObject(By.text("Pause and resume apply across manga, novels and Smart Offline.")))
            assertNotNull(device.findObject(By.text("Pause all · Smart")))
            assertNotNull(device.findObject(By.text("Resume all · novels")))
            device.findObject(By.text("Pause all · Smart")).click()
            device.findObject(By.text("Resume all · novels")).click()
            assertTrue("Pause callback remains global and reachable", pauses.get() == 1)
            assertTrue("Resume callback remains global and reachable", resumes.get() == 1)
        } finally {
            scenario.close()
        }
    }
}
