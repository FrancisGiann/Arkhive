package mihon.readingapp.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.*
import java.io.File
import mihon.app.di.appGraph
import mihon.readingapp.library.LibraryToolsStore
import mihon.readingapp.library.ReadingRoom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual route/render check: bitmap XML compiled but crashed Compose before this regression. */
@RunWith(AndroidJUnit4::class)
class SettingsRuntimeTest {
    private suspend fun scrollToSettingsRow(device:UiDevice,label:String,maxSwipes:Int):UiObject2 {
        val height=device.displayHeight
        val width=device.displayWidth
        require(height>0 && width>0) { "Invalid display geometry for Settings navigation: ${width}x$height" }
        repeat(maxSwipes+1) { attempt ->
            device.findObject(By.text(label))?.let { return it }
            if(attempt<maxSwipes) {
                val x=width/2
                device.swipe(x,(height*.80f).toInt(),x,(height*.40f).toInt(),20)
                delay(250)
            }
        }
        val known=listOf("MAKE IT YOURS","Manga & webtoon reader","Library & collections","Downloads",
            "Support Arkhive","About & acknowledgments")
            .joinToString { text -> "$text=${device.findObject(By.text(text))!=null}" }
        throw AssertionError("Settings row '$label' was not reachable after $maxSwipes viewport-bounded swipes; " +
            "package=${device.currentPackageName},display=${width}x$height,visibleKnown={$known}")
    }

    private suspend fun navigateBack(device: UiDevice) {
        // Let the destination's navigation callback finish entering before using its actual up action.
        delay(350)
        val up = device.findObject(By.desc("Navigate up")) ?: device.findObject(By.desc("Back"))
            ?: throw AssertionError("The rendered destination has no app-bar Back or Navigate up action")
        up.click()
        delay(200)
    }

