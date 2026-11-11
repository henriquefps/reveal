package dev.hfps.reveal.feature.editor

import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.WbMode

/** Test tags of the editor, for instrumented and screenshot tests (locale-independent). */
object EditorTags {
    const val CANVAS = "editor-canvas"
    const val PREVIEW = "editor-preview"
    const val LOADING = "editor-loading"
    const val DEVELOPED = "editor-developed"
    const val DETAIL_HINT = "editor-detail-hint"
    const val ERROR = "editor-error"
    const val TOP_BAR = "editor-top-bar"
    const val COMPARE = "editor-compare"
    const val MORE = "editor-more"
    const val UNDO = "editor-undo"
    const val REDO = "editor-redo"
    const val EXPORT = "editor-export"
    const val PANEL = "editor-panel"
    const val PANEL_HEADER = "editor-panel-header"
    const val PANEL_RESET = "editor-panel-reset"
    const val PANEL_BACK = "editor-panel-back"
    const val PANEL_AUTO = "editor-panel-auto"
    const val PROFILE_ROW = "editor-profile-row"
    const val TOOL_STRIP = "editor-tool-strip"
    const val HISTOGRAM = "editor-histogram"
    const val HISTOGRAM_SHADOWS = "editor-histogram-shadows"
    const val HISTOGRAM_HIGHLIGHTS = "editor-histogram-highlights"
    const val MENU_HISTOGRAM = "editor-menu-histogram"
    const val MENU_SHADOWS = "editor-menu-shadows"
    const val MENU_HIGHLIGHTS = "editor-menu-highlights"
    const val COMPARE_MENU = "editor-compare-menu"
    const val COMPARE_DIVIDER = "editor-compare-divider"
    const val EYEDROPPER = "editor-eyedropper"
    const val EYEDROPPER_HINT = "editor-eyedropper-hint"
    const val HISTORY_LIST = "editor-history"
    const val MENU_HISTORY = "editor-menu-history"
    const val MENU_COPY = "editor-menu-copy"
    const val MENU_PASTE = "editor-menu-paste"
    const val MENU_PREVIOUS = "editor-menu-previous"
    const val MENU_RESET_ALL = "editor-menu-reset-all"
    const val MENU_RESET_AS_OPENED = "editor-menu-reset-as-opened"
    const val PASTE_CHOOSER = "editor-paste"
    const val PASTE_CONFIRM = "$PASTE_CHOOSER-confirm"
    const val MODE_CONTROLS = "editor-mode-controls"
    const val MODE_DONE = "editor-mode-done"
    const val MODE_CANCEL = "editor-mode-cancel"
    const val CROP_OVERLAY = "editor-crop-overlay"
    const val CROP_DIAL = "editor-crop-dial"
    const val CROP_ROTATE_LEFT = "editor-crop-rotate-left"
    const val CROP_ROTATE_RIGHT = "editor-crop-rotate-right"
    const val CROP_FLIP_H = "editor-crop-flip-h"
    const val CROP_FLIP_V = "editor-crop-flip-v"
    const val CROP_HORIZON = "editor-crop-horizon"
    const val CROP_LOCK = "editor-crop-lock"
    const val CROP_SWAP = "editor-crop-swap"
    const val CROP_CONSTRAIN = "editor-crop-constrain"
    const val CROP_RESET = "editor-crop-reset"
    const val CROP_AUTO = "editor-crop-auto"

    /** The slider row of recipe control [id], e.g. `editor-control-exposure`. */
    fun control(id: ControlId): String = "editor-control-${id.name.lowercase()}"

    /** The exposure slider (the measurement test drags it). */
    val EXPOSURE: String = control(ControlId.EXPOSURE)

    fun tool(id: ToolId): String = "editor-tool-${id.name.lowercase()}"

    fun look(look: BaseLook): String = "editor-look-${look.name.lowercase()}"

    fun wbMode(mode: WbMode): String = "editor-wb-${mode.name.lowercase()}"

    fun compareOption(index: Int): String = "editor-compare-option-$index"

    /** A row of the history list; position -1 is "Original". */
    fun historyStep(position: Int): String = "editor-history-step-$position"

    /** A group's row in the paste chooser. */
    fun pasteGroup(group: RecipeGroup): String = "$PASTE_CHOOSER-$group"

    fun aspect(preset: AspectPreset): String = "editor-crop-aspect-${preset.name.lowercase()}"
}
