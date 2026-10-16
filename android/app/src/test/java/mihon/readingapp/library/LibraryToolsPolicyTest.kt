package mihon.readingapp.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LibraryToolsPolicyTest {
    @Test fun unknownSchemaUnsafeSeriesAndOutOfRangeProfilesAreRejected() {
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(schema=2))}.isFailure)
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(profiles=mapOf("../../file" to ReadingProfile())))}.isFailure)
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(profiles=mapOf("manga:1" to ReadingProfile(brightnessPercent=101))))}.isFailure)
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(smartOffline=mapOf("manga:1" to SmartOfflineRule(true,500))))}.isFailure)
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(assignments=mapOf("manga:1" to ShelfAssignment(shelves=setOf("Missing")))))}.isFailure)
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(dashboardFilter="Bookmarks"))}.isFailure)
        assertTrue(runCatching {LibraryToolsPolicy.validate(LibraryToolsState(dashboardCollection="Removed shelf"))}.isFailure)
    }
    @Test fun addToShelvesUnionsEverySupportedSeriesKeyAndPreservesExistingAssignments() {
        val manga = "manga:42"
        val book = "book:" + "a".repeat(64)
        val novel = "novel:" + "b".repeat(64)
        val initial = LibraryToolsState(
            shelves = listOf("Existing", "Shared"),
            assignments = mapOf(
                manga to ShelfAssignment(ShelfStatus.READING, setOf("Existing")),
                book to ShelfAssignment(ShelfStatus.ON_HOLD, setOf("Shared")),
            ),
        )

        val updated = LibraryToolsPolicy.addToShelves(
            initial,
            setOf(manga, book, novel),
            setOf(" Existing ", "New", "New"),
        )

        assertEquals(listOf("Existing", "Shared", "New"), updated.shelves)
        assertEquals(ShelfAssignment(ShelfStatus.READING, setOf("Existing", "New")), updated.assignments[manga])
        assertEquals(ShelfAssignment(ShelfStatus.ON_HOLD, setOf("Shared", "Existing", "New")), updated.assignments[book])
        assertEquals(ShelfAssignment(null, setOf("Existing", "New")), updated.assignments[novel])
    }

    @Test fun emptyShelfDraftDoesNotCreateShelvesOrAssignments() {
        val initial = LibraryToolsState(shelves = listOf("Kept"), assignments = mapOf("manga:9" to ShelfAssignment(ShelfStatus.FINISHED, setOf("Kept"))))
        assertEquals(initial, LibraryToolsPolicy.addToShelves(initial, setOf("manga:9"), emptySet()))
        assertEquals(initial, LibraryToolsPolicy.addToShelves(initial, emptySet(), setOf("Draft only")))
        assertEquals(initial, LibraryToolsPolicy.addToShelves(initial, setOf("manga:9"), setOf("  ")))
    }

    @Test fun metadataGapsAreOnlyPotentialNumberingGaps() {
        assertEquals(listOf("2 → 4"),LibraryToolsPolicy.possibleGaps(listOf(-1.0,1.0,2.0,2.0,2.5,4.0,Double.NaN)))
    }
    @Test fun roomsHaveAccessibleTextContrast() {
        fun luminance(color:Int):Double {
            fun channel(shift:Int):Double {val s=((color shr shift) and 255)/255.0;return if(s<=.04045) s/12.92 else Math.pow((s+.055)/1.055,2.4)}
            return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
        }
        ReadingRoom.entries.forEach {val a=luminance(it.paper);val b=luminance(it.text);assertTrue((maxOf(a,b)+.05)/(minOf(a,b)+.05)>=7,it.label)}
    }
}
