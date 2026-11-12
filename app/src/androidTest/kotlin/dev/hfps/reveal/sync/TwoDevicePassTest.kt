package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.account.PairingLink
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two-device pass of ADR 0018 on real devices (e.g. emulator + phone), one step per run, all through the app's
 * own singletons (Keystore key, Room, the sync engine):
 *
 * - `-e step edit -e syncLink <link> -e seed <n> -e exposure <ev>`: pair (if needed), add a synthetic JPEG made from
 *   `seed` in a synthetic folder, set its exposure, sync;
 * - `-e step check -e syncLink <link> -e seed <n> -e exposure <ev>`: pair (if needed), sync, and find the same photo
 *   (by content) with that exposure, in a folder or under Server;
 * - `-e step unpair`: forget the server and the folder.
 *
 * Skipped without `step`.
 */
@RunWith(AndroidJUnit4::class)
class TwoDevicePassTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    @Test
    fun step() {
        val step = args.getString("step")
        assumeTrue("needs -e step edit|check|unpair", step != null)
        val sync = access.sync()
        if (step == "unpair") {
            runBlocking { sync.unpair() }
            SyntheticFolder("two-device").delete()
            return
        }
        val seed = checkNotNull(args.getString("seed")).toInt()
        val exposure = checkNotNull(args.getString("exposure")).toFloat()
        if (sync.account.value == null) {
            runBlocking { sync.pair(checkNotNull(PairingLink.parse(checkNotNull(args.getString("syncLink"))))) }
        }
        val folder = SyntheticFolder("two-device")
        val bytes = folder.gradientJpeg(seed, 1200, 800)
        val fingerprint = FileFingerprint.of(bytes)
        if (step == "edit") {
            File(folder.dir, "PASS_$seed.JPG").writeBytes(bytes)
            folder.addAndIndex()
            runBlocking { access.library().refresh() }
            // The folder's fingerprints are filled in the background after the walk: waiting for it finds the photo.
            val photo = waitForPhoto(fingerprint, LibraryScope.All)
            val edits = access.edits()
            runBlocking {
                val before = edits.recipe(photo)
                val after = before.copy(light = before.light.copy(exposureEv = exposure))
                edits.commit(photo, after, RecipeDiff.between(before, after))
                sync.syncMetadata()
            }
        } else {
            val version = waitForPhoto(fingerprint, null)
            assertThat(runBlocking { access.edits().recipe(version) }.light.exposureEv).isEqualTo(exposure)
        }
    }

    /** The first version of the photo whose file has [fingerprint] (syncing while waiting unless [scope] is local). */
    private fun waitForPhoto(fingerprint: String, scope: LibraryScope?): Long {
        val photos = access.database().photoDao()
        repeat(ATTEMPTS) {
            if (scope == null) runBlocking { access.sync().syncMetadata() }
            val file = runBlocking { photos.filesByFingerprint(listOf(fingerprint)) }.firstOrNull()?.file
            val version = file?.let { runBlocking { photos.firstVersion(it.photoId) } }
            if (version != null) return version
            Thread.sleep(WAIT_MS)
        }
        error("photo not found")
    }

    private companion object {
        const val ATTEMPTS = 30
        const val WAIT_MS = 1_000L
    }
}
