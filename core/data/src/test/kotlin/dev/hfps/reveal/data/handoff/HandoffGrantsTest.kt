package dev.hfps.reveal.data.handoff

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Which hand-off documents make Reveal start the grant service (ADR 0011 "Amendment W4", "Grant service"). */
@RunWith(AndroidJUnit4::class)
class HandoffGrantsTest {
    private val images = (1..3).map { Uri.parse("content://media/external_primary/images/media/$it") }
    private val download = Uri.parse("content://media/external_primary/downloads/7")
    private val document = Uri.parse("content://com.example.docs/document/raw%3A1")

    @Test
    fun `a gallery share under full photo access needs no grant service`() {
        assertThat(HandoffGrants.needing(images, persistable = false, GalleryAccessState.FULL)).isEmpty()
    }

    @Test
    fun `images need the grant for their previews below full access`() {
        assertThat(HandoffGrants.needing(images, persistable = false, GalleryAccessState.PARTIAL)).isEqualTo(images)
        assertThat(HandoffGrants.needing(images, persistable = false, GalleryAccessState.NONE)).isEqualTo(images)
    }

    @Test
    fun `downloads are copied under the grant whatever the access, and only they are counted`() {
        val needed = HandoffGrants.needing(images + download, persistable = false, GalleryAccessState.FULL)
        assertThat(needed).containsExactly(download)
    }

    @Test
    fun `another provider's document needs it unless its grant is persistable`() {
        assertThat(HandoffGrants.needing(listOf(document), persistable = false, GalleryAccessState.FULL))
            .containsExactly(document)
        assertThat(HandoffGrants.needing(listOf(document), persistable = true, GalleryAccessState.NONE)).isEmpty()
        assertThat(HandoffGrants.needing(listOf(Uri.parse("file:///sdcard/a.jpg")), false, GalleryAccessState.NONE))
            .isEmpty()
    }
}
