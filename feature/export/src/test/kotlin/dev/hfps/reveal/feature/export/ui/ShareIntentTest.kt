package dev.hfps.reveal.feature.export.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** "Share" on the export result hands the saved files over as they are (no re-export). */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    private val a = Uri.parse("content://media/external/images/media/11")
    private val b = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AOut/document/x.jpg")

    @Test
    fun `one saved file is ACTION_SEND with a read grant`() {
        val intent = shareIntent(listOf(a), "image/jpeg")
        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("image/jpeg")
        assertThat(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(a)
        assertThat(intent.clipData?.itemCount).isEqualTo(1)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun `several saved files are ACTION_SEND_MULTIPLE in order, every one in the ClipData`() {
        val intent = shareIntent(listOf(a, b), "image/heif")
        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            .containsExactly(a, b).inOrder()
        val clip = checkNotNull(intent.clipData)
        assertThat((0 until clip.itemCount).map { clip.getItemAt(it).uri }).containsExactly(a, b).inOrder()
    }
}
