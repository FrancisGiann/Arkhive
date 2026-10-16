package mihon.readingapp.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartOfflineStoreRuntimeTest {
    @Test fun pauseAndOwnershipSurviveStoreRecreationWithoutActivatingRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SmartOfflineStore(context)
        val tools = LibraryToolsStore(context)
        val before = store.state.value
        val seriesKey = "novel:" + "a".repeat(64)
        val chapterId = "b".repeat(64)
        try {
            store.setPaused(true)
            store.setCurrent(seriesKey, "Private title · Chapter 3", "Saved 9 Smart chapters in this bounded pass.")
            store.recordOwned(SmartOwnedCopy(seriesKey, chapterId, "novel", 128, 1000, 999))
            val afterRestart = SmartOfflineStore(context).state.value
            assertTrue(afterRestart.paused)
            assertFalse(afterRestart.running)
            assertNull(afterRestart.currentSeries)
            assertNull(afterRestart.currentChapter)
            assertEquals("Smart offline pass finished.", afterRestart.message)
            assertEquals(999L, afterRestart.ownedCopies["novel:$chapterId"]?.lastModifiedAt)
            assertFalse(tools.state.value.smartOffline[seriesKey]?.enabled == true)
        } finally {
            store.update { before }
        }
    }
}
