package dev.hfps.reveal.feature.editor.tools

/** Release builds have no debug tools: no mask lab, no LiteRT, no models (docs/adr/0021). */
internal object DebugTools {
    val maskLab: EditorTool? = null
}
