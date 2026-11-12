package dev.hfps.reveal.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.data.index.IndexReport
import dev.hfps.reveal.data.library.AddSourceResult
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A folder of generated photos served by the debug SAF provider (`files/saf-test/<name>`): RAW+JPEG pairs (a TIFF
 * "ARW" with an embedded preview next to a JPEG of the same stem), single JPEGs and XMP sidecars, every file with EXIF
 * capture times. Nothing private, nothing downloaded; deleted with [delete].
 */
class SyntheticFolder(val name: String) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/$name")
    val treeUri: Uri = SyntheticDocumentsProvider.treeUri(name)
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    val library get() = access.library()
    val edits get() = access.edits()

    init {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    /** A real (decodable) JPEG gradient, its hue from [seed]. */
    fun gradientJpeg(seed: Int, width: Int = 480, height: Int = 320, quality: Int = 85): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val hue = (seed * 47 % 360).toFloat()
        val a = Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.9f))
        val b = Color.HSVToColor(floatArrayOf((hue + 50) % 360, 0.7f, 0.35f))
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader =
                    LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), a, b, Shader.TileMode.CLAMP)
            },
        )
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    fun exif(index: Int, day: Int = 7) = SyntheticExif(
        dateTime = "2026:08:%02d %02d:%02d:%02d".format(day, 10 + index / 3600 % 10, index / 60 % 60, index % 60),
        subSecond = "%03d".format(index * 37 % 1000),
    )

    fun pair(stem: String, index: Int, preview: ByteArray = gradientJpeg(index), sub: String = "") {
        val folder = if (sub.isEmpty()) dir else File(dir, sub).apply { mkdirs() }
        File(folder, "$stem.ARW").writeBytes(SyntheticShoot.raw(preview, exif(index)))
        File(folder, "$stem.JPG").writeBytes(SyntheticShoot.jpeg(preview, exif(index)))
    }

    fun jpeg(name: String, index: Int, base: ByteArray = gradientJpeg(index), sub: String = "") {
        val folder = if (sub.isEmpty()) dir else File(dir, sub).apply { mkdirs() }
        File(folder, name).writeBytes(SyntheticShoot.jpeg(base, exif(index)))
    }

    fun sidecar(stem: String, rating: Int, creatorTool: String = "Hoard 1.7.2") {
        File(dir, "$stem.xmp").writeText(SyntheticShoot.sidecar(rating, creatorTool = creatorTool))
    }

    /**
     * Forgets every source of the library (unless [alongside]: other test folders stay), adds this folder like the
     * picker result would, waits for the index.
     */
    fun addAndIndex(timeoutMs: Long = 120_000, alongside: Boolean = false): Pair<Long, IndexReport> = runBlocking {
        if (!alongside) library.sources().first().forEach { library.removeSource(it.id) }
        val result = library.addSource(treeUri)
        val id = (result as? AddSourceResult.Added)?.sourceId ?: error("add failed: $result")
        val report = withTimeout(timeoutMs) { library.indexReports.first { id in it } }.getValue(id)
        id to report
    }

    fun delete() {
        runBlocking { library.sources().first().forEach { library.removeSource(it.id) } }
        dir.deleteRecursively()
    }
}
