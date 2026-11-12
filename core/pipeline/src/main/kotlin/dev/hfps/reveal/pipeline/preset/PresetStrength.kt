package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.cpu.MonotoneSpline
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/**
 * Strength (docs/adr/0019, "Strength"): every field a preset sets moves from the photo's value `b` (strength `s` = 0)
 * to the preset's `p` (`s` = 1) and on to `b + 2 (p − b)` (`s` = 2), by its [FieldKind]:
 *
 * - [FieldKind.LINEAR]: `b + s (p − b)`, clamped to the control's range.
 * - [FieldKind.RATIO]: `b (p / b)^s` (log space; linear when either is not positive), clamped.
 * - [FieldKind.KELVIN]: linear in mired, `1e6 / (1e6/b + s (1e6/p − 1e6/b))`, clamped to 2000-15000 K. `null` (as
 *   shot) is the [ApplyContext]'s as-shot kelvin; at `s ≥ 1` towards as shot the field is `null` again. Without an
 *   as-shot value it switches like [FieldKind.SWITCH]. [FieldKind.TINT] is the same, linear.
 * - [FieldKind.GRADE_HUE] / [FieldKind.GRADE_SATURATION]: a wheel whose hue and saturation are both set blends the
 *   tint as a 2D vector `(sat cos hue, sat sin hue)` (a tint fades through grey, never round the colour wheel); hue
 *   alone takes the shortest arc; saturation alone is linear.
 * - [FieldKind.CURVE]: both point curves are sampled at the union of their points' x (16 evenly spaced x when that is
 *   more than 16) with their monotone splines (the identity for a straight line) and the y values blend linearly,
 *   clamped to [0, 1]; a result that is the straight line is stored as the empty list.
 * - [FieldKind.SWITCH]: the preset's value from `s ≥ 0.5`, the photo's below.
 *
 * At exactly 0 and 1 every field is the photo's or the preset's value, untouched by arithmetic.
 */
object PresetStrength {
    /** The strength from which a switch takes the preset's value. */
    const val SWITCH_THRESHOLD: Float = 0.5f

    private const val MIRED = 1_000_000.0
    private const val FULL_TURN = 360.0
    private const val HALF_TURN = 180.0
    private const val TINY = 1e-6

    /** [before] (a full recipe JSON) with [values] blended in at strength [s] (0 < s <= 2). */
    internal fun blend(before: JsonObject, values: Map<String, JsonElement>, s: Float, ctx: ApplyContext): JsonObject {
        var out = before
        val done = HashSet<String>()
        for ((path, preset) in values) {
            val field = PresetFields[path]?.takeIf { path !in done } ?: continue
            val segments = field.segments
            if (field.kind == FieldKind.GRADE_HUE || field.kind == FieldKind.GRADE_SATURATION) {
                val wheel = path.substringBeforeLast('.')
                out = gradeWheel(out, before, values, wheel, s)
                done += "$wheel.hue"
                done += "$wheel.saturation"
            } else {
                val b = JsonTree.get(before, segments) ?: JsonNull
                val value = if (s == 1f) preset else blendOne(field, b, preset, s, ctx)
                out = JsonTree.set(out, segments, value)
            }
        }
        return out
    }

    private fun blendOne(field: PresetField, b: JsonElement, p: JsonElement, s: Float, ctx: ApplyContext): JsonElement =
        when (field.kind) {
            FieldKind.LINEAR -> number(field, b, p) { bv, pv -> bv + s * (pv - bv) }

            FieldKind.RATIO -> number(field, b, p) { bv, pv -> ratio(bv, pv, s) }

            FieldKind.KELVIN -> asShot(b, p, s, ctx.asShotTemperatureK) { bv, pv ->
                (MIRED / (MIRED / bv + s * (MIRED / pv - MIRED / bv))).toFloat()
                    .let(RecipeRanges.temperature::clamp)
            }

            FieldKind.TINT -> asShot(b, p, s, ctx.asShotTint) { bv, pv ->
                RecipeRanges.tint.clamp(bv + s * (pv - bv))
            }

            FieldKind.CURVE -> curve(b, p, s)

            FieldKind.SWITCH, FieldKind.GRADE_HUE, FieldKind.GRADE_SATURATION -> if (s >= SWITCH_THRESHOLD) p else b
        }

    private inline fun number(field: PresetField, b: JsonElement, p: JsonElement, f: (Float, Float) -> Float) =
        JsonPrimitive(
            run {
                val bv = (b as? JsonPrimitive)?.floatOrNull
                val pv = (p as? JsonPrimitive)?.floatOrNull
                if (bv == null || pv == null) return@run pv ?: 0f
                val v = f(bv, pv)
                field.range?.clamp(v) ?: v
            },
        )

