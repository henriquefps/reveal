package dev.hfps.reveal.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.serializer
import org.junit.Test

class SettingsRouteTest {
    @Test
    fun `route has a stable serial name`() {
        assertThat(serializer<SettingsRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.settings.SettingsRoute")
    }
}
