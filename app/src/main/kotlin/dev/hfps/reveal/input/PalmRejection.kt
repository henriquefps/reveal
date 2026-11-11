package dev.hfps.reveal.input

import android.view.MotionEvent

/**
 * Palm rejection while the pen is down (docs/plan/ux.md "Landscape and tablets"; docs/adr/0016): from the moment a
 * stylus touches the screen until it lifts, finger touches are not delivered, so a palm resting on the screen (or a
 * finger on another control) cannot move anything while the pen draws a curve point, a crop handle or a guide.
 *
 * - A pen landing while fingers are down cancels the fingers' gesture first, then starts the pen's own.
 * - Fingers still down when the pen lifts stay ignored until they lift too (no half gesture appears).
 * - Without a pen, events pass untouched; hover events never come through here (they are generic motion events).
 *
 * The decisions are pure ([PalmRejectionPolicy], JVM-tested); this class only rebuilds the [MotionEvent]s.
 */
class PalmRejection {
    private val policy = PalmRejectionPolicy()
    private var penDownTime = 0L

    /** The events to dispatch in place of [event] (possibly [event] itself, possibly none). Recycle the new ones. */
    fun filter(event: MotionEvent): List<MotionEvent> {
        val pointers = List(event.pointerCount) { i ->
            val tool = event.getToolType(i)
            TouchPointer(
                event.getPointerId(i),
                tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER,
            )
        }
        val outputs = policy.onEvent(TouchFrame(event.actionMasked, event.actionIndex, pointers))
        return outputs.map { out ->
            if (out.passThrough) return@map event
            if (out.action == MotionEvent.ACTION_DOWN) penDownTime = event.eventTime
            subset(event, out)
        }
    }

    private fun subset(event: MotionEvent, out: TouchOutput): MotionEvent {
        if (out.keep.isEmpty()) return MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL }
        val indices = (0 until event.pointerCount).filter { event.getPointerId(it) in out.keep }
        val properties = Array(indices.size) { MotionEvent.PointerProperties() }
        val coords = Array(indices.size) { MotionEvent.PointerCoords() }
        indices.forEachIndexed { j, i ->
            event.getPointerProperties(i, properties[j])
            event.getPointerCoords(i, coords[j])
        }
        return MotionEvent.obtain(
            penDownTime,
            event.eventTime,
            out.action,
            indices.size,
            properties,
            coords,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )
    }
}

/** One pointer of a touch event: its id and whether it is a pen (stylus or eraser end). */
data class TouchPointer(val id: Int, val pen: Boolean)

/** A touch event as the policy sees it: the masked action, the action's pointer index and every pointer. */
data class TouchFrame(val action: Int, val actionIndex: Int, val pointers: List<TouchPointer>)

/**
 * What to dispatch: the original event ([passThrough]) or a copy holding only the pointers in [keep] with [action]
 * (`ACTION_CANCEL` with an empty [keep] cancels the original's whole gesture).
 */
data class TouchOutput(val action: Int, val keep: Set<Int>, val passThrough: Boolean = false)

/** The palm-rejection state machine of [PalmRejection], over plain data (JVM-tested). */
class PalmRejectionPolicy {
    private var pen: Int? = null

    /** Fingers that were down when the pen lifted (or landed): ignored until every pointer is up. */
    private var blocked = false

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun onEvent(frame: TouchFrame): List<TouchOutput> {
        val action = frame.action
        val actor = frame.pointers.getOrNull(frame.actionIndex)
        val penId = pen
        if (action == MotionEvent.ACTION_CANCEL) {
            val wasActive = penId != null || blocked
            pen = null
            blocked = false
            return when {
                penId != null -> listOf(TouchOutput(MotionEvent.ACTION_CANCEL, setOf(penId)))
                wasActive -> emptyList()
                else -> listOf(PASS)
            }
        }
        if (penId == null) {
            val landing = actor?.pen == true &&
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
            if (landing) {
                pen = actor.id
                val fingersDown = frame.pointers.size > 1
                val wasBlocked = blocked
                blocked = false
                return buildList {
                    // The fingers' gesture is cancelled before the pen's starts (unless it was already dropped).
                    if (fingersDown && !wasBlocked) add(TouchOutput(MotionEvent.ACTION_CANCEL, emptySet()))
                    add(TouchOutput(MotionEvent.ACTION_DOWN, setOf(actor.id)))
                }
            }
            if (blocked) {
                if (action == MotionEvent.ACTION_UP) blocked = false
                return emptyList()
            }
            return listOf(PASS)
        }
        // The pen is down: only its pointer goes through.
        val others = frame.pointers.size > 1
        return when {
            actor?.id == penId && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) -> {
                pen = null
                blocked = others
                listOf(TouchOutput(MotionEvent.ACTION_UP, setOf(penId)))
            }

            frame.pointers.none { it.id == penId } -> emptyList()

            else -> listOf(TouchOutput(MotionEvent.ACTION_MOVE, setOf(penId)))
        }
    }

    private companion object {
        val PASS = TouchOutput(MotionEvent.ACTION_MOVE, emptySet(), passThrough = true)
    }
}
