package mihon.readingapp.discovery

import kotlinx.serialization.json.*
import mihon.domain.discovery.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Catalog captures are public provider responses. Store data below is explicitly a parser fixture. */
class DiscoveryDataPolicyTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/discovery/$name.json")).readText()
    private fun feed(name: String, query: CatalogQuery) = DiscoveryDataPolicy.parseCatalog(
        fixture(name), query, "https://openlibrary.org/$name", 1234L, "Tue, 13 Oct 2026 00:00:00 GMT",
    )
    @Test fun actualMangaAndNovelSubjectsKeepProviderMetadataAndProvenance() {
        val manga = feed("openlibrary-manga", CatalogQuery())
        assertEquals("鬼滅の刃 1", manga.titles.first().title)
        assertEquals(9364005L, manga.titles.first().coverId)
        assertTrue(manga.titles.first().subjects.any { it == "Comics & graphic novels, manga, general" })
        assertEquals(1234L, manga.provenance.fetchedAt)
        assertNull(manga.provenance.windowDays)
        assertTrue(manga.provenance.basis.contains("not a popularity ranking"))
        val novels = feed("openlibrary-lightnovels", CatalogQuery(focus = CatalogFocus.NOVELS))
        assertEquals("薬屋のひとりごと (light novel)", novels.titles.first().title)
        assertFalse(novels.titles.first().authors.isEmpty())
        val fantasy = feed("openlibrary-fantasy-manga", CatalogQuery(genre = CatalogGenre.FANTASY))
        assertTrue(fantasy.titles.first().subjects.any { it == "Comics & graphic novels, manga, fantasy" })
    }
    @Test fun actualWeeklyAndMonthlyFeedsKeepSourceOrderAndConfirmedWindow() {
        listOf(CatalogWindow.WEEK to "openlibrary-weekly", CatalogWindow.MONTH to "openlibrary-monthly").forEach { (window, name) ->
            val result = feed(name, CatalogQuery(window = window))
            assertEquals("Atomic Habits", result.titles.first().title)
            assertEquals(window.days, result.provenance.windowDays)
            assertTrue(result.provenance.basis.contains("across its catalog"))
            assertEquals("Open Library", result.provenance.provider)
            assertEquals(5, result.titles.size)
        }
    }
    @Test fun wrongOrUnconfirmedTrendingPeriodIsRejected() {
        val source = DiscoveryDataPolicy.root(fixture("openlibrary-weekly"))
        listOf(source + ("days" to JsonPrimitive(30)), source + ("hours" to JsonPrimitive(1)),
            source - "days", source + ("query" to JsonPrimitive("/trending/monthly"))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                DiscoveryDataPolicy.parseCatalog(JsonObject(invalid).toString(), CatalogQuery(window = CatalogWindow.WEEK), "fixture", 1, null)
            }
        }
    }
    @Test fun missingMetadataStaysMissingAndInvalidOrDuplicateWorkKeysAreDiscarded() {
        val result = DiscoveryDataPolicy.parseCatalog("""{"docs":[{"key":"/works/OL1W","title":"An original fixture"},
          {"key":"/works/OL1W","title":"Duplicate"},{"key":"https://evil.example/works/OL2W","title":"Bad"},
          {"key":"/works/OL3W","title":""}]}""", CatalogQuery(), "fixture", 1, null)
        assertEquals(1, result.titles.size)
        assertNull(result.titles.single().coverId)
        assertTrue(result.titles.single().authors.isEmpty())
        assertNull(result.total)
    }
    @Test fun isbnValidationChecksChecksumBookPrefixAndAsciiWithoutCorrectingInvalidInput() {
        assertEquals("9781974709939", DiscoveryDataPolicy.validIsbn("978-1-9747-0993-9"))
        assertEquals("0306406152", DiscoveryDataPolicy.validIsbn("0 306 40615 2"))
        assertEquals("080442957X", DiscoveryDataPolicy.validIsbn("080442957x"))
        listOf("9781974709930", "ISBN9781974709939", "１２３４５６７８９０", "1234567890128", "", "X306406152").forEach {
            assertNull(DiscoveryDataPolicy.validIsbn(it), it)
        }
    }
    private fun offer(country: String = "US", isbn: String = "9781974709939", sale: String = "FOR_SALE",
        price: String = "\"retailPrice\":{\"amount\":12.99,\"currencyCode\":\"USD\"},",
        link: String = "https://books.google.com/books?id=original-fixture") = """{"volumeInfo":{"title":"Original store parser fixture",
        "industryIdentifiers":[{"type":"ISBN_13","identifier":"$isbn"}]},"saleInfo":{"country":"$country",
        "saleability":"$sale",$price"isEbook":true,"buyLink":"$link"}}"""
    private fun store(vararg rows: String) = DiscoveryDataPolicy.parseOffers("""{"items":[${rows.joinToString()}]}""",
        "9781974709939", "US", "https://www.googleapis.com/books/v1/volumes?q=isbn:9781974709939&country=US", 9000)
    @Test fun storePricesRequireExactIsbnRegionRetailAmountCurrencyAndAvailability() {
        val result = store(offer(), offer(country = "GB"), offer(isbn = "9780306406157"))
        val only = result.offers.single { it.regionConfirmed }
        assertEquals("12.99", only.amount)
        assertEquals("USD", only.currency)
        assertEquals("US", only.country)
        assertEquals(9000L, only.fetchedAt)
        assertNull(result.message)
        val unconfirmed = store(offer(country = "GB")).offers.single()
        assertFalse(unconfirmed.regionConfirmed); assertNull(unconfirmed.amount); assertNull(unconfirmed.currency)
        assertEquals("GB", unconfirmed.providerCountry); assertEquals("REGION_UNCONFIRMED", unconfirmed.availability)
    }
    @Test fun absentInvalidOrNonSalePricesNeverBecomeAnInventedZero() {
        val fixtures = listOf(offer(price = ""), offer(sale = "FREE"), offer(sale = "NOT_FOR_SALE"),
            offer(price = "\"retailPrice\":{\"amount\":-1,\"currencyCode\":\"USD\"},"),
            offer(price = "\"retailPrice\":{\"amount\":1,\"currencyCode\":\"FAKE\"},"),
            offer(price = "\"listPrice\":{\"amount\":1,\"currencyCode\":\"USD\"},"),
            offer(price = "\"retailPrice\":{\"amount\":\"1e999999999\",\"currencyCode\":\"USD\"},"))
        fixtures.forEach { fixture -> val only = store(fixture).offers.single(); assertNull(only.amount); assertNull(only.currency) }
    }
    @Test fun providerOfferLinksRejectCredentialHostsAndExecutableOrUntrustedUrls() {
        listOf("https://books.google.com.evil.example/buy", "https://books.google.com@evil.example/buy",
            "javascript:alert(1)", "https://books.google.com:8080/buy", "https://evil.example/buy").forEach {
            assertNull(store(offer(link = it)).offers.single().buyUrl)
        }
        assertEquals("https://books.google.com/books?id=original-fixture",
            store(offer(link = "http://books.google.com/books?id=original-fixture")).offers.single().buyUrl)
    }
    @Test fun providerErrorsAreRejectedAndGenuineEmptyResultsRemainEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryDataPolicy.parseOffers("""{"error":{"message":"quota"}}""", "9781974709939", "US", "fixture", 1)
        }
        val empty = DiscoveryDataPolicy.parseOffers("""{"totalItems":0}""", "9781974709939", "US", "fixture", 1)
        assertTrue(empty.offers.isEmpty())
        assertNotNull(empty.message)
        assertThrows(IllegalStateException::class.java) { DiscoveryDataPolicy.parseEditions("""{"error":"missing"}""") }
    }
    @Test fun editionIsbnsAreValidatedRatherThanCopiedFromWorkLevelSearch() {
        val editions = DiscoveryDataPolicy.parseEditions("""{"entries":[{"key":"/books/OL99M","title":"Original edition fixture",
        "isbn_13":["9781974709939","9781974709930"],"publishers":["Fixture Press"],"publish_date":"2026",
        "languages":[{"key":"/languages/eng"}],"physical_format":"Paperback"}]}""")
        assertEquals(listOf("9781974709939"), editions.single().isbn)
        assertEquals("Paperback", editions.single().format)
    }
    @Test fun isbn10And13AreComparedAsTheSameEditionOnlyWhenEquivalent() {
        assertTrue(DiscoveryDataPolicy.sameEditionIsbn("0306406152", "9780306406157"))
        assertFalse(DiscoveryDataPolicy.sameEditionIsbn("0306406152", "9781974709939"))
        assertFalse(DiscoveryDataPolicy.sameEditionIsbn("0306406150", "9780306406157"))
    }
    @Test fun exactCatalogueFallbackCarriesMetadataWithoutInventingAnOffer() {
        val result = DiscoveryDataPolicy.parseIsbnCatalogue("""{"ISBN:0306406152":{"title":"Original catalogue fixture","url":"https://openlibrary.org/books/OL99M/Original",
            "identifiers":{"isbn_13":["9780306406157"]},"authors":[{"name":"Fixture Author"}],"publishers":[{"name":"Fixture Press"}]}}""", "0306406152")
        assertNotNull(result); assertEquals("Original catalogue fixture", result!!.first.title)
        assertEquals(listOf("Fixture Author"), result.first.authors)
        assertEquals("https://openlibrary.org/books/OL99M/Original", result.second)
        assertNull(DiscoveryDataPolicy.parseIsbnCatalogue("""{}""", "0306406152"))
    }
    @Test fun wrongSearchOffsetIsRejectedAndTrueShortPageEnds() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryDataPolicy.parseCatalog("""{"docs":[],"start":0}""", CatalogQuery(), "fixture", 0, null, 2)
        }
        val last = DiscoveryDataPolicy.parseCatalog("""{"docs":[{"key":"/works/OL1W","title":"Fixture"}],"start":24,"numFound":25}""", CatalogQuery(), "fixture", 0, null, 2)
        assertNull(last.nextPage)
    }

}
