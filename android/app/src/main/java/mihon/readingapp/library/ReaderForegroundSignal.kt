package mihon.readingapp.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Collections
import java.util.IdentityHashMap

/** Lifecycle-only signal; Smart work is kicked when the last reading screen leaves foreground. */
@Inject
@SingleIn(AppScope::class)
class ReaderForegroundSignal {
    private val active = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Synchronized fun enter(owner: Any) { active += owner }
    @Synchronized fun leave(owner: Any) { active -= owner }
    @Synchronized fun isActive(): Boolean = active.isNotEmpty()
}
