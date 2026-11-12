package dev.hfps.reveal.data.preset

import android.content.ContentResolver
import android.net.Uri
import dev.hfps.reveal.pipeline.preset.PresetError
import dev.hfps.reveal.pipeline.preset.PresetFormatException
import dev.hfps.reveal.pipeline.preset.PresetLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException

/** What an import or export did, for a short message (docs/adr/0019 "Import and export"). */
sealed interface PresetEvent {
    data class Created(val name: String) : PresetEvent

    data class Imported(val result: ImportResult) : PresetEvent

    /** The file was refused: [error] says why (null = it could not be read at all). */
    data class Refused(val error: PresetError?) : PresetEvent

    data object Exported : PresetEvent

    data object ExportFailed : PresetEvent
}

/**
 * Reads and writes preset files through the content resolver (SAF documents, shared files): a `.revealpack` is
 * recognised by its zip signature, anything else is read as a `.revealpreset` (bounded by its size limit).
 */
class PresetFilesIo(private val resolver: ContentResolver, private val presets: PresetRepository) {
    /** Imports the preset or pack at [uri]. */
    suspend fun import(uri: Uri): PresetEvent = withContext(Dispatchers.IO) {
        try {
            val stream = resolver.openInputStream(uri) ?: return@withContext PresetEvent.Refused(null)
            BufferedInputStream(stream).use { input ->
                input.mark(ZIP_MAGIC.size)
                val head = ByteArray(ZIP_MAGIC.size)
                val n = input.read(head)
                input.reset()
                val result = if (n == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)) {
                    presets.importPack(input)
                } else {
                    val bytes = input.readNBytesCompat(PresetLimits.PRESET_BYTES + 1)
                    presets.importPreset(bytes)
                }
                PresetEvent.Imported(result)
            }
        } catch (e: PresetFormatException) {
            PresetEvent.Refused(e.error)
        } catch (e: IOException) {
            PresetEvent.Refused(null)
        } catch (e: SecurityException) {
            PresetEvent.Refused(null)
        }
    }

    suspend fun exportPreset(uuid: String, uri: Uri): PresetEvent = write(uri) { presets.export(uuid) }

    suspend fun exportGroup(groupId: Long, uri: Uri, author: String, licence: String): PresetEvent =
        write(uri) { presets.exportGroup(groupId, author, licence) }

    private suspend fun write(uri: Uri, bytes: suspend () -> ByteArray?): PresetEvent = withContext(Dispatchers.IO) {
        val data = bytes() ?: return@withContext PresetEvent.ExportFailed
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: return@withContext PresetEvent.ExportFailed
            out.use { it.write(data) }
            PresetEvent.Exported
        } catch (e: IOException) {
            PresetEvent.ExportFailed
        } catch (e: SecurityException) {
            PresetEvent.ExportFailed
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        const val BUFFER = 16 * 1024
    }
}
