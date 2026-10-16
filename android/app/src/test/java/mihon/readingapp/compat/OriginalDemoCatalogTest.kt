package mihon.readingapp.compat

import mihon.readingapp.fixtures.OriginalDemoCatalog
import mihon.readingapp.fixtures.OriginalDemoCatalog.Format
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OriginalDemoCatalogTest {
    @Test
    fun `catalog isolates paged manga and vertical titles with stable URLs`() {
        assertEquals(2, OriginalDemoCatalog.search(Format.MANGA, "").size)
        assertEquals(2, OriginalDemoCatalog.search(Format.WEBTOON, "").size)
        assertEquals("rainlight-district", OriginalDemoCatalog.search(Format.WEBTOON, " RAINLIGHT ").single().slug)
        assertTrue(OriginalDemoCatalog.search(Format.MANGA, "Rainlight").isEmpty())
        assertEquals(OriginalDemoCatalog.titles.size, OriginalDemoCatalog.titles.map { it.slug }.toSet().size)
    }

    @Test
    fun `chapters descend while pages stay in narrative order`() {
        val chapters = OriginalDemoCatalog.chapterPaths("neon-courier")
        assertEquals(listOf(3, 2, 1), chapters.map { it.substringAfterLast('/').toInt() })
        val pages = OriginalDemoCatalog.pageUrls(chapters.first())
        assertEquals(6, pages.size)
        assertTrue(pages.all { java.net.URI(it).query == "art=storyboard2" })
        assertTrue(java.net.URI(pages.first()).path.endsWith("/3/1.png"))
        assertTrue(java.net.URI(pages.last()).path.endsWith("/3/6.png"))
        assertTrue(pages.all { it.startsWith(OriginalDemoCatalog.BASE_URL + "/pages/") })
        assertFalse(pages.any { it.contains("http://") })
    }

    @Test
    fun `unknown title and out of range chapter fail rather than manufacturing content`() {
        assertThrows(IllegalStateException::class.java) { OriginalDemoCatalog.chapterPaths("unknown") }
        assertThrows(IllegalArgumentException::class.java) { OriginalDemoCatalog.pageUrls("/neon-courier/chapter/4") }
        assertThrows(IllegalArgumentException::class.java) { OriginalDemoCatalog.pageUrls("/neon-courier/chapter/0") }
        assertThrows(IllegalArgumentException::class.java) { OriginalDemoCatalog.pageUrls("/neon-courier/page/1") }
    }
}
