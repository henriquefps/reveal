package dev.hfps.reveal.pipeline.optics

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.Matrix3
import dev.hfps.image.color.SourceColorSpace
import dev.hfps.image.color.WhiteBalance
import dev.hfps.image.format.ImageInspector
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.image.format.SonyLensCorrectionReader
import dev.hfps.image.source.FileSource
import dev.hfps.reveal.pipeline.input.LensResolver
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class LensfunDatabaseTest {
    private val db = LensfunDatabase.shipped()

    @Test
    fun `the shipped database loads`() {
        assertThat(db.cameraCount).isGreaterThan(1000)
        assertThat(db.lensCount).isGreaterThan(1500)
    }

    @Test
    fun `tokens split numbers and words`() {
        assertThat(LensfunDatabase.tokens("E PZ 16-50mm F3.5-5.6 OSS"))
            .containsExactly("e", "pz", "16", "50", "mm", "f", "3.5", "5.6", "oss")
        assertThat(LensfunDatabase.tokens("FE 24-105mm f/4.0 G")).contains("4")
    }

    @Test
    fun `the zv-e10 kit lens is found and interpolated to the focal length`() {
        val p = checkNotNull(
            db.find(ShotOptics("SONY", "ZV-E10", "E PZ 16-50mm F3.5-5.6 OSS", 29.0, 4.5, 43)),
        )
        assertThat(p.name).contains("16-50mm")
        assertThat(p.distortionModel).isEqualTo(LensfunDistortion.PTLENS)
        assertThat(p.hasChromatic).isTrue()
        assertThat(p.hasVignetting).isTrue()
        // The camera's own (embedded) correction of the public sample at 29 mm pulls the corners in by about 1.9 %;
        // lensfun's calibration of the same lens model agrees in sign and roughly in size.
        assertThat(p.distortion(1.0)).isLessThan(1.0)
        assertThat(p.distortion(1.0)).isGreaterThan(0.97)
        // Same camera, both ends of the zoom: barrel at 16 mm is stronger than at 50 mm.
        val wide = checkNotNull(db.find(ShotOptics("SONY", "ZV-E10", "E PZ 16-50mm F3.5-5.6 OSS", 16.0, 3.5)))
        val tele = checkNotNull(db.find(ShotOptics("SONY", "ZV-E10", "E PZ 16-50mm F3.5-5.6 OSS", 50.0, 5.6)))
        assertThat(wide.distortion(0.9)).isNotEqualTo(tele.distortion(0.9))
        assertThat(wide.vignetting(1.0)).isLessThan(1.0)
    }

    @Test
    fun `unknown lenses and missing names give no profile`() {
        assertThat(db.find(ShotOptics("SONY", "ZV-E10", "Imaginary 7-777mm F1.0", 50.0, 2.0))).isNull()
        assertThat(db.find(ShotOptics("SONY", "ZV-E10", null, 50.0, 2.0))).isNull()
        assertThat(db.find(ShotOptics("SONY", "ZV-E10", "", 50.0, 2.0))).isNull()
    }

    @Test
    fun `an unknown camera takes its crop from the 35 mm focal length`() {
        val p = checkNotNull(db.find(ShotOptics("Acme", "Cam 1", "E PZ 16-50mm F3.5-5.6 OSS", 20.0, 4.0, 30)))
        assertThat(p.cropImage).isWithin(1e-9).of(1.5)
    }

    private val samples = File("../image/build/samples")

    private fun standard(make: String?, model: String?) = object : ColorInfo {
        override val toXyz: Matrix3 get() = error("unused")
        override val colorSpace: SourceColorSpace get() = SourceColorSpace.SRGB
        override val asShotWhiteBalance: WhiteBalance? = null
        override val make: String? = make
        override val model: String? = model
        override val orientation: Int = 1
    }

    /** Golden numbers from the public CC0 samples (`scripts/fetch-samples.sh`); skipped when they are not fetched. */
    @Test
    fun `cc0 samples resolve their lens profiles`() {
        val zv = File(samples, "sony-zv-e10.arw")
        assumeTrue("CC0 samples not fetched", zv.exists())
        val zvData = checkNotNull(FileSource(zv).use { SonyLensCorrectionReader.read(it) })
        val zvMeta = FileSource(zv).use { ImageInspector.inspect(it, zv.name) }.metadata
        assertThat(zvMeta.lens).isEqualTo("E PZ 16-50mm F3.5-5.6 OSS")
        val embedded = checkNotNull(
            SonyLensProfile.of(zvMeta.lens!!, zvData.distortion, zvData.vignetting, zvData.chromaticAberration),
        )
        assertThat(embedded.distortion(1.0)).isWithin(1e-12).of(0.98138427734375)
        assertThat(embedded.vignetting(1.0)).isWithin(1e-9).of(0.6991246873217006)
        // A7C + FE 24-105mm F4 G (16 knots): pincushion at 105 mm, the corners pushed out.
        val a7c = File(samples, "sony-a7c-iso2000.arw")
        val a7cData = checkNotNull(FileSource(a7c).use { SonyLensCorrectionReader.read(it) })
        val a7cMeta = FileSource(a7c).use { ImageInspector.inspect(it, a7c.name) }.metadata
        val a7cProfile = checkNotNull(
            SonyLensProfile.of(a7cMeta.lens!!, a7cData.distortion, a7cData.vignetting, a7cData.chromaticAberration),
        )
        assertThat(a7cProfile.distortion(1.0)).isWithin(1e-12).of(1 + 629 / 16384.0)
        // The resolver prefers the embedded data; a standard image (JPEG) of the same shot goes to lensfun.
        val meta = PhotoMetadata(make = "SONY", model = "ZV-E10", lens = zvMeta.lens, focalLength = 29.0, fNumber = 4.5)
        val fromJpeg = LensResolver.resolve(standard("SONY", "ZV-E10"), meta)
        assertThat(fromJpeg).isInstanceOf(LensfunProfile::class.java)
    }
}
