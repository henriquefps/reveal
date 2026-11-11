package dev.hfps.reveal.feature.export

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.serializer
import org.junit.Test

class ExportRouteTest {
    @Test
    fun `route has a stable serial name`() {
        assertThat(serializer<ExportRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.export.ExportRoute")
    }
}
