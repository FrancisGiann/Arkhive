package mihon.domain.discovery

/** Metadata discovery never grants readable text or a download entitlement. */
data class CatalogTitle(
    val key: String, val title: String, val authors: List<String>, val subjects: List<String>,
    val coverId: Long?, val firstYear: Int?, val editionIds: List<String>,
)
data class CatalogProvenance(val provider: String, val endpoint: String, val basis: String,
    val windowDays: Int?, val fetchedAt: Long, val providerDate: String?)
data class CatalogFeed(val titles: List<CatalogTitle>, val provenance: CatalogProvenance, val total: Long?,
    val page: Int = 1, val nextPage: Int? = null)
data class CatalogLoad(val feed: CatalogFeed?, val cached: Boolean = false, val stale: Boolean = false,
    val message: String? = null)

enum class CatalogFocus(val label: String, val subject: String?) {
    MANGA("Manga", "manga"), MANHWA("Manhwa", "manhwa"), MANHUA("Manhua", "manhua"),
    NOVELS("Light novels", "light_novels"), BOOKS("Fiction", "fiction"), ALL("All books", null),
}
enum class CatalogGenre(val label: String, val subject: String?) {
    ANY("All genres", null), FANTASY("Fantasy", "fantasy"), ADVENTURE("Adventure", "adventure"),
    ROMANCE("Romance", "romance"), SCIFI("Science fiction", "science_fiction"), MYSTERY("Mystery", "mystery"),
}
enum class CatalogWindow(val label: String, val path: String?, val days: Int?) {
    SUBJECT("Genres", null, null), WEEK("7 days", "weekly", 7),
    MONTH("30 days", "monthly", 30),
}
data class CatalogQuery(val focus: CatalogFocus = CatalogFocus.MANGA,
    val genre: CatalogGenre = CatalogGenre.ANY, val window: CatalogWindow = CatalogWindow.SUBJECT,
    val search: String = "")

data class CatalogEdition(val id: String, val title: String, val publishers: List<String>,
    val published: String?, val isbn: List<String>, val languageIds: List<String>, val format: String?,
    val authors: List<String> = emptyList(), val coverUrl: String? = null)
data class StoreOffer(val provider: String, val editionTitle: String, val isbn: String, val country: String,
    val availability: String, val amount: String?, val currency: String?, val buyUrl: String?,
    val isEbook: Boolean?, val fetchedAt: Long, val providerCountry: String? = null,
    val regionConfirmed: Boolean = true)
enum class StoreLookupState { MATCHES, EMPTY, INVALID_INPUT, PROVIDER_UNAVAILABLE }
data class StoreLookup(val offers: List<StoreOffer>, val message: String?, val checkedAt: Long,
    val endpoint: String, val country: String, val catalogue: CatalogEdition? = null,
    val catalogueUrl: String? = null, val providerFailed: Boolean = false,
    val state: StoreLookupState = if (offers.isEmpty() && catalogue == null) StoreLookupState.EMPTY else StoreLookupState.MATCHES)
