package dev.hfps.reveal.feature.editor.edit

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A colour grading wheel's maths (pure, JVM-tested): the puck sits at angle = hue (degrees, counter-clockwise from the
 * right, as the OkLab hue the operator uses) and distance = saturation / 100 of the radius. Drags are relative (the
 * thumb can rest anywhere on the wheel and never covers the puck it moves).
 */
object WheelMath {
    private const val FULL = 100f
    private const val TURN = 360f

    /** The puck's offset from the centre (x right, y down) for [hue], [saturation] on a wheel of [radius]. */
    fun puck(hue: Float, saturation: Float, radius: Float): Pair<Float, Float> {
        val r = saturation.coerceIn(0f, FULL) / FULL * radius
        val a = Math.toRadians(hue.toDouble())
        return (r * cos(a)).toFloat() to (-r * sin(a)).toFloat()
    }

    /**
     * Hue and saturation (whole numbers) of the puck at offset ([x], [y]) from the centre on a wheel of [radius]; the
     * puck stays inside the wheel. At the centre the hue is undefined: [previousHue] is kept.
     */
    fun fromPuck(x: Float, y: Float, radius: Float, previousHue: Float): Pair<Float, Float> {
        val d = hypot(x, y)
        val saturation = (d / radius * FULL).coerceIn(0f, FULL).roundToInt().toFloat()
        if (saturation == 0f) return previousHue to 0f
        val deg = Math.toDegrees(atan2(-y.toDouble(), x.toDouble())).toFloat()
        val hue = ((deg % TURN + TURN) % TURN).roundToInt().toFloat() % TURN
        return hue to saturation
    }
}
