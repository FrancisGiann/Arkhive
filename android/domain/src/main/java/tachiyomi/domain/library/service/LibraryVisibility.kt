package tachiyomi.domain.library.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

/** Default library/history/update consumers exclude hidden series; backups retain their membership. */
@Inject
@SingleIn(AppScope::class)
class LibraryVisibility(store: PreferenceStore) {
    val hiddenSeries = store.getStringSet("arkhive_hidden_series", emptySet())
    fun isHidden(key: String): Boolean = key in hiddenSeries.get()
    fun isMangaHidden(id: Long): Boolean = isHidden("manga:$id")
    @Synchronized fun setHidden(keys: Set<String>, hidden: Boolean) {
        require(keys.all { Regex("(?:manga:[0-9]+|(?:book|novel):[a-f0-9]{64})").matches(it) })
        hiddenSeries.set(if (hidden) hiddenSeries.get() + keys else hiddenSeries.get() - keys)
    }
}
