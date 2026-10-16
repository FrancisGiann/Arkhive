package mihon.readingapp.discovery

import kotlinx.serialization.json.*
import mihon.domain.discovery.*
import java.math.BigDecimal

/** Pure provider validation. Missing data stays missing; source windows must match the response. */
object DiscoveryDataPolicy {
    private val workKey = Regex("/works/OL[0-9]+W")
    private val editionKey = Regex("(?:/books/)?OL[0-9]+M")
    private val json = Json { ignoreUnknownKeys = true }
    fun root(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun strings(array: JsonArray, limit: Int = 32): List<String> = array.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.take(500)
    }.distinct().take(limit)
    fun parseCatalog(text: String, query: CatalogQuery, endpoint: String, fetchedAt: Long, date: String?, page: Int = 1): CatalogFeed {
        require(page in 1..CatalogPager.MAX_PAGES)
        val obj = root(text)
        val ranked = query.window != CatalogWindow.SUBJECT
        if (ranked) {
            require(obj["days"]?.jsonPrimitive?.intOrNull == query.window.days &&
                obj["hours"]?.jsonPrimitive?.intOrNull == 0 && obj.string("query") == "/trending/${query.window.path}") {
                "The provider did not confirm the requested trending period."
            }
        }
        val list = obj[if (ranked) "works" else "docs"] as? JsonArray
            ?: error("The provider returned an unsupported catalog response.")
        if (!ranked) obj["start"]?.jsonPrimitive?.intOrNull?.let {
            require(it == (page - 1) * 24) { "The provider returned a different search page." }
        }
        val titles = list.take(32).mapNotNull { item ->
            val book = item as? JsonObject ?: return@mapNotNull null
            val key = book.string("key")?.takeIf(workKey::matches) ?: return@mapNotNull null
            val title = book.string("title")?.trim()?.takeIf(String::isNotBlank)?.take(500) ?: return@mapNotNull null
            val cover = (book["cover_i"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            val editionIds = strings(book.array("edition_key"), 12).filter(editionKey::matches)
                .ifEmpty { listOfNotNull(book.string("cover_edition_key")?.takeIf(editionKey::matches)) }
            CatalogTitle(key, title, strings(book.array("author_name"), 8), strings(book.array("subject")),
                cover, (book["first_publish_year"] as? JsonPrimitive)?.intOrNull, editionIds)
        }.distinctBy { it.key }
        val basis = if (ranked) "Open Library trending order across its catalog; ${query.window.days}-day window confirmed by provider."
            else "Open Library subject/title search in provider relevance order. This genre shelf is not a popularity ranking."
        val total = (obj["numFound"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        // Trends expose no total. Request their real next page until it is short/empty;
        // never manufacture a rank, a count or a continuation URL from a third party.
        val next = (page + 1).takeIf { titles.isNotEmpty() && list.size >= 24 &&
            (total == null || page.toLong() * 24 < total) && page < CatalogPager.MAX_PAGES }
        return CatalogFeed(titles, CatalogProvenance("Open Library", endpoint, basis,
            query.window.days, fetchedAt, date), total, page, next)
    }
    fun parseEditions(text: String): List<CatalogEdition> {
        val entries = root(text)["entries"] as? JsonArray ?: error("The provider returned unsupported edition information.")
        return entries.take(12).mapNotNull { item ->
        val book = item as? JsonObject ?: return@mapNotNull null
        val key = book.string("key")?.takeIf(editionKey::matches) ?: return@mapNotNull null
        val isbn = (strings(book.array("isbn_13"), 8) + strings(book.array("isbn_10"), 8)).mapNotNull(::validIsbn).distinct()
        CatalogEdition(key.removePrefix("/books/"), book.string("title")?.take(500) ?: "Untitled edition",
            strings(book.array("publishers"), 4), book.string("publish_date"), isbn,
            book.array("languages").mapNotNull { (it as? JsonObject)?.string("key") }.take(6), book.string("physical_format"))
        }
    }
    fun validIsbn(input: String): String? {
        if (input.any { it !in '0'..'9' && !it.isWhitespace() && it != '-' && it != 'x' && it != 'X' }) return null
        val s = input.filter { it in '0'..'9' || it == 'x' || it == 'X' }.uppercase()
        if (s.length == 13 && s.all { it in '0'..'9' } && (s.startsWith("978") || s.startsWith("979"))) {
            val total = s.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
            return s.takeIf { total % 10 == 0 }
        }
        if (s.length == 10 && s.take(9).all { it in '0'..'9' } && (s.last() in '0'..'9' || s.last() == 'X')) {
            val total = s.mapIndexed { i, c -> (if (c == 'X') 10 else c.digitToInt()) * (10 - i) }.sum()
            return s.takeIf { total % 11 == 0 }
        }
        return null
    }
    /** ISBN10/13 pairs identify the same edition; different valid ISBNs still stay separate. */
    fun sameEditionIsbn(first: String, second: String): Boolean {
        fun canonical(raw: String): String? {
            val value = validIsbn(raw) ?: return null
            if (value.length == 13) return value
            val prefix = "978" + value.take(9)
            val sum = prefix.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
            return prefix + ((10 - sum % 10) % 10)
        }
        val one = canonical(first) ?: return false
        return one == canonical(second)
    }

    fun parseIsbnCatalogue(text: String, isbn: String): Pair<CatalogEdition, String>? {
        val book = root(text)["ISBN:$isbn"] as? JsonObject ?: return null
        val title = book.string("title")?.trim()?.takeIf(String::isNotBlank)?.take(500) ?: return null
        val identifiers = book["identifiers"] as? JsonObject ?: return null
        val values = (strings(identifiers.array("isbn_13")) + strings(identifiers.array("isbn_10")))
            .mapNotNull(::validIsbn).distinct()
        if (values.none { sameEditionIsbn(it, isbn) }) return null
        val source = book.string("url") ?: return null
        val url = runCatching { java.net.URI(source) }.getOrNull() ?: return null
        if (url.host != "openlibrary.org" || url.userInfo != null || url.port !in listOf(-1, 443) ||
            url.scheme !in setOf("https", "http") || !Regex("/books/OL[0-9]+M(?:/.*)?").matches(url.path)) return null
        val link = "https://openlibrary.org" + url.rawPath
        val cover = (book["cover"] as? JsonObject)?.string("medium")?.let { value ->
            val u = runCatching { java.net.URI(value) }.getOrNull()
            if (u?.host == "covers.openlibrary.org" && u.userInfo == null && u.scheme in setOf("http", "https"))
                "https://covers.openlibrary.org" + u.rawPath else null
        }
        fun names(key: String) = book.array(key).mapNotNull { (it as? JsonObject)?.string("name")?.take(500) }.take(8)
        val edition = CatalogEdition(url.path.split('/')[2], title, names("publishers"), book.string("publish_date"),
            values, emptyList(), null, names("authors"), cover)
        return edition to link
    }

    fun parseOffers(text: String, isbn: String, requestedCountry: String, endpoint: String, fetchedAt: Long): StoreLookup {
        require(validIsbn(isbn) == isbn)
        val root = root(text)
        require("error" !in root && ("items" in root || (root["totalItems"] as? JsonPrimitive)?.intOrNull == 0)) { "The provider returned unsupported store information." }
        val offers = root.array("items").mapNotNull { item ->
            val book = item as? JsonObject ?: return@mapNotNull null
            val info = book["volumeInfo"] as? JsonObject ?: return@mapNotNull null
            val exact = info.array("industryIdentifiers").any {
                sameEditionIsbn((it as? JsonObject)?.string("identifier") ?: "", isbn)
            }
            if (!exact) return@mapNotNull null
            val sale = book["saleInfo"] as? JsonObject ?: JsonObject(emptyMap())
            val country = sale.string("country")
            val regionConfirmed = country == requestedCountry
            val availability = if (regionConfirmed) sale.string("saleability") ?: "UNKNOWN" else "REGION_UNCONFIRMED"
            val price = sale["retailPrice"] as? JsonObject
            val amount = price?.string("amount")?.let { runCatching { BigDecimal(it) }.getOrNull() }?.takeIf { it >= BigDecimal.ZERO && it.precision() <= 20 && it.scale() in -6..6 }
            val currency = price?.string("currencyCode")?.takeIf { runCatching { java.util.Currency.getInstance(it) }.isSuccess }
            val hasPrice = availability in setOf("FOR_SALE", "FOR_PREORDER") && amount != null && currency != null
            val buy = sale.string("buyLink")?.let { raw -> runCatching {
                val uri = java.net.URI(raw)
                if (uri.host !in setOf("books.google.com", "play.google.com") || uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.scheme !in setOf("https", "http")) null
                else if (uri.scheme == "http") "https:" + raw.removePrefix("http:") else raw
            }.getOrNull() }
            StoreOffer("Google Books", info.string("title") ?: "Selected edition", isbn, requestedCountry, availability,
                if (hasPrice) amount?.toPlainString() else null, if (hasPrice) currency else null,
                buy, (sale["isEbook"] as? JsonPrimitive)?.booleanOrNull, fetchedAt, country, regionConfirmed)
        }.distinctBy { listOf(it.isbn, it.editionTitle, it.country, it.amount, it.currency) }
        return StoreLookup(offers, if (offers.isEmpty()) "No exact-ISBN store information was returned for this region." else null,
            fetchedAt, endpoint, requestedCountry)
    }
}
