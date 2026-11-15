package dev.hfps.reveal.data.handoff

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.model.PickFlag
import org.junit.Test
import org.junit.runner.RunWith

/** The server-reference extras and `CULLING_VERSION` 2 of ADR 0011 "Amendment W3/H": every key, limit and malformed case. */
@RunWith(AndroidJUnit4::class)
class ServerRefIntentsTest {
    private val server = "5b1c0a52-0000-4000-8000-000000000001"
    private fun id(n: Int) = "00000000-0000-8000-8000-%012d".format(n)
    private fun fp(n: Int) = "v1:" + "%064x".format(n)

    private fun bundle(itemId: String? = id(1), fingerprint: String? = null, vararg more: Pair<String, Any?>) =
        Bundle().apply {
            itemId?.let { putString("itemId", it) }
            fingerprint?.let { putString("fingerprint", it) }
            for ((k, v) in more) {
                when (v) {
                    is String -> putString(k, v)
                    is Array<*> -> @Suppress("UNCHECKED_CAST") putStringArray(k, v as Array<String>)
                    is Int -> putInt(k, v)
                }
            }
        }

    private fun intent(items: List<Bundle>, serverId: String? = server, version: Int? = 1) =
        Intent(HandoffIntents.ACTION_DEVELOP).apply {
            version?.let { putExtra(HandoffIntents.EXTRA_SERVER_REF_VERSION, it) }
            serverId?.let { putExtra(HandoffIntents.EXTRA_SERVER_ID, it) }
            putParcelableArrayListExtra(HandoffIntents.EXTRA_SERVER_ITEMS, ArrayList(items))
        }

    @Test
    fun `server items alone are a hand-off with no uris, in order, with every field`() {
        val request = HandoffIntents.parse(
            intent(
                listOf(
                    bundle(id(1), fp(1), "fingerprints" to arrayOf(fp(1), fp(2)), "name" to "DSC1.ARW"),
                    bundle(id(2)),
                ),
            ).apply { putExtra(HandoffIntents.EXTRA_TITLE, "Trip") },
        )!!
        assertThat(request.uris).isEmpty()
        assertThat(request.title).isEqualTo("Trip")
        val refs = request.server!!
        assertThat(refs.serverId).isEqualTo(server)
        assertThat(refs.items.map { it.itemId }).containsExactly(id(1), id(2)).inOrder()
        assertThat(refs.items[0].fingerprint).isEqualTo(fp(1))
        assertThat(refs.items[0].fingerprints).containsExactly(fp(1), fp(2)).inOrder()
        assertThat(refs.items[0].allFingerprints).containsExactly(fp(1), fp(2)).inOrder()
        assertThat(refs.items[0].name).isEqualTo("DSC1.ARW")
        assertThat(refs.items[1].fingerprint).isNull()
        assertThat(refs.items[1].fingerprints).isEmpty()
    }

    @Test
    fun `no version, version 0, no server id or no valid item means no server references`() {
        val one = listOf(bundle())
        assertThat(HandoffIntents.parse(intent(one, version = null))).isNull()
        assertThat(HandoffIntents.parse(intent(one, version = 0))).isNull()
        assertThat(HandoffIntents.parse(intent(one, serverId = null))).isNull()
        assertThat(HandoffIntents.parse(intent(one, serverId = "  "))).isNull()
        assertThat(HandoffIntents.parse(intent(emptyList()))).isNull()
        assertThat(HandoffIntents.parse(intent(listOf(bundle(itemId = null))))).isNull()
    }

    @Test
    fun `a newer layout version is read with the keys known, and uri items are unaffected by bad references`() {
        val uri = Uri.parse("content://hoard.files/a/DSC1.ARW")
        val i = intent(listOf(bundle()), version = 7).apply { data = uri }
        val request = HandoffIntents.parse(i)!!
        assertThat(request.server!!.items).hasSize(1)
        val bad = intent(emptyList()).apply { data = uri }
        val onlyUris = HandoffIntents.parse(bad)!!
        assertThat(onlyUris.uris).containsExactly(uri)
        assertThat(onlyUris.server).isNull()
    }

    @Test
    fun `malformed items are skipped one by one and fields of the wrong type or length are dropped`() {
        val long = "x".repeat(300)
        val items = listOf(
            bundle(itemId = null, fingerprint = fp(9)),
            bundle(id(1), "  ", "name" to long, "fingerprints" to arrayOf(fp(3), "", fp(3), fp(4))),
            Bundle().apply { putInt("itemId", 5) },
            bundle(id(1)),
            bundle(id(2), fp(2), "name" to 5),
            bundle("y".repeat(200)),
        )
        val refs = HandoffIntents.parse(intent(items))!!.server!!
        assertThat(refs.items.map { it.itemId }).containsExactly(id(1), id(2)).inOrder()
        assertThat(refs.items[0].fingerprint).isNull()
        assertThat(refs.items[0].name).isNull()
        assertThat(refs.items[0].fingerprints).containsExactly(fp(3), fp(4)).inOrder()
        assertThat(refs.items[1].name).isNull()
    }

