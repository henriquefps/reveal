package dev.hfps.reveal.data.handoff

import android.Manifest
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.storage.isAccessError
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Test

/** The optional gallery permission (ADR 0011 "Gallery access") and what counts as "access lost". */
class GalleryAccessTest {
    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun `the permissions asked follow the Android version`() {
        assertThat(GalleryAccess.permissions(34)).asList().containsExactly(images, GalleryAccess.USER_SELECTED)
        assertThat(GalleryAccess.permissions(33)).asList().containsExactly(images)
        assertThat(GalleryAccess.permissions(29)).asList().containsExactly(storage)
    }

    @Test
    fun `full, selected photos only, or none`() {
        assertThat(GalleryAccess.stateOf(35) { it == images }).isEqualTo(GalleryAccessState.FULL)
        assertThat(GalleryAccess.stateOf(35) { it == GalleryAccess.USER_SELECTED })
            .isEqualTo(GalleryAccessState.PARTIAL)
        assertThat(GalleryAccess.stateOf(33) { it == GalleryAccess.USER_SELECTED }).isEqualTo(GalleryAccessState.NONE)
        assertThat(GalleryAccess.stateOf(31) { it == storage }).isEqualTo(GalleryAccessState.FULL)
        assertThat(GalleryAccess.stateOf(35) { false }).isEqualTo(GalleryAccessState.NONE)
    }

    @Test
    fun `offered once, only for an unreadable gallery photo, only without access`() {
        val media = listOf("content://media/external/images/media/42")
        val document = listOf("content://com.android.providers.downloads.documents/document/7")
        assertThat(GalleryAccess.shouldOffer(media, GalleryAccessState.NONE, asked = false)).isTrue()
        assertThat(GalleryAccess.shouldOffer(media, GalleryAccessState.NONE, asked = true)).isFalse()
        assertThat(GalleryAccess.shouldOffer(media, GalleryAccessState.PARTIAL, asked = false)).isFalse()
        assertThat(GalleryAccess.shouldOffer(document, GalleryAccessState.NONE, asked = false)).isFalse()
        assertThat(GalleryAccess.shouldOffer(emptyList(), GalleryAccessState.NONE, asked = false)).isFalse()
    }

    @Test
    fun `refused or gone is an access error, other failures are not`() {
        assertThat(isAccessError(SecurityException("Permission Denial"))).isTrue()
        assertThat(isAccessError(FileNotFoundException("gone"))).isTrue()
        assertThat(isAccessError(IOException("wrapped", SecurityException("denied")))).isTrue()
        assertThat(isAccessError(IOException("Not a decodable image"))).isFalse()
        assertThat(isAccessError(IllegalArgumentException("Cannot round NaN value."))).isFalse()
    }
}
