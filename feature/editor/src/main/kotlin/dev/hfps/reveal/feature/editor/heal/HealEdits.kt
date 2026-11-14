package dev.hfps.reveal.feature.editor.heal

import dev.hfps.reveal.pipeline.mask.StrokeCodec
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max

/** Pure edits of the recipe's spots (docs/adr/0023 "Editor"); JVM-tested. */
object HealEdits {
    /** The spot [id] of [recipe], `null` when there is none. */
    fun find(recipe: Recipe, id: String?): Heal? = id?.let { i -> recipe.heals.firstOrNull { it.id == i } }

    /** A new circle spot at image fraction ([x], [y]) with [settings]. */
    fun circle(x: Float, y: Float, settings: HealSettings, id: String = newId()): Heal = Heal(
        id = id,
        kind = settings.kind.key,
        x = x,
        y = y,
        radius = settings.size,
        feather = settings.feather,
        opacity = settings.opacity,
        darken = settings.darken,
        method = methodOf(settings),
    )

    /**
     * A new stroke spot through [points] (image fractions), simplified like a mask stroke (tolerance relative to the
     * dab radius; [aspect] = width / height of the image).
     */
    fun stroke(points: List<StrokePoint>, settings: HealSettings, aspect: Float, id: String = newId()): Heal = Heal(
        id = id,
        kind = settings.kind.key,
        radius = settings.size,
        stroke = StrokeCodec.encode(StrokeCodec.simplify(points, settings.size, aspect)),
        feather = settings.feather,
        opacity = settings.opacity,
        darken = settings.darken,
        method = methodOf(settings),
        x = points.first().x,
        y = points.first().y,
    )

    /** [recipe] with [heal] added at the end, or null when it already holds [Heal.MAX_HEALS] spots. */
    fun add(recipe: Recipe, heal: Heal): Recipe? =
        if (recipe.heals.size >= Heal.MAX_HEALS) null else recipe.copy(heals = recipe.heals + heal)

    /** [recipe] with spot [id] changed by [change]. */
    fun update(recipe: Recipe, id: String, change: (Heal) -> Heal): Recipe =
        recipe.copy(heals = recipe.heals.map { if (it.id == id) change(it) else it })

    fun delete(recipe: Recipe, id: String): Recipe = recipe.copy(heals = recipe.heals.filter { it.id != id })

    /** [heal] with the [settings] (kind, size, feather, opacity, darken). */
    fun applySettings(heal: Heal, settings: HealSettings): Heal = heal.copy(
        kind = settings.kind.key,
        radius = settings.size,
        feather = settings.feather,
        opacity = settings.opacity,
        darken = settings.darken,
    )

    /** The settings that reproduce [heal] (the controls show the selected spot). */
    fun settingsOf(heal: Heal, base: HealSettings): HealSettings = base.copy(
        kind = heal.type.takeIf { it != HealKind.UNKNOWN } ?: base.kind,
        size = heal.radius,
        feather = heal.feather,
        opacity = heal.opacity,
        darken = heal.darken,
        removeMethod = if (heal.type == HealKind.REMOVE) heal.method else base.removeMethod,
    )

    /** The method a new spot of [settings] stores: Remove's chosen one; the patch fill for the other kinds. */
    private fun methodOf(settings: HealSettings): String =
        if (settings.kind == HealKind.REMOVE) settings.removeMethod ?: Heal.METHOD_PATCH else Heal.METHOD_PATCH

    /**
     * A spot moved so that its centre is at ([x], [y]): a circle's centre, a stroke's points translated. The source
     * moves with it (the offset stays).
     */
    fun moved(heal: Heal, x: Float, y: Float): Heal {
        if (!heal.isStroke) return heal.copy(x = x, y = y)
        val c = centre(heal)
        val dx = x - c.first
        val dy = y - c.second
        val points = heal.strokePoints.map { StrokePoint(it.x + dx, it.y + dy, it.pressure) }
        return heal.copy(stroke = StrokeCodec.encode(points), x = x, y = y)
    }

    /** The centre of a spot (a stroke's: the middle of its points' box), image fractions. */
    fun centre(heal: Heal): Pair<Float, Float> {
        val p = heal.strokePoints
        return if (!heal.isStroke || p.isEmpty()) {
            heal.x to heal.y
        } else {
            (p.minOf { it.x } + p.maxOf { it.x }) / 2f to (p.minOf { it.y } + p.maxOf { it.y }) / 2f
        }
    }

    /**
     * A source offset that keeps the spot inside the image when the automatic pick finds nothing: two and a half radii
     * to the right, or to the left when that leaves the image ([aspect] = width / height).
     */
    fun fallbackSource(heal: Heal, aspect: Float): Pair<Float, Float> {
        val (cx, _) = centre(heal)
        // The radius (a long-edge fraction) as a fraction of the width.
        val rx = heal.radius * if (aspect >= 1f) 1f else 1f / aspect
        val step = FALLBACK_RADII * rx
        return if (cx + step + rx <= 1f) step to 0f else -step to 0f
    }

    /** The length of a stroke in image fractions scaled by [aspect] (isotropic, long edge = 1). */
    fun length(points: List<StrokePoint>, aspect: Float): Float {
        var total = 0f
        val sx = if (aspect >= 1f) 1f else aspect
        val sy = if (aspect >= 1f) 1f / aspect else 1f
        for (i in 1 until points.size) {
            total += hypot((points[i].x - points[i - 1].x) * sx, (points[i].y - points[i - 1].y) * sy)
        }
        return total
    }

    fun newId(): String = UUID.randomUUID().toString()

    private const val FALLBACK_RADII = 2.5f
}
