package dev.hfps.reveal.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.toSize

/**
 * Brings the node tagged [tag] on screen through **every** scrollable container around it, outermost first.
 * `performScrollTo` (and `performScrollToNode`, once the node is composed) scroll only the closest container: the
 * Masks panel's "+" sits in a horizontal row inside the vertically scrolled panel, so with the panel scrolled down to a
 * mask's sliders (a portrait phone or tablet, where the panel is docked at the bottom) the "+" stayed above the visible
 * part and the click landed on something else (FIX/H, item 7). Each container is scrolled by the offset that puts the
 * node's (unclipped) bounds inside the container's visible bounds.
 */
fun SemanticsNodeInteractionsProvider.scrollIntoView(tag: String) {
    val containers = hasScrollAction() and hasAnyDescendant(hasTestTag(tag))
    val count = onAllNodes(containers).fetchSemanticsNodes().size
    for (i in 0 until count) {
        val container = onAllNodes(containers)[i]
        val viewport = container.fetchSemanticsNode().boundsInRoot
        val node = onNode(hasTestTag(tag)).fetchSemanticsNode()
        val bounds = Rect(node.positionInRoot, node.size.toSize())
        val dx = offset(bounds.left, bounds.right, viewport.left, viewport.right)
        val dy = offset(bounds.top, bounds.bottom, viewport.top, viewport.bottom)
        if (dx != 0f || dy != 0f) {
            runCatching { container.performSemanticsAction(SemanticsActions.ScrollBy) { it(dx, dy) } }
        }
    }
}

/** How far to scroll so [start]..[end] lies within [from]..[to] (its start first when it does not fit). */
private fun offset(start: Float, end: Float, from: Float, to: Float): Float = when {
    start < from -> start - from
    end > to -> minOf(end - to, start - from)
    else -> 0f
}
