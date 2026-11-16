package dev.hfps.reveal.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which folders and files Hoard's phone backup covers (ADR 0018 "R1", Hoard ADR 0024 "One backup per phone"). */
class BackupCoverageTest {
    private val external = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val camera = BackupRoot("r1", "primary", "DCIM/Camera", listOf("image", "video", "raw"), active = true)
    private val sd = BackupRoot("r2", "1A2B-3C4D", "", listOf("image"), active = true)
    private val paused = BackupRoot("r3", "primary", "Pictures", listOf("image"), active = false)
    private val roots = listOf(camera, sd, paused)

    @Test
    fun `only ExternalStorageProvider trees have a location`() {
        assertThat(BackupCoverage.location(external, "primary:DCIM")).isEqualTo(StorageLocation("primary", "DCIM"))
        assertThat(
            BackupCoverage.location(
                "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A",
                "1A2B-3C4D:",
            ),
        ).isEqualTo(StorageLocation("1A2B-3C4D", ""))
        // A USB drive's own provider, a cloud provider, the hand-offs, the test provider: never covered.
        assertThat(BackupCoverage.location("content://com.android.providers.media.documents/tree/x", "x:y")).isNull()
        assertThat(BackupCoverage.location("content://com.google.android.apps.docs.storage/tree/a", "a:b")).isNull()
        assertThat(BackupCoverage.location("reveal-handoff:", "")).isNull()
        assertThat(BackupCoverage.location(external, "no-colon")).isNull()
    }

    @Test
    fun `a folder is covered by an active root at or above it, volume compared without case`() {
        assertThat(BackupCoverage.rootFor(roots, StorageLocation("primary", "DCIM/Camera"))).isEqualTo(camera)
        assertThat(BackupCoverage.rootFor(roots, StorageLocation("PRIMARY", "DCIM/Camera/Burst"))).isEqualTo(camera)
        // A prefix only at a `/`; the parent of a root is not covered.
        assertThat(BackupCoverage.rootFor(roots, StorageLocation("primary", "DCIM/Camera2"))).isNull()
        assertThat(BackupCoverage.rootFor(roots, StorageLocation("primary", "DCIM"))).isNull()
        // An SD card root at its top covers the whole card; any case of its id.
        assertThat(BackupCoverage.rootFor(roots, StorageLocation("1a2b-3c4d", "Photos/2026"))).isEqualTo(sd)
        // A paused or stopped root covers nothing (Reveal backs the folder up again).
        assertThat(BackupCoverage.rootFor(roots, StorageLocation("primary", "Pictures"))).isNull()
        assertThat(BackupCoverage.contains(paused, StorageLocation("primary", "Pictures/Screenshots"))).isTrue()
    }

    @Test
    fun `a file is covered only when its kind is in the root's types`() {
        val folder = StorageLocation("primary", "DCIM/Camera")
        assertThat(BackupCoverage.coveringRoot(roots, folder, "PXL_1.jpg")).isEqualTo(camera)
        assertThat(BackupCoverage.coveringRoot(roots, folder, "PXL_1.dng")).isEqualTo(camera)
        assertThat(BackupCoverage.coveringRoot(roots, folder, "IMG_1.CR3")).isEqualTo(camera)
        // The SD root backs up images only: a CR3 (MediaStore does not list it) is not covered; a DNG is an image.
        val card = StorageLocation("1A2B-3C4D", "DCIM")
        assertThat(BackupCoverage.coveringRoot(roots, card, "IMG_1.CR3")).isNull()
        assertThat(BackupCoverage.coveringRoot(roots, card, "IMG_1.DNG")).isEqualTo(sd)
        assertThat(BackupCoverage.coveringRoot(roots, card, "IMG_1.xmp")).isNull()
        assertThat(BackupCoverage.coveringRoot(roots, card, "notes.txt")).isNull()
    }

    @Test
    fun `types and the stored form`() {
        assertThat(BackupCoverage.typesOf("a.HEIC")).containsExactly("image")
        assertThat(BackupCoverage.typesOf("Screenshot_1.png")).containsExactly("image")
        assertThat(BackupCoverage.typesOf("STK-1.webp")).containsExactly("image")
        assertThat(BackupCoverage.typesOf("loop.gif")).containsExactly("image")
        assertThat(BackupCoverage.typesOf("a.arw")).containsExactly("raw", "image")
        assertThat(BackupCoverage.typesOf("a.cr3")).containsExactly("raw")
        assertThat(BackupCoverage.typesOf("a.xmp")).containsExactly("sidecar")
        assertThat(BackupCoverage.typesOf("a.mp4")).containsExactly("video")
        assertThat(BackupCoverage.decode(BackupCoverage.encode(roots))).isEqualTo(roots)
        assertThat(BackupCoverage.decode(null)).isEmpty()
        assertThat(BackupCoverage.decode("not json")).isEmpty()
    }
}
