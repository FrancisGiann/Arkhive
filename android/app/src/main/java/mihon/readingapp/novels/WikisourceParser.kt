package mihon.readingapp.novels

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.net.URLDecoder

/** Pure parsing shared by the real transport and captured-source regression tests. */
object WikisourceParser {
    fun catalog(root: JsonObject, page: Int, category: String): NovelCatalogPage {
        val items = root["query"]?.jsonObject?.get("search")?.jsonArray ?: error("The source returned an unsupported catalogue.")
        val novels = items.mapNotNull { entry ->
            val title = entry.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!NovelHtmlPolicy.validTitle(title)) return@mapNotNull null
            NovelItem(title, title)
        }.distinctBy { it.path }
        val next = root["continue"]?.jsonObject?.get("sroffset")?.jsonPrimitive?.intOrNull
        return NovelCatalogPage(novels, (page + 1).takeIf { next == page * 24 && novels.isNotEmpty() && page < 40 },
            "Wikisource indexed $category · provider relevance. No popularity or trend ranking is supplied.")
    }
    fun detail(path: String, parsed: JsonObject, site: String = "https://en.wikisource.org"): SourceNovel {
        val html = parsed["text"]?.jsonPrimitive?.contentOrNull ?: error("The source returned no title page.")
        val doc = Jsoup.parse(html, site)
        val actual = parsed["title"]?.jsonPrimitive?.contentOrNull ?: path
        val rootName = NovelHtmlPolicy.workName(actual)
        val author = doc.select(".ws-header a[href^=/wiki/Author:], .wst-header a[href^=/wiki/Author:]").first()?.text()?.take(500)
        val name = doc.select(".wst-header-title-text").first()?.text()?.trim()?.takeIf { it.isNotBlank() } ?: actual
        val contents = doc.clone()
        contents.select(".ws-header,.wst-header,.ws-noexport,.noprint,.wst-footer,.licenseContainer,.licenseBanner").remove()
        val toc = contents.select(".toc-line-entry-text a[href], .toc a[href]")
        val candidates = if (toc.isNotEmpty()) toc else contents.select(".mw-parser-output a[href]")
        val chapters = candidates.mapNotNull { link ->
            val href = link.attr("href")
            if (!href.startsWith("/wiki/") || '#' in href || link.hasClass("new")) return@mapNotNull null
            val title = URLDecoder.decode(href.removePrefix("/wiki/"), "UTF-8").replace('_', ' ')
            if (!NovelHtmlPolicy.validTitle(title) || '/' !in title || NovelHtmlPolicy.workName(title) != rootName) return@mapNotNull null
            if (Regex("(?i).*(illustrations?|frontispiece|title page|contents|index)$").matches(title)) return@mapNotNull null
            NovelChapter(title, link.text().trim().takeIf { it.length >= 3 }?.take(500) ?: title.substringAfterLast('/'))
        }.distinctBy { it.path }
        val readable = if (chapters.isEmpty()) {
            val content = NovelHtmlPolicy.wikiBody(html)
            require(Jsoup.parse(content).text().length >= 1200) { "This page lists editions or versions. Choose a specific edition with chapters from the source catalogue." }
            listOf(NovelChapter(path, name))
        } else chapters
        val genres = parsed["categories"]?.jsonArray.orEmpty().mapNotNull { category ->
            (category.jsonObject["category"] ?: category.jsonObject["*"])?.jsonPrimitive?.contentOrNull
        }.filter { it.endsWith("novels", true) || it == "Novels" }.distinct().take(12)
        val cover = doc.select(".prp-pages-output img[src]").first()?.attr("abs:src")?.takeIf {
            val u = runCatching { it.toHttpUrl() }.getOrNull()
            u?.isHttps == true && u.username.isEmpty() && u.host in setOf("upload.wikimedia.org", "en.wikisource.org")
        }
        val rights = doc.select(".licenseContainer, .licensetpl").text().take(1200).ifBlank {
            "Wikisource supplies public-domain or freely licensed works. Source attribution and its license remain linked with each chapter."
        }
        return SourceNovel(path, name.take(500), author, cover, genres, null,
            readable, "Source edition", url(site, actual), rights)
    }
    fun url(site: String, title: String): String = site + "/wiki/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
}
