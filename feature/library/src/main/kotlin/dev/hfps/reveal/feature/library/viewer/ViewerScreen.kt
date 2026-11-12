package dev.hfps.reveal.feature.library.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hfps.image.decode.IntRect
import dev.hfps.image.decode.Sampling
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.thumb.RegionSource
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.components.UnavailablePlaceholder
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.ConflictSheet
import dev.hfps.reveal.feature.library.ConflictTags
import dev.hfps.reveal.feature.library.ForgetDialog
import dev.hfps.reveal.feature.library.R
import dev.hfps.reveal.feature.library.TwoPaneMinWidth
import dev.hfps.reveal.feature.library.UnavailableSheet
import dev.hfps.reveal.feature.library.UnavailableTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full-screen viewer (always dark, zero-chroma surround like the editor): swipe between photos (pairs are one
 * page), pinch or double-tap to 100 %, tap to hide the controls, rate and flag, info sheet, "Edit".
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onEdit: (versionId: Long, strip: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages: ViewerPages = viewModel
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.tryAgain(viewModel.state.value.unavailable?.photoId ?: return@rememberLauncherForActivityResult)
    }
    EditorTheme {
        BoxWithConstraints(modifier.fillMaxSize().background(RevealTheme.colors.background)) {
            val wide = maxWidth >= TwoPaneMinWidth
            // The info side panel closes on back before the viewer does (the phone's sheet has its own window).
            BackHandler(enabled = wide && info != null) { info = null }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (!state.loaded) return@Box
                    val pager = rememberPagerState(initialPage = state.start) { state.ids.size }
                    var zoomed by remember { mutableStateOf(false) }
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = !zoomed,
                        key = { state.ids[it] },
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize().testTag(ViewerTags.PAGER),
                    ) { page ->
                        val id = state.ids[page]
                        ViewerPage(
                            id = id,
                            pages = pages,
                            reloads = state.reloads,
                            onExplain = { viewModel.explain(id) },
                            active = pager.currentPage == page,
                            onTap = { chrome = !chrome },
                            onZoomChange = { if (pager.currentPage == page) zoomed = it },
                        )
                    }
                    val currentId = state.ids.getOrNull(pager.currentPage)
                    val photo by remember(currentId) { currentId?.let(viewModel::photo) ?: flowOf(null) }
                        .collectAsStateWithLifecycle(null)
                    ViewerTopChrome(
                        visible = chrome,
                        title = photo?.name.orEmpty(),
                        position = pager.currentPage + 1,
                        total = state.ids.size,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        chrome,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        photo?.let { p ->
                            val conflict by produceState(false, p.id, state.conflictsResolved) {
                                value = viewModel.hasConflict(p.id)
                            }
                            ViewerControls(
                                photo = p,
                                onRate = { viewModel.rate(p.id, it) },
                                onFlag = { viewModel.flag(p.id, it) },
                                onInfo = { info = p.id },
                                onResolveConflict = if (conflict) {
                                    { viewModel.openConflict(p.id, p.name) }
                                } else {
                                    null
                                },
                                onEdit = {
                                    scope.launch { viewModel.versionOf(p.id)?.let { onEdit(it, viewModel.strip) } }
                                },
                            )
                        }
                    }
                    // The side panel follows the page on wide windows.
                    LaunchedEffect(currentId, wide) { if (wide && info != null && currentId != null) info = currentId }
                }
                val shown = info
                if (wide && shown != null) {
                    InfoPanel(
                        photoId = shown,
                        pages = pages,
                        onClose = { info = null },
                        modifier = Modifier.width(InfoPanelWidth).fillMaxHeight().systemBarsPadding(),
                    )
                }
            }
            state.unavailable?.let { unavailable ->
                UnavailableSheet(
                    info = unavailable,
                    onOpenApp = viewModel::openSender,
                    onGallery = { galleryPermission.launch(GalleryAccess.permissions()) },
                    onTryAgain = { viewModel.tryAgain(unavailable.photoId) },
                    onRemove = { viewModel.forget(unavailable.photoId, onBack) },
                    onDismiss = viewModel::dismissUnavailable,
                )
            }
            state.conflict?.let { choice ->
                ConflictSheet(
                    choice = choice,
                    thumbnail = { side -> viewModel.conflictThumbnail(choice.pair.photoId, side) },
                    onKeep = viewModel::keepConflict,
                    onDismiss = viewModel::dismissConflict,
                )
            }
            state.forgetPrompt?.let { prompt ->
                ForgetDialog(
                    prompt,
                    onConfirm = { viewModel.confirmForget(onBack) },
                    onDismiss = viewModel::dismissForget,
                )
            }
            if (!wide) info?.let { id -> InfoSheet(photoId = id, pages = pages, onDismiss = { info = null }) }
        }
    }
}

