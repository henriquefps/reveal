package dev.hfps.reveal.ui.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LicenceEntryTest {
    @Test
    fun `licence lines split on the first bar and skip malformed ones`() {
        val parsed = parseLicences(arrayOf("LibRaw 0.22.3|LGPL-2.1 | or CDDL", "no separator", " Inter | OFL "))
        assertThat(parsed).containsExactly(
            LicenceEntry("LibRaw 0.22.3", "LGPL-2.1 | or CDDL"),
            LicenceEntry("Inter", "OFL"),
        ).inOrder()
    }
}
