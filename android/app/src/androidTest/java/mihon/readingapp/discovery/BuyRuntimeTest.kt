package mihon.readingapp.discovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import mihon.domain.discovery.StoreLookupState
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Controlled transports distinguish actual catalogue matches from unavailable retail data. */
@RunWith(AndroidJUnit4::class)
class BuyRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isbn(): String {
        val digits = "978" + (UUID.randomUUID().mostSignificantBits and 0x7fffffff).toString().padStart(9, '0').takeLast(9)
        val sum = digits.mapIndexed { i, c -> c.digitToInt() * if(i % 2 == 0) 1 else 3 }.sum()
        return digits + ((10 - sum % 10) % 10)
    }
    private fun cache(url: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "arkhive-public-metadata/$key.json")
    }
    private fun catalogue(isbn: String) = """{"ISBN:$isbn":{"title":"Original runtime edition fixture","url":"https://openlibrary.org/books/OL999999M/Original","identifiers":{"isbn_13":["$isbn"]}}}"""
    private fun client(isbn: String, quota: Boolean, captured: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); captured += request.url.toString()
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        val google = request.url.host == "www.googleapis.com"
        val body = if (!google) catalogue(isbn) else if (quota) """{"error":{"code":429,"message":"Controlled quota fixture"}}""" else
            """{"items":[{"volumeInfo":{"title":"Original unpriced fixture","industryIdentifiers":[{"identifier":"$isbn"}]},"saleInfo":{"country":"GB","saleability":"FOR_SALE"}}]}"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(google && quota) 429 else 200)
            .message("Controlled fixture").body(body.toResponseBody()).build()
    }.build()
    @Test fun quotaFailureRetainsExactCatalogueMatchWithoutInventingPrice() = runBlocking {
        val prefs = context.appGraph.basePreferences; val original = prefs.incognitoMode.get(); prefs.incognitoMode.set(false)
        val own = isbn(); val urls = mutableListOf<String>()
        try {
            val repository = PublicDiscoveryRepository(context, prefs, client(own, true, urls))
            val result = repository.store(own, "US")
            assertEquals(StoreLookupState.PROVIDER_UNAVAILABLE, result.state); assertTrue(result.providerFailed)
            assertNotNull(result.catalogue); assertEquals("Original runtime edition fixture", result.catalogue!!.title)
            assertTrue(result.offers.isEmpty()); assertTrue(result.message!!.contains("HTTP429"))
        } finally { urls.forEach { cache(it).delete() }; prefs.incognitoMode.set(original) }
    }
    @Test fun matchingBookWithUnconfirmedRegionIsVisibleButUnpriced() = runBlocking {
        val prefs = context.appGraph.basePreferences; val original = prefs.incognitoMode.get(); prefs.incognitoMode.set(false)
        val own = isbn(); val urls = mutableListOf<String>()
        try {
            val repository = PublicDiscoveryRepository(context, prefs, client(own, false, urls))
            val result = repository.store(own, "US")
            assertEquals(StoreLookupState.MATCHES, result.state)
            val row = result.offers.single(); assertFalse(row.regionConfirmed); assertEquals("GB", row.providerCountry)
            assertNull(row.amount); assertNull(row.currency); assertEquals("REGION_UNCONFIRMED", row.availability)
        } finally { urls.forEach { cache(it).delete() }; prefs.incognitoMode.set(original) }
    }
}
