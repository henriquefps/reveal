package dev.hfps.reveal.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.LibraryActions
import dev.hfps.reveal.feature.library.LibraryContent
import dev.hfps.reveal.feature.library.buildGridEntries
import dev.hfps.reveal.feature.settings.sync.BackupFolder
import dev.hfps.reveal.feature.settings.sync.PairingState
import dev.hfps.reveal.feature.settings.sync.SyncActions
import dev.hfps.reveal.feature.settings.sync.SyncSettingsContent
import dev.hfps.reveal.feature.settings.sync.SyncUiState
import dev.hfps.reveal.sync.PairProblem
import dev.hfps.reveal.sync.SyncProblem
import dev.hfps.reveal.sync.SyncStatus
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.account.SyncOptions
import dev.hfps.reveal.ui.library.LibrarySamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sync goldens (ADR 0018) under `screenshots/sync/`: the Sync screen unpaired, paired and with a problem, and the
 * library's Server entry with cloud badges and a conflict copy. Fictional server and folder names.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h1100dp-xhdpi")
class SyncScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = 1_762_851_600_000L
    private val account = SyncAccount("https://photos.example.org", "858e1b9d", "Home server", "d1", now - 86_400_000)
    private val paired = SyncUiState(
        account = account,
        options = SyncOptions(),
        status = SyncStatus(
            lastSyncAt = now - 120_000,
            pending = 3,
            uploadsPending = 4,
            uploadBytesPending = 96_000_000,
            uploadedBytes = 1_850_000_000,
            serverPhotos = 2481,
        ),
        folders = listOf(BackupFolder(1, "Camera", true), BackupFolder(2, "Beach day", false)),
    )

    @Test fun unpaired() = shoot("phone_unpaired_light") {
        RevealTheme(darkTheme = false) { SyncSettingsContent(SyncUiState(), SyncActions(), now = now) }
    }

    @Test fun pairFailed() = shoot("phone_pair_failed_dark") {
        RevealTheme(darkTheme = true) {
            SyncSettingsContent(
                SyncUiState(pairing = PairingState.Failed(PairProblem.INVALID_TOKEN)),
                SyncActions(),
                now = now,
            )
        }
    }

    @Test fun pairedLight() = shoot("phone_paired_light") {
        RevealTheme(darkTheme = false) { SyncSettingsContent(paired, SyncActions(), now = now) }
    }

    @Test fun pairedProblemDark() = shoot("phone_paired_problem_dark") {
        RevealTheme(darkTheme = true) {
            SyncSettingsContent(
                paired.copy(status = paired.status.copy(problem = SyncProblem.OFFLINE)),
                SyncActions(),
                now = now,
            )
        }
    }

    @Test fun serverGrid() = shoot("phone_library_server_light") {
        val rows: List<PhotoRow> = LibrarySamples.rows.take(9).mapIndexed { i, row ->
            row.copy(remote = true, cloudOriginal = i % 3 != 2, cloudPreview = true)
        } + LibrarySamples.rows[1].copy(
            remote = true,
            cloudOriginal = true,
            versionId = 999,
            versionPosition = 1,
            conflict = true,
            copyDevice = "Tablet",
        )
        val sorted = rows.sortedWith(compareByDescending<PhotoRow> { it.sortTime }.thenBy { it.versionPosition })
        val query = PhotoQuery.SERVER_ONLY
        val state = LibrarySamples.grid(query, paired = true).copy(
            entries = buildGridEntries(sorted, query),
            photoCount = sorted.size,
        )
        RevealTheme(darkTheme = false) { LibraryContent(state, LibraryActions(), LibrarySamples.thumbnails) }
    }

    @Test fun backedUpGrid() = shoot("phone_library_backed_up_dark") {
        val rows = LibrarySamples.rows.take(12).mapIndexed { i, row -> row.copy(cloudOriginal = i % 2 == 0) }
        val state = LibrarySamples.grid(PhotoQuery.ofSource(1), paired = true).copy(
            entries = buildGridEntries(rows, PhotoQuery.ofSource(1)),
            photoCount = rows.size,
        )
        RevealTheme(darkTheme = true) { LibraryContent(state, LibraryActions(), LibrarySamples.thumbnails) }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/sync/$name.png")
    }
}
