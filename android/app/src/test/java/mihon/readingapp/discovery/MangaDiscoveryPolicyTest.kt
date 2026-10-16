package mihon.readingapp.discovery

import mihon.domain.discovery.CatalogFocus
import mihon.domain.discovery.CatalogGenre
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaDiscoveryPolicyTest {
    @Test fun unsupportedActionDoesNotBecomeAdventureOrTitleSearch() {
        val context = MangaDiscoveryPolicy.forMetadata("Source title", listOf("Action"))
        assertEquals(CatalogFocus.MANGA, context.query.focus)
        assertEquals(CatalogGenre.ANY, context.query.genre)
        assertEquals("", context.query.search)
        assertTrue(context.provenance.contains("category"))
    }

    @Test fun exactSupportedTagsSelectSubjectFilters() {
        val context = MangaDiscoveryPolicy.forMetadata("Source title", listOf("Manhwa", "Fantasy"))
        assertEquals(CatalogFocus.MANHWA, context.query.focus)
        assertEquals(CatalogGenre.FANTASY, context.query.genre)
        assertEquals("", context.query.search)
    }

    @Test fun knownScienceFictionAliasMapsToProviderGenre() {
        val context = MangaDiscoveryPolicy.forMetadata("Title", listOf("Sci-Fi"))
        assertEquals(CatalogGenre.SCIFI, context.query.genre)
    }
}
