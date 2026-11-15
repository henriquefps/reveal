package dev.hfps.reveal.sync

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.storage.LibraryThumbsPreferences
import dev.hfps.reveal.data.thumb.ServerThumb
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.UploadCreateRequest
import dev.hfps.reveal.sync.engine.ServerHeaders
import dev.hfps.reveal.sync.thumbs.LibraryThumbs
import dev.hfps.reveal.sync.thumbs.ThumbCodec
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library thumbnails and the items' headers against the real hoard-server (0.9.0, `scripts/sync/local-server.sh`,
 * loopback; ADR 0018 "W3/C"): an original backed up by this device becomes an item; the warm request returns its
 * thumb's `v` once made, the immutable GET serves WebP bytes, the store keeps them; the item lookup carries the
 * header. `REVEAL_THUMBS_LINK=<pairing link>`; skipped without it; run with `--no-configuration-cache`.
 */
@RunWith(AndroidJUnit4::class)
class RealServerThumbsTest {
    private val link = System.getenv("REVEAL_THUMBS_LINK")
    private var device: TestDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() = assumeTrue("needs REVEAL_THUMBS_LINK", !link.isNullOrBlank())

    @After
    fun tearDown() {
        device?.close()
        scope.cancel()
    }

    private fun jpeg(seed: Int): ByteArray {
        val image = BufferedImage(1500, 1000, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(seed * 37 % 256, 90, 160)
        g.fillRect(0, 0, 1500, 1000)
        g.color = Color(240, seed * 11 % 256, 30)
        g.fillOval(200, 150, 600 + seed % 300, 500)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun backUp(api: SyncApi, bytes: ByteArray) {
        val created = api.createUpload(
            UploadCreateRequest(sha256(bytes), bytes.size.toLong(), "original", FileFingerprint.of(bytes)),
        )
        val id = created.uploadId ?: return
        var offset = created.offset
        while (offset < bytes.size) {
            val length = minOf(created.chunkSize.coerceAtLeast(1).toInt(), bytes.size - offset.toInt())
            offset = api.patchUpload(id, offset, length) { it.write(bytes, offset.toInt(), length) }.offset
        }
    }

    /** Keeps the server's WebP bytes as they are (the JVM has no WebP encoder) and checks their signature. */
    private val codec = object : ThumbCodec {
        override fun shrink(bytes: ByteArray, edge: Int): ByteArray? = bytes.takeIf { webp(it) }

        override fun decode(bytes: ByteArray): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun decode(file: File): Bitmap? = if (file.isFile) decode(file.readBytes()) else null

        private fun webp(b: ByteArray) = b.size > 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP"
    }

    @Test
    fun aBackedUpPhotoGetsItsServerThumbnailAndHeader() {
        val parsed = checkNotNull(PairingLink.parse(link!!))
        val paired = SyncApi(parsed.url, null).pair(PairRequest(parsed.token, "Thumbs (test)", "android", "test"))
        val phone = TestDevice("Phone", parsed.url, paired.apiKey, paired.serverId, now = System.currentTimeMillis())
            .also { device = it }
        val seed = (System.nanoTime() % 1000).toInt()
        val bytes = jpeg(seed)
        val print = FileFingerprint.of(bytes)
        val photo = phone.addHandoffPhoto("W3C$seed", bytes)
        backUp(phone.api, bytes)
        runBlocking { phone.db.syncDao().setOnServer(print, true) }

        val thumbs = LibraryThumbs(
            db = phone.db,
            api = { phone.api },
            dir = Files.createTempDirectory("thumbs").toFile(),
            preferences = LibraryThumbsPreferences(null),
            scope = scope,
            codec = codec,
            clock = { System.currentTimeMillis() },
        )
        // The server makes the thumb within seconds (a warm queues it first).
        var answer: ServerThumb = ServerThumb.Later
        val deadline = System.currentTimeMillis() + 60_000
        while (answer !is ServerThumb.Image && System.currentTimeMillis() < deadline) {
            answer = runBlocking { thumbs.thumbnail(print) }
            if (answer !is ServerThumb.Image) Thread.sleep(1_000)
        }
        assertThat(answer).isInstanceOf(ServerThumb.Image::class.java)
        val stats = runBlocking { thumbs.stats() }
        assertThat(stats.stored).isEqualTo(1)
        assertThat(stats.bytes).isGreaterThan(1_000)

        // The item describes the photo: its size, without reading the file.
        val item = phone.api.itemsLookup(listOf(print)).items.single()
        val header = ServerHeaders.headerOf(item)
        assertThat(header.width).isEqualTo(1500)
        assertThat(header.height).isEqualTo(1000)
        assertThat(photo).isGreaterThan(0)
    }
}