private val InfoPanelWidth = 360.dp

/** The top bar over the photo: back, file name, position. Hidden with a tap on the photo. */
@Composable
private fun ViewerTopChrome(
    visible: Boolean,
    title: String,
    position: Int,
    total: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .background(RevealTheme.colors.background.copy(alpha = CHROME_ALPHA))
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            RevealTopBar(
                title = title,
                titleStyle = RevealTheme.typography.mono,
                onBack = onBack,
                backContentDescription = stringResource(R.string.library_back),
                actions = {
                    Text(
                        stringResource(R.string.viewer_position, position, total),
                        style = RevealTheme.typography.ui,
                        color = RevealTheme.colors.mutedForeground,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                },
            )
        }
    }
}

private const val CHROME_ALPHA = 0.72f

/** Smallest display decode, in case the window size is not known yet. */
private const val MIN_DISPLAY_EDGE = 1080
private const val MAX_STARS = 5

@Suppress("LongMethod")
@Composable
private fun ViewerControls(
    photo: PhotoEntity,
    onRate: (Int) -> Unit,
    onFlag: (PickFlag) -> Unit,
    onInfo: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onResolveConflict: (() -> Unit)? = null,
) {
    val colors = RevealTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background.copy(alpha = CHROME_ALPHA))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RevealIconButton(
                icon = if (photo.flag == PickFlag.REJECT) PhosphorFill.XCircle else PhosphorRegular.XCircle,
                contentDescription = stringResource(R.string.library_action_reject),
                onClick = { onFlag(if (photo.flag == PickFlag.REJECT) PickFlag.NONE else PickFlag.REJECT) },
                tint = if (photo.flag == PickFlag.REJECT) colors.destructive else colors.foreground,
            )
            Row {
                for (n in 1..MAX_STARS) {
                    val on = photo.rating >= n
                    RevealIconButton(
                        icon = if (on) PhosphorFill.Star else PhosphorRegular.Star,
                        contentDescription = pluralStringResource(R.plurals.library_stars, n, n),
                        onClick = { onRate(if (photo.rating == n) 0 else n) },
                        tint = if (on) colors.warning else colors.foreground,
                        modifier = Modifier.testTag(ViewerTags.star(n)),
                    )
                }
            }
            RevealIconButton(
                icon = if (photo.flag == PickFlag.PICK) PhosphorFill.CheckCircle else PhosphorRegular.CheckCircle,
                contentDescription = stringResource(R.string.library_action_pick),
                onClick = { onFlag(if (photo.flag == PickFlag.PICK) PickFlag.NONE else PickFlag.PICK) },
                tint = if (photo.flag == PickFlag.PICK) colors.primary else colors.foreground,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RevealIconButton(
                icon = PhosphorRegular.Info,
                contentDescription = stringResource(R.string.viewer_info),
                onClick = onInfo,
                modifier = Modifier.testTag(ViewerTags.INFO),
            )
            Box(Modifier.weight(1f))
            if (onResolveConflict != null) {
                SecondaryButton(
                    text = stringResource(R.string.viewer_resolve_conflict),
                    onClick = onResolveConflict,
                    icon = PhosphorRegular.GitBranch,
                    modifier = Modifier.padding(end = 8.dp).testTag(ConflictTags.VIEWER_RESOLVE),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.viewer_edit),
                onClick = onEdit,
                icon = PhosphorRegular.PencilSimple,
                modifier = Modifier.testTag(ViewerTags.EDIT),
            )
        }
    }
}

