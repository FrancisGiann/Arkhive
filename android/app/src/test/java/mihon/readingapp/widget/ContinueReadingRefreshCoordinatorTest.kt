package mihon.readingapp.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContinueReadingRefreshCoordinatorTest {
    private data class Selection(
        val key: String?, val title: String, val chapter: String, val progress: Double,
    )
    private data class Rendered(val selection: Selection, val cover: String?)

    @Test fun equivalentRecreatedCoverSourcesDoNotStarveTheRefresh() = runTest {
        data class Snapshot(val key: String, val coverSource: Any)
        val coordinator = ContinueReadingRefreshCoordinator<Snapshot, String>()
        var published = 0
        coordinator.refresh(
            select = { Snapshot("manga:2", Any()) },
            loadCover = { "cover for ${it.key}" },
            publish = { selection, cover ->
                assertEquals("manga:2", selection.key)
                assertEquals("cover for manga:2", cover)
                published++
            },
            sameSelection = { first, second -> first.key == second.key },
        )
        assertEquals(1, published)
    }

    @Test fun switchingBooksDuringCoverLoadPublishesOnlyMatchingMetadataAndCover() = runTest {
        val coordinator = ContinueReadingRefreshCoordinator<Selection, String>()
        val first = Selection("manga:1", "First", "3 / 10 read", .3)
        val second = Selection("manga:2", "Second", "9 / 20 read", .45)
        var selected = first
        val loadingFirst = CompletableDeferred<Unit>()
        val releasesFirst = CompletableDeferred<Unit>()
        val published = mutableListOf<Rendered>()
        val refresh = async {
            coordinator.refresh(
                select = { selected },
                loadCover = { story ->
                    if (story == first) {
                        loadingFirst.complete(Unit)
                        releasesFirst.await()
                    }
                    story.key
                },
                publish = { story, cover -> published += Rendered(story, cover) },
            )
        }
        loadingFirst.await()
        selected = second
        releasesFirst.complete(Unit)
        refresh.await()
        assertEquals(listOf(Rendered(second, "manga:2")), published)
    }

    @Test fun rapidRefreshesNeverLetAnOlderSelectionOverwriteTheLatest() = runTest {
        val coordinator = ContinueReadingRefreshCoordinator<Selection, String>()
        var selected = Selection("manga:1", "First", "1 / 10 read", .1)
        val published = mutableListOf<Rendered>()
        val first = async {
            coordinator.refresh({ selected }, { it.key }, { story, cover -> published += Rendered(story, cover) })
        }
        first.await()
        selected = Selection("manga:2", "Second", "2 / 10 read", .2)
        val newer = async {
            coordinator.refresh({ selected }, { it.key }, { story, cover -> published += Rendered(story, cover) })
        }
        val another = async {
            coordinator.refresh({ selected }, { it.key }, { story, cover -> published += Rendered(story, cover) })
        }
        newer.await()
        another.await()
        assertEquals("Second", published.last().selection.title)
        assertEquals("manga:2", published.last().cover)
        assertEquals(.2, published.last().selection.progress)
    }

    @Test fun emptySelectionAndCoverFailureClearOldContentTogether() = runTest {
        val coordinator = ContinueReadingRefreshCoordinator<Selection, String>()
        var selected = Selection("manga:1", "First", "1 / 10 read", .1)
        val published = mutableListOf<Rendered>()
        suspend fun refresh(failCover: Boolean) = coordinator.refresh(
            select = { selected },
            loadCover = { if (failCover) error("Cover unavailable") else it.key },
            publish = { story, cover -> published += Rendered(story, cover) },
        )
        refresh(false)
        selected = Selection(null, "Your next chapter", "Pick up where you left off", 0.0)
        refresh(false)
        assertNull(published.last().selection.key)
        assertNull(published.last().cover)
        selected = Selection("manga:2", "Second", "2 / 10 read", .2)
        refresh(true)
        assertEquals("Second", published.last().selection.title)
        assertNull(published.last().cover)
    }
}
