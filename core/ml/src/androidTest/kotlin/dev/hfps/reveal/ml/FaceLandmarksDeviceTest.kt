package dev.hfps.reveal.ml

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.ml.face.FaceLandmarker
import dev.hfps.reveal.ml.face.FaceLandmarks
import dev.hfps.reveal.ml.face.FaceParts
import dev.hfps.reveal.ml.service.PhotoCues
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Eyes, irises, lips, teeth and brows on the public-domain portraits (docs/adr/0021 "M4/C"): BlazeFace (ADR 0010)
 * finds the faces, MediaPipe's landmark model (LiteRT, `Fixtures.preferred` backend) places the contours, the parts
 * are drawn as polygons. Several faces (the studio trio, the outdoor group); logs `REVEAL_ML landmarks ...`.
 */
@RunWith(AndroidJUnit4::class)
class FaceLandmarksDeviceTest {
    private fun landmarks(image: MlImage): Pair<List<FaceLandmarks>, Int> {
        val cues = PhotoCues.of(image)
        val crops = cues.faces.map { it.crop(image.width, image.height) }
        return FaceLandmarker(Fixtures.engine(MaskModels.FACE_LANDMARKER)).use { lm ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            val faces = lm.detect(image, crops)
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / NANOS
            Log.i(
                TAG,
                "landmarks ${image.width}x${image.height} faces=${faces.size}/${crops.size} time=$ms ms " +
                    "(2 passes per face)",
            )
            faces to crops.size
        }
    }

    /** The mask's coverage in a [r]-pixel box around ([x], [y]). */
    private fun Mask.around(x: Float, y: Float, r: Int = 2): Float {
        var s = 0f
        var n = 0
        for (j in y.toInt() - r..y.toInt() + r) {
            for (i in x.toInt() - r..x.toInt() + r) {
                if (i in 0 until width && j in 0 until height) {
                    s += this[i, j]
                    n++
                }
            }
        }
        return s / n
    }

    @Test
    fun portrait() {
        val image = Fixtures.image("studio-portrait")
        val (faces, detected) = landmarks(image)
        assertThat(detected).isEqualTo(1)
        assertThat(faces).hasSize(1)
        val f = faces.single()
        val eyes = FaceParts.mask(MaskKind.EYES, faces, image.width, image.height)
        val irises = FaceParts.mask(MaskKind.IRISES, faces, image.width, image.height)
        val lips = FaceParts.mask(MaskKind.LIPS, faces, image.width, image.height)
        val teeth = FaceParts.mask(MaskKind.TEETH, faces, image.width, image.height)
        val brows = FaceParts.mask(MaskKind.EYEBROWS, faces, image.width, image.height)
        // The irises' centres are inside the eyes; the eyes sit in the face's upper half, the mouth below them.
        for (iris in listOf(FaceParts.RIGHT_IRIS[0], FaceParts.LEFT_IRIS[0])) {
            assertThat(eyes.around(f.x[iris], f.y[iris])).isGreaterThan(0.9f)
            assertThat(irises.around(f.x[iris], f.y[iris], 1)).isGreaterThan(0.9f)
        }
        // The fixture's face (614 x 768): eyes near y 262, mouth near y 322, brows above the eyes.
        assertThat(f.y[FaceParts.RIGHT_IRIS[0]]).isWithin(15f).of(262f)
        assertThat(f.y[FaceParts.LIPS_OUTER[0]]).isWithin(20f).of(322f)
        assertThat(f.y[FaceParts.RIGHT_BROW[0]]).isLessThan(f.y[FaceParts.RIGHT_IRIS[0]])
        // She smiles with her teeth showing: the mouth is open, lips and teeth both present, never overlapping.
        assertThat(teeth.mean()).isGreaterThan(0f)
        assertThat(lips.mean()).isGreaterThan(0f)
        assertThat(brows.mean()).isGreaterThan(0f)
        for (i in lips.values.indices) assertThat(lips.values[i] + teeth.values[i]).isAtMost(1.0001f)
        assertThat(irises.mean()).isLessThan(eyes.mean())
        shot("studio-portrait", image, faces)
    }

    /**
     * With `-e shots true`: the photo with every face part tinted (eyes cyan, irises violet, lips red, teeth yellow,
     * brows green) as `files/shots/<name>-face-parts.jpg` of the test app (public-domain fixtures only).
     */
    private fun shot(name: String, image: MlImage, faces: List<FaceLandmarks>) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "true") return
        val tints = listOf(
            MaskKind.EYES to floatArrayOf(0.2f, 0.9f, 1f),
            MaskKind.IRISES to floatArrayOf(0.6f, 0.3f, 1f),
            MaskKind.LIPS to floatArrayOf(1f, 0.15f, 0.2f),
            MaskKind.TEETH to floatArrayOf(1f, 0.9f, 0.1f),
            MaskKind.EYEBROWS to floatArrayOf(0.2f, 1f, 0.3f),
        )
        val rgb = image.rgb.copyOf()
        for ((kind, c) in tints) {
            val m = FaceParts.mask(kind, faces, image.width, image.height)
            for (i in m.values.indices) {
                val a = m.values[i] * TINT
                for (ch in 0 until 3) rgb[i * 3 + ch] = rgb[i * 3 + ch] * (1 - a) + c[ch] * a
            }
        }
        val px = IntArray(image.width * image.height) { i ->
            val r = (rgb[i * 3] * 255).toInt().coerceIn(0, 255)
            val g = (rgb[i * 3 + 1] * 255).toInt().coerceIn(0, 255)
            val b = (rgb[i * 3 + 2] * 255).toInt().coerceIn(0, 255)
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, image.width, image.height, Bitmap.Config.ARGB_8888)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name-face-parts.jpg").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
    }

    @Test
    fun severalFaces() {
        for ((name, image, least) in listOf(
            Triple("studio-trio", Fixtures.face("studio-trio"), 3),
            Triple("outdoor-group", Fixtures.image("outdoor-group"), 6),
        )) {
            val (faces, detected) = landmarks(image)
            assertWithMessage("$name faces").that(faces.size).isAtLeast(least)
            assertWithMessage("$name kept").that(faces.size).isAtMost(detected)
            val eyes = FaceParts.mask(MaskKind.EYES, faces, image.width, image.height)
            shot(name, image, faces)
            // Every face gets its eyes: covered at the iris when the face is large enough for the eye to span
            // several pixels (eye corners 16 px apart or more), at least touched on the group's small faces.
            for (f in faces) {
                val right = FaceParts.RIGHT_IRIS[0]
                val at = eyes.around(f.x[right], f.y[right], 1)
                Log.i(TAG, "landmarks $name eyeSpan=${f.eyeSpan.toInt()} px eyeCoverage=$at")
                if (f.eyeSpan >= LARGE_FACE) {
                    assertWithMessage("$name eye").that(at).isGreaterThan(0.5f)
                } else {
                    assertWithMessage("$name small eye").that(at).isGreaterThan(0f)
                }
            }
        }
    }

    private companion object {
        const val TAG = "REVEAL_ML"
        const val NANOS = 1_000_000
        const val LARGE_FACE = 16f
        const val TINT = 0.55f
        const val JPEG_QUALITY = 88
    }
}
