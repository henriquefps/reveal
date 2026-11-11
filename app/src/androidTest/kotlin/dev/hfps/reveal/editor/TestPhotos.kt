package dev.hfps.reveal.editor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.synthetic.PerspectiveScenes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.UUID

/**
 * Test photos served through a real content provider: they are inserted into MediaStore's Downloads collection (the
 * app owns them, so it reads them without a permission) and removed afterwards. Public samples (raw.pixls.us, CC0)
 * are pushed into the app's `files/samples` beforehand (see CLAUDE.md); the owner's private copies, opt-in, into
 * `files/private` and are deleted after the run.
 */
object TestPhotos {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The app's sample directory (pushed with `run-as`), or null when it has no such file. */
    fun sample(name: String): File? = File(context.filesDir, "samples/$name").takeIf { it.isFile }

    fun privateFiles(): List<File> =
        File(context.filesDir, "private").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /**
     * Inserts [data] as a download named [name] and returns its content URI. An image gets a unique trailer after its
     * data (decoders ignore bytes after a JPEG's EOI or outside a TIFF's offsets): Reveal recognises a photo by its
     * content fingerprint (ADR 0006 "Identity"), and edits persist per photo, so without it a run would reopen the
     * previous run's photo, edits and history (the device keeps app data between runs).
     */
    fun insert(name: String, mimeType: String, data: InputStream, unique: Boolean = true): Uri {
        val content = if (unique && mimeType.startsWith("image/")) SequenceInputStream(data, uniqueTrailer()) else data
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/RevealTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "insert $name" }
        checkNotNull(resolver.openOutputStream(uri)).use { out -> content.use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A per-call unique tail (see [insert]). */
    private fun uniqueTrailer() =
        "\nreveal-test ${System.nanoTime()} ${UUID.randomUUID()}\n".toByteArray().inputStream()

    /** [name] with a unique part before its extension, for tests that must not meet an earlier run's photo. */
    fun uniqueName(name: String): String =
        "${name.substringBeforeLast('.')}-${System.nanoTime()}.${name.substringAfterLast('.')}"

    fun insert(name: String, mimeType: String, file: File): Uri = insert(name, mimeType, file.inputStream())

    fun insert(name: String, mimeType: String, bytes: ByteArray, unique: Boolean = true): Uri =
        insert(name, mimeType, bytes.inputStream(), unique)

    /** [bytes] made a picture no earlier run has inserted (see [insert]); insert it with `unique = false` to reuse. */
    fun uniqueContent(bytes: ByteArray): ByteArray = bytes + uniqueTrailer().readBytes()

    fun delete(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Opens [uri] in Reveal like another app would: `ACTION_VIEW` with a read grant. */
    fun launchView(uri: Uri, mimeType: String): ActivityScenario<MainActivity> {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    /**
     * A synthetic facade ([PerspectiveScenes.facade]) photographed with converging verticals: Upright with
     * [correction] makes it upright again. A JPEG at quality 95.
     */
    @Suppress("MagicNumber")
    fun facadeJpeg(width: Int = 1200, height: Int = 800, correction: Transform = Transform()): ByteArray {
        val gray = PerspectiveScenes.facade(width, height, correction)
        val pixels = IntArray(width * height) { i ->
            // The scene is linear-ish luminance 0..1: encode with a plain gamma for a JPEG.
            val v = (Math.pow(gray[i].toDouble(), 1 / 2.2) * 255).toInt().coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /**
     * A synthetic 8-bit sRGB test card (smooth ramps, every grey level, saturated patches) as a JPEG at quality 100,
     * plus the bitmap it was made from.
     */
    @Suppress("MagicNumber", "NestedBlockDepth")
    fun syntheticJpeg(width: Int = 1200, height: Int = 800): ByteArray {
        val pixels = IntArray(width * height)
        val patches = intArrayOf(
            Color.rgb(200, 30, 30),
            Color.rgb(30, 160, 60),
            Color.rgb(40, 60, 200),
            Color.rgb(230, 200, 40),
            Color.rgb(20, 180, 190),
            Color.rgb(190, 40, 170),
            Color.rgb(250, 250, 250),
            Color.rgb(5, 5, 5),
        )
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = when {
                    y < height / 4 -> (x * 255 / (width - 1)).let { Color.rgb(it, it, it) }
                    y < height / 2 -> Color.rgb(x * 255 / (width - 1), y * 255 / height, 255 - x * 255 / (width - 1))
                    else -> patches[(x * patches.size / width)]
                }
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
    }
}
