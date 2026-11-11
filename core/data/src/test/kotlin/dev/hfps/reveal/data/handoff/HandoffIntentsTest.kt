package dev.hfps.reveal.data.handoff

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.model.PickFlag
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

    @Test
    fun `culling values travel per item, keyed by uri, for one or several items`() {
        val single = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(a)
            putExtra(
                HandoffIntents.EXTRA_CULLING,
                HandoffIntents.cullingExtra(mapOf(a to HandoffCulling(rating = 3, flag = PickFlag.PICK))),
            )
        }
        assertThat(HandoffIntents.parse(single)!!.culling).containsExactly(a, HandoffCulling(3, PickFlag.PICK))

        val several = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(a, b, c)
            putExtra(HandoffIntents.EXTRA_CULLING_VERSION, HandoffIntents.CULLING_VERSION)
            putExtra(
                HandoffIntents.EXTRA_CULLING,
                HandoffIntents.cullingExtra(
                    mapOf(
                        c to HandoffCulling(flag = PickFlag.REJECT),
                        a to HandoffCulling(rating = 5, label = "Red"),
                    ),
                ),
            )
        }
        val request = HandoffIntents.parse(several)!!
        assertThat(request.uris).containsExactly(a, b, c).inOrder()
        assertThat(request.culling).containsExactly(
            a,
            HandoffCulling(rating = 5, label = "Red"),
            c,
            HandoffCulling(flag = PickFlag.REJECT),
        )
    }

    @Test
    fun `invalid culling values are dropped field by field, unknown uris and keys ignored`() {
        val values = Bundle().apply {
            putBundle(
                a.toString(),
                Bundle().apply {
                    putInt(HandoffIntents.KEY_RATING, 7)
                    putString(HandoffIntents.KEY_FLAG, " Reject ")
                    putString(HandoffIntents.KEY_LABEL, "  ")
                    putString("future_key", "x")
                },
            )
            putBundle(b.toString(), Bundle().apply { putString(HandoffIntents.KEY_RATING, "4") })
            putBundle(c.toString(), Bundle().apply { putInt(HandoffIntents.KEY_RATING, 2) })
            putString("content://elsewhere/x.ARW", "not a bundle")
        }
        val intent = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(a, b)
            putExtra(HandoffIntents.EXTRA_CULLING_VERSION, 2)
            putExtra(HandoffIntents.EXTRA_CULLING, values)
        }
        // A later version is read with the keys this one knows; c was not sent, b's rating has the wrong type.
        assertThat(HandoffIntents.parse(intent)!!.culling)
            .containsExactly(a, HandoffCulling(rating = null, flag = PickFlag.REJECT, label = ""))
    }

    @Test
    fun `no culling extra, or a version below 1, means no values`() {
        val plain = Intent(HandoffIntents.ACTION_DEVELOP).apply { clipData = clip(a) }
        assertThat(HandoffIntents.parse(plain)!!.culling).isEmpty()
        val old = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            clipData = clip(a)
            putExtra(HandoffIntents.EXTRA_CULLING_VERSION, 0)
            putExtra(HandoffIntents.EXTRA_CULLING, HandoffIntents.cullingExtra(mapOf(a to HandoffCulling(rating = 1))))
        }
        assertThat(HandoffIntents.parse(old)!!.culling).isEmpty()
    }
}
