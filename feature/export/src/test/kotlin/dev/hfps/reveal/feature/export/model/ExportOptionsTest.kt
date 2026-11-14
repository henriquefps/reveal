package dev.hfps.reveal.feature.export.model

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.export.OutputSharpening
import dev.hfps.reveal.pipeline.export.SharpenAmount
import dev.hfps.reveal.pipeline.export.SharpenMedium
import org.junit.Test

class ExportOptionsTest {
    @Test
    fun `options round trip through json`() {
        val options = ExportOptions(
            format = ExportFormat.HEIF,
            quality = 80,
            colour = ExportColour.DISPLAY_P3,
            size = ExportSize.INSTAGRAM_PORTRAIT,
            metadata = MetadataOptions(MetadataLevel.COPYRIGHT, removeLocation = true, keepCaptureTime = false),
            destination = ExportDestination.Folder("content://tree/x", "Exports"),
            naming = NamingOptions(NameTemplate.SEQUENCE, "-ig"),
        )
        assertThat(ExportOptions.decode(options.encode())).isEqualTo(options)
    }

    @Test
    fun `broken json falls back to defaults and every rendered format keeps its colour`() {
        assertThat(ExportOptions.decode("{nope")).isEqualTo(ExportOptions())
        for (format in ExportFormat.entries.filter { it.rendered }) {
            assertThat(ExportOptions(format = format, colour = ExportColour.DISPLAY_P3).effectiveColour)
                .isEqualTo(ExportColour.DISPLAY_P3)
        }
    }

    @Test
    fun `the new options round trip, and an older app's json still opens`() {
        val options = ExportOptions(
            format = ExportFormat.PNG,
            bitDepth = 16,
            sharpening = OutputSharpening(SharpenMedium.MATTE, SharpenAmount.HIGH),
            originalSidecar = false,
        )
        assertThat(ExportOptions.decode(options.encode())).isEqualTo(options)
        // A preset saved before W2/E (no bitDepth, sharpening or sidecar): defaults, sharpening off.
        val old = ExportOptions.decode("""{"format":"jpeg","quality":85}""")
        assertThat(old.sharpening).isNull()
        assertThat(old.effectiveDepth).isEqualTo(8)
        assertThat(old.originalSidecar).isTrue()
    }

    @Test
    fun `each format writes the depths it offers`() {
        assertThat(ExportOptions(format = ExportFormat.TIFF).effectiveDepth).isEqualTo(16)
        assertThat(ExportOptions(format = ExportFormat.PNG, bitDepth = 16).effectiveDepth).isEqualTo(16)
        assertThat(ExportOptions(format = ExportFormat.PNG, bitDepth = 10).effectiveDepth).isEqualTo(8)
        assertThat(ExportOptions(format = ExportFormat.AVIF, bitDepth = 10).effectiveDepth).isEqualTo(10)
        assertThat(ExportOptions(format = ExportFormat.JPEG, bitDepth = 16).effectiveDepth).isEqualTo(8)
    }

    @Test
    fun `the original copy keeps its name and gets an image type and a sidecar name`() {
        assertThat(OriginalCopy.fileName("DSC01234.ARW")).isEqualTo("DSC01234.ARW")
        assertThat(OriginalCopy.fileName("a/b:c.dng")).isEqualTo("a_b_c.dng")
        assertThat(OriginalCopy.mimeType("DSC01234.ARW")).isEqualTo("image/x-sony-arw")
        assertThat(OriginalCopy.mimeType("x.jpg")).isEqualTo("image/jpeg")
        assertThat(OriginalCopy.mimeType("x.xyz")).isEqualTo("image/x-xyz")
        assertThat(OriginalCopy.sidecarName("DSC01234 (1).ARW")).isEqualTo("DSC01234 (1).xmp")
        assertThat(OriginalCopy.sidecarFits(ExportDestination.Pictures)).isFalse()
        assertThat(OriginalCopy.sidecarFits(ExportDestination.Share)).isTrue()
    }

    @Test
    fun `file names follow the template`() {
        val naming = NamingOptions()
        assertThat(
            ExportNaming.fileName("DSC01234.ARW", null, 1, naming, ExportFormat.JPEG),
        ).isEqualTo("DSC01234-edit.jpg")
        assertThat(
            ExportNaming.fileName(
                "DSC01234.ARW",
                "2026:08:07 14:30:12",
                1,
                naming.copy(template = NameTemplate.DATE),
                ExportFormat.HEIF,
            ),
        ).isEqualTo("20260807-143012-edit.heic")
        assertThat(
            ExportNaming.fileName("DSC01234.ARW", null, 7, NamingOptions(NameTemplate.SEQUENCE, ""), ExportFormat.JPEG),
        ).isEqualTo("DSC01234-0007.jpg")
        assertThat(ExportNaming.fileName("a.jpg", null, 1, NamingOptions(suffix = "/x:y"), ExportFormat.JPEG))
            .isEqualTo("a_x_y.jpg")
        // A date template without a capture time keeps the original name.
        assertThat(ExportNaming.fileName("IMG.JPG", null, 1, NamingOptions(NameTemplate.DATE), ExportFormat.JPEG))
            .isEqualTo("IMG-edit.jpg")
    }
}
