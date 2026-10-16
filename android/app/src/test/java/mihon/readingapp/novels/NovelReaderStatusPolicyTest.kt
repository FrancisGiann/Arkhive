package mihon.readingapp.novels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelReaderStatusPolicyTest {
    @Test fun loadingAndErrorRemainVisibleUntilContentIsReady() {
        assertEquals(NovelReaderStatus.LOADING, novelReaderStatus(ready = false, error = false))
        assertEquals(NovelReaderStatus.ERROR, novelReaderStatus(ready = false, error = true))
    }

    @Test fun readyContentHidesAStaleError() {
        assertEquals(NovelReaderStatus.CONTENT, novelReaderStatus(ready = true, error = false))
        assertEquals(NovelReaderStatus.CONTENT, novelReaderStatus(ready = true, error = true))
    }
}
