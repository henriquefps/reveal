package dev.hfps.reveal.data.index

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.format.ImageKind
import dev.hfps.image.format.ListedFile
import dev.hfps.image.format.ShotPairing
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.sync.BackupCoverage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots, stickers and GIFs (Hoard 0.12.0 mirrors PNG, WebP and GIF from the phone): Reveal lists them as photos
 * with their own badge, Hoard's `image` type covers them, and they decode through the platform path the viewer, the
 * editor's proxy and the export use (a GIF as its first frame).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StillFormatsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }

    private fun png(): File = temp.newFile("Screenshot_1.png").apply {
        outputStream().use { bitmap(48, 32).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun webp(): File = temp.newFile("STK-1.webp").apply {
        outputStream().use { bitmap(40, 30).compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
    }

    private fun gif(): File = temp.newFile("loop.gif").apply {
        val image = BufferedImage(36, 24, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 24) for (x in 0 until 36) image.setRGB(x, y, 0x3366CC)
        check(ImageIO.write(image, "gif", this))
    }

    @Test
    fun pngWebpAndGifAreListedWithTheirOwnBadge() {
        val formats = ShotPairing.pair(
            listOf(ListedFile("Screenshot_1.png"), ListedFile("STK-1.webp"), ListedFile("loop.gif")),
        ).associate { it.stem to LibraryIndexer.format(it) }
        assertThat(formats).containsExactly(
            "Screenshot_1",
            PhotoFormat.PNG,
            "STK-1",
            PhotoFormat.WEBP,
            "loop",
            PhotoFormat.GIF,
        )
        for (name in listOf("Screenshot_1.png", "STK-1.webp", "loop.gif")) {
            assertThat(BackupCoverage.typesOf(name)).containsExactly(BackupCoverage.TYPE_IMAGE)
        }
    }

    @Test
    fun pngWebpAndGifDecodeThroughThePlatformPath() {
        for ((file, kind, size) in listOf(
            Triple(png(), ImageKind.PNG, 48 to 32),
            Triple(webp(), ImageKind.WEBP, 40 to 30),
            Triple(gif(), ImageKind.GIF, 36 to 24),
        )) {
            ImageInput.fromFile(file).use { input ->
                assertThat(input.type.kind).isEqualTo(kind)
                val proxy = ProxyBuilder.decodeProxy(input, 1024)
                assertThat(proxy.image.width to proxy.image.height).isEqualTo(size)
            }
        }
    }
}
