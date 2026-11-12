package dev.hfps.reveal.data.preset

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Finding the link in pasted or shared text (docs/adr/0019 "Import from a link"). */
class PresetLinksTest {
    @Test
    fun `the first https link of the text is taken, with its host`() {
        val parsed = PresetLinks.parse("Golden hour pack: https://Example.com/packs/golden.revealpack?v=3 enjoy!")
        assertThat(parsed).isEqualTo(
            PresetLinks.Parsed.Link("https://Example.com/packs/golden.revealpack?v=3", "example.com"),
        )
        // Trailing punctuation of a sentence is not part of the link.
        assertThat((PresetLinks.parse("(see https://example.com/a.revealpreset).") as PresetLinks.Parsed.Link).url)
            .isEqualTo("https://example.com/a.revealpreset")
    }

    @Test
    fun `cleartext, credentials, other schemes and plain text are refused`() {
        assertThat(PresetLinks.parse("http://example.com/a.revealpack")).isEqualTo(PresetLinks.Parsed.NotHttps)
        assertThat(PresetLinks.parse("https://user:secret@example.com/a.revealpack"))
            .isEqualTo(PresetLinks.Parsed.None)
        assertThat(PresetLinks.parse("ftp://example.com/a.revealpack")).isEqualTo(PresetLinks.Parsed.None)
        assertThat(PresetLinks.parse("just some words")).isEqualTo(PresetLinks.Parsed.None)
        assertThat(PresetLinks.parse(null)).isEqualTo(PresetLinks.Parsed.None)
        assertThat(PresetLinks.parse("https:///nohost")).isEqualTo(PresetLinks.Parsed.None)
        assertThat(PresetLinks.parse("https://example.com/" + "a".repeat(PresetLinks.MAX_LENGTH)))
            .isEqualTo(PresetLinks.Parsed.None)
    }
}
