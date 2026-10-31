package mihon.readingapp.novels.lnreader

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LnReaderHttpBrokerOriginTest {
    @Test
    fun `unreviewed public subdomain is returned as exact origin without DNS`() {
        val dnsLookups = AtomicInteger()
        val broker = LnReaderHttpBroker {
            dnsLookups.incrementAndGet()
            listOf(InetAddress.getByName("93.184.216.34"))
        }
        val failure = assertThrows(LnReaderUnapprovedSourceOriginException::class.java) {
            runBlocking {
                broker.fetch(
                    LnReaderHttpRequest(
                        id = "1",
                        url = "https://api.asurascans.com/api/novel-series",
                        method = "GET",
                        headers = emptyMap(),
                        body = null,
                    ),
                    allowedOrigins = setOf("https://asurascans.com:443"),
                    maxBytes = 1024,
                )
            }
        }
        assertEquals("https://api.asurascans.com:443", failure.origin)
        assertEquals(0, dnsLookups.get())
    }

    @Test
    fun `approved origin still rejects a private DNS result before connection`() {
        val dnsLookups = AtomicInteger()
        val broker = LnReaderHttpBroker {
            dnsLookups.incrementAndGet()
            listOf(InetAddress.getByName("10.0.0.7"))
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                broker.fetch(
                    LnReaderHttpRequest("1", "https://api.fixture.invalid/catalog", "GET", emptyMap(), null),
                    allowedOrigins = setOf("https://api.fixture.invalid:443"),
                    maxBytes = 1024,
                )
            }
        }
        assertTrue(failure !is LnReaderUnapprovedSourceOriginException)
        assertTrue(failure.message.orEmpty().contains("private address"))
        assertEquals(1, dnsLookups.get())
    }

    @Test
    fun `localhost and non-HTTPS URLs are denied without review or DNS`() {
        val dnsLookups = AtomicInteger()
        val broker = LnReaderHttpBroker {
            dnsLookups.incrementAndGet()
            listOf(InetAddress.getByName("93.184.216.34"))
        }
        val localFailure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                broker.fetch(
                    LnReaderHttpRequest("1", "https://localhost/catalog", "GET", emptyMap(), null),
                    allowedOrigins = setOf("https://fixture.invalid:443"),
                    maxBytes = 1024,
                )
            }
        }
        val insecureFailure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                broker.fetch(
                    LnReaderHttpRequest("2", "http://api.fixture.invalid/catalog", "GET", emptyMap(), null),
                    allowedOrigins = setOf("https://fixture.invalid:443"),
                    maxBytes = 1024,
                )
            }
        }
        assertTrue(localFailure !is LnReaderUnapprovedSourceOriginException)
        assertTrue(insecureFailure !is LnReaderUnapprovedSourceOriginException)
        assertEquals(0, dnsLookups.get())
    }
}
