package dev.hfps.reveal.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.settings.appearance.LanguageChoice
import kotlinx.serialization.serializer
import org.junit.Test

class SettingsRouteTest {
    @Test
    fun `routes have stable serial names`() {
        assertThat(serializer<SettingsRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.settings.SettingsRoute")
        assertThat(serializer<PhotoServerRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.settings.PhotoServerRoute")
        assertThat(serializer<StorageRoute>().descriptor.serialName)
            .isEqualTo("dev.hfps.reveal.feature.settings.StorageRoute")
    }

    @Test
    fun `doors from outside Settings land on their screens`() {
        assertThat(SettingsLink.SYNC_STATUS.route).isEqualTo(PhotoServerRoute)
        assertThat(SettingsLink.PAIR.route).isEqualTo(PhotoServerRoute)
    }

    @Test
    fun `the hub lists the seven entries in order, each with its own tag`() {
        assertThat(SettingsEntry.entries).containsExactly(
            SettingsEntry.SERVER,
            SettingsEntry.BACKUP,
            SettingsEntry.FOLDERS,
            SettingsEntry.STORAGE,
            SettingsEntry.APPEARANCE,
            SettingsEntry.EDITING,
            SettingsEntry.ABOUT,
        ).inOrder()
        assertThat(SettingsEntry.entries.map { it.tag }.toSet()).hasSize(SettingsEntry.entries.size)
    }
}

class LanguageChoiceTest {
    @Test
    fun `language tags map to Reveal's translations`() {
        assertThat(LanguageChoice.of(null)).isEqualTo(LanguageChoice.SYSTEM)
        assertThat(LanguageChoice.of("")).isEqualTo(LanguageChoice.SYSTEM)
        assertThat(LanguageChoice.of("pt-BR")).isEqualTo(LanguageChoice.PORTUGUESE_BRAZIL)
        assertThat(LanguageChoice.of("pt")).isEqualTo(LanguageChoice.PORTUGUESE_BRAZIL)
        assertThat(LanguageChoice.of("pt-PT")).isEqualTo(LanguageChoice.PORTUGUESE_BRAZIL)
        assertThat(LanguageChoice.of("en")).isEqualTo(LanguageChoice.ENGLISH)
        assertThat(LanguageChoice.of("en-GB")).isEqualTo(LanguageChoice.ENGLISH)
        assertThat(LanguageChoice.of("pt-BR,en")).isEqualTo(LanguageChoice.PORTUGUESE_BRAZIL)
        // A language Reveal has no translation for (set in the system's App languages) shows as the system's.
        assertThat(LanguageChoice.of("fr-FR")).isEqualTo(LanguageChoice.SYSTEM)
    }

    @Test
    fun `every choice round-trips through its tag`() {
        for (choice in LanguageChoice.entries) assertThat(LanguageChoice.of(choice.tag)).isEqualTo(choice)
    }
}
