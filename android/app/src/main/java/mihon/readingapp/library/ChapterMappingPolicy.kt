package mihon.readingapp.library

data class ChapterReference(val id:String,val name:String,val number:Double?)
data class ChapterMapping(val previous:ChapterReference,val target:ChapterReference,val verified:Boolean) {
    val key:String get()="${previous.id}|${target.id}"
}
data class ChapterMappingPlan(val verified:List<ChapterMapping>,val ambiguous:List<ChapterMapping>,val unmatched:Int)

/** Numbers alone are candidates. Only a unique number plus matching title is automatically accepted. */
object ChapterMappingPolicy {
    private fun name(value:String)=value.trim().lowercase().replace(Regex("\\s+")," ")
    fun plan(previous:List<ChapterReference>,target:List<ChapterReference>):ChapterMappingPlan {
        val verified=mutableListOf<ChapterMapping>();val ambiguous=mutableListOf<ChapterMapping>();var unmatched=0
        target.forEach {chapter->
            val sameNumber=chapter.number?.takeIf {it.isFinite() && it>=0}?.let {number->previous.filter {it.number==number}}.orEmpty()
            val exact=if(sameNumber.isEmpty()) previous.filter {name(it.name)==name(chapter.name)} else sameNumber.filter {name(it.name)==name(chapter.name)}
            val targetDuplicates=target.count {it.number==chapter.number && name(it.name)==name(chapter.name)}
            if(exact.size==1 && targetDuplicates==1 && target.count {name(it.name)==name(chapter.name)}==1) verified+=ChapterMapping(exact.single(),chapter,true)
            else if(sameNumber.isNotEmpty()) ambiguous+=sameNumber.take(10).map {ChapterMapping(it,chapter,false)}
            else unmatched++
        }
        val used=verified.groupBy {it.previous.id}.filterValues {it.size>1}.keys
        return ChapterMappingPlan(verified.filterNot {it.previous.id in used},ambiguous+verified.filter {it.previous.id in used}.map {it.copy(verified=false)},unmatched)
    }
    fun selected(plan:ChapterMappingPlan,reviewed:Set<String>):List<ChapterMapping> {
        val result=plan.verified+plan.ambiguous.filter {it.key in reviewed}
        require(result.map {it.previous.id}.distinct().size==result.size && result.map {it.target.id}.distinct().size==result.size) {"Each chapter can map to only one chapter. Review conflicting selections."}
        return result
    }
}
