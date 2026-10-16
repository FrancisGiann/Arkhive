package mihon.readingapp.library

import eu.kanade.tachiyomi.data.download.SmartDownloadGate
import eu.kanade.tachiyomi.data.download.SmartOfflineBlockedException

/** Synchronized across page fan-out: every output chunk reserves the shared remaining budget. */
internal class SmartOfflineBudget(
    private val limitBytes: () -> Long,
    initialOwnedBytes: Long,
    private val wifiAvailable: () -> Boolean,
    private val stillAllowed: () -> Boolean,
    private val disallowedReason: () -> String = { "Smart offline is paused or this series rule is off." },
) : SmartDownloadGate {
    private val lock = Any()
    private var committedBytes = initialOwnedBytes.coerceAtLeast(0)
    private var writingBytes = 0L
    private var reservedBytes = 0L
    @Volatile private var blocked: String? = null

    override val itemBytes: Long get() = synchronized(lock) { writingBytes }
    override val blockedReason: String? get() = blocked

    override fun checkAllowed() = synchronized(lock) {
        val reason = when {
            blocked != null -> blocked
            !stillAllowed() -> disallowedReason()
            !wifiAvailable() -> "Smart offline waits for Wi-Fi."
            writingBytes == 0L && reservedBytes == 0L && committedBytes >= limitBytes().coerceAtLeast(0) -> "Smart offline reached its storage limit. Increase the limit or remove a Smart-owned copy."
            else -> null
        }
        if (reason != null) reject(reason)
    }

    override fun write(bytes: Int, write: () -> Unit) {
        require(bytes >= 0)
        if (bytes == 0) return
        synchronized(lock) {
            checkAllowed()
            if (committedBytes + writingBytes + reservedBytes + bytes > limitBytes().coerceAtLeast(0)) {
                reject("Smart offline reached its storage limit. Increase the limit or remove a Smart-owned copy.")
            }
            reservedBytes += bytes
        }
        try {
            write()
            synchronized(lock) {
                reservedBytes -= bytes
                writingBytes += bytes
            }
        } catch (error: Throwable) {
            synchronized(lock) { reservedBytes -= bytes }
            throw error
        }
    }

    override fun accountMetadata(maxBytes: Int, write: () -> Unit) = this.write(maxBytes, write)

    override fun commitItem(actualStoredBytes: Long?) = synchronized(lock) {
        val measured = actualStoredBytes ?: writingBytes
        require(measured >= 0 && measured <= writingBytes) { "Measured Smart copy size must fit within the guarded writes." }
        committedBytes += measured
        writingBytes = 0
    }

    override fun discardItem() = synchronized(lock) { writingBytes = 0 }

    private fun reject(message: String): Nothing {
        blocked = message
        throw SmartOfflineBlockedException(message)
    }
}
