package mihon.readingapp.discovery

import mihon.domain.discovery.CatalogGenre
import mihon.domain.discovery.CatalogMedium
import mihon.domain.discovery.CatalogQuery
import mihon.domain.discovery.CatalogWindow
import tachiyomi.domain.manga.model.Manga
import java.util.Locale

data class MangaDiscoveryContext(
    val title: String,
    val query: CatalogQuery,
    val provenance: String,
    val matchedLabels: List<String>,
)

object MangaDiscoveryPolicy {
    fun forManga(manga: Manga): MangaDiscoveryContext = forMetadata(manga.title, manga.genre)

    fun forMetadata(title: String, genres: List<String>?): MangaDiscoveryContext {
        val tags = genres.orEmpty().map(String::trim).filter(String::isNotEmpty)
        val normalized = tags.map { it.lowercase(Locale.ROOT).replace('-', ' ') }
        val classification = MangaContentClassification.fromGenres(tags)
        val focus = classification.relatedFocus.takeUnless { it == mihon.domain.discovery.CatalogFocus.COMICS }
            ?: mihon.domain.discovery.CatalogFocus.ALL
        val medium = if (classification.relatedFocus == mihon.domain.discovery.CatalogFocus.COMICS ||
            classification in setOf(MangaContentCategory.MANGA, MangaContentCategory.MANHWA, MangaContentCategory.MANHUA)
        ) CatalogMedium.COMICS else CatalogMedium.ALL_BOOKS
        val genre = when {
            normalized.any { it == "fantasy" } -> CatalogGenre.FANTASY
            normalized.any { it == "adventure" } -> CatalogGenre.ADVENTURE
            normalized.any { it == "romance" } -> CatalogGenre.ROMANCE
            normalized.any { it == "science fiction" || it == "sci fi" || it == "scifi" } -> CatalogGenre.SCIFI
            normalized.any { it == "mystery" } -> CatalogGenre.MYSTERY
            else -> CatalogGenre.ANY
        }
        val matched = buildList {
            if (medium == CatalogMedium.COMICS) add(medium.label)
            if (focus != mihon.domain.discovery.CatalogFocus.ALL) add(focus.label)
            if (genre != CatalogGenre.ANY) add(genre.label)
        }
        val basis = "Related by category: ${matched.joinToString(" · ")} · Open Library subject catalog."
        return MangaDiscoveryContext(
            title = title.removeSuffix(" · Original demo"),
            query = CatalogQuery(focus = focus, genre = genre, window = CatalogWindow.SUBJECT, search = "", medium = medium),
            provenance = basis,
            matchedLabels = matched,
        )
    }
}
