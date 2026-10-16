package mihon.readingapp.discovery

import mihon.domain.discovery.CatalogFocus
import java.util.Locale

/** Presentation category from explicit source metadata; source identity and language are not evidence. */
enum class MangaContentCategory(val label: String, val relatedFocus: CatalogFocus) {
    MANGA("Manga", CatalogFocus.MANGA),
    MANHWA("Manhwa", CatalogFocus.MANHWA),
    MANHUA("Manhua", CatalogFocus.MANHUA),
    WEBTOON("Webtoon", CatalogFocus.COMICS),
    COMICS("Comics", CatalogFocus.COMICS),
}

object MangaContentClassification {
    fun fromGenres(genres: List<String>?): MangaContentCategory {
        val tags = genres.orEmpty().map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val explicitKinds = buildSet {
            if ("manga" in tags || "manga · rtl" in tags) add(MangaContentCategory.MANGA)
            if ("manhwa" in tags) add(MangaContentCategory.MANHWA)
            if ("manhua" in tags) add(MangaContentCategory.MANHUA)
        }
        if (explicitKinds.size == 1) return explicitKinds.single()
        if (explicitKinds.size > 1) return MangaContentCategory.COMICS

        // This is an authored format label, not a claim about country or reading direction.
        if ("vertical webtoon" in tags) return MangaContentCategory.WEBTOON
        return MangaContentCategory.COMICS
    }
}
