package dev.hfps.reveal.feature.export.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.feature.export.model.ExportOptions
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportJobStoreTest {
    private val store = ExportJobStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a job and its item states survive a new store (process restart)`() {
        val job =
            ExportJob(
                "job-1",
                listOf(ExportJobItem(versionId = 1), ExportJobItem(uri = "content://x", recipeJson = "{}")),
                ExportOptions(),
            )
        store.save(job)
        store.update("job-1", 0) { it.copy(partialUri = "content://partial") }
        store.update("job-1", 0) { ItemState(ItemStatus.DONE, "content://done", "a.jpg") }
        store.update("job-1", 1) { it.copy(status = ItemStatus.FAILED, failure = "LOW_STORAGE") }

        val again = ExportJobStore(ApplicationProvider.getApplicationContext())
        assertThat(again.job("job-1")).isEqualTo(job)
        val states = again.states("job-1")
        assertThat(states[0].status).isEqualTo(ItemStatus.DONE)
        assertThat(states[0].partialUri).isNull()
        assertThat(states[1].failure).isEqualTo("LOW_STORAGE")
        // Saving the job again (WorkManager retry) keeps the progress.
        again.save(job)
        assertThat(again.states("job-1")[0].status).isEqualTo(ItemStatus.DONE)
        again.delete("job-1")
        assertThat(again.job("job-1")).isNull()
    }
}
