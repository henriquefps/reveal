package dev.hfps.reveal.ml

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelIdTest {
    @Test
    fun `accepts a lowercase dashed id`() {
        assertThat(ModelId("subject-segmenter-v1").toString()).isEqualTo("subject-segmenter-v1")
    }

    @Test
    fun `rejects spaces and upper case`() {
        assertThrows(IllegalArgumentException::class.java) { ModelId("Subject Segmenter") }
    }
}
