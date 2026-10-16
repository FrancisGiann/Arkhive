package mihon.readingapp.discovery

import mihon.domain.discovery.CatalogFocus
import mihon.domain.discovery.CatalogGenre
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaDiscoveryPolicyTest {
    @Test fun unsupportedActionDoesNotBecomeAdventureOrTitleSearch() {
        val context = MangaDiscoveryPolicy.forMetadata("Source title", listOf("Action"))
        assertEquals(CatalogFocus.COMICS, context.query.focus)
        assertEquals(CatalogGenre.ANY, context.query.genre)
        assertEquals("", context.query.search)
        assertTrue(context.provenance.contains("category"))
    }

    @Test fun exactSupportedTagsSelectSubjectFilters() {
        val context = MangaDiscoveryPolicy.forMetadata("Source title", listOf("Manhwa", "Fantasy"))
        assertEquals(CatalogFocus.MANHWA, context.query.focus)
        assertEquals(CatalogGenre.FANTASY, context.query.genre)
        assertEquals("", context.query.search)
        assertEquals(MangaContentCategory.MANHWA, MangaContentClassification.fromGenres(listOf("Manhwa")))
        assertEquals(MangaContentCategory.MANHUA, MangaContentClassification.fromGenres(listOf("MANHUA")))
        assertEquals(MangaContentCategory.MANGA, MangaContentClassification.fromGenres(listOf("Manga")))
    }

    @Test fun knownScienceFictionAliasMapsToProviderGenre() {
        val context = MangaDiscoveryPolicy.forMetadata("Title", listOf("Sci-Fi"))
        assertEquals(CatalogGenre.SCIFI, context.query.genre)
    }

    @Test fun unknownAndConflictingCategoriesUseHonestComicsSubject() {
        assertEquals(CatalogFocus.COMICS, MangaDiscoveryPolicy.forMetadata("Title", null).query.focus)
        assertEquals(CatalogFocus.COMICS, MangaDiscoveryPolicy.forMetadata("Title", listOf("Manhwa", "Manga")).query.focus)
        assertEquals(MangaContentCategory.COMICS, MangaContentClassification.fromGenres(listOf("Unspecified")))
        assertEquals("comics", CatalogFocus.COMICS.subject)
    }

    @Test fun authoredAliasesStaySpecificButDoNotImplyManhwa() {
        assertEquals(MangaContentCategory.MANGA, MangaContentClassification.fromGenres(listOf("Manga · RTL")))
        assertEquals(MangaContentCategory.WEBTOON, MangaContentClassification.fromGenres(listOf("Vertical webtoon")))
        assertEquals(CatalogFocus.COMICS, MangaDiscoveryPolicy.forMetadata("Title", listOf("Vertical webtoon")).query.focus)
        assertEquals(CatalogFocus.COMICS, MangaDiscoveryPolicy.forMetadata("Title", listOf("Korean")).query.focus)
    }
}
