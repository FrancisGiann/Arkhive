package mihon.core.archive

import android.content.Context
import android.system.Os
import android.system.StructStat
import com.hippo.unifile.UniFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.nio.ByteBuffer

class ZipWriter(val context: Context, file: UniFile, private val writeGuard: ArchiveWriteGuard? = null) : Closeable {
    private val pfd = file.openFileDescriptor(context, "wt")
    private val archive = Archive.writeNew()
    private val entry = ArchiveEntry.new2(archive)
    private val buffer = ByteBuffer.allocateDirect(8192)

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.writeSetFormatZip(archive)
            Archive.writeZipSetCompressionStore(archive)
            Archive.writeOpenFd(archive, pfd.fd)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    fun write(file: UniFile) {
        file.openFileDescriptor(context, "r").use {
            val fd = it.fileDescriptor
            ArchiveEntry.clear(entry)
            ArchiveEntry.setPathnameUtf8(entry, file.name)
            val stat = Os.fstat(fd)
            ArchiveEntry.setStat(entry, stat.toArchiveStat())
            val nameBytes = file.name.orEmpty().toByteArray(Charsets.UTF_8).size
            val metadataBound = (160 + nameBytes * 4).coerceAtMost(8192)
            if (writeGuard == null) Archive.writeHeader(archive, entry)
            else writeGuard.accountMetadata(metadataBound) { Archive.writeHeader(archive, entry) }
            while (true) {
                buffer.clear()
                Os.read(fd, buffer)
                if (buffer.position() == 0) break
                buffer.flip()
                if (writeGuard == null) Archive.writeData(archive, buffer)
                else {
                    writeGuard.checkAllowed()
                    Archive.writeData(archive, buffer)
                }
            }
            Archive.writeFinishEntry(archive)
        }
    }

    override fun close() {
        try {
            if (writeGuard == null) Archive.writeFree(archive)
            else writeGuard.accountMetadata(64) { Archive.writeFree(archive) }
        } catch (error: Throwable) {
            Archive.writeFree(archive)
            throw error
        } finally {
            ArchiveEntry.free(entry)
            pfd.close()
        }
    }
}

private fun StructStat.toArchiveStat() = ArchiveEntry.StructStat().apply {
    stDev = st_dev
    stMode = st_mode
    stNlink = st_nlink.toInt()
    stUid = st_uid
    stGid = st_gid
    stRdev = st_rdev
    stSize = st_size
    stBlksize = st_blksize
    stBlocks = st_blocks
    stAtim = st_atime.toTimespec()
    stMtim = st_mtime.toTimespec()
    stCtim = st_ctime.toTimespec()
    stIno = st_ino
}

private fun Long.toTimespec() = ArchiveEntry.StructTimespec().also { it.tvSec = this }
