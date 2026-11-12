package dev.hfps.reveal.feature.editor.tools

import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.masklab.MaskLabControls
import dev.hfps.reveal.feature.editor.masklab.MaskLabOverlay
import dev.hfps.reveal.feature.editor.masklab.MaskLabSession

/**
 * Debug-only editor tools. The mask lab (docs/adr/0021, M4 spike) is a mode opened from More ⋯: it runs each bundled
 * mask model on the open photo and tints the mask, so models can be judged on real photos before M4's masks exist.
 */
internal object DebugTools {
    val maskLab: EditorTool? = EditorTool(
        ToolId.MASK_LAB,
        R.string.mask_lab_title,
        PhosphorRegular.Selection,
        PhosphorFill.Selection,
        ToolBehaviour.Mode(
            title = R.string.mask_lab_title,
            controls = { MaskLabControls(it) },
            overlay = { MaskLabOverlay() },
            done = { MaskLabSession.close() },
            cancel = { MaskLabSession.close() },
        ),
    )
}
