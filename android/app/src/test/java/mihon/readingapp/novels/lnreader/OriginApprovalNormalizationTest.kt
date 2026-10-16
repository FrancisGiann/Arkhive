package mihon.readingapp.novels.lnreader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OriginApprovalNormalizationTest {
    @Test
    fun `page and source urls normalize to exact canonical origin`() {
        assertEquals(
            "https://cdn.sanity.io:443",
            normalizePublicHttpsOrigin("  https://CDN.SANITY.IO:443/images/cover.jpg?width=600#preview  "),
        )
        assertEquals(
            "https://catalog.example:8443",
            normalizePublicHttpsOrigin("https://Catalog.Example:8443/source/title?page=2"),
        )
        assertEquals("https://cdn.sanity.io:443", normalizePublicHttpsOrigin("https://cdn.sanity.io/path"))
    }

    @Test
    fun `multiline input preserves commas inside a page url and deduplicates origins`() {
        assertEquals(
            setOf("https://catalog.example:443"),
            normalizePublicHttpsOriginLines(
                "https://catalog.example/search?q=action,adventure\nhttps://catalog.example/another-page",
            ),
        )
    }

    @Test
    fun `http is rejected with an explicit trust-policy message`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            normalizePublicHttpsOrigin("http://catalog.example/title?query=book")
        }
        assertTrue(failure.message.orEmpty().contains("HTTP origins cannot be trusted"))
    }

    @Test
    fun `malformed credential and ip-literal urls remain rejected`() {
        assertThrows(IllegalArgumentException::class.java) { normalizePublicHttpsOrigin("not a URL") }
        assertThrows(IllegalArgumentException::class.java) { normalizePublicHttpsOrigin("https://user:pass@catalog.example/path") }
        assertThrows(IllegalArgumentException::class.java) { normalizePublicHttpsOrigin("https://10.0.0.4/image") }
    }
}