    @Test fun settingsSupportAboutAndNativePreferencesRenderWithoutResourceCrash():Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings=device.wait(Until.findObject(By.desc("Settings")),15000) ?: error("Library Settings entry unavailable")
            settings.click()
            assertNotNull(device.wait(Until.findObject(By.text("MAKE IT YOURS")),10000))
            scrollToSettingsRow(device,"Manga & webtoon reader",6).click()
            assertNotNull(device.wait(Until.findObject(By.text("READER")),10000))
            navigateBack(device)
            assertNotNull(device.wait(Until.findObject(By.text("SETTINGS")),10000))
            scrollToSettingsRow(device,"Support Arkhive",8).click()
            assertNotNull(device.wait(Until.findObject(By.text("KEEP YOUR STORY MOVING")),10000))
            assertNotNull(device.wait(Until.findObject(By.text("Report an issue")),10000))
            assertFalse(device.hasObject(By.textContains("Whoops")))
            navigateBack(device)
            assertNotNull("Support returns to Settings before its next scroll",device.wait(Until.findObject(By.text("SETTINGS")),10000))
            scrollToSettingsRow(device,"About & acknowledgments",8).click()
            assertNotNull(device.wait(Until.findObject(By.textContains("Manga & Novel Reader")),10000))
            assertFalse(device.hasObject(By.textContains("Whoops")))
            navigateBack(device);navigateBack(device)
        }
    }

    private suspend fun visibleAction(device: UiDevice, label: String, forward: Boolean = true, allowChecked: Boolean = false): UiObject2 {
        repeat(26) { attempt ->
            device.findObject(By.text(label))?.let { text ->
                var node: UiObject2? = text
                for (level in 0 until 8) {
                    val target = node ?: break
                    if (target.isClickable || (allowChecked && target.isChecked)) {
                        val bounds = target.visibleBounds
                        val minimum = (48 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density).toInt()
                        if (bounds.width() >= minimum && bounds.height() >= minimum) return target
                    }
                    node = target.parent
                }
            }
            if (attempt < 25) {
                val x = device.displayWidth / 2
                val low = (device.displayHeight * .80f).toInt()
                val high = (device.displayHeight * .57f).toInt()
                device.swipe(x, if (forward) low else high, x, if (forward) high else low, 24)
                delay(180)
            }
        }
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            device.dumpWindowHierarchy(File(context.cacheDir, "qa-settings-failure.xml"))
            device.takeScreenshot(File(context.cacheDir, "qa-settings-failure.png"))
        }
        throw AssertionError("Clickable action '$label' with a 48dp target was not reachable; forward=$forward")
    }

    @Test fun settingsTilesAndReadingRoomChoicesNavigateAndPersistSelection(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val originalRoom = graph.libraryToolsStore.state.value.room
        val device = UiDevice.getInstance(instrumentation)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                requireNotNull(device.wait(Until.findObject(By.desc("Settings")), 15_000)).click()
                assertNotNull(device.wait(Until.findObject(By.text("MAKE IT YOURS")), 10_000))
                visibleAction(device, "Queue")
                for (label in listOf("Queue", "Collections", "Stats")) {
                    assertTrue("$label exposes a clickable accessible target", visibleAction(device, label).isClickable)
                }
                assertTrue(device.takeScreenshot(File(context.cacheDir, "qa-settings-actions.png")))
                for ((label, destination) in listOf("Queue" to "Download queue", "Collections" to "Edit categories", "Stats" to "Statistics")) {
                    visibleAction(device, label).click()
                    assertNotNull("$label opens its actual destination", device.wait(Until.findObject(By.text(destination)), 10_000))
                    navigateBack(device)
                    assertNotNull(device.wait(Until.findObject(By.text("SETTINGS")), 10_000))
                }
                visibleAction(device, "Check for updates").click()
                assertNotNull("The real Settings route opens the manual updater", device.wait(Until.findObject(By.text("ARKHIVE UPDATES")), 10_000))
                assertNotNull(device.wait(Until.findObject(By.text("Current version")), 5_000))
                visibleAction(device, "Check for updates")
                assertTrue(device.takeScreenshot(File(context.cacheDir, "qa-arkhive-updates.png")))
                navigateBack(device)
                assertNotNull(device.wait(Until.findObject(By.text("SETTINGS")), 10_000))
                visibleAction(device, "Library tools", forward = false).click()
                assertNotNull(device.wait(Until.findObject(By.text("LIBRARY TOOLS")), 10_000))
                visibleAction(device, "Warm paper", allowChecked = true).let { if (it.isClickable) it.click() }
                withTimeout(5_000) {
                    while (graph.libraryToolsStore.state.value.room != ReadingRoom.WARM_PAPER) delay(50)
                }
                val selected = visibleAction(device, "Warm paper", allowChecked = true)
                assertTrue("Selected palette is exposed to accessibility", selected.isSelected || selected.isChecked)
                assertEquals("The real palette selection survives a store reload", ReadingRoom.WARM_PAPER, LibraryToolsStore(context).state.value.room)
                assertTrue(device.takeScreenshot(File(context.cacheDir, "qa-reading-room.png")))
                visibleAction(device, "View local stats").click()
                assertNotNull("The surfaced stats action opens local stats", device.wait(Until.findObject(By.text("LOCAL STATS")), 10_000))
                navigateBack(device)
                assertNotNull(device.wait(Until.findObject(By.text("LIBRARY TOOLS")), 10_000))
                navigateBack(device)
                assertNotNull(device.wait(Until.findObject(By.text("SETTINGS")), 10_000))
                navigateBack(device)
                assertNotNull(device.wait(Until.findObject(By.desc("Settings")), 10_000))
            }
        } catch (failure: Throwable) {
            runCatching {
                device.dumpWindowHierarchy(File(context.cacheDir, "qa-settings-failure.xml"))
                device.takeScreenshot(File(context.cacheDir, "qa-settings-failure.png"))
            }
            throw failure
        } finally {
            graph.libraryToolsStore.update { it.copy(room = originalRoom) }
        }
    }
}
