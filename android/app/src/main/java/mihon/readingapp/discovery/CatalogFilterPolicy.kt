package mihon.readingapp.discovery

import mihon.domain.discovery.CatalogFocus
import mihon.domain.discovery.CatalogMedium
import mihon.domain.discovery.CatalogQuery
import mihon.domain.discovery.CatalogWindow
import mihon.domain.discovery.CatalogGenre
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class CatalogMediumSelection(val query: CatalogQuery, val notice: String? = null)
data class CatalogWindowSelection(val query: CatalogQuery, val notice: String? = null)
data class CatalogFilterDraft(
    val appliedQuery: CatalogQuery,
    val query: CatalogQuery = appliedQuery,
    val notice: String? = null,
)

/** One source of truth for compatible type/medium selections and their effective labels. */
object CatalogFilterPolicy {
    fun beginDraft(query: CatalogQuery): CatalogFilterDraft = CatalogFilterDraft(normalize(query))

    fun draftMedium(draft: CatalogFilterDraft, medium: CatalogMedium): CatalogFilterDraft {
        val selection = selectMedium(draft.query, medium)
        return draft.copy(query = selection.query, notice = selection.notice)
    }

    fun draftFocus(draft: CatalogFilterDraft, focus: CatalogFocus): CatalogFilterDraft =
        selectFocus(draft.query, focus)?.let { draft.copy(query = it, notice = null) } ?: draft

    fun draftGenre(draft: CatalogFilterDraft, genre: CatalogGenre): CatalogFilterDraft =
        draft.copy(query = draft.query.copy(genre = genre), notice = null)

    fun draftWindow(draft: CatalogFilterDraft, window: CatalogWindow, searchText: String): CatalogFilterDraft {
        val selection = selectWindow(draft.query, window, searchText)
        return draft.copy(query = selection.query, notice = selection.notice)
    }

    fun resetDraft(draft: CatalogFilterDraft): CatalogFilterDraft =
        draft.copy(query = reset(draft.query), notice = null)

    fun cancelDraft(draft: CatalogFilterDraft): CatalogFilterDraft =
        draft.copy(query = draft.appliedQuery, notice = null)

    fun applyDraft(draft: CatalogFilterDraft, search: String): CatalogQuery = applySearch(draft.query, search)

    fun normalize(query: CatalogQuery): CatalogQuery = query.normalized()

    /** Kept as a named migration hook for callers restoring old focus-only query state. */
    fun normalizeLegacy(query: CatalogQuery): CatalogQuery = normalize(query)

    fun selectMedium(query: CatalogQuery, medium: CatalogMedium): CatalogMediumSelection {
        val current = normalize(query)
        val incompatibleBookFocus = medium == CatalogMedium.COMICS &&
            current.focus in setOf(CatalogFocus.NOVELS, CatalogFocus.BOOKS)
        val next = current.copy(
            medium = medium,
            focus = if (incompatibleBookFocus) CatalogFocus.ALL else current.focus,
        )
        return CatalogMediumSelection(
            next,
            "Comics cannot be combined with Light novels or Fiction. The type filter was cleared."
                .takeIf { incompatibleBookFocus },
        )
    }

    fun canSelectFocus(medium: CatalogMedium, focus: CatalogFocus): Boolean =
        medium != CatalogMedium.COMICS || focus !in setOf(CatalogFocus.NOVELS, CatalogFocus.BOOKS)

    fun selectFocus(query: CatalogQuery, focus: CatalogFocus): CatalogQuery? =
        normalize(query).takeIf { canSelectFocus(it.medium, focus) }?.copy(focus = focus)

    fun canSelectWindow(window: CatalogWindow, searchText: String): Boolean =
        window == CatalogWindow.SUBJECT || searchText.isBlank()

    fun selectWindow(query: CatalogQuery, window: CatalogWindow, searchText: String): CatalogWindowSelection =
        if (canSelectWindow(window, searchText)) CatalogWindowSelection(
            normalize(query).copy(window = window, search = if (window == CatalogWindow.SUBJECT) query.search else ""),
        )
        else CatalogWindowSelection(
            normalize(query),
            "Trending lists do not support title search. Clear the search field before choosing a trend window.",
        )

    fun applySearch(query: CatalogQuery, search: String): CatalogQuery {
        val current = normalize(query)
        val clean = search.trim().take(120)
        return current.copy(search = clean, window = if (clean.isNotEmpty()) CatalogWindow.SUBJECT else current.window)
    }

    /** Reset filters, keeping the separate search field intact. */
    fun reset(query: CatalogQuery): CatalogQuery = normalize(query).copy(
        medium = CatalogMedium.ALL_BOOKS,
        focus = CatalogFocus.ALL,
        genre = CatalogGenre.ANY,
        window = CatalogWindow.SUBJECT,
    )

    fun subjectTerms(query: CatalogQuery): List<String> = query.normalized().let { effective -> listOfNotNull(
        effective.medium.subject,
        effective.focus.subject,
        effective.genre.subject,
    ).distinct().map { "subject:$it" } }

    fun appliedSummary(query: CatalogQuery): String = query.normalized().let { effective -> when (effective.window) {
        CatalogWindow.SUBJECT -> buildList {
            if (effective.medium == CatalogMedium.COMICS) add(CatalogMedium.COMICS.label)
            if (effective.focus !in setOf(CatalogFocus.ALL, CatalogFocus.COMICS)) add(effective.focus.label)
            if (effective.genre != CatalogGenre.ANY) add(effective.genre.label)
            if (effective.search.isNotBlank() && isEmpty()) add("Search results")
        }.ifEmpty { listOf("Fiction (default)") }.joinToString(" · ")
        CatalogWindow.WEEK -> "7-day trends · all categories"
        CatalogWindow.MONTH -> "30-day trends · all categories"
    } }

    /** Includes every selectable subject facet so horizontally scrolled chips remain discoverable. */
    fun savedSubjectFacetSummary(query: CatalogQuery): String = query.normalized().let { effective ->
        "Category ${effective.medium.label} · Type ${effective.focus.label} · Genre ${effective.genre.label}"
    }
}

internal fun catalogRequestUrl(query: CatalogQuery, page: Int): HttpUrl {
    require(page in 1..CatalogPager.MAX_PAGES)
    val effectiveQuery = query.normalized()
    if (effectiveQuery.window != CatalogWindow.SUBJECT) {
        return "https://openlibrary.org/trending/${effectiveQuery.window.path}.json".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "24")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
    }
    val searchTerm = effectiveQuery.search.trim().take(120).takeIf(String::isNotBlank)?.let {
        "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
    val terms = CatalogFilterPolicy.subjectTerms(effectiveQuery) + listOfNotNull(searchTerm)
    return "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
        .addQueryParameter("q", terms.joinToString(" AND ").ifBlank { "subject:fiction" })
        .addQueryParameter("limit", "24")
        .apply { if (page > 1) addQueryParameter("offset", ((page - 1) * 24).toString()) }
        .addQueryParameter("fields", "key,title,author_name,cover_i,subject,first_publish_year,edition_key")
        .build()
}
