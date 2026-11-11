package dev.hfps.reveal.pipeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PipelineVersionTest {
    @Test
    fun `pipeline version starts at one`() {
        assertThat(PIPELINE_VERSION).isAtLeast(1)
    }
}
