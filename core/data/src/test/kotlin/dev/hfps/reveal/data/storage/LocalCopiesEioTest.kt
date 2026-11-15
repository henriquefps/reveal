package dev.hfps.reveal.data.storage

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import java.io.File
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** A Hoard descriptor that fails with EIO is closed and the URI opened again (Hoard 2.0.2), never "unreadable". */
@RunWith(AndroidJUnit4::class)
class LocalCopiesEioTest {
    private val dir = kotlin.io.path.createTempDirectory("copies").toFile()

    private fun source(content: String): ImageInput {
        val f = File(dir, "src-${System.nanoTime()}").apply { writeText(content) }
        return ImageInput("a.jpg", ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
    }

    private fun copier(failures: Int): Pair<(ImageInput, File) -> Unit, () -> Int> {
        var calls = 0
        return Pair(
            { s: ImageInput, to: File ->
                calls++
                if (calls <= failures) throw IOException("read failed: EIO (I/O error)")
                java.io.FileInputStream(s.descriptor.fileDescriptor).use { to.writeBytes(it.readBytes()) }
            },
            { calls },
        )
    }

    @Test
    fun `an EIO mid-copy closes the descriptor, reopens the document and copies again`() {
        var reopened = 0
        val (copier, calls) = copier(failures = 1)
        val copy = LocalCopies.copy(source("pixels"), dir, reopen = {
            reopened++
            source("pixels")
        }, copier = copier)
        assertThat(reopened).isEqualTo(1)
        assertThat(calls()).isEqualTo(2)
        val text = java.io.FileInputStream(copy.descriptor.fileDescriptor).use { String(it.readBytes()) }
        assertThat(text).isEqualTo("pixels")
        copy.close()
    }

    @Test
    fun `EIO is bounded, and any other failure is not retried`() {
        var reopened = 0
        val (always, _) = copier(failures = 100)
        assertThrows(IOException::class.java) {
            LocalCopies.copy(source("x"), dir, reopen = {
                reopened++
                source("x")
            }, copier = always)
        }
        assertThat(reopened).isEqualTo(3)
        reopened = 0
        assertThrows(SecurityException::class.java) {
            LocalCopies.copy(source("x"), dir, reopen = {
                reopened++
                source("x")
            }, copier = { _, _ ->
                throw SecurityException("denied")
            })
        }
        assertThat(reopened).isEqualTo(0)
    }
}
