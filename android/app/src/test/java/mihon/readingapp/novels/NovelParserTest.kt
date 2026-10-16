package mihon.readingapp.novels

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NovelParserTest {
    private fun capture(file: String)=Json.parseToJsonElement(javaClass.getResource("/novels/$file")!!.readText()).jsonObject
    @Test fun actualEditionKeepsTwentySixSourceOrderedChaptersAndAttribution() {
        val detail=WikisourceParser.detail("The War of the Worlds (1898)",capture("war-worlds-edition.json")["parse"]!!.jsonObject)
        assertEquals(26,detail.chapters.size)
        assertTrue(detail.chapters.first().path.endsWith("/Book 1/Chapter 1"))
        assertTrue(detail.chapters.last().path.endsWith("/Book 2/Chapter 9"))
        assertTrue(detail.chapters.none {it.path.contains("Author:") || it.path.contains("Illustration",true)})
        assertTrue(detail.author.orEmpty().contains("Wells"))
        assertTrue(detail.provenanceUrl.startsWith("https://en.wikisource.org/wiki/"))
        assertTrue(detail.rights.isNotBlank())
    }
    @Test fun actualPublicDomainChapterRetainsStoryAndDropsSourceNavigation() {
        val html=capture("war-worlds-chapter.json")["parse"]!!.jsonObject["text"]!!.toString()
        // Decode JSON primitive rather than treating its escaped representation as HTML.
        val text=kotlinx.serialization.json.Json.decodeFromString<String>(html)
        val safe=NovelHtmlPolicy.sanitize(NovelHtmlPolicy.wikiBody(text))
        assertTrue(Jsoup.parse(safe).text().contains("No one would have believed"))
        assertFalse(safe.contains("<script"));assertFalse(safe.contains("wst-header"));assertFalse(safe.contains("licenseContainer"))
        assertTrue(Jsoup.parse(safe).text().length>6000)
    }
    @Test fun hostileMarkupCannotLoadScriptsResourcesFormsOrNavigate() {
        val safe=NovelHtmlPolicy.sanitize("""<p onclick="attack()">An original chapter contains enough readable text for a safe fixture.</p><script>attack()</script><style>@import 'https://bad.invalid'</style><iframe src='https://bad.invalid'></iframe><img src='https://bad.invalid/pixel'><form>private<input value='x'></form><a href='file:///private'>visible words</a>""")
        val doc=Jsoup.parse(safe)
        assertEquals(0,doc.select("script,style,iframe,img,form,input,a").size)
        assertTrue(doc.getAllElements().all {it.attributes().size()==0})
        assertTrue(doc.text().contains("visible words"));assertFalse(doc.text().contains("attack()"))
    }
    @Test fun emptyOversizedAndNonContentPathsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {NovelHtmlPolicy.sanitize("<p>Empty</p>")}
        assertThrows(IllegalArgumentException::class.java) {NovelHtmlPolicy.sanitize("a".repeat(2_000_001))}
        val redirect = "<div><div><p>Redirect to:</p><ul><li>The War of the Worlds (1898)/Book 1/Chapter 1</li></ul></div></div>"
        assertTrue(NovelHtmlPolicy.isRedirectStub(redirect))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {NovelHtmlPolicy.sanitize(redirect)}.message.orEmpty().contains("redirect stub"))
        assertFalse(NovelHtmlPolicy.isRedirectStub("<p>Redirect to the hills, she said, and then continued the story with a long original passage.</p><p>${"A real chapter paragraph. ".repeat(4)}</p>"))
        listOf("Author:H. G. Wells","File:Cover.png","Category:Novels","Special:Search","User:Other", "bad\u0000path").forEach {assertFalse(NovelHtmlPolicy.validTitle(it))}
        assertTrue(NovelHtmlPolicy.validTitle("The War of the Worlds (1898)/Book 1/Chapter 1"))
    }
    @Test fun catalogueUsesOnlyReturnedTitlesAndExactContinuation() {
        val page=WikisourceParser.catalog(capture("catalogue-capture.json"),1,"Novels")
        assertTrue(page.items.any {it.path=="The War of the Worlds (1898)"})
        assertNull(page.nextPage) // This captured API response used an eight-item limit, not our24-item continuation.
        val body=Json.parseToJsonElement("""{"query":{"search":[{"title":"Original novel"},{"title":"Original novel"},{"title":"Author:Ignored"}]},"continue":{"sroffset":24}}""").jsonObject
        val next=WikisourceParser.catalog(body,1,"Novels")
        assertEquals(listOf("Original novel"),next.items.map {it.path});assertEquals(2,next.nextPage)
        assertTrue(next.basis.contains("No popularity"))
    }
}
