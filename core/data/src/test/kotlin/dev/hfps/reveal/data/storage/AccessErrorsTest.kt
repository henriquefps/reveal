package dev.hfps.reveal.data.storage

import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Test

/** Every shape of "Reveal cannot read this document any more" counts, and nothing else (ADR 0011). */
class AccessErrorsTest {
    @Test
    fun `an ended grant counts in every shape providers use`() {
        val denial = SecurityException(
            "Permission Denial: opening provider dev.example.sender.StreamProvider from ProcessRecord{1 2:app/u0a1} " +
                "(pid=2, uid=3) that is not exported from UID 4",
        )
        assertThat(isAccessError(denial)).isTrue()
        assertThat(isAccessError(SecurityException("app has no access to content://media/external/images/media/1")))
            .isTrue()
        assertThat(isAccessError(FileNotFoundException("gone"))).isTrue()
        assertThat(isAccessError(IOException("open failed", denial))).isTrue()
        assertThat(isAccessError(RuntimeException("wrapped", IllegalStateException("x", denial)))).isTrue()
        // Some wrappers keep only the message.
        assertThat(isAccessError(IOException("java.lang.SecurityException: Permission Denial: reading"))).isTrue()
        assertThat(isAccessError(IllegalStateException("caller has no access to content://x/1"))).isTrue()
    }

    @Test
    fun `a provider's IllegalArgumentException counts, a decoder's does not`() {
        val provider = IllegalArgumentException("Unknown URI content://dev.example.sender/stream/1").apply {
            stackTrace = arrayOf(
                StackTraceElement("android.database.DatabaseUtils", "readExceptionFromParcel", "DatabaseUtils.java", 1),
                StackTraceElement("android.content.ContentProviderProxy", "openTypedAssetFile", "x.java", 2),
                StackTraceElement("android.content.ContentResolver", "openFileDescriptor", "ContentResolver.java", 3),
                StackTraceElement("dev.hfps.image.ImageInput", "fromUri", "ImageInput.kt", 4),
            )
        }
        assertThat(isAccessError(provider)).isTrue()
        assertThat(isAccessError(IOException("wrapped", provider))).isTrue()
        val unsupported = UnsupportedOperationException("Unsupported Uri").apply {
            stackTrace = arrayOf(StackTraceElement("android.content.ContentResolver", "openFileDescriptor", "x", 1))
        }
        assertThat(isAccessError(unsupported)).isTrue()

        val decoder = IllegalArgumentException("Cannot round NaN value.").apply {
            stackTrace = arrayOf(StackTraceElement("kotlin.math.MathKt", "roundToInt", "x", 1))
        }
        assertThat(isAccessError(decoder)).isFalse()
        assertThat(isAccessError(IOException("Not a decodable image"))).isFalse()
        assertThat(isAccessError(OutOfMemoryError())).isFalse()
    }
}
