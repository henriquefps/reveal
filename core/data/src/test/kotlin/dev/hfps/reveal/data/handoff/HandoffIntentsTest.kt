package dev.hfps.reveal.data.handoff

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** One test per intent shape of ADR 0011. */
@RunWith(AndroidJUnit4::class)
class HandoffIntentsTest {
    private val a = Uri.parse("content://hoard.files/pick/DSC00001.ARW")
    private val b = Uri.parse("content://hoard.files/pick/DSC00001.JPG")
    private val c = Uri.parse("content://hoard.files/pick/DSC00002.ARW")

    private fun clip(vararg uris: Uri): ClipData {
        val clip = ClipData.newRawUri("photos", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    @Test
    fun `develop reads every ClipData item in order`() {
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            type = "image/*"
            clipData = clip(a, b, c)
            putExtra(HandoffIntents.EXTRA_TITLE, "  Beach culling ")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val request = HandoffIntents.parse(intent)!!
        assertThat(request.uris).containsExactly(a, b, c).inOrder()
        assertThat(request.title).isEqualTo("Beach culling")
        assertThat(request.initial).isNull()
        assertThat(request.persistable).isFalse()
    }

    @Test
    fun `develop honours the initial item and the persistable flag`() {
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(a, b, c)
            putExtra(HandoffIntents.EXTRA_INITIAL_URI, c)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        val request = HandoffIntents.parse(intent)!!
        assertThat(request.initial).isEqualTo(c)
        assertThat(request.persistable).isTrue()
    }

    @Test
    fun `an initial item that was not sent is ignored`() {
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(a)
            putExtra(HandoffIntents.EXTRA_INITIAL_URI, c)
        }
        assertThat(HandoffIntents.parse(intent)!!.initial).isNull()
    }

    @Test
    fun `develop falls back to EXTRA_STREAM list and to the data`() {
        val list = Intent(HandoffIntents.ACTION_DEVELOP).putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(a, b),
        )
        assertThat(HandoffIntents.parse(list)!!.uris).containsExactly(a, b).inOrder()
        val data = Intent(HandoffIntents.ACTION_DEVELOP).setData(c)
        assertThat(HandoffIntents.parse(data)!!.uris).containsExactly(c)
    }

    @Test
    fun `view and edit read the data`() {
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT)) {
            val intent = Intent(action).setDataAndType(a, "image/x-sony-arw")
            assertThat(HandoffIntents.parse(intent)!!.uris).containsExactly(a)
        }
    }

    @Test
    fun `send reads EXTRA_STREAM, else the first ClipData item`() {
        val stream = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)
        assertThat(HandoffIntents.parse(stream)!!.uris).containsExactly(a)
        val clipOnly = Intent(Intent.ACTION_SEND).apply { clipData = clip(b) }
        assertThat(HandoffIntents.parse(clipOnly)!!.uris).containsExactly(b)
    }

    @Test
    fun `send multiple reads the stream list and does not duplicate the ClipData copy`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b, c))
            clipData = clip(a, b, c)
        }
        assertThat(HandoffIntents.parse(intent)!!.uris).containsExactly(a, b, c).inOrder()
    }

    @Test
    fun `foreign actions, schemes and empty intents are not hand-offs`() {
        assertThat(HandoffIntents.parse(null)).isNull()
        assertThat(HandoffIntents.parse(Intent(Intent.ACTION_MAIN))).isNull()
        assertThat(HandoffIntents.parse(Intent(HandoffIntents.ACTION_DEVELOP))).isNull()
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/a.jpg"))
        assertThat(HandoffIntents.parse(web)).isNull()
        val mixed = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(Uri.parse("https://example.com/a.jpg"), a)
        }
        assertThat(HandoffIntents.parse(mixed)!!.uris).containsExactly(a)
    }

    @Test
    fun `a blank title is dropped and a long one capped`() {
        val blank = Intent(HandoffIntents.ACTION_DEVELOP).setData(a).putExtra(HandoffIntents.EXTRA_TITLE, "   ")
        assertThat(HandoffIntents.parse(blank)!!.title).isNull()
        val long = Intent(
            HandoffIntents.ACTION_DEVELOP,
        ).setData(a).putExtra(HandoffIntents.EXTRA_TITLE, "x".repeat(500))
        assertThat(HandoffIntents.parse(long)!!.title).hasLength(120)
    }
}
