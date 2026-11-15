package dev.hfps.reveal.debug

import android.app.Activity

/**
 * Debug builds only: a bare activity that plays the sending app in the hand-off tests. Another app starts Reveal into
 * its own task (Hoard: `startActivity` without `FLAG_ACTIVITY_NEW_TASK`), so Reveal's `MainActivity` is never the task
 * root; a test that starts it from here is in the same situation (ADR 0011 "Batches over 500").
 */
class HandoffHostActivity : Activity()
