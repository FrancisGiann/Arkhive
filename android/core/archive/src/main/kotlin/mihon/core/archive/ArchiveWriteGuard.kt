package mihon.core.archive

/** Optional write checks for archives whose storage is governed by a bounded feature. */
interface ArchiveWriteGuard {
    fun checkAllowed()
    fun accountMetadata(maxBytes: Int, write: () -> Unit)
}
