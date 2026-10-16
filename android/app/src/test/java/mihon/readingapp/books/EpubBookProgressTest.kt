package mihon.readingapp.books

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpubBookProgressTest {
    @Test
    fun `actual unequal position groups define resource boundaries and interpolate discrete Readium buckets`() {
        val progress = EpubBookProgress.fromReadingOrder(
            listOf(
                resource("one.xhtml", 1, 3),
                resource("two.xhtml", 4, 1),
            ),
        )

        assertEquals(.45, progress.progression("https://readium-package.invalid/one.xhtml#p2", 0.5, 3, .6)!!, 1e-8)
        assertEquals(.875, progress.progression("two.xhtml", .75, 4, .5)!!, 1e-8)
        assertEquals(.75, progress.progression("two.xhtml", .75, 4, 0.0)!!, 1e-8)
    }

    @Test
    fun `one position final resource still advances and one resource spans the publication`() {
        val multi = EpubBookProgress.fromReadingOrder(
            listOf(resource("first.xhtml", 1, 3), resource("last.xhtml", 4, 1)),
        )
        assertEquals(.875, multi.progression("last.xhtml", .75, 4, .5)!!, 1e-8)
        assertEquals(1.0, multi.progression("last.xhtml", .75, 4, 2.0)!!, 1e-8)

        val single = EpubBookProgress.fromReadingOrder(listOf(resource("only.xhtml", 1, 1)))
        assertEquals(.4, single.progression("only.xhtml", 0.0, 1, .4)!!, 1e-8)
        assertEquals(.9, single.progression("only.xhtml", null, 1, .9)!!, 1e-8)
    }

    @Test
    fun `valid continuous native totals remain authoritative while null totals use resource position bounds`() {
        val progress = EpubBookProgress.fromReadingOrder(
            listOf(resource("one.xhtml", 1, 2), resource("two.xhtml", 3, 2)),
        )

        assertEquals(.68, progress.progression("two.xhtml", .68, 4, .8)!!, 1e-8)
        assertEquals(.7, progress.progression("two.xhtml", null, 4, .4)!!, 1e-8)
        assertEquals(.68, progress.progression("unlisted.xhtml", .68, 4, .8)!!, 1e-8)
        assertNull(progress.progression("unlisted.xhtml", Double.NaN, null, null))
    }

    @Test
    fun `nonsequential position service data never fabricates resource boundaries`() {
        val progress = EpubBookProgress.fromReadingOrder(
            listOf(
                EpubProgressResource("one.xhtml", 2, 1, 9, positionsAreSequential = false),
                resource("two.xhtml", 3, 1),
            ),
        )
        assertEquals(.5, progress.progression("one.xhtml", .5, 2, .8)!!, 1e-8)
    }

    private fun resource(href: String, firstPosition: Int, count: Int) = EpubProgressResource(
        href = href,
        positionCount = count,
        firstPosition = firstPosition,
        lastPosition = if(count > 0) firstPosition + count - 1 else null,
        positionsAreSequential = true,
    )
}
