package dev.hfps.reveal.feature.library.xmpexport

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.library.SidecarOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Export XMP job files: a run stopped and run again resumes per photo and knows the documents it created. */
class XmpExportJobsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the log records each photo, so a resumed job skips the done ones and reuses its own documents`() {
        val jobs = XmpExportJobs(temp.newFolder("xmp-jobs"))
        jobs.write("a", XmpExportJob(listOf(1, 2, 3), folderUri = "content://t/tree/out"))
        jobs.created("a", 1, "out/DSC00001.xmp")
        jobs.done("a", 1, SidecarOutcome(1, 0))
        jobs.done("a", 2, SidecarOutcome(0, 0, 1))
        // Killed after creating photo 3's document, before its bytes: the next run overwrites that document.
        jobs.created("a", 3, "out/DSC00003.xmp")

        val log = jobs.log("a")
        assertThat(log.done).containsExactly(1L, 2L)
        assertThat(log.created).containsExactly(1L, "out/DSC00001.xmp", 3L, "out/DSC00003.xmp")
        assertThat(log.outcome).isEqualTo(SidecarOutcome(1, 0, 1))
        // A photo logged twice (a rerun after a kill between the write and its line) counts once.
        jobs.done("a", 1, SidecarOutcome(1, 0))
        assertThat(jobs.log("a").outcome).isEqualTo(SidecarOutcome(1, 0, 1))
    }

    @Test
    fun `other jobs keep their trees in use, and a deleted job leaves nothing behind`() {
        val dir = temp.newFolder("xmp-jobs")
        val jobs = XmpExportJobs(dir)
        jobs.write("a", XmpExportJob(listOf(1), folderUri = "content://t/tree/out"))
        jobs.write("b", XmpExportJob(listOf(2), oneOff = mapOf(4L to "content://t/tree/shoot")))
        jobs.done("a", 1, SidecarOutcome(1, 0))
        assertThat(jobs.others("a").flatMap { it.trees }).containsExactly("content://t/tree/shoot")
        assertThat(jobs.read("a")?.trees).containsExactly("content://t/tree/out")

        jobs.delete("a")
        assertThat(jobs.read("a")).isNull()
        assertThat(dir.list().orEmpty().toList()).containsExactly("b.json")
    }
}
