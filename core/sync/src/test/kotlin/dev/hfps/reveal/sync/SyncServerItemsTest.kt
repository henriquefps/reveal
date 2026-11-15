package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.handoff.ServerItemResult
import dev.hfps.reveal.data.handoff.ServerPairing
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.handoff.SyncServerItems
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `GET /v1/items/{id}` and the lookup as the hand-off reads them (ADR 0011 "Amendment W3/H"), against the fake. */
@RunWith(AndroidJUnit4::class)
class SyncServerItemsTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private lateinit var key: String
    private var pulls = 0

    @Before
    fun setUp() {
        url = server.start()
        server.hoard()
        key = server.addDevice("Phone")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun items(api: SyncApi? = SyncApi(url, key)) = SyncServerItems(
        pairingOf = { ServerPairing(server.serverId, "Home", "dev") },
        api = { api },
        pull = { pulls++ },
    )

    private fun file(fingerprint: String, role: String, name: String, vararg locations: JsonObject) = buildJsonObject {
        put("fingerprint", fingerprint)
        put("role", role)
        put("name", name)
        put("size", 1234)
        put("locations", buildJsonArray { locations.forEach(::add) })
    }

    private fun nas(folder: String, path: String) = buildJsonObject {
        put("source", "nas")
        put("folderName", folder)
        put("path", path)
    }

    @Test
    fun `an item comes back with its files, folder and where the original is`() = runBlocking {
        server.itemRecords["i1"] = buildJsonObject {
            put("id", "i1")
            put("deleted", false)
            put("mainFingerprint", "v1:aa")
            put("sortTime", 99)
            put("width", 6000)
            put("height", 4000)
            put("orientation", 6)
            put(
                "files",
                buildJsonArray {
                    add(file("v1:aa", "raw", "DSC1.ARW", nas("Trips", "2026/Day1/DSC1.ARW")))
                    add(file("v1:bb", "rendered", "DSC1.JPG", nas("Trips", "2026/Day1/DSC1.JPG")))
                },
            )
        }
        val found = items().item("i1") as ServerItemResult.Found
        val info = found.item
        assertThat(info.files.map { it.name }).containsExactly("DSC1.ARW", "DSC1.JPG").inOrder()
        assertThat(info.folder).isEqualTo("Trips/2026/Day1")
        assertThat(info.originalSource).isEqualTo("nas")
        assertThat(info.orientation).isEqualTo(6)
        assertThat(info.sortTime).isEqualTo(99)
    }

    @Test
    fun `an uploaded-only item is a backup original with no folder`() = runBlocking {
        server.itemRecords["i2"] = buildJsonObject {
            put("id", "i2")
            put("mainFingerprint", "v1:cc")
            put(
                "files",
                buildJsonArray {
                    add(
                        file(
                            "v1:cc",
                            "rendered",
                            "IMG.JPG",
                            buildJsonObject {
                                put("source", "backup")
                                put("sha256", "x")
                            },
                        ),
                    )
                },
            )
        }
        val info = (items().item("i2") as ServerItemResult.Found).item
        assertThat(info.originalSource).isEqualTo("backup")
        assertThat(info.folder).isEmpty()
    }

    @Test
    fun `tombstones, unknown ids and an unreachable server are told apart`() = runBlocking {
        server.itemRecords["old"] = buildJsonObject {
            put("id", "old")
            put("deleted", true)
            put("replacedBy", "new")
        }
        assertThat(items().item("old")).isEqualTo(ServerItemResult.Gone("new"))
        assertThat(items().item("nope")).isEqualTo(ServerItemResult.Missing)
        assertThat(items(api = null).item("old")).isEqualTo(ServerItemResult.Unreachable)
        server.shutdown()
        assertThat(items().item("old")).isEqualTo(ServerItemResult.Unreachable)
        assertThat(items().lookup(listOf("v1:aa"))).isNull()
    }

    @Test
    fun `the lookup maps fingerprints to item ids and the culling pull is passed on`() = runBlocking {
        server.culling.items["item-9"] = listOf("v1:aa", "v1:bb")
        val found = items().lookup(listOf("v1:bb", "v1:zz"))!!
        assertThat(found).containsExactly("v1:bb", "item-9")
        items().pullCulling()
        assertThat(pulls).isEqualTo(1)
    }
}
