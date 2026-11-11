package dev.hfps.reveal.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

/**
 * Motion rules from hfps-app-ui: 150-250 ms, ease-out for things entering, only for what the user caused, and every
 * transition is a `tween` (interruptible: a new target retargets from the current value).
 */
object RevealMotion {
    const val Fast = 150
    const val Standard = 200
    const val Slow = 250

    /** Strong ease-out (the skill's custom curve). */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    fun <T> fast() = tween<T>(durationMillis = Fast, easing = EaseOut)

    fun <T> standard() = tween<T>(durationMillis = Standard, easing = EaseOut)
}
