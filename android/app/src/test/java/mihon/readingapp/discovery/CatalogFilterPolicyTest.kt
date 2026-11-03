package mihon.readingapp.discovery

import mihon.domain.discovery.CatalogFocus
import mihon.domain.discovery.CatalogGenre
import mihon.domain.discovery.CatalogMedium
import mihon.domain.discovery.CatalogQuery
import mihon.domain.discovery.CatalogWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogFilterPolicyTest {
    @Test fun manhwaAndComicsUseSeparateFieldsAndAdventureIsOrderIndependent() {
        val selectedMedium = CatalogFilterPolicy.selectMedium(
            CatalogQuery(focus = CatalogFocus.MANHWA), CatalogMedium.COMICS,
        )
        assertEquals(CatalogFocus.MANHWA, selectedMedium.query.focus)
        assertEquals(CatalogMedium.COMICS, selectedMedium.query.medium)
        assertNull(selectedMedium.notice)

        val manhwaThenAdventure = selectedMedium.query.copy(genre = CatalogGenre.ADVENTURE)
        val adventureThenManhwa = requireNotNull(CatalogFilterPolicy.selectFocus(
            CatalogQuery(medium = CatalogMedium.COMICS, genre = CatalogGenre.ADVENTURE), CatalogFocus.MANHWA,
        ))
        assertEquals(manhwaThenAdventure, adventureThenManhwa)

        val url = catalogRequestUrl(manhwaThenAdventure, 1)
        assertEquals("subject:comics AND subject:manhwa AND subject:adventure", url.queryParameter("q"))
        val pageTwo = catalogRequestUrl(manhwaThenAdventure, 2)
        assertEquals(url.queryParameter("q"), pageTwo.queryParameter("q"))
        assertEquals("24", pageTwo.queryParameter("offset"))
        assertNotEquals(CatalogQuery(focus = CatalogFocus.MANHWA), manhwaThenAdventure)
    }

    @Test fun incompatibleBookSubtypesAreExplicitlyClearedOrDisabled() {
        val bookFilter = CatalogQuery(focus = CatalogFocus.NOVELS, genre = CatalogGenre.FANTASY)
        assertFalse(CatalogFilterPolicy.canSelectFocus(CatalogMedium.COMICS, CatalogFocus.NOVELS))
        assertNull(CatalogFilterPolicy.selectFocus(
            CatalogQuery(medium = CatalogMedium.COMICS, focus = CatalogFocus.MANHWA), CatalogFocus.BOOKS,
        ))

        val switched = CatalogFilterPolicy.selectMedium(bookFilter, CatalogMedium.COMICS)
        assertEquals(CatalogFocus.ALL, switched.query.focus)
        assertEquals(CatalogMedium.COMICS, switched.query.medium)
        assertEquals(CatalogGenre.FANTASY, switched.query.genre)
        assertNotNull(switched.notice)
        assertEquals("subject:comics AND subject:fantasy", catalogRequestUrl(switched.query, 1).queryParameter("q"))
        val legacyConflict = CatalogQuery(focus = CatalogFocus.NOVELS, medium = CatalogMedium.COMICS)
        val restored = CatalogFilterPolicy.normalize(legacyConflict)
        assertEquals(CatalogMedium.COMICS, restored.medium)
        assertEquals(CatalogFocus.ALL, restored.focus)
    }

    @Test fun applyCancelReopenAndResetKeepChangesDraftUntilExplicitApply() {
        val applied = CatalogQuery(
            focus = CatalogFocus.MANHWA,
            genre = CatalogGenre.ANY,
            search = "author search",
        )
        var draft = CatalogFilterPolicy.beginDraft(applied)
        draft = CatalogFilterPolicy.draftMedium(draft, CatalogMedium.COMICS)
        draft = CatalogFilterPolicy.draftGenre(draft, CatalogGenre.ADVENTURE)
        assertEquals(applied, CatalogFilterPolicy.cancelDraft(draft).query)

        draft = CatalogFilterPolicy.beginDraft(applied)
        draft = CatalogFilterPolicy.draftGenre(draft, CatalogGenre.ADVENTURE)
        val committed = CatalogFilterPolicy.applyDraft(draft, "author search")
        assertEquals(CatalogMedium.ALL_BOOKS, committed.medium)
        assertEquals(CatalogFocus.MANHWA, committed.focus)
        assertEquals(CatalogGenre.ADVENTURE, committed.genre)
        assertEquals("author search", committed.search)
        assertEquals(committed, CatalogFilterPolicy.beginDraft(committed).query)

        val reset = CatalogFilterPolicy.applyDraft(CatalogFilterPolicy.resetDraft(draft), "author search")
        assertEquals(CatalogMedium.ALL_BOOKS, reset.medium)
        assertEquals(CatalogFocus.ALL, reset.focus)
        assertEquals(CatalogGenre.ANY, reset.genre)
        assertEquals(CatalogWindow.SUBJECT, reset.window)
        assertEquals("author search", reset.search)
        assertTrue(committed != applied)
    }

    @Test fun trendsKeepSubjectSelectionsButUseTheUnfilteredProviderEndpoint() {
        val subjectQuery = CatalogQuery(
            medium = CatalogMedium.COMICS,
            focus = CatalogFocus.MANHWA,
            genre = CatalogGenre.ADVENTURE,
        )
        val draft = CatalogFilterPolicy.selectWindow(subjectQuery, CatalogWindow.WEEK, "")
        assertEquals(CatalogMedium.COMICS, draft.query.medium)
        assertEquals(CatalogFocus.MANHWA, draft.query.focus)
        assertEquals(CatalogGenre.ADVENTURE, draft.query.genre)
        assertEquals("7-day trends · all categories", CatalogFilterPolicy.appliedSummary(draft.query))
        val trendUrl = catalogRequestUrl(draft.query, 2)
        assertTrue(trendUrl.encodedPath.endsWith("/trending/weekly.json"))
        assertNull(trendUrl.queryParameter("q"))
        assertEquals("2", trendUrl.queryParameter("page"))

        val blockedTrend = CatalogFilterPolicy.selectWindow(subjectQuery, CatalogWindow.MONTH, "searching")
        assertEquals(CatalogWindow.SUBJECT, blockedTrend.query.window)
        assertNotNull(blockedTrend.notice)
        assertFalse(CatalogFilterPolicy.canSelectWindow(CatalogWindow.WEEK, "searching"))

        val draftFromSearch = CatalogFilterPolicy.selectWindow(
            subjectQuery.copy(search = "old search"), CatalogWindow.MONTH, "",
        )
        assertEquals("", draftFromSearch.query.search)
        assertEquals("30-day trends · all categories", CatalogFilterPolicy.appliedSummary(draftFromSearch.query))
        assertEquals(
            "Category Comics · Type Manhwa · Genre Adventure",
            CatalogFilterPolicy.savedSubjectFacetSummary(draftFromSearch.query),
        )
        assertTrue(catalogRequestUrl(draftFromSearch.query, 1).encodedPath.endsWith("/trending/monthly.json"))

        val appliedSearch = CatalogFilterPolicy.applySearch(draft.query, "searching")
        assertEquals(CatalogWindow.SUBJECT, appliedSearch.window)
        assertEquals("searching", appliedSearch.search)
        assertEquals("subject:comics AND subject:manhwa AND subject:adventure AND \"searching\"",
            catalogRequestUrl(appliedSearch, 1).queryParameter("q"))
    }

    @Test fun reopenedFilterDraftKeepsSelectedManhwaAndAdventureInVisibleSummary() {
        val applied = CatalogQuery(
            medium = CatalogMedium.COMICS,
            focus = CatalogFocus.MANHWA,
            genre = CatalogGenre.ADVENTURE,
        )
        val reopened = CatalogFilterPolicy.beginDraft(applied)
        assertEquals(applied, reopened.query)
        assertEquals(
            "Category Comics · Type Manhwa · Genre Adventure",
            CatalogFilterPolicy.savedSubjectFacetSummary(reopened.query),
        )
    }

    @Test fun legacyComicsFocusMapsToTheNewMediumAxis() {
        val normalized = CatalogFilterPolicy.normalizeLegacy(CatalogQuery(focus = CatalogFocus.COMICS))
        assertEquals(CatalogMedium.COMICS, normalized.medium)
        assertEquals(CatalogFocus.ALL, normalized.focus)
        assertEquals("subject:comics", catalogRequestUrl(normalized, 1).queryParameter("q"))
    }

    @Test fun restoredTrendAndSearchStateNormalizesBeforeRequestAndSummary() {
        // Older saved UI state could retain a nonempty title search alongside a trend window.
        val restored = CatalogQuery(
            focus = CatalogFocus.MANHWA,
            genre = CatalogGenre.ADVENTURE,
            window = CatalogWindow.WEEK,
            search = "old title search",
            medium = CatalogMedium.COMICS,
        )
        val effective = CatalogFilterPolicy.normalize(restored)
        assertEquals(CatalogWindow.SUBJECT, effective.window)
        assertEquals("old title search", effective.search)
        assertEquals("Comics · Manhwa · Adventure", CatalogFilterPolicy.appliedSummary(effective))
        val url = catalogRequestUrl(restored, 1)
        assertTrue(url.encodedPath.endsWith("/search.json"))
        assertEquals(
            "subject:comics AND subject:manhwa AND subject:adventure AND \"old title search\"",
            url.queryParameter("q"),
        )
    }

    @Test fun trendSearchDraftApplyAndCancelStayAlignedWithEffectiveMode() {
        val appliedSearch = CatalogQuery(
            focus = CatalogFocus.MANHWA,
            genre = CatalogGenre.ADVENTURE,
            search = "title query",
        )
        var draft = CatalogFilterPolicy.beginDraft(appliedSearch)
        draft = CatalogFilterPolicy.draftWindow(draft, CatalogWindow.WEEK, "")
        assertEquals("", draft.query.search)
        assertEquals(CatalogWindow.WEEK, draft.query.window)
        assertEquals(appliedSearch, CatalogFilterPolicy.cancelDraft(draft).query)
        assertEquals(CatalogWindow.SUBJECT, CatalogFilterPolicy.normalize(appliedSearch).window)

        val appliedTrend = CatalogQuery(
            medium = CatalogMedium.COMICS,
            focus = CatalogFocus.MANHWA,
            genre = CatalogGenre.ADVENTURE,
            window = CatalogWindow.MONTH,
        )
        draft = CatalogFilterPolicy.beginDraft(appliedTrend)
        draft = CatalogFilterPolicy.draftWindow(draft, CatalogWindow.SUBJECT, "")
        draft = CatalogFilterPolicy.draftGenre(draft, CatalogGenre.FANTASY)
        assertEquals(appliedTrend, CatalogFilterPolicy.cancelDraft(draft).query)
        val appliedSubject = CatalogFilterPolicy.applyDraft(draft, "")
        assertEquals(CatalogWindow.SUBJECT, appliedSubject.window)
        assertEquals(CatalogGenre.FANTASY, appliedSubject.genre)
        assertTrue(catalogRequestUrl(appliedSubject, 1).queryParameter("q").orEmpty().contains("subject:fantasy"))
    }

    @Test fun defaultSummaryNamesTheActualFictionFallback() {
        val reset = CatalogFilterPolicy.reset(CatalogQuery(focus = CatalogFocus.MANHWA))
        assertEquals("subject:fiction", catalogRequestUrl(reset, 1).queryParameter("q"))
        assertEquals("Fiction (default)", CatalogFilterPolicy.appliedSummary(reset))
        assertEquals(
            "Search results",
            CatalogFilterPolicy.appliedSummary(CatalogQuery(focus = CatalogFocus.ALL, search = "a title")),
        )
    }
}