    private fun ratio(b: Float, p: Float, s: Float): Float =
        if (b <= 0f || p <= 0f) b + s * (p - b) else (b * exp(s * ln((p / b).toDouble()))).toFloat()

    /** A nullable absolute white-balance value: `null` = as shot, blended through [asShot] when it is known. */
    private inline fun asShot(
        b: JsonElement,
        p: JsonElement,
        s: Float,
        asShot: Float?,
        f: (Float, Float) -> Float,
    ): JsonElement {
        val bv = (b as? JsonPrimitive)?.floatOrNull
        val pv = (p as? JsonPrimitive)?.floatOrNull
        return when {
            bv == null && pv == null -> JsonNull
            bv != null && pv != null -> JsonPrimitive(f(bv, pv))
            asShot == null -> if (s >= SWITCH_THRESHOLD) p else b
            pv == null && s >= 1f -> JsonNull
            else -> JsonPrimitive(f(bv ?: asShot, pv ?: asShot))
        }
    }

    private fun curve(b: JsonElement, p: JsonElement, s: Float): JsonElement {
        val before = JsonTree.curvePoints(b)
        val preset = JsonTree.curvePoints(p)
        val sb = MonotoneSpline.of(before)
        val sp = MonotoneSpline.of(preset)
        val union = (listOf(0f, 1f) + before.map { it.x } + preset.map { it.x }).distinct().sorted()
        val xs = if (union.size <=
            Curves.MAX_POINTS
        ) {
            union
        } else {
            List(Curves.MAX_POINTS) { it / (Curves.MAX_POINTS - 1f) }
        }
        val points = xs.map { x ->
            val yb = sb?.eval(x.toDouble()) ?: x.toDouble()
            val yp = sp?.eval(x.toDouble()) ?: x.toDouble()
            CurvePoint(x, (yb + s * (yp - yb)).toFloat().coerceIn(0f, 1f))
        }
        val stored = if (points.all { kotlin.math.abs(it.y - it.x) < TINY }) emptyList() else points
        return JsonArray(stored.map { JsonObject(mapOf("x" to JsonPrimitive(it.x), "y" to JsonPrimitive(it.y))) })
    }

    @Suppress("LongMethod")
    private fun gradeWheel(
        out: JsonObject,
        before: JsonObject,
        values: Map<String, JsonElement>,
        wheel: String,
        s: Float,
    ): JsonObject {
        val segments = wheel.split('.')
        fun read(json: JsonObject, key: String) =
            (JsonTree.get(json, segments + key) as? JsonPrimitive)?.floatOrNull ?: 0f
        val hb = read(before, "hue")
        val satB = read(before, "saturation")
        val hp = (values["$wheel.hue"] as? JsonPrimitive)?.floatOrNull
        val satP = (values["$wheel.saturation"] as? JsonPrimitive)?.floatOrNull
        val satRange = PresetFields["$wheel.saturation"]?.range
        var hue = hb
        var sat = satB
        when {
            s == 1f -> {
                hue = hp ?: hb
                sat = satP ?: satB
            }

            hp != null && satP != null -> {
                val xb = satB * cos(Math.toRadians(hb.toDouble()))
                val yb = satB * sin(Math.toRadians(hb.toDouble()))
                val xp = satP * cos(Math.toRadians(hp.toDouble()))
                val yp = satP * sin(Math.toRadians(hp.toDouble()))
                val x = xb + s * (xp - xb)
                val y = yb + s * (yp - yb)
                sat = hypot(x, y).toFloat()
                hue = if (sat < TINY) hp else normaliseHue(Math.toDegrees(atan2(y, x)))
            }

            hp != null -> {
                var delta = (hp - hb).toDouble() % FULL_TURN
                if (delta > HALF_TURN) delta -= FULL_TURN
                if (delta < -HALF_TURN) delta += FULL_TURN
                hue = normaliseHue(hb + s * delta)
            }

            satP != null -> sat = satB + s * (satP - satB)
        }
        sat = satRange?.clamp(sat) ?: sat
        var result = JsonTree.set(out, segments + "hue", JsonPrimitive(hue))
        result = JsonTree.set(result, segments + "saturation", JsonPrimitive(sat))
        return result
    }

    private fun normaliseHue(degrees: Double): Float {
        val h = degrees % FULL_TURN
        return (if (h < 0) h + FULL_TURN else h).toFloat()
    }
}