/** One page: the screen-size image at once, full-resolution detail when zoomed past it. */
@Composable
private fun ViewerPage(
    id: Long,
    pages: ViewerPages,
    reloads: Int,
    onExplain: () -> Unit,
    active: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
) {
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)
    val window = LocalWindowInfo.current.containerSize
    val longEdge = maxOf(window.width, window.height).coerceAtLeast(MIN_DISPLAY_EDGE)
    val image by produceState<Loaded?>(null, id, reloads) {
        val display = pages.display(id, longEdge)
        value = if (display == null) {
            if (pages.isUnavailable(id)) {
                Loaded.Unavailable(pages.detail(id)?.photo?.name.orEmpty())
            } else {
                Loaded.Failed
            }
        } else {
            Loaded.Image(
                display.bitmap.asImageBitmap(),
                IntSize(display.fullWidth, display.fullHeight),
                display.developed,
            )
        }
    }
    val zoom = remember(id) { ZoomState() }
    LaunchedEffect(zoom, active) { snapshotFlow { zoom.zoomed }.collect { currentOnZoomChange(it && active) } }
    if (!active) {
        LaunchedEffect(Unit) {
            zoom.scale = 1f
            zoom.offset = androidx.compose.ui.geometry.Offset.Zero
        }
    }
    when (val loaded = image) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RevealTheme.colors.primary)
        }

        Loaded.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.viewer_unavailable),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
        }

        is Loaded.Unavailable -> UnavailablePage(loaded.name, onExplain)

        is Loaded.Image -> {
            val detail = rememberDetail(id, loaded, zoom, pages, active)
            ZoomableImage(
                base = loaded.bitmap,
                fullSize = loaded.fullSize,
                state = zoom,
                detail = detail,
                onTap = onTap,
            )
        }
    }
}

/** The centred placeholder of a photo nothing can show, with what happened and a way to the actions. */
@Composable
private fun UnavailablePage(name: String, onExplain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(UnavailableBox).clip(RevealTheme.shapes.card)) {
            UnavailablePlaceholder(name, stringResource(R.string.library_unavailable_label))
        }
        Text(
            stringResource(R.string.viewer_unavailable_title),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            stringResource(R.string.viewer_unavailable_body),
            style = RevealTheme.typography.body,
            color = RevealTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        PrimaryButton(
            text = stringResource(R.string.viewer_unavailable_details),
            onClick = onExplain,
            icon = PhosphorRegular.Info,
            modifier = Modifier.testTag(UnavailableTags.VIEWER_DETAILS),
        )
    }
}

private val UnavailableBox = 180.dp

private sealed interface Loaded {
    data object Failed : Loaded

    /** No file, no preview: the placeholder with its explanation (FIX/U). */
    class Unavailable(val name: String) : Loaded

    class Image(val bitmap: ImageBitmap, val fullSize: IntSize, val developed: Boolean) : Loaded
}

/**
 * The full-resolution detail of the visible area, decoded after the gesture settles: regions of the camera
 * rendering (JPEG/HEIF), or for a RAW alone a larger develop render once.
 */
@Composable
private fun rememberDetail(
    id: Long,
    loaded: Loaded.Image,
    zoom: ZoomState,
    pages: ViewerPages,
    active: Boolean,
): DetailTile? {
    var detail by remember(id) { mutableStateOf<DetailTile?>(null) }
    var region by remember(id) { mutableStateOf<RegionSource?>(null) }
    var regionTried by remember(id) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(id) { onDispose { region?.close() } }
    LaunchedEffect(id, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { Triple(zoom.scale, zoom.offset, zoom.viewport) }.collectLatest {
            delay(SETTLE_MS)
            val baseScale = loaded.bitmap.width.toFloat() / loaded.fullSize.width.coerceAtLeast(1)
            // Detail only when the screen shows more pixels than the base image has.
            if (!zoom.zoomed || zoom.screenScale <= baseScale * DETAIL_THRESHOLD ||
                loaded.developed
            ) {
                return@collectLatest
            }
            if (!regionTried) {
                regionTried = true
                region = pages.region(id)
                if (region == null) {
                    // A RAW without its JPEG: develop it larger once (half-size decode) and use it as the detail.
                    val big = pages.developed(id, RAW_DETAIL_EDGE) ?: return@collectLatest
                    detail =
                        DetailTile(
                            big.asImageBitmap(),
                            Rect(0f, 0f, loaded.fullSize.width.toFloat(), loaded.fullSize.height.toFloat()),
                        )
                    return@collectLatest
                }
            }
            val source = region ?: return@collectLatest
            val visible = zoom.visibleImageRect()
            val rect = IntRect(
                visible.left.toInt(),
                visible.top.toInt(),
                visible.right.toInt() + 1,
                visible.bottom.toInt() + 1,
            )
            val sample = Sampling.sampleForScale(zoom.screenScale)
            val bitmap =
                withContext(Dispatchers.IO) { runCatching { source.decode(rect, sample) }.getOrNull() }
                    ?: return@collectLatest
            detail =
                DetailTile(
                    bitmap.asImageBitmap(),
                    Rect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
                )
        }
    }
    return detail
}

private const val SETTLE_MS = 150L
private const val DETAIL_THRESHOLD = 1.1f
private const val RAW_DETAIL_EDGE = 3200
