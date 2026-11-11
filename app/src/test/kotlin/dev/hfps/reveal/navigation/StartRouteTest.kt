package dev.hfps.reveal.navigation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.serializer
import org.junit.Test

class StartRouteTest {
    @Test
    fun `start route has a stable serial name`() {
        assertThat(serializer<StartRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.navigation.StartRoute")
    }
}
