package dev.hfps.reveal.feature.export

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.library.OriginalReach
import dev.hfps.reveal.feature.export.work.OriginalPolicy
import dev.hfps.reveal.feature.export.work.OriginalStep
import org.junit.Test

class OriginalPolicyTest {
    @Test
    fun `local originals export, server ones download first, elsewhere waits and then gives up`() {
        val now = 10_000_000_000L
        assertThat(OriginalPolicy.step(OriginalReach.DEVICE, null, now)).isEqualTo(OriginalStep.EXPORT)
        assertThat(OriginalPolicy.step(OriginalReach.SERVER, null, now)).isEqualTo(OriginalStep.DOWNLOAD_FIRST)
        assertThat(OriginalPolicy.step(OriginalReach.SERVER, now - 1, now)).isEqualTo(OriginalStep.DOWNLOAD_FIRST)
        assertThat(OriginalPolicy.step(OriginalReach.ELSEWHERE, null, now)).isEqualTo(OriginalStep.WAIT)
        assertThat(OriginalPolicy.step(OriginalReach.ELSEWHERE, now - OriginalPolicy.WAIT_LIMIT_MS + 1, now))
            .isEqualTo(OriginalStep.WAIT)
        assertThat(OriginalPolicy.step(OriginalReach.ELSEWHERE, now - OriginalPolicy.WAIT_LIMIT_MS, now))
            .isEqualTo(OriginalStep.GIVE_UP)
    }
}
