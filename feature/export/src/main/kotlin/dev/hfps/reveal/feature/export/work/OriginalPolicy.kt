package dev.hfps.reveal.feature.export.work

import dev.hfps.reveal.data.library.OriginalReach

/** What an export does about a photo's original (ADR 0018 "Export"). */
enum class OriginalStep {
    /** On this device: decode it. */
    EXPORT,

    /** On the sync server: download it first (progress, cancellable), then decode the local copy. */
    DOWNLOAD_FIRST,

    /** Only another device has it: keep the item waiting; the job runs again later. */
    WAIT,

    /** Waited [OriginalPolicy.WAIT_LIMIT_MS] and the original never came: fail the item with its reason. */
    GIVE_UP,
}

object OriginalPolicy {
    /** How long an item waits for its original before it fails. */
    const val WAIT_LIMIT_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** How long a job waits between attempts while items wait for their originals. */
    const val RETRY_MINUTES: Long = 30

    /** The step for an original at [reach]; [waitingSince] is when the item started waiting (null = it never has). */
    fun step(reach: OriginalReach, waitingSince: Long?, now: Long): OriginalStep = when (reach) {
        OriginalReach.DEVICE -> OriginalStep.EXPORT

        OriginalReach.SERVER -> OriginalStep.DOWNLOAD_FIRST

        OriginalReach.ELSEWHERE ->
            if (waitingSince != null && now - waitingSince >= WAIT_LIMIT_MS) OriginalStep.GIVE_UP else OriginalStep.WAIT
    }
}
