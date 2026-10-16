package mihon.readingapp.readers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LongPagePolicyTest {
    @Test fun ordinaryPortraitAndLandscapeMangaKeepPagingGeometry() {
        assertFalse(LongPagePolicy.needsWidthFit(720, 1080, 1080, 1920))
        assertFalse(LongPagePolicy.needsWidthFit(1800, 1200, 1080, 1920))
        assertFalse(LongPagePolicy.needsWidthFit(720, 1080, 1920, 1080))
    }
    @Test fun narrowTallImageUsesAvailableWidthAndKeepsFullAspect() {
        assertTrue(LongPagePolicy.needsWidthFit(720, 60000, 1080, 1920))
        assertEquals(90000, LongPagePolicy.displayedHeight(720, 60000, 1080))
        assertEquals(1, LongPagePolicy.widthSample(720, 1080))
        assertEquals(4, LongPagePolicy.widthSample(5000, 1080))
    }
    @Test fun nativeRegionsStayBoundedRegardlessOfTotalHeight() {
        for (width in listOf(720, 1440, 5000, 20000)) {
            val sample = LongPagePolicy.widthSample(width, 1080)
            val rows = LongPagePolicy.regionRows(width, sample)
            val bytes = ((width.toLong() + sample - 1) / sample) * ((rows.toLong() + sample - 1) / sample) * 4
            assertTrue(bytes <= 4L * 1024 * 1024 + 32000)
        }
    }
    @Test fun unsupportedViewExtentFailsRatherThanWrappingOrCropping() {
        assertThrows(IllegalArgumentException::class.java) { LongPagePolicy.displayedHeight(1, Int.MAX_VALUE, 1080) }
    }
}
