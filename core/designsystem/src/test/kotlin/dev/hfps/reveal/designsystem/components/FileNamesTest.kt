package dev.hfps.reveal.designsystem.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileNamesTest {
    private fun fitting(max: Int): (String) -> Boolean = { it.length <= max }

    @Test
    fun aNameThatFitsIsKept() {
        assertThat(FileNames.ellipsize("DSC01234.ARW", fitting(12))).isEqualTo("DSC01234.ARW")
    }

    @Test
    fun theMiddleOfTheStemGoesFirstAndTheExtensionStays() {
        assertThat(FileNames.ellipsize("DSC01234.ARW", fitting(11))).isEqualTo("DSC…234.ARW")
        assertThat(FileNames.ellipsize("DSC01234.ARW", fitting(9))).isEqualTo("DS…34.ARW")
        assertThat(FileNames.ellipsize("a-very-long-file-name-0001.DNG", fitting(14))).isEqualTo("a-ver…0001.DNG")
    }

    @Test
    fun extremesFallBackGracefully() {
        assertThat(FileNames.ellipsize("DSC01234.ARW", fitting(5))).isEqualTo("….ARW")
        assertThat(FileNames.ellipsize("DSC01234.ARW", fitting(4))).isEqualTo("…")
        assertThat(FileNames.ellipsize("no-extension-at-all", fitting(9))).isEqualTo("no-e…-all")
    }
}
