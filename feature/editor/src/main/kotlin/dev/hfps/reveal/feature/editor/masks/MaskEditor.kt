package dev.hfps.reveal.feature.editor.masks

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.MaskOverlay
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.LabColour
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.MaskPoint
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The view model's half of the Masks tool (docs/adr/0022 "M4/B (editor)"): every [MaskActions] verb as an edit of the
 * working recipe, committed as one history step where the user finished something (create, delete, rename, a stroke, a
 * handle or slider release). It also keeps the overlay, the AI states and the coverage thumbnails in step with the
 * recipe. [host] is the view model.
 */
@Suppress("TooManyFunctions")
internal class MaskEditor(private val scope: CoroutineScope, private val host: Host) : MaskActions {
    /** What the editor gives the masks. */
    interface Host {
        val ui: EditorUiState

        fun updateMasks(change: (MasksUiState) -> MasksUiState)

        /** Per frame: the working recipe changes (not committed). */
        fun edit(change: (Recipe) -> Recipe)

        /** The working recipe becomes one history step (nothing changed: none). */
        fun commit()

        fun renderer(): GpuDevelopRenderer?

        /** The AI masks of this photo, `null` until the photo is open. */
        fun aiSession(): AiMasks.Session?

        /** The photo before local adjustments in OkLab (Auto's sample, rendered without masks), `null` before open. */
        suspend fun preLocal(): PreLocalImage?

        /** Default names: `"Mask %d"`, and an AI kind's name. */
        fun defaultName(): String

        fun kindName(kind: MaskKind): String

        /** The name of a duplicate of [name]. */
        fun copyName(name: String): String
    }

    private val recipe: Recipe get() = host.ui.edit.recipe
    private val masks: MasksUiState get() = host.ui.masks
    private val selectedMask: Mask? get() = MaskEdits.find(recipe, masks.selected)

