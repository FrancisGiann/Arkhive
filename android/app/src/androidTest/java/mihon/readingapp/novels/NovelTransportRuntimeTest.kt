package mihon.readingapp.novels

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NovelTransportRuntimeTest {
    @Test fun actualAdapterUsesOffsetPagesChapterLinksAndSanitizedText():Unit=runBlocking {
        val fixture=OriginalNovelFixture();val source=fixture.source
        val a=source.catalog(1);val b=source.catalog(2)
        assertEquals(24,a.items.size);assertEquals(2,a.nextPage);assertEquals(24,b.items.size);assertNull(b.nextPage)
        assertEquals(48,(a.items+b.items).map {it.path}.toSet().size)
        assertTrue(fixture.urls.any {it.contains("sroffset=24")})
        val detail=source.detail(fixture.first);assertEquals(6,detail.chapters.size);assertEquals(fixture.first,detail.path)
        val html=source.chapter(detail.chapters.first().path)
        assertTrue(html.contains("lantern keeper"));assertFalse(html.contains("<script"));assertFalse(html.contains("<img"));assertFalse(html.contains("Source navigation"))
        assertTrue(fixture.urls.filter {it.contains("action=parse")}.all {it.contains("redirects=1")})
    }
    @Test fun sourceRateLimitCooldownDoesNotHammerTheProvider():Unit=runBlocking {
        val calls=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor {chain->calls.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Controlled cooldown").header("Retry-After","1").body("{}".toResponseBody()).build()}.build()
        val source=WikisourceNovelSource(client,requestIntervalMillis=0)
        assertTrue(runCatching {source.catalog(1)}.exceptionOrNull()!!.message!!.contains("HTTP429"))
        assertTrue(runCatching {source.catalog(1)}.exceptionOrNull()!!.message!!.contains("cooldown"));assertEquals(1,calls.get())
    }
    @Test fun oversizedChapterAndSourceApiErrorsAreHonestFailures():Unit=runBlocking {
        val large=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Controlled size limit").body("a".repeat(2_000_001).toResponseBody()).build()}.build()
        assertTrue(runCatching {WikisourceNovelSource(large,requestIntervalMillis=0).chapter("Original chapter")}.exceptionOrNull()!!.message!!.contains("limit"))
        val error=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Controlled API error").body("""{"error":{"info":"Original source fixture missing"}}""".toResponseBody()).build()}.build()
        assertEquals("Original source fixture missing",runCatching {WikisourceNovelSource(error,requestIntervalMillis=0).detail("Original title")}.exceptionOrNull()!!.message)
    }
    @Test fun internalWikiRedirectsAreRequestedAndStubTextIsNeverReadable():Unit=runBlocking {
        val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor {chain->
            val request=chain.request();requests+=request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Internal redirect fixture")
                .body("""{"parse":{"title":"Resolved chapter title","text":"<div class='mw-parser-output'><p>Redirect to: <a href='/wiki/Resolved_chapter_title'>Resolved chapter title</a></p></div>"}}""".toResponseBody()).build()
        }.build()
        val source=WikisourceNovelSource(client,requestIntervalMillis=0)
        val error=runCatching {source.chapter("Original chapter alias")}.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("redirect stub"))
        val request=requests.single()
        assertEquals("en.wikisource.org",request.url.host)
        assertEquals("1",request.url.queryParameter("redirects"))
        assertEquals("Original chapter alias",request.url.queryParameter("page"))
    }
    @Test fun externalHttpRedirectsRemainBlocked():Unit=runBlocking {
        val calls=AtomicInteger()
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor {chain->
            calls.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("External redirect fixture")
                .header("Location","https://elsewhere.invalid/chapter").body("".toResponseBody()).build()
        }.build()
        val failure=runCatching {WikisourceNovelSource(client,requestIntervalMillis=0).chapter("Original chapter")}.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("HTTP302"));assertEquals(1,calls.get())
    }
}
