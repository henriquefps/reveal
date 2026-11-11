package dev.hfps.reveal.feature.editor

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertThrows
import org.junit.Test

class EditorRouteTest {
    @Test
    fun `route has a stable serial name`() {
        assertThat(serializer<EditorRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.editor.EditorRoute")
    }

    @Test
    fun `route round-trips a content uri and a version id`() {
        val byUri = EditorRoute(uri = "content://com.android.providers.media.documents/document/image%3A42")
        assertThat(Json.decodeFromString<EditorRoute>(Json.encodeToString(byUri))).isEqualTo(byUri)
        val byVersion = EditorRoute(versionId = 7)
        assertThat(Json.decodeFromString<EditorRoute>(Json.encodeToString(byVersion))).isEqualTo(byVersion)
    }

    @Test
    fun `exactly one target`() {
        assertThrows(IllegalArgumentException::class.java) { EditorRoute() }
        assertThrows(IllegalArgumentException::class.java) { EditorRoute(uri = "content://x", versionId = 1) }
    }
}
