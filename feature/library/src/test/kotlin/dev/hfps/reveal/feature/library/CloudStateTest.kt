package dev.hfps.reveal.feature.library

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import org.junit.Test

/** The grid's cloud badge per row (ADR 0018 "Cloud states"). */
class CloudStateTest {
    private fun row(
        remote: Boolean = false,
        original: Boolean = false,
        preview: Boolean = false,
        unreadable: Boolean = false,
        uploading: Boolean = false,
    ) = PhotoRow(
        id = 1, sourceId = 1, name = "p.arw", format = PhotoFormat.RAW, sortTime = 0, captureTime = null, rating = 4,
        label = null, flag = PickFlag.PICK, hasEdit = false, versionId = 1, editTime = null, cloudOriginal = original,
        cloudPreview = preview, remote = remote, unreadable = unreadable, uploading = uploading,
    )

    @Test
    fun `a photo only another device has shows whether the server holds a preview of it`() {
        // Edited on the other device: its smart preview is on the server.
        assertThat(cloudState(row(remote = true, preview = true))).isEqualTo(CloudState.PREVIEW)
        // Only rated or picked there (FIX/P: no preview for those): its metadata, no pixels.
        assertThat(cloudState(row(remote = true))).isEqualTo(CloudState.ELSEWHERE)
        assertThat(cloudDescription(CloudState.ELSEWHERE)).isEqualTo(R.string.library_cloud_elsewhere)
        // The server has the original: it opens from there either way.
        assertThat(cloudState(row(remote = true, original = true))).isEqualTo(CloudState.CLOUD)
        assertThat(cloudState(row(remote = true, original = true, preview = true))).isEqualTo(CloudState.CLOUD)
    }

    @Test
    fun `photos on this device keep their states`() {
        assertThat(cloudState(row())).isEqualTo(CloudState.LOCAL)
        assertThat(cloudState(row(original = true))).isEqualTo(CloudState.BACKED_UP)
        assertThat(cloudState(row(preview = true))).isEqualTo(CloudState.LOCAL)
        assertThat(cloudState(row(unreadable = true))).isEqualTo(CloudState.LOCAL_PREVIEW)
    }

    @Test
    fun `an upload still waiting shows the pending badge until the server has the original`() {
        assertThat(cloudState(row(uploading = true))).isEqualTo(CloudState.UPLOADING)
        assertThat(cloudDescription(CloudState.UPLOADING)).isEqualTo(R.string.library_cloud_uploading)
        // The original landed and only its preview waits: backed up.
        assertThat(cloudState(row(uploading = true, original = true))).isEqualTo(CloudState.BACKED_UP)
    }
}
