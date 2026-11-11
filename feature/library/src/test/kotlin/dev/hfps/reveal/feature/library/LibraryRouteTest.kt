package dev.hfps.reveal.feature.library

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.serializer
import org.junit.Test

class LibraryRouteTest {
    @Test
    fun `route has a stable serial name`() {
        assertThat(serializer<LibraryRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.library.LibraryRoute")
    }
}
