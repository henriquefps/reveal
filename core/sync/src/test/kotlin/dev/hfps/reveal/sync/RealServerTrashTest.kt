package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hoard's trash (Hoard ADR 0023, ADR 0018 "R1") against a real hoard-server 0.11.0+ (`trash`): a NAS file Reveal
 * edited is moved into `<folder>/.hoard-trash/<day>/` in the server's NAS directory (as Hoard does over SMB), walked,
 * restored, trashed again and purged. `REVEAL_TRASH_DIR` = the server's work dir, `REVEAL_TRASH_LINK` = a fresh pairing
 * link. Skipped without them; run with `--no-configuration-cache`:
 *
 * ```
 * D=$(mktemp -d) && HOARD_SERVER_IMAGE=<0.11.0 image> scripts/sync/local-server.sh start $D 3476 \
 *   app/src/androidTest/assets/faces/studio-trio.jpg
 * REVEAL_TRASH_DIR=$D REVEAL_TRASH_LINK=$(scripts/sync/local-server.sh pair $D) \
 *   ./gradlew --no-configuration-cache :core:sync:testDebugUnitTest --tests '*RealServerTrashTest*'
 * scripts/sync/local-server.sh stop $D
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealServerTrashTest {
    private val dir = System.getenv("REVEAL_TRASH_DIR")
    private val link = System.getenv("REVEAL_TRASH_LINK")
    private val model = ClientDevice("reveal", "samsung", "SM-S928B")
    private var device: TestDevice? = null

    @Before
    fun setUp() = assumeTrue(
        "needs REVEAL_TRASH_DIR and REVEAL_TRASH_LINK",
        !dir.isNullOrBlank() && !link.isNullOrBlank(),
    )

    @After
    fun tearDown() {
        device?.close()
    }

    private fun pair(): TestDevice {
        val parsed = checkNotNull(PairingLink.parse(checkNotNull(link)))
        val response = SyncApi(parsed.url, null).pair(
            PairRequest(parsed.token, "Tablet", "android", "test", "reveal", model.manufacturer, model.model),
        )
        return TestDevice(
            "Tablet",
            parsed.url,
            response.apiKey,
            response.serverId,
            now = System.currentTimeMillis(),
            device = model,
        )
            .also { device = it }
    }

    private val shoots get() = File(checkNotNull(dir), "mnt/nas/Shoots")

    private fun reindex() {
        val script = File(System.getenv("REVEAL_REPO") ?: "../..", "scripts/sync/local-server.sh")
        val process = ProcessBuilder("bash", script.path, "reindex", dir!!).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "reindex failed: $out" }
    }

    private fun rows(tablet: TestDevice, filters: PhotoFilters = PhotoFilters.NONE): List<PhotoRow> = runBlocking {
        tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(filters = filters)))
    }

    /** Syncs until [done] holds (the server walks in the background). */
    private fun syncUntil(tablet: TestDevice, what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            tablet.now = System.currentTimeMillis()
            tablet.sync()
            if (done()) return
            Thread.sleep(POLL_MS)
        }
        error("never: $what")
    }

    @Test
    fun aTrashedNasPhotoIsHiddenRestoredWithItsEditAndUnavailableAfterThePurge() {
        val tablet = pair()
        val name = "TR${System.nanoTime()}.ARW"
        val bytes = Random(UUID.randomUUID().hashCode()).nextBytes(RAW_BYTES)
        val file = File(shoots.apply { mkdirs() }, name).apply { writeBytes(bytes) }
        reindex()
        val fingerprint = FileFingerprint.of(bytes)
        syncUntil(tablet, "the NAS feed lists the file") { tablet.photoByFingerprint(fingerprint) != null }
        val photo = checkNotNull(tablet.photoByFingerprint(fingerprint)).id
        tablet.edit(tablet.version(photo)) { it.copy(light = it.light.copy(exposureEv = 0.5f)) }
        tablet.sync()
        assertThat(tablet.api.server().has("trash")).isTrue()

        // Into Hoard's trash, as Hoard moves it over SMB (a rename keeps size and mtime: no re-hash).
        val day = File(shoots, ".hoard-trash/2026-11-16").apply { mkdirs() }
        val trashed = File(day, name)
        check(file.renameTo(trashed))
        reindex()
        val inTrash = PhotoFilters(sync = setOf(SyncFilter.IN_TRASH))
        syncUntil(tablet, "the photo is in Hoard's trash") { rows(tablet, inTrash).any { it.id == photo } }
        assertThat(rows(tablet).map { it.id }).doesNotContain(photo)

        // Restored: the same path and fingerprint, live again with its edit.
        check(trashed.renameTo(file))
        reindex()
        syncUntil(tablet, "the photo is back") { rows(tablet).any { it.id == photo && !it.inTrash } }
        assertThat(tablet.recipe(tablet.version(photo)).light.exposureEv).isEqualTo(0.5f)

        // Trashed again, then emptied: unavailable, the edit kept, never a cell that fails to open.
        check(file.renameTo(trashed))
        reindex()
        syncUntil(tablet, "the photo is in Hoard's trash again") { rows(tablet, inTrash).any { it.id == photo } }
        check(trashed.delete())
        reindex()
        syncUntil(tablet, "the photo is unavailable") { rows(tablet).any { it.id == photo && it.unavailable } }
        assertThat(rows(tablet).single { it.id == photo }.cloudOriginal).isFalse()
        assertThat(tablet.recipe(tablet.version(photo)).light.exposureEv).isEqualTo(0.5f)
    }

    private companion object {
        const val RAW_BYTES = 64 * 1024
        const val WAIT_MS = 60_000L
        const val POLL_MS = 1_000L
    }
}
