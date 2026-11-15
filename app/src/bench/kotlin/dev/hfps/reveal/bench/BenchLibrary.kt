package dev.hfps.reveal.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.hfps.reveal.data.library.AddSourceResult
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The library of `LibraryMeasurementsTest` (docs/adr/0006 "Measurements") for the minified bench app: one folder of
 * 10,000 generated files (3,000 RAW+JPEG pairs with 640 x 427 embedded previews, 4,000 JPEGs, a sidecar on every
 * tenth pair; capture times over seven years and three cameras, `SyntheticExif.spread`, W3/D) served by the
 * generated-files SAF provider, added and indexed like a picked folder. Nothing private.
 */
internal object BenchLibrary {
    // A new name for the W3/D files (dates over seven years): a kept library of the older files is made again.
    private const val FOLDER = "bench-10k-dated"
    private const val PAIRS = 3_000
    private const val JPEGS = 4_000
    private const val SIDECAR_EVERY = 10
    private const val PREVIEW_VARIANTS = 24
    private const val PREVIEW_WIDTH = 640
    private const val PREVIEW_HEIGHT = 427
    private const val PREVIEW_QUALITY = 80
    private const val INDEX_TIMEOUT_MS = 900_000L
    private const val HUE_STEP = 47
    private const val HUE_SPAN = 50
    private const val DEGREES = 360
    private const val NS_PER_MS = 1_000_000
    private const val RATINGS = 6

    /** Makes the folder (unless it is there with every file), adds it as the only source and waits for the index. */
    suspend fun prepare(context: Context, library: LibraryRepository, log: (String) -> Unit) {
        val dir = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/$FOLDER")
        withContext(Dispatchers.IO) {
            val expected = 2 * PAIRS + JPEGS + PAIRS / SIDECAR_EVERY
            if ((dir.list()?.size ?: 0) != expected) generate(dir)
        }
        // Only the folder: hand-offs left by the editor open benchmark (Downloads copies, unreadable once deleted)
        // would add preview-only cells to the home grid.
        val existing = library.sources().first()
        existing.filter { it.name != FOLDER }.forEach { library.removeSource(it.id) }
        val tree = SyntheticDocumentsProvider.treeUri(FOLDER)
        val kept = existing.firstOrNull { it.name == FOLDER && it.photoCount >= PAIRS + JPEGS }
        if (kept != null) {
            library.setLastFilters(PhotoFilters.NONE)
            log("library: ready, ${kept.photoCount} photos (kept)")
            return
        }
        existing.filter { it.name == FOLDER }.forEach { library.removeSource(it.id) }
        val start = System.nanoTime()
        val id = (library.addSource(tree) as? AddSourceResult.Added)?.sourceId ?: error("could not add the folder")
        val report = withTimeout(INDEX_TIMEOUT_MS) { library.indexReports.first { id in it } }.getValue(id)
        library.setLastFilters(PhotoFilters.NONE)
        log(
            "library: ready, ${report.photosAdded} photos indexed in ${(System.nanoTime() - start) / NS_PER_MS} ms " +
                "(list ${report.listMs}, save ${report.saveMs}, headers ${report.metadataMs})",
        )
    }

    private fun generate(dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        val previews = (0 until PREVIEW_VARIANTS).map { gradientJpeg(it) }
        for (i in 0 until PAIRS) {
            val stem = String.format(Locale.ROOT, "DSC%05d", i)
            val preview = previews[i % previews.size]
            File(dir, "$stem.ARW").writeBytes(SyntheticShoot.raw(preview, SyntheticExif.spread(i)))
            File(dir, "$stem.JPG").writeBytes(SyntheticShoot.jpeg(preview, SyntheticExif.spread(i)))
            if (i % SIDECAR_EVERY == 0) {
                File(dir, "$stem.xmp").writeText(SyntheticShoot.sidecar(i % RATINGS, creatorTool = "Hoard 1.7.2"))
            }
        }
        for (i in 0 until JPEGS) {
            val name = String.format(Locale.ROOT, "IMG_%05d.JPG", i)
            File(
                dir,
                name,
            ).writeBytes(SyntheticShoot.jpeg(previews[i % previews.size], SyntheticExif.spread(PAIRS + i)))
        }
    }

    @Suppress("MagicNumber")
    private fun gradientJpeg(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888)
        val hue = (seed * HUE_STEP % DEGREES).toFloat()
        val a = Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.9f))
        val b = Color.HSVToColor(floatArrayOf((hue + HUE_SPAN) % DEGREES, 0.7f, 0.35f))
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    PREVIEW_WIDTH.toFloat(),
                    PREVIEW_HEIGHT.toFloat(),
                    a,
                    b,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it)
        }.toByteArray()
    }
}
