package mihon.readingapp.novels

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LnReaderSourceCompatibilityTest {
    @Test
    fun `LNReader query filter names accept literal array suffix without accepting query syntax`() {
        assertTrue(isValidLnReaderFilterKey("genre[]"))
        assertTrue(isValidLnReaderFilterKey("type[]"))
        assertTrue(isValidLnReaderFilterKey("status"))
        assertTrue(isValidLnReaderFilterKey("a".repeat(80)))
        assertFalse(isValidLnReaderFilterKey("genre[][]"))
        assertFalse(isValidLnReaderFilterKey("genre[other]"))
        assertFalse(isValidLnReaderFilterKey("genre[]&other=1"))
        assertFalse(isValidLnReaderFilterKey(""))
        assertFalse(isValidLnReaderFilterKey("a".repeat(81)))
    }

    @Test
    fun `catalog adapter accepts bounded pages above legacy 50 row ceiling without dropping rows`() {
        val sourceRows = JsonArray((0 until 75).map { JsonPrimitive("novel-$it") })

        assertEquals(sourceRows.toList(), boundedCatalogRows(sourceRows))
        assertThrows(IllegalArgumentException::class.java) {
            boundedCatalogRows(JsonArray((0..LN_READER_MAX_CATALOG_ROWS_PER_OPERATION).map { JsonPrimitive(it) }))
        }
    }
}