    @Test
    fun `at most 500 items count and repeats of an item id are dropped`() {
        val many = (1..600).map { bundle(id(it)) } + bundle(id(1))
        val refs = HandoffIntents.parse(intent(many))!!.server!!
        assertThat(refs.items).hasSize(HandoffIntents.MAX_SERVER_ITEMS)
        assertThat(refs.items.last().itemId).isEqualTo(id(500))
    }

    @Test
    fun `the initial item must be among the items`() {
        val items = listOf(bundle(id(1)), bundle(id(2)))
        val ok = HandoffIntents.parse(intent(items).apply { putExtra(HandoffIntents.EXTRA_INITIAL_ITEM, id(2)) })!!
        assertThat(ok.server!!.initialItem).isEqualTo(id(2))
        val unknown = HandoffIntents.parse(intent(items).apply { putExtra(HandoffIntents.EXTRA_INITIAL_ITEM, id(9)) })!!
        assertThat(unknown.server!!.initialItem).isNull()
    }

    @Test
    fun `uris and server items mix in one request, and the initial item wins over the initial uri`() {
        val a = Uri.parse("content://hoard.files/a/DSC1.ARW")
        val i = intent(listOf(bundle(id(1)))).apply {
            data = a
            putExtra(HandoffIntents.EXTRA_INITIAL_URI, a)
            putExtra(HandoffIntents.EXTRA_INITIAL_ITEM, id(1))
            putExtra(HandoffIntents.EXTRA_TITLE, "Mixed")
        }
        val request = HandoffIntents.parse(i)!!
        assertThat(request.uris).containsExactly(a)
        assertThat(request.initial).isEqualTo(a)
        assertThat(request.server!!.initialItem).isEqualTo(id(1))
        assertThat(request.title).isEqualTo("Mixed")
    }

    @Test
    fun `the sender's builder and the parser agree`() {
        val refs = ServerRefs(
            server,
            listOf(ServerItemRef(id(1), fp(1), listOf(fp(1), fp(2)), "A.ARW"), ServerItemRef(id(2))),
            id(2),
        )
        val request = HandoffIntents.parse(HandoffIntents.serverExtras(Intent(HandoffIntents.ACTION_DEVELOP), refs))!!
        assertThat(request.server).isEqualTo(refs)
    }

    @Test
    fun `culling version 2 carries per-field authors next to the times, dropped with their field`() {
        val a = Uri.parse("content://hoard.files/a/DSC1.ARW")
        val values = Bundle().apply {
            putBundle(
                a.toString(),
                Bundle().apply {
                    putInt("rating", 4)
                    putLong("ratingModifiedAt", 1_000)
                    putString("ratingDevice", "dev-b")
                    putString("flag", "pick")
                    putString("flagDevice", "dev-c")
                    putString("labelDevice", "dev-d")
                    putLong("labelModifiedAt", 5)
                    putInt("flagModifiedAt", 7)
                },
            )
        }
        val i = Intent(HandoffIntents.ACTION_DEVELOP).apply {
            data = a
            putExtra(HandoffIntents.EXTRA_CULLING_VERSION, 2)
            putExtra(HandoffIntents.EXTRA_CULLING, values)
        }
        val culling = HandoffIntents.parse(i)!!.culling.getValue(a)
        assertThat(culling.rating).isEqualTo(4)
        assertThat(culling.ratingAt).isEqualTo(1_000)
        assertThat(culling.ratingDevice).isEqualTo("dev-b")
        assertThat(culling.flag).isEqualTo(PickFlag.PICK)
        assertThat(culling.flagAt).isNull() // wrong type
        assertThat(culling.flagDevice).isEqualTo("dev-c")
        assertThat(culling.label).isNull() // no label sent: its time and author are ignored
        assertThat(culling.labelAt).isNull()
        assertThat(culling.labelDevice).isNull()
        assertThat(HandoffIntents.CULLING_VERSION).isEqualTo(2)
        val again = HandoffIntents.cullingExtra(mapOf(a to culling))
        assertThat(again.getBundle(a.toString())!!.getString("ratingDevice")).isEqualTo("dev-b")
    }
}
