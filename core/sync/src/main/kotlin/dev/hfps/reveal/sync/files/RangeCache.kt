package dev.hfps.reveal.sync.files

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A block cache of remote files read by range (ADR 0018 "Server photos"): each [BLOCK] bytes of a file are one small
 * file under `dir/<key>/<block index>`, written whole (temp + rename) so a crash never leaves a short block. A miss
 * fetches up to [READ_AHEAD] blocks in one request (a RAW decoder reads forward). [trim] keeps the cache under a cap,
 * dropping the least recently used files whole.
 */
class RangeCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    /** Reads [length] bytes at [offset] of the remote file [key] of [size] bytes; [fetch] reads one range remotely. */
    fun read(
        key: String,
        size: Long,
        offset: Long,
        length: Int,
        into: ByteArray,
        fetch: (offset: Long, length: Int) -> ByteArray,
    ): Int {
        if (offset >= size || length <= 0) return 0
        val end = minOf(size, offset + length)
        val folder = File(dir, key).apply { mkdirs() }
        folder.setLastModified(System.currentTimeMillis())
        var position = offset
        var written = 0
        while (position < end) {
            val index = position / BLOCK
            val block = blockFile(folder, index) ?: fetchBlocks(folder, index, size, fetch)
            val blockStart = index * BLOCK
            val from = (position - blockStart).toInt()
            val n = minOf(end - position, BLOCK - from.toLong()).toInt()
            RandomAccessFile(block, "r").use { raf ->
                raf.seek(from.toLong())
                raf.readFully(into, written, n)
            }
            position += n
            written += n
        }
        return written
    }

    private fun blockFile(folder: File, index: Long): File? = File(folder, index.toString()).takeIf { it.exists() }

    /** Fetches [index] and the missing blocks after it (up to [READ_AHEAD]) in one range; returns the first. */
    private fun fetchBlocks(folder: File, index: Long, size: Long, fetch: (Long, Int) -> ByteArray): File {
        val lastBlock = (size - 1) / BLOCK
        var count = 1
        while (count < READ_AHEAD && index + count <= lastBlock && blockFile(folder, index + count) == null) count++
        val start = index * BLOCK
        val length = minOf(size - start, count * BLOCK).toInt()
        val bytes = fetch(start, length)
        if (bytes.size < length) throw IOException("short read: ${bytes.size} of $length")
        for (i in 0 until count) {
            val from = (i * BLOCK).toInt()
            val to = minOf(bytes.size.toLong(), (i + 1) * BLOCK).toInt()
            val temp = File(folder, "${index + i}.tmp")
            temp.writeBytes(bytes.copyOfRange(from, to))
            temp.renameTo(File(folder, (index + i).toString()))
        }
        return File(folder, index.toString())
    }

    /** Total bytes cached. */
    fun bytes(): Long = dir.listFiles()?.sumOf { folder -> folder.listFiles()?.sumOf { it.length() } ?: 0L } ?: 0L

    /** Drops whole files, least recently read first, until the cache is under its cap. */
    fun trim() {
        val folders = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return
        var total = folders.sumOf { folder -> folder.listFiles()?.sumOf { it.length() } ?: 0L }
        for (folder in folders) {
            if (total <= maxBytes) break
            val size = folder.listFiles()?.sumOf { it.length() } ?: 0L
            folder.deleteRecursively()
            total -= size
        }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        const val BLOCK: Long = 256L * 1024
        const val READ_AHEAD = 16
        const val DEFAULT_MAX_BYTES = 1024L * 1024 * 1024
    }
}
