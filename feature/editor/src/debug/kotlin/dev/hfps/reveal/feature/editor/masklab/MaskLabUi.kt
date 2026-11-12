package dev.hfps.reveal.feature.editor.masklab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.tools.ModeHost
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskPrompt
import java.util.Locale
import kotlin.math.min

private const val MAX_ZOOM = 16f

/** The lab's controls: model, mask kind, edge refinement, and what the last run cost. */
@Composable
internal fun MaskLabControls(host: ModeHost, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tint = RevealTheme.colors.primary.toArgb()
    val lab = MaskLabSession
    val rerun = { lab.run(context.assets, tint) }
    LaunchedEffect(Unit) {
        host.photo.renderer?.let { lab.open(it, host.state.edit.recipe, context.assets, tint) }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(stringResource(R.string.mask_lab_model), Modifier.padding(horizontal = 16.dp))
        ChipRow {
            for (m in LabModel.all) {
                val label = when (m) {
                    LabModel.AutoSubject -> stringResource(R.string.mask_lab_auto_subject)
                    LabModel.FaceParts -> stringResource(R.string.mask_lab_face_parts)
                    is LabModel.Plain -> m.spec.key
                }
                RevealChip(
                    label,
                    selected = lab.model == m,
                    onClick = {
                        lab.model = m
                        if (lab.kind !in m.kinds) lab.kind = m.kinds.first()
                        rerun()
                    },
                    modifier = Modifier.testTag("mask-lab-model-${m.tag}"),
                )
            }
        }
        Eyebrow(stringResource(R.string.mask_lab_mask), Modifier.padding(horizontal = 16.dp))
        ChipRow {
            for (k in lab.model.kinds) {
                RevealChip(stringResource(kindLabel(k)), selected = lab.kind == k, onClick = {
                    lab.kind = k
                    rerun()
                }, modifier = Modifier.testTag("mask-lab-kind-$k"))
            }
            RevealChip(stringResource(R.string.mask_lab_refine), selected = lab.refine, onClick = {
                lab.refine = !lab.refine
                rerun()
            })
        }
        Text(
            status(),
            style = RevealTheme.typography.mono,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("mask-lab-status"),
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun status(): String {
    val lab = MaskLabSession
    val r = lab.result
    return when {
        lab.error != null -> stringResource(R.string.mask_lab_error, lab.error.orEmpty())

        lab.running -> stringResource(R.string.mask_lab_running)

        lab.model.needsTap && lab.prompt == null ->
            stringResource(R.string.mask_lab_tap)

        r != null -> stringResource(
            R.string.mask_lab_result,
            r.backend,
            String.format(Locale.ROOT, "%.0f", r.inferenceMs),
            String.format(Locale.ROOT, "%.0f", r.totalMs),
            r.refineMs,
            (r.coverage * 100).toInt(),
        )

        else -> ""
    }
}

private fun kindLabel(kind: MaskKind): Int = when (kind) {
    MaskKind.SUBJECT -> R.string.mask_lab_kind_subject
    MaskKind.BACKGROUND -> R.string.mask_lab_kind_background
    MaskKind.SKY -> R.string.mask_lab_kind_sky
    MaskKind.PEOPLE -> R.string.mask_lab_kind_people
    MaskKind.FACE_SKIN -> R.string.mask_lab_kind_face_skin
    MaskKind.BODY_SKIN -> R.string.mask_lab_kind_body_skin
    MaskKind.SKIN -> R.string.mask_lab_kind_skin
    MaskKind.HAIR -> R.string.mask_lab_kind_hair
    MaskKind.CLOTHES -> R.string.mask_lab_kind_clothes
    MaskKind.EYES -> R.string.mask_lab_kind_eyes
    MaskKind.IRISES -> R.string.mask_lab_kind_irises
    MaskKind.LIPS -> R.string.mask_lab_kind_lips
    MaskKind.TEETH -> R.string.mask_lab_kind_teeth
    MaskKind.EYEBROWS -> R.string.mask_lab_kind_eyebrows
}

/**
 * The photo as the models saw it with the mask tinted over it, fitted in the photo slot; pinch to zoom (to judge
 * edges at 100 %), tap to place MagicTouch's point.
 */
@Composable
internal fun BoxScope.MaskLabOverlay(modifier: Modifier = Modifier) {
    val lab = MaskLabSession
    val context = LocalContext.current
    val tint = RevealTheme.colors.primary.toArgb()
    val photo = lab.photo
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        modifier
            .matchParentSize()
            .background(RevealTheme.colors.background)
            .onSizeChanged { size = it }
            .transformable(transform)
            .testTag("mask-lab-overlay"),
    ) {
        if (photo == null) return@Box
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(photo) {
                    detectTapGestures { p ->
                        val k = min(size.width / photo.width.toFloat(), size.height / photo.height.toFloat())
                        val x = (p.x - (size.width - photo.width * k) / 2f) / (photo.width * k)
                        val y = (p.y - (size.height - photo.height * k) / 2f) / (photo.height * k)
                        if (x in 0f..1f && y in 0f..1f) {
                            lab.prompt = MaskPrompt(x, y)
                            if (lab.model.needsTap) {
                                lab.run(context.assets, tint)
                            }
                        }
                    }
                },
        ) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            lab.result?.let { Image(it.tint, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
}
