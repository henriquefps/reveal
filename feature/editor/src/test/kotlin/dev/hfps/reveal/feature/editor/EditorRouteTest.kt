package dev.hfps.reveal.feature.editor

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.serializer
import org.junit.Test

class EditorRouteTest {
    @Test
    fun `route has a stable serial name`() {
        assertThat(serializer<EditorRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.editor.EditorRoute")
    }
}