    /** The Masks panel is on screen: the overlay and the photo tools work only then. */
    var panelOpen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) host.updateMasks { it.copy(tool = PhotoTool.NONE, interacting = false, luminanceMap = false) }
            pushOverlay()
        }

    // ------------------------------------------------------------------ masks

    override fun select(id: String?) {
        val mask = MaskEdits.find(recipe, id)
        host.updateMasks {
            it.copy(selected = mask?.id, component = mask?.let { m -> defaultComponent(m) }, luminanceMap = false)
                .withTool(mask, mask?.let { m -> defaultComponent(m) })
        }
        pushOverlay()
    }

    override fun selectComponent(index: Int?) {
        val mask = selectedMask ?: return
        val i = index?.takeIf { it in mask.components.indices }
        host.updateMasks { it.copy(component = i, luminanceMap = false).withTool(mask, i) }
    }

    override fun openCreate(target: CreateTarget?) {
        host.updateMasks { it.copy(creating = target, full = false) }
    }

    override fun create(kind: NewComponent) {
        val target = masks.creating ?: CreateTarget.NewMask
        host.updateMasks { it.copy(creating = null) }
        val selected = selectedMask
        if (target is CreateTarget.AddTo && selected != null) {
            host.edit { MaskEdits.addComponent(it, selected.id, kind, target.op) }
            host.commit()
            val index = MaskEdits.find(recipe, selected.id)?.components?.lastIndex ?: return
            host.updateMasks { it.copy(component = index).withTool(MaskEdits.find(recipe, selected.id), index) }
            requestAi(MaskEdits.find(recipe, selected.id)?.components?.getOrNull(index))
            return
        }
        val name = when (kind) {
            is NewComponent.Ai -> numbered(host.kindName(kind.kind))
            else -> MaskEdits.nextName(recipe, host.defaultName())
        }
        val made = MaskEdits.create(recipe, kind, name)
        if (made == null) {
            host.updateMasks { it.copy(full = true) }
            return
        }
        host.edit { made.first }
        host.commit()
        val mask = made.second
        host.updateMasks { it.copy(selected = mask.id, component = 0).withTool(mask, 0) }
        requestAi(mask.components.first())
        pushOverlay()
    }

    /** [base], or "[base] 2", "[base] 3"… when a mask already has that name. */
    private fun numbered(base: String): String {
        val used = recipe.masks.map { it.name }.toSet()
        if (base !in used) return base
        var n = 2
        while ("$base $n" in used) n++
        return "$base $n"
    }

    override fun rename(id: String, name: String) = step { MaskEdits.rename(it, id, name) }

    override fun duplicate(id: String) {
        val mask = MaskEdits.find(recipe, id) ?: return
        val made = MaskEdits.duplicate(recipe, id, host.copyName(mask.name))
        if (made == null) {
            host.updateMasks { it.copy(full = true) }
            return
        }
        host.edit { made.first }
        host.commit()
        select(made.second.id)
    }

    override fun invert(id: String) = step { MaskEdits.invert(it, id) }

    override fun delete(id: String) {
        val index = recipe.masks.indexOfFirst { it.id == id }
        step { MaskEdits.delete(it, id) }
        if (masks.selected == id) {
            select(recipe.masks.getOrNull(index.coerceAtMost(recipe.masks.lastIndex))?.id)
        }
    }

    override fun move(id: String, to: Int) = step { MaskEdits.move(it, id, to) }

    override fun setEnabled(id: String, enabled: Boolean) = step { MaskEdits.setEnabled(it, id, enabled) }

    override fun setAmount(id: String, amount: Float) = host.edit { MaskEdits.setAmount(it, id, amount) }

    override fun setAdjustment(id: String, adjustment: LocalAdjustment, value: Float) =
        host.edit { MaskEdits.setAdjustment(it, id, adjustment, value) }

    override fun resetAdjustment(id: String, adjustment: LocalAdjustment) =
        step { MaskEdits.setAdjustment(it, id, adjustment, 0f) }

    override fun resetAdjustments(id: String) = step { MaskEdits.resetAdjustments(it, id) }

    // ------------------------------------------------------------------ components

    override fun setComponentOp(index: Int, op: MaskOp) = onSelected { id ->
        step { MaskEdits.setComponentOp(it, id, index, op) }
    }

    override fun toggleComponentInvert(index: Int) =
        onSelected { id -> step { MaskEdits.toggleComponentInvert(it, id, index) } }

    override fun removeComponent(index: Int) = onSelected { id ->
        step { MaskEdits.removeComponent(it, id, index) }
        val mask = selectedMask
        val next = mask?.components?.indices?.lastOrNull()?.let { minOf(index, it) }
        host.updateMasks { it.copy(component = next).withTool(mask, next) }
    }

    override fun editComponent(index: Int, change: (MaskComponent) -> MaskComponent) =
        onSelected { id -> host.edit { MaskEdits.updateComponent(it, id, index, change) } }

    override fun commit() {
        host.commit()
        val component = selectedMask?.components?.getOrNull(masks.component ?: -1)
        requestAi(component)
    }

    // ------------------------------------------------------------------ brush

    override fun setBrush(settings: BrushSettings) = host.updateMasks { it.copy(brush = settings) }

    /** The stroke being drawn: the component it goes into and its raw points. */
    private class Stroke(val maskId: String, val index: Int, val erase: Boolean, val points: MutableList<StrokePoint>)

    private var stroke: Stroke? = null

    override fun strokeStart(point: StrokePoint, erase: Boolean) {
        val mask = selectedMask ?: return
        val index = masks.component ?: return
        if (mask.components.getOrNull(index) !is MaskComponent.Brush) return
        val s = Stroke(mask.id, index, erase || masks.brush.erase, mutableListOf(point))
        stroke = s
        setInteracting(true)
        host.edit { r ->
            MaskEdits.updateComponent(r, s.maskId, s.index) { c ->
                (c as? MaskComponent.Brush)?.let { it.copy(strokes = it.strokes + raw(s)) } ?: c
            }
        }
    }

    override fun strokeMove(points: List<StrokePoint>) {
        val s = stroke ?: return
        if (points.isEmpty()) return
        s.points.addAll(points)
        host.edit { r -> replaceLast(r, s, raw(s)) }
    }

    override fun strokeEnd() {
        val s = stroke ?: return
        stroke = null
        val ready = host.ui.phase as? EditorPhase.Ready
        val aspect = ready?.let { it.width.toFloat() / it.height } ?: 1f
        val b = masks.brush
        val finished = BrushStroke.of(
            s.points,
            b.size,
            b.feather,
            b.flow,
            s.erase,
            aspect,
            b.density,
            b.autoMask,
            b.pressure,
        )
        host.edit { r -> replaceLast(r, s, finished) }
        host.commit()
        setInteracting(false)
    }

    override fun strokeCancel() {
        val s = stroke ?: return
        stroke = null
        host.edit { r ->
            MaskEdits.updateComponent(r, s.maskId, s.index) { c ->
                (c as? MaskComponent.Brush)?.let { it.copy(strokes = it.strokes.dropLast(1)) } ?: c
            }
        }
        setInteracting(false)
    }

    private fun raw(s: Stroke): BrushStroke {
        val b = masks.brush
        return BrushStroke.raw(s.points, b.size, b.feather, b.flow, s.erase, b.density, b.autoMask, b.pressure)
    }

    private fun replaceLast(r: Recipe, s: Stroke, with: BrushStroke): Recipe =
        MaskEdits.updateComponent(r, s.maskId, s.index) { c ->
            (c as? MaskComponent.Brush)?.let { b ->
                if (b.strokes.isEmpty()) b else b.copy(strokes = b.strokes.dropLast(1) + with)
            } ?: c
        }

    // ------------------------------------------------------------------ photo tools

    override fun setTool(tool: PhotoTool) = host.updateMasks { it.copy(tool = tool) }

    override fun pickColour(x: Float, y: Float) {
        val mask = selectedMask ?: return
        val index = masks.component ?: return
        if (mask.components.getOrNull(index) !is MaskComponent.ColourRange) return
        scope.launch {
            val image = host.preLocal() ?: return@launch
            val lab = image.meanLab(x, y)
            host.edit { r ->
                MaskEdits.updateComponent(r, mask.id, index) { c ->
                    (c as? MaskComponent.ColourRange)?.let {
                        it.copy(samples = (it.samples + lab).takeLast(MaskComponent.ColourRange.MAX_SAMPLES))
                    } ?: c
                }
            }
            host.commit()
        }
    }

    override fun subjectTap(x: Float, y: Float) {
        val mask = selectedMask ?: return
        val index = masks.component ?: return
        val point = MaskPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        when (masks.tool) {
            PhotoTool.SUBJECT_ADD -> {
                host.edit { r ->
                    MaskEdits.updateComponent(r, mask.id, index) { c ->
                        (c as? MaskComponent.Ai)?.let { it.copy(prompts = it.prompts + point) } ?: c
                    }
                }
                host.commit()
                requestAi(selectedMask?.components?.getOrNull(index))
            }

            PhotoTool.SUBJECT_SUBTRACT -> {
                val component = (
                    MaskEdits.component(
                        NewComponent.Ai(MaskKind.SUBJECT),
                        MaskOp.SUBTRACT,
                    ) as MaskComponent.Ai
                    )
                    .copy(prompts = listOf(point), autoSeeds = false)
                host.edit { r -> MaskEdits.update(r, mask.id) { it.copy(components = it.components + component) } }
                host.commit()
                requestAi(component)
            }

            else -> Unit
        }
    }

    // ------------------------------------------------------------------ overlay

    override fun setOverlay(options: MaskOverlayOptions) {
        host.updateMasks { it.copy(overlay = options) }
        pushOverlay()
    }

    override fun toggleOverlay() = setOverlay(masks.overlay.copy(shown = !masks.overlay.shown))

    override fun setInteracting(on: Boolean) {
        if (masks.interacting == on) return
        host.updateMasks { it.copy(interacting = on) }
        pushOverlay()
    }

    override fun setLuminanceMap(on: Boolean) {
        host.updateMasks { it.copy(luminanceMap = on) }
        if (on) scope.launch { host.preLocal() }
    }

    override fun retryAi() {
        host.aiSession()?.retry()
        host.renderer()?.let { it.setRecipe(recipe) }
        selectedMask?.components?.filterIsInstance<MaskComponent.Ai>()?.forEach { requestAi(it) }
    }

    /** The overlay the renderer shows now: the selected mask while the panel is open and it is wanted. */
    fun pushOverlay() {
        val m = masks
        val id = m.selected?.takeIf { panelOpen && MaskEdits.find(recipe, it) != null }
        val shown = id != null && (m.overlay.shown || m.interacting)
        host.renderer()?.setMaskOverlay(
            if (shown) MaskOverlay(checkNotNull(id), m.overlay.mode, m.overlay.colour, m.overlay.opacity) else null,
        )
    }

    /** Starts an AI component's mask (a new mask shows its progress before any slider moves). */
    fun requestAi(component: MaskComponent?) {
        (component as? MaskComponent.Ai)?.let { host.aiSession()?.request(it) }
    }

    /** After undo, redo or a reload: a selection that no longer exists is dropped. */
    fun onRecipeShown() {
        val mask = selectedMask
        if (masks.selected != null && mask == null) {
            host.updateMasks { it.copy(selected = null, component = null, tool = PhotoTool.NONE) }
        } else if (mask != null && (masks.component ?: -1) !in mask.components.indices) {
            val c = defaultComponent(mask)
            host.updateMasks { it.copy(component = c).withTool(mask, c) }
        }
        pushOverlay()
        scheduleThumbnails()
    }

    // ------------------------------------------------------------------ thumbnails

    private var thumbJob: Job? = null
    private val thumbKeys = HashMap<String, Any>()

    /** Coverage pictures of the masks whose shape changed, a moment after the last change (`readCoverage`). */
    fun scheduleThumbnails() {
        thumbJob?.cancel()
        thumbJob = scope.launch {
            delay(THUMB_DELAY_MS)
            val renderer = host.renderer() ?: return@launch
            val ready = host.ui.phase as? EditorPhase.Ready ?: return@launch
            val r = host.ui.edit.recipe
            val ai = host.ui.masks.ai
            val next = HashMap(host.ui.masks.thumbnails)
            next.keys.retainAll(r.masks.map { it.id }.toSet())
            for (mask in r.masks) {
                val key = mask.components to ai
                if (thumbKeys[mask.id] == key && next.containsKey(mask.id)) continue
                val coverage = runCatching { renderer.readCoverage(r, mask.id) }.getOrNull() ?: continue
                next[mask.id] = withContext(Dispatchers.Default) { thumbnail(coverage, ready.width, ready.height) }
                thumbKeys[mask.id] = key
            }
            host.updateMasks { it.copy(thumbnails = next) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun step(change: (Recipe) -> Recipe) {
        host.edit(change)
        host.commit()
    }

    private inline fun onSelected(block: (String) -> Unit) {
        masks.selected?.let(block)
    }

    private fun MasksUiState.withTool(mask: Mask?, index: Int?): MasksUiState {
        val c = index?.let { mask?.components?.getOrNull(it) }
        val tool = when {
            c is MaskComponent.Brush -> PhotoTool.BRUSH
            c is MaskComponent.ColourRange && c.samples.isEmpty() -> PhotoTool.PICK_COLOUR
            else -> PhotoTool.NONE
        }
        return copy(tool = tool)
    }

    private fun defaultComponent(mask: Mask): Int? = mask.components.indices.lastOrNull()

    companion object {
        private const val THUMB_DELAY_MS = 350L
        private const val THUMB_SIDE = 96

        /** A small grey picture of [coverage] (white = covered). */
        fun thumbnail(coverage: FloatArray, width: Int, height: Int): ImageBitmap {
            val k = THUMB_SIDE.toFloat() / max(width, height)
            val w = max(1, (width * k).roundToInt())
            val h = max(1, (height * k).roundToInt())
            val px = IntArray(w * h)
            for (y in 0 until h) {
                val y0 = (y * height / h)
                val y1 = max(y0 + 1, (y + 1) * height / h)
                for (x in 0 until w) {
                    val x0 = (x * width / w)
                    val x1 = max(x0 + 1, (x + 1) * width / w)
                    var sum = 0f
                    var n = 0
                    // Sparse sampling of the block: a thumbnail needs no more.
                    val sy = max(1, (y1 - y0) / SAMPLES)
                    val sx = max(1, (x1 - x0) / SAMPLES)
                    var yy = y0
                    while (yy < y1) {
                        var xx = x0
                        while (xx < x1) {
                            sum += coverage[yy * width + xx]
                            n++
                            xx += sx
                        }
                        yy += sy
                    }
                    val v = ((sum / n).coerceIn(0f, 1f) * BYTE).roundToInt()
                    px[y * w + x] = (OPAQUE shl ALPHA_SHIFT) or (v shl RED_SHIFT) or (v shl GREEN_SHIFT) or v
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        private const val SAMPLES = 4
        private const val BYTE = 255f
        private const val OPAQUE = 0xff
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
    }
}

/** The photo before local adjustments (Auto's analysis sample rendered without masks) in OkLab, for the range tools. */
class PreLocalImage(val width: Int, val height: Int, val lab: FloatArray) {
    /** The mean OkLab colour around image fraction ([x], [y]) (a 3 x 3 block). */
    fun meanLab(x: Float, y: Float): LabColour {
        val cx = (x * width).toInt().coerceIn(0, width - 1)
        val cy = (y * height).toInt().coerceIn(0, height - 1)
        var l = 0f
        var a = 0f
        var b = 0f
        var n = 0
        for (yy in maxOf(0, cy - 1)..minOf(height - 1, cy + 1)) {
            for (xx in maxOf(0, cx - 1)..minOf(width - 1, cx + 1)) {
                val i = (yy * width + xx) * 3
                l += lab[i]
                a += lab[i + 1]
                b += lab[i + 2]
                n++
            }
        }
        return LabColour(l / n, a / n, b / n)
    }

    /** The OkLab lightness at sample pixel [i]. */
    fun lightness(i: Int): Float = lab[i * 3]
}
