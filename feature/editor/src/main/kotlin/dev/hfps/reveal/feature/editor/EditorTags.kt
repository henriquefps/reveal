package dev.hfps.reveal.feature.editor

import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.recipe.AspectPreset
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ColourBand
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.GradeRegion
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.WbMode

/** Test tags of the editor, for instrumented and screenshot tests (locale-independent). */
object EditorTags {
    const val CANVAS = "editor-canvas"

    // Presets (docs/adr/0019)
    const val PRESET_GRID = "editor-preset-grid"
    const val PRESET_STRENGTH = "editor-preset-strength"
    const val PRESET_SMART = "editor-preset-smart"
    const val PRESET_CREATE = "editor-preset-create"
    const val PRESET_MENU = "editor-preset-menu"
    const val PRESET_EMPTY = "editor-preset-empty"
    const val PRESET_NAME = "editor-preset-name"
    const val PRESET_NEW_GROUP = "editor-preset-new-group"
    const val PRESET_IMPORT = "editor-preset-import"
    const val PRESET_IMPORT_LINK = "editor-preset-import-link"
    const val PRESET_LINK_FIELD = "editor-preset-link-field"
    const val PRESET_LINK_CONTINUE = "editor-preset-link-continue"
    const val PRESET_LINK_ERROR = "editor-preset-link-error"
    const val PRESET_LINK_CONFIRM = "editor-preset-link-confirm"
    const val PRESET_LINK_HOST = "editor-preset-link-host"
    const val PRESET_LINK_PROGRESS = "editor-preset-link-progress"
    const val PRESET_LINK_CANCEL = "editor-preset-link-cancel"
    const val PRESET_KEEP_PACK = "editor-preset-keep-pack"
    const val PRESET_REMOVE_PACK = "editor-preset-remove-pack"
    const val PRESET_SECTIONS = "editor-preset-section"

    /** A preset cell by the preset's id. */
    fun preset(uuid: String): String = "editor-preset-$uuid"

    /** A presets chip: a group's uuid, `favourites` or `recent`. */
    fun presetChip(key: String): String = "editor-preset-chip-$key"

    /** A row of a preset's or group's options sheet ("rename", "delete", "favourite", …). */
    fun presetOption(name: String): String = "editor-preset-option-$name"
    const val PREVIEW = "editor-preview"
    const val LOADING = "editor-loading"
    const val DEVELOPED = "editor-developed"
    const val DETAIL_HINT = "editor-detail-hint"
    const val ERROR = "editor-error"
    const val ERROR_BODY = "editor-error-body"
    const val ERROR_OPEN_APP = "editor-error-open-app"
    const val ERROR_GALLERY = "editor-error-gallery"
    const val PREVIEW_ONLY = "editor-preview-only"
    const val TOP_BAR = "editor-top-bar"
    const val COMPARE = "editor-compare"
    const val MORE = "editor-more"
    const val UNDO = "editor-undo"
    const val REDO = "editor-redo"
    const val EXPORT = "editor-export"
    const val PANEL = "editor-panel"
    const val PANEL_HEADER = "editor-panel-header"
    const val PANEL_RESET = "editor-panel-reset"
    const val PANEL_RESIZE = "editor-panel-resize"
    const val FILMSTRIP = "editor-filmstrip"
    const val FILMSTRIP_TOGGLE = "editor-filmstrip-toggle"
    const val HOVER_TOOLTIP = "editor-hover-tooltip"
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

    /** More ⋯ → "Create virtual copy" and "Save snapshot…" (docs/adr/0025). */
    const val MENU_CREATE_COPY = "editor-menu-create-copy"
    const val MENU_SAVE_SNAPSHOT = "editor-menu-save-snapshot"

    /** The History panel's snapshots section, its "Save snapshot" button and each snapshot's row and menu. */
    const val SNAPSHOTS = "editor-snapshots"
    const val SNAPSHOT_SAVE = "editor-snapshot-save"
    const val SNAPSHOT_RENAME = "editor-snapshot-rename"
    const val SNAPSHOT_DELETE = "editor-snapshot-delete"

    fun snapshot(id: Long): String = "editor-snapshot-$id"

    fun snapshotMenu(id: Long): String = "editor-snapshot-menu-$id"
    const val MENU_MASK_LAB = "editor-menu-mask-lab"
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
    const val CROP_TAB_CROP = "editor-crop-tab-crop"
    const val CROP_TAB_TRANSFORM = "editor-crop-tab-transform"
    const val UPRIGHT_FAILED = "editor-upright-failed"
    const val GUIDES_HINT = "editor-guides-hint"
    const val GUIDES_CLEAR = "editor-guides-clear"
    const val OPTICS_PROFILE = "editor-optics-profile"
    const val OPTICS_LENS = "editor-optics-lens"
    const val OPTICS_CA = "editor-optics-ca"
    const val TARGETED_HINT = "editor-targeted-hint"
    const val TARGETED = "editor-targeted"
    const val TONE_CURVE_ROW = "editor-tone-curve-row"
    const val CURVE_EDITOR = "editor-curve"
    const val CURVE_MODE_POINT = "editor-curve-mode-point"
    const val CURVE_MODE_PARAMETRIC = "editor-curve-mode-parametric"
    const val MIXER_ROW = "editor-mixer-row"
    const val GRADING_ROW = "editor-grading-row"
    const val GREY_MIXER_ROW = "editor-grey-mixer-row"
    const val AUTO_MIX = "editor-auto-mix"
    const val GRADING_THREE_WAY = "editor-grading-three-way"

    fun curveChannel(channel: CurveChannel): String = "editor-curve-channel-${channel.name.lowercase()}"

    fun curvePreset(index: Int): String = "editor-curve-preset-$index"

    /** A point handle of the curve editor (its accessibility node). */
    fun curvePoint(index: Int): String = "editor-curve-point-$index"

    fun band(band: ColourBand): String = "editor-band-${band.name.lowercase()}"

    fun treatment(treatment: Treatment): String = "editor-treatment-${treatment.name.lowercase()}"

    fun targetedTarget(target: TargetedTarget): String = "editor-targeted-${target.name.lowercase()}"

    fun gradeRegion(region: GradeRegion): String = "editor-grade-${region.name.lowercase()}"

    fun gradeWheel(region: GradeRegion): String = "editor-grade-wheel-${region.name.lowercase()}"

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

    fun upright(mode: dev.hfps.reveal.pipeline.recipe.UprightMode): String = "editor-upright-${mode.name.lowercase()}"

    /** The filmstrip cell of a photo's version. */
    fun filmstripCell(versionId: Long): String = "editor-filmstrip-$versionId"
}
