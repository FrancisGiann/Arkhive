package mihon.readingapp.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChapterMappingPolicyTest {
    @Test fun onlyUniqueNumbersAndTitlesAreVerifiedWithoutBlanketReadTransfer() {
        val previous=listOf(ChapterReference("1","Chapter 1",1.0),ChapterReference("2","Different title",2.0),ChapterReference("3","Special",-1.0))
        val target=listOf(ChapterReference("a","Chapter 1",1.0),ChapterReference("b","Chapter 2",2.0),ChapterReference("c","Special",-1.0),ChapterReference("d","Unmatched",8.0))
        val plan=ChapterMappingPolicy.plan(previous,target)
        assertEquals(setOf("1|a","3|c"),plan.verified.map {it.key}.toSet())
        assertEquals(listOf("2|b"),plan.ambiguous.map {it.key});assertEquals(1,plan.unmatched)
        assertEquals(2,ChapterMappingPolicy.selected(plan,emptySet()).size)
        assertEquals(3,ChapterMappingPolicy.selected(plan,setOf("2|b")).size)
    }
    @Test fun duplicateNumberCandidatesRequireAnUnambiguousUserSelection() {
        val plan=ChapterMappingPolicy.plan(listOf(ChapterReference("1","Edition A",1.0),ChapterReference("2","Edition B",1.0)),listOf(ChapterReference("a","Chapter 1",1.0)))
        assertTrue(plan.verified.isEmpty());assertEquals(2,plan.ambiguous.size)
        assertThrows(IllegalArgumentException::class.java) {ChapterMappingPolicy.selected(plan,plan.ambiguous.map {it.key}.toSet())}
        assertEquals("1",ChapterMappingPolicy.selected(plan,setOf("1|a")).single().previous.id)
    }
}
