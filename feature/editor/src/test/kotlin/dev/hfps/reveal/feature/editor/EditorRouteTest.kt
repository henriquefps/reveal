package dev.hfps.reveal.feature.editor

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test

class EditorRouteTest {
    @Test
    fun `route has a stable serial name`() {
        assertThat(serializer<EditorRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.editor.EditorRoute")
    }

    @Test
    fun `route round-trips a content uri`() {
        val route = EditorRoute("content://com.android.providers.media.documents/document/image%3A42")
        assertThat(Json.decodeFromString<EditorRoute>(Json.encodeToString(route))).isEqualTo(route)
    }
}
