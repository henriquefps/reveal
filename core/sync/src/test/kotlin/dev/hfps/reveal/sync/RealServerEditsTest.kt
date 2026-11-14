package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.EditMeta
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.api.UploadCreateRequest
import dev.hfps.reveal.sync.engine.EditPreviews
import dev.hfps.reveal.sync.engine.EditRenderer
import dev.hfps.reveal.sync.engine.RenderedEdit
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edited previews against the real hoard-server (0.9.0, `scripts/sync/local-server.sh`, loopback): an original backed
 * up by one device becomes an item; [EditPreviews] uploads the version's edit (a real JPEG), the lookup lists it, a
 * rename is a header-only PUT, a reset deletes it. `REVEAL_EDITS_LINK=<pairing link>`; skipped without it; run with
 * `--no-configuration-cache`.
 */
@RunWith(AndroidJUnit4::class)
class RealServerEditsTest {
    private val link = System.getenv("REVEAL_EDITS_LINK")
    private var device: TestDevice? = null

    @Before
    fun setUp() = assumeTrue("needs REVEAL_EDITS_LINK", !link.isNullOrBlank())

    @After
    fun tearDown() {
        device?.close()
    }

    private fun jpeg(seed: Int, brightness: Int): ByteArray {
        val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(brightness, (brightness + seed) % 256, brightness / 2)
        g.fillRect(0, 0, 1200, 800)
        g.color = Color(seed % 256, 40, 200)
        g.fillRect(100, 100, 300 + seed % 400, 200)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Backs [bytes] up as an original (one PUT-sized chunk at a time), so the server makes an item of it. */
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

    @Test
    fun anEditGoesUpIsListedRenamedAndDeleted() {
        val parsed = checkNotNull(PairingLink.parse(link!!))
        val paired = SyncApi(parsed.url, null).pair(PairRequest(parsed.token, "Edits (test)", "android", "test"))
        val phone = TestDevice("Phone", parsed.url, paired.apiKey, paired.serverId, now = System.currentTimeMillis())
            .also { device = it }
        val seed = UUID.randomUUID().hashCode() and 0xff
        val original = jpeg(seed, 60)
        val fingerprint = FileFingerprint.of(original)
        val photo = phone.addFolderJpeg("IMG$seed", original)
        backUp(phone.api, original)
        // The server indexes uploads in the background.
        var itemId: String? = null
        repeat(60) {
            itemId = phone.api.itemsLookup(listOf(fingerprint)).matches.firstOrNull()?.itemId
            if (itemId != null) return@repeat
            Thread.sleep(500)
        }
        checkNotNull(itemId)

        val version = phone.version(photo)
        phone.edit(version) { it.copy(light = it.light.copy(exposureEv = 1.5f)) }
        phone.sync()
        phone.now += EditPreviews.SETTLE_MS + 1_000
        val edited = jpeg(seed, 220)
        val previews =
            EditPreviews(phone.db, EditRenderer { _, _, _, _ -> RenderedEdit(edited, 1200, 800) }) { phone.now }
        val first = runBlocking { previews.run(phone.api, 5) }
        assertThat(first.uploaded).isEqualTo(1)

        val uuid = phone.versions(photo).single().uuid
        val listed = phone.api.itemsLookup(listOf(fingerprint)).items.single().edits.single()
        assertThat(listed.versionId).isEqualTo(uuid)
        assertThat(listed.primary).isTrue()
        assertThat(listed.recipeDigest).isEqualTo(
            EditPreviews.digest(
                runBlocking {
                    phone.db.editDao().recipe(version)
                }!!.json,
            ),
        )
        // Stored cleaned, never re-encoded: Reveal's pixels.
        assertThat(phone.api.editImage(itemId, uuid)).isNotNull()
        // Nothing new: nothing sent.
        assertThat(runBlocking { previews.run(phone.api, 5) }.progress).isEqualTo(0)

        // A metadata-only PUT keeps the image; one without it for a new digest is refused (`image_required`).
        val meta = EditMeta(listed.recipeDigest, listed.editedAt + 1, "Cópia 1", virtualCopy = false, primary = true)
        assertThat(phone.api.putEdit(itemId, uuid, meta, null).status).isEqualTo("updated")
        val missing = assertThrows(SyncHttpException::class.java) {
            phone.api.putEdit(itemId, uuid, meta.copy(recipeDigest = "r0.0:new", editedAt = meta.editedAt + 1), null)
        }
        assertThat(missing.status).isEqualTo(422)

        // Reset to unedited: deleted on the server.
        runBlocking {
            val before = phone.edits.recipe(version)
            phone.edits.commit(
                version,
                dev.hfps.reveal.pipeline.recipe.Recipe(),
                dev.hfps.reveal.pipeline.recipe.RecipeDiff.between(before, dev.hfps.reveal.pipeline.recipe.Recipe()),
            )
        }
        phone.sync()
        assertThat(runBlocking { previews.run(phone.api, 5) }.deleted).isEqualTo(1)
        assertThat(phone.api.itemsLookup(listOf(fingerprint)).items.single().edits).isEmpty()
    }
}
