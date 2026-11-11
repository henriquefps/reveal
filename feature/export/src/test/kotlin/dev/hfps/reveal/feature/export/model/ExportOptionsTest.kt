package dev.hfps.reveal.feature.export.model

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.export.ExportSize
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
    fun `broken json falls back to defaults and heif is always srgb`() {
        assertThat(ExportOptions.decode("{nope")).isEqualTo(ExportOptions())
        assertThat(ExportOptions(format = ExportFormat.HEIF, colour = ExportColour.DISPLAY_P3).effectiveColour)
            .isEqualTo(ExportColour.SRGB)
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
