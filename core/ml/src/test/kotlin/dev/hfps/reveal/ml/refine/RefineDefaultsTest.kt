package dev.hfps.reveal.ml.refine

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MaskKind
import org.junit.Test

class RefineDefaultsTest {
    @Test
    fun `the coefficient grid stays near 512 px whatever the guide's size`() {
        assertThat(RefineDefaults.subsample(400, 300)).isEqualTo(1)
        assertThat(RefineDefaults.subsample(1024, 683)).isEqualTo(2)
        assertThat(RefineDefaults.subsample(6000, 4000)).isEqualTo(12)
        val p = RefineDefaults.SKY.params(6000, 4000)
        assertThat(p.subsample).isEqualTo(12)
        assertThat(p.radius).isEqualTo(RefineDefaults.SKY.radius)
    }

    @Test
    fun `every kind has its default, face parts none`() {
        assertThat(RefineDefaults.of(MaskKind.HAIR)).isEqualTo(RefineDefaults.HAIR)
        assertThat(RefineDefaults.of(MaskKind.SKY)).isEqualTo(RefineDefaults.SKY)
        assertThat(RefineDefaults.of(MaskKind.PEOPLE)).isEqualTo(RefineDefaults.DEFAULT)
        assertThat(RefineDefaults.of(MaskKind.SUBJECT)).isEqualTo(RefineDefaults.DEFAULT)
        for (k in MaskKind.entries.filter { it.isFacePart }) assertThat(RefineDefaults.of(k)).isNull()
        assertThat(RefineDefaults.HAIR.eps).isLessThan(RefineDefaults.DEFAULT.eps)
        assertThat(RefineDefaults.SKY.radius).isGreaterThan(RefineDefaults.DEFAULT.radius)
    }
}
