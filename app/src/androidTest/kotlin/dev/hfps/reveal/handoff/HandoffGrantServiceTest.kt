package dev.hfps.reveal.handoff

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.editor.TestPhotos
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grant service (ADR 0011 "Amendment W4", "Grant service"): Downloads originals that another app (here the shell,
 * which owns the files) hands over with a temporary read grant are copied after Reveal's activity has finished,
 * because the activity passes the grant on to [HandoffGrantService]; once the copies are done the service stops and
 * the grant is gone. Each file is its own silent batch member (`BATCH_INDEX` 1..3), so each activity leaves at once
 * and the three service starts settle in any order.
 */
@RunWith(AndroidJUnit4::class)
class HandoffGrantServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val stem = "GS${System.nanoTime() % 1_000_000_000}"
    private val rows = mutableListOf<Uri>()

    @After
    fun tearDown() {
        rows.forEach { shell("content delete --uri $it") }
        shell("rm -f /sdcard/Download/$stem-1.CR3 /sdcard/Download/$stem-2.CR3 /sdcard/Download/$stem-3.CR3")
    }

    private fun shell(command: String): String {
        val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$command\nexit\n".toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { String(it.readBytes()) }
    }

    /** A CR3-looking file of [megabytes] owned by the shell in `Download/`: Reveal reads it only through a grant. */
    private fun shellDownload(name: String, megabytes: Int): Uri {
        val path = "/sdcard/Download/$name"
        shell(
            "printf '\\000\\000\\000\\030ftypcrx ' > $path && " +
                "dd if=/dev/urandom bs=1048576 count=$megabytes >> $path 2>/dev/null && " +
                "content call --uri content://media/external_primary/file --method scan_file --arg $path",
        )
        val row = shell(
            "content query --uri content://media/external_primary/downloads --projection _id " +
                "--where \"_display_name='$name'\"",
        )
        val id = checkNotNull(Regex("_id=(\\d+)").find(row)) { "no row for $name: $row" }.groupValues[1]
        return Uri.parse("content://media/external_primary/downloads/$id").also(rows::add)
    }

    private fun readable(uri: Uri): Boolean =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() }.isSuccess

    private fun hasService(): Boolean =
        shell("dumpsys activity services ${context.packageName}").contains("HandoffGrantService")

    @Test
    fun copiesFinishAfterTheActivityLeftAndTheGrantEndsWithTheService() {
        assumeTrue("shell scripts through UiAutomation need API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val uris = (1..3).map { shellDownload("$stem-$it.CR3", megabytes = FILE_MB) }
        // The shell's file: without a grant Reveal cannot read it.
        assertThat(uris.none(::readable)).isTrue()

        val destroyed = AtomicInteger()
        val copiesAtDestroy = mutableListOf<Int>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) {
                    destroyed.incrementAndGet()
                    synchronized(copiesAtDestroy) { copiesAtDestroy += localCopies(uris) }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
        try {
            uris.forEachIndexed { index, uri ->
                shell(
                    "am start -a ${HandoffIntents.ACTION_DEVELOP} " +
                        "-n ${context.packageName}/${MainActivity::class.java.name} -d $uri " +
                        "-t application/octet-stream --grant-read-uri-permission " +
                        "--es ${HandoffIntents.EXTRA_TITLE} $stem " +
                        "--ei ${HandoffIntents.EXTRA_BATCH_INDEX} ${index + 1} " +
                        "--ei ${HandoffIntents.EXTRA_BATCH_COUNT} 4",
                )
            }
            waitUntil("the three silent activities left") { destroyed.get() == uris.size }
            waitUntil("every original copied") { localCopies(uris) == uris.size }
            waitUntil("the grant service stopped") { !hasService() }
        } finally {
            (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
        }
        Log.i(TAG, "copies done when each activity was destroyed: $copiesAtDestroy of ${uris.size}")
        // At least one copy was made after its activity had gone: only the service's grant covered it.
        assertThat(copiesAtDestroy.first()).isLessThan(uris.size)
        // The service let go of its starts: the grants ended with it.
        waitUntil("the grants ended") { uris.none(::readable) }
        runBlocking {
            val photos = access.database().photoDao().handoffFiles()
                .filter { f -> uris.any { it.toString() == f.relativePath } }
            assertThat(photos).hasSize(uris.size)
            access.library().forget(photos.map { it.photoId }.distinct())
        }
    }

    /** How many of [uris] are filed under a local copy now. */
    private fun localCopies(uris: List<Uri>): Int = runBlocking {
        access.database().photoDao().handoffFiles().count { f ->
            uris.any { it.toString() == f.relativePath } && access.localOriginals().isLocal(f.documentId)
        }
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (!check()) {
            check(System.currentTimeMillis() < until) { "timed out: $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TAG = "REVEAL_HANDOFF_TEST"
        const val FILE_MB = 96
        const val TIMEOUT_MS = 120_000L
        const val POLL_MS = 100L
    }
}
