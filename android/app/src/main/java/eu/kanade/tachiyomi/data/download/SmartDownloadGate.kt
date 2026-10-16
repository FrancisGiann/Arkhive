package eu.kanade.tachiyomi.data.download

import java.io.IOException
import mihon.core.archive.ArchiveWriteGuard

/** A Smart-only check at request/write boundaries. It never changes Mihon's manual queue. */
interface SmartDownloadGate : ArchiveWriteGuard {
    override fun checkAllowed()
    fun write(bytes: Int, write: () -> Unit)
    fun commitItem(actualStoredBytes: Long? = null)
    fun discardItem()
    val itemBytes: Long
    val blockedReason: String?
}

class SmartOfflineBlockedException(message: String) : IOException(message)
