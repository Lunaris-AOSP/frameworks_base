@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.CompositionLocalProvider

import android.view.MotionEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.res.R
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.islandevents.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

private val KeyguardMediaMaxHeight = 640.dp
private val KeyguardMediaArtworkMaxSize = 340.dp

@Composable
internal fun AnimatedVisibilityScope.KeyguardExpandedContent(
    event: IslandEvent,
    allEvents: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    if (event is IslandEvent.KeyguardIndication ||
        event is IslandEvent.AppSwitch ||
        event is IslandEvent.AospChip
    ) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }

    val view = LocalView.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val currentOnCollapse by rememberUpdatedState(onCollapse)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter {
                when (it.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            .pointerInput(touchSlop) {
                awaitEachGesture {
                    var downEvent = awaitPointerEvent(PointerEventPass.Final)
                    while (downEvent.changes.none { it.changedToDownIgnoreConsumed() }) {
                        downEvent = awaitPointerEvent(PointerEventPass.Final)
                    }
                    val down = downEvent.changes.first { it.changedToDownIgnoreConsumed() }
                    val downPosition = down.position
                    val downConsumed = down.isConsumed
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) {
                            if (!downConsumed && !change.isConsumed) {
                                val dx = change.position.x - downPosition.x
                                val dy = change.position.y - downPosition.y
                                if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                                    change.consume()
                                    currentOnCollapse()
                                }
                            }
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (event is IslandEvent.Media) {
            KeyguardMediaPanel(event, interactor, Modifier.align(Alignment.BottomCenter))
        } else {
            val motionScheme = MaterialTheme.motionScheme
            val blurAlpha by transition.animateFloat(
                transitionSpec = {
                    if (targetState == EnterExitState.Visible) motionScheme.defaultEffectsSpec()
                    else tween(250)
                },
                label = "keyguard_panel_blur_alpha",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            val parentBlurAlpha = LocalBlurAlpha.current
            CompositionLocalProvider(LocalBlurAlpha provides parentBlurAlpha * blurAlpha) {
                when (event) {
                    is IslandEvent.Timer -> KeyguardTimerPanel(event, interactor)
                    is IslandEvent.Stopwatch -> KeyguardStopwatchPanel(event, interactor)
                    is IslandEvent.AudioRecording -> KeyguardAudioRecordingPanel(event, interactor)
                    else -> KeyguardGenericPanel(event, interactor, hapticsViewModelFactory)
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = color.copy(alpha = AlphaFaint),
    strokeWidth: Dp = 10.dp,
    handleRadius: Dp = 8.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val handle = handleRadius.toPx()
        val pad = handle.coerceAtLeast(stroke / 2)
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        val topLeft = Offset(pad, pad)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            val sweep = 360f * progress
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val angleRad = Math.toRadians((-90.0 + sweep)).toFloat()
            val cx = size.width / 2 + (arcSize.width / 2) * cos(angleRad)
            val cy = size.height / 2 + (arcSize.height / 2) * sin(angleRad)
            drawCircle(color = color, radius = handle, center = Offset(cx, cy))
        }
    }
}

@Composable
private fun KeyguardPanelSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(max = ExpandedMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = SpaceSection)
            .clip(ShapeXl)
            .border(1.dp, CardBorderBrush, ShapeXl)
            .islandBlur(cornerRadius = RadiusXl, fallbackColor = CardBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        content()
    }
}

@Composable
private fun TonalBanner(
    colors: IslandColorScheme,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeLg)
            .background(colors.tonal)
            .padding(horizontal = SpaceXxl, vertical = SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        content()
    }
}

@Composable
private fun KeyguardMediaPanel(
    event: IslandEvent.Media,
    interactor: IslandActions,
    modifier: Modifier,
) {
    val colors = rememberMediaColors(event, useAlbumArt = true)
    val motionScheme = MaterialTheme.motionScheme

    BoxWithConstraints(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .widthIn(max = ExpandedMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = SpaceSection, vertical = SpaceLg)
            .heightIn(max = KeyguardMediaMaxHeight)
            .clip(ShapeXl)
            .background(CardBg)
            .border(1.dp, CardBorderBrush, ShapeXl)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        val compact = maxHeight < 400.dp
        val panelPadding = if (compact) SpaceLg else SpaceSection
        val sectionSpacing = if (compact) SpaceMd else SpaceLg

        Crossfade(
            targetState = event.albumArt,
            modifier = Modifier.matchParentSize(),
            animationSpec = motionScheme.defaultEffectsSpec(),
            label = "kg_media_backdrop",
        ) { art ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (art != null) {
                    Image(
                        bitmap = art.toSquareScaledBitmap(350.dp),
                        contentDescription = null,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.15f
                                scaleY = 1.15f
                            }
                            .blur(32.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Brush.verticalGradient(listOf(colors.tonal, CardBg)))
                    )
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.30f),
                                0.45f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.88f),
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(colors.accent.copy(alpha = 0.08f))
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(panelPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Measure the controls first, then fit a square into the remaining height.
            // Do not fill the slot: surplus height would leave a gap below the artwork.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = event.albumArt,
                    modifier = Modifier.size(minOf(maxWidth, maxHeight, KeyguardMediaArtworkMaxSize)),
                    animationSpec = motionScheme.defaultEffectsSpec(),
                    label = "kg_media_album_art",
                ) { art ->
                    if (art != null) {
                        Image(
                            bitmap = art.toSquareScaledBitmap(KeyguardMediaArtworkMaxSize),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(ShapeLg),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(ShapeLg)
                                .background(colors.tonal),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                null,
                                tint = colors.accent.copy(alpha = AlphaDisabled),
                                modifier = Modifier.size(SpacePanelLarge),
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(sectionSpacing))

                val trackText = event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) }
                AnimatedContent(
                    targetState = trackText,
                    transitionSpec = {
                        fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                            fadeOut(motionScheme.fastEffectsSpec()) using
                            SizeTransform(clip = false)
                    },
                    label = "kg_media_track",
                ) { title ->
                    Text(
                        title,
                        color = Color.White,
                        style = if (compact) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (event.artist.isNotEmpty()) {
                    Spacer(Modifier.height(SpaceMd))
                    AnimatedContent(
                        targetState = event.artist,
                        transitionSpec = {
                            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                fadeOut(motionScheme.fastEffectsSpec()) using
                                SizeTransform(clip = false)
                        },
                        label = "kg_media_artist",
                    ) { artist ->
                        Text(
                            artist,
                            color = Color.White.copy(alpha = AlphaSecondary),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(sectionSpacing))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CardBorderBrush, ShapeCard),
                    shape = ShapeCard,
                    color = Color.Black.copy(alpha = 0.22f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = panelPadding, vertical = sectionSpacing),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                            ) {
                                event.appIcon?.let { icon ->
                                    Image(
                                        bitmap = icon.toScaledBitmap(SizeIconSm),
                                        contentDescription = null,
                                        modifier = Modifier.size(SizeIconSm).clip(ShapeXs),
                                        colorFilter = ColorFilter.tint(colors.accent),
                                    )
                                } ?: Icon(
                                    Icons.Filled.MusicNote,
                                    null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(SizeIconSm),
                                )
                                Text(
                                    event.outputDeviceName.ifBlank {
                                        stringResource(R.string.ax_dynamic_bar_now_playing)
                                    },
                                    color = colors.accent,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Surface(
                                onClick = {
                                    interactor.openMediaOutputSwitcher()
                                    interactor.collapseIsland()
                                },
                                shape = ShapeChip,
                                color = colors.accent.copy(alpha = AlphaSubtle),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceSm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(SpaceXs),
                                ) {
                                    Icon(
                                        Icons.Filled.VolumeUp,
                                        stringResource(R.string.ax_dynamic_bar_output),
                                        tint = colors.accent,
                                        modifier = Modifier.size(SizeIconSm),
                                    )
                                }
                            }
                        }

                        if (event.duration > 0L) {
                            Spacer(Modifier.height(SpaceMd))
                            val mediaProgress = rememberMediaProgress(event)
                            var isSeeking by remember(event.track, event.artist, event.duration) {
                                mutableStateOf(false)
                            }
                            var seekProgress by remember(event.track, event.artist, event.duration) {
                                mutableFloatStateOf(mediaProgress.progress)
                            }
                            if (!isSeeking) seekProgress = mediaProgress.progress
                            val displayMs =
                                if (isSeeking) (seekProgress * event.duration).toLong()
                                else mediaProgress.positionMs

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SpaceSection)
                                    .pointerInput(interactor, event.track, event.artist, event.duration) {
                                        detectTapGestures { offset ->
                                            val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            seekProgress = f
                                            interactor.seekTo((f * event.duration).toLong())
                                        }
                                    }
                                    .pointerInput(interactor, event.track, event.artist, event.duration) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { offset ->
                                                isSeeking = true
                                                seekProgress =
                                                    (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            },
                                            onDragEnd = {
                                                isSeeking = false
                                                interactor.seekTo((seekProgress * event.duration).toLong())
                                            },
                                            onDragCancel = { isSeeking = false },
                                            onHorizontalDrag = { change, _ ->
                                                seekProgress =
                                                    (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                                change.consume()
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                LinearWavyProgressIndicator(
                                    progress = { seekProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colors.accent,
                                    trackColor = colors.accent.copy(alpha = AlphaSubtle),
                                    amplitude = { if (event.isPlaying) 1f else 0f },
                                )
                            }

                            Spacer(Modifier.height(SpaceXs))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatElapsedTime(displayMs),
                                    color = Color.White.copy(alpha = AlphaSecondary),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    formatElapsedTime(event.duration),
                                    color = Color.White.copy(alpha = AlphaSecondary),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }

                        Spacer(Modifier.height(SpaceMd))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            var customAction1ToggleCount by remember { mutableIntStateOf(0) }
                            var prevToggleCount by remember { mutableIntStateOf(0) }
                            var playPauseToggleCount by remember { mutableIntStateOf(0) }
                            var nextToggleCount by remember { mutableIntStateOf(0) }
                            var customAction2ToggleCount by remember { mutableIntStateOf(0) }
                            IconButton(
                                onClick = {
                                    event.customActions.firstOrNull()?.let {
                                        interactor.sendCustomAction(it.action)
                                    }
                                    customAction1ToggleCount++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .size(SizeButtonLg)
                                    .squishAnimation(customAction1ToggleCount),
                            ) {
                                val ca = event.customActions.firstOrNull()
                                if (ca != null) {
                                    CustomActionIcon(ca, tint = colors.accent, modifier = Modifier.size(SizeIconMd))
                                } else {
                                    Icon(
                                        Icons.Filled.Shuffle,
                                        stringResource(R.string.ax_dynamic_bar_shuffle),
                                        tint = colors.accent.copy(alpha = AlphaDisabled),
                                        modifier = Modifier.size(SizeIconMd),
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    interactor.skipPrev()
                                    prevToggleCount++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .size(SizeButtonLg)
                                    .squishAnimation(prevToggleCount),
                            ) {
                                Icon(
                                    Icons.Filled.SkipPrevious,
                                    stringResource(R.string.ax_dynamic_bar_previous),
                                    tint = colors.accent,
                                    modifier = Modifier.size(SizeIconMd),
                                )
                            }

                            Surface(
                                onClick = {
                                    interactor.togglePlayPause()
                                    playPauseToggleCount++
                                },
                                modifier = Modifier
                                    .size(SizeButtonLg)
                                    .squishAnimation(playPauseToggleCount),
                                shape = CircleShape,
                                color = colors.accent,
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    AnimatedContent(
                                        targetState = event.isPlaying,
                                        transitionSpec = {
                                            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                                fadeOut(motionScheme.fastEffectsSpec())
                                        },
                                        label = "kg_media_playpause",
                                    ) { playing ->
                                        Icon(
                                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            if (playing) stringResource(R.string.ax_dynamic_bar_pause)
                                            else stringResource(R.string.ax_dynamic_bar_play),
                                            tint = colors.onAccent,
                                            modifier = Modifier.size(SizeIconMd),
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {
                                    interactor.skipNext()
                                    nextToggleCount++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .size(SizeButtonLg)
                                    .squishAnimation(nextToggleCount),
                            ) {
                                Icon(
                                    Icons.Filled.SkipNext,
                                    stringResource(R.string.ax_dynamic_bar_next),
                                    tint = colors.accent,
                                    modifier = Modifier.size(SizeIconMd),
                                )
                            }

                            IconButton(
                                onClick = {
                                    event.customActions.getOrNull(1)?.let {
                                        interactor.sendCustomAction(it.action)
                                    }
                                    customAction2ToggleCount++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .size(SizeButtonLg)
                                    .squishAnimation(customAction2ToggleCount),
                            ) {
                                val ca = event.customActions.getOrNull(1)
                                if (ca != null) {
                                    CustomActionIcon(ca, tint = colors.accent, modifier = Modifier.size(SizeIconMd))
                                } else {
                                    Icon(
                                        Icons.Filled.Shuffle,
                                        stringResource(R.string.ax_dynamic_bar_shuffle),
                                        tint = colors.accent.copy(alpha = AlphaDisabled),
                                        modifier = Modifier.size(SizeIconMd),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyguardTimerPanel(event: IslandEvent.Timer, interactor: IslandActions) {
    val context = LocalContext.current
    val colors = rememberIslandColors(event)
    var remainingMs by remember(event.endTimeMs) {
        mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    if (!event.isPaused) {
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
    }

    val elapsedFraction = if (event.originalDurationMs > 0L)
        (remainingMs.toFloat() / event.originalDurationMs).coerceIn(0f, 1f)
    else 0f

    val pulseTransition = rememberInfiniteTransition(label = "timer_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f, targetValue = if (remainingMs < 10_000L) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "timer_pulse_scale",
    )

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = AlphaSubtle)),
                contentAlignment = Alignment.Center,
            ) {
                eventStyleFor(event).icon?.let { Icon(it, null, tint = colors.accent, modifier = Modifier.size(18.dp)) }
            }
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(SizeAlbumLg)
                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale },
        ) {
            ProgressRing(
                progress = elapsedFraction,
                color = colors.accent,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                if (event.isPaused) stringResource(R.string.ax_dynamic_bar_paused) else formatCountdownLong(remainingMs),
                color = OnCardText,
                style = MaterialTheme.typography.displayMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            val toggleAction = event.actions.firstOrNull()
            if (toggleAction != null) {
                ExpressivePillButton(
                    label = if (event.isPaused) stringResource(R.string.ax_dynamic_bar_resume) else stringResource(R.string.ax_dynamic_bar_pause),
                    icon = if (event.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentColor = colors.onAccent,
                    backgroundColor = colors.accent,
                    modifier = Modifier.weight(1f),
                    onClick = { toggleAction.action.actionIntent?.sendWithBal(context) },
                )
            }
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                icon = Icons.Filled.Stop,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardStopwatchPanel(event: IslandEvent.Stopwatch, interactor: IslandActions) {
    val context = LocalContext.current
    val colors = rememberIslandColors(event)
    var elapsedMs by remember(event.startTimeMs) {
        mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
    }
    if (event.isRunning) {
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
    }

    val secFraction = if (event.isRunning) (elapsedMs % 60000) / 60000f else 0f

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = AlphaSubtle)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AvTimer, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_stopwatch) }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SizeAlbumLg)) {
            ProgressRing(
                progress = secFraction,
                color = colors.accent,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                if (event.isRunning) formatStopwatch(elapsedMs) else stringResource(R.string.ax_dynamic_bar_paused),
                color = OnCardText,
                style = MaterialTheme.typography.displayMedium,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            val toggleAction = event.actions.firstOrNull()
            if (toggleAction != null) {
                ExpressivePillButton(
                    label = if (event.isRunning) stringResource(R.string.ax_dynamic_bar_pause) else stringResource(R.string.ax_dynamic_bar_resume),
                    icon = if (event.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentColor = colors.onAccent,
                    backgroundColor = colors.accent,
                    modifier = Modifier.weight(1f),
                    onClick = { toggleAction.action.actionIntent?.sendWithBal(context) },
                )
            }
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_reset),
                icon = Icons.Filled.Stop,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardAudioRecordingPanel(event: IslandEvent.AudioRecording, interactor: IslandActions) {
    val context = LocalContext.current
    val colors = rememberIslandColors(event)
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(event.startTimeMs, event.state, event.pausedDurationMs) {
        if (event.state == RecordingState.RECORDING) {
            while (true) {
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                    .coerceAtLeast(0L)
                delay(1000)
            }
        } else {
            elapsedMs = (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }

    val isRecording = event.state == RecordingState.RECORDING

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(colors) {
            if (isRecording) PulsingDot(color = colors.accent, size = SpaceMd)
            Icon(Icons.Filled.Mic, null, tint = colors.accent, modifier = Modifier.size(SizeIconSm))
            Text(
                when (event.state) {
                    RecordingState.RECORDING -> stringResource(R.string.ax_dynamic_bar_recording)
                    RecordingState.PAUSED -> stringResource(R.string.ax_dynamic_bar_paused)
                    RecordingState.SAVED -> stringResource(R.string.ax_dynamic_bar_saved)
                }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Text(
            formatElapsedTime(elapsedMs),
            color = OnCardText,
            style = MaterialTheme.typography.displayLarge,
        )

        if (isRecording) {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(ShapeChip),
                color = colors.accent,
                trackColor = colors.accent.copy(alpha = AlphaFaint),
            )
        } else {
            LinearWavyProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().clip(ShapeChip),
                color = colors.accent.copy(alpha = AlphaDisabled),
                trackColor = colors.accent.copy(alpha = AlphaFaint),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            val classifiedActions = remember(event.actions, context) {
                event.actions.map { action ->
                    val pkg = action.action.actionIntent?.creatorPackage ?: context.packageName
                    action to action.action.classify(context, pkg)
                }
            }
            val pauseResume = classifiedActions.firstOrNull { (_, kind) ->
                kind == NotificationActionType.PAUSE || kind == NotificationActionType.RESUME
            }?.first
            val stop = classifiedActions.firstOrNull { (_, kind) ->
                kind == NotificationActionType.STOP || kind == NotificationActionType.DELETE
            }?.first
            if (pauseResume != null) {
                ExpressivePillButton(
                    label = if (isRecording) stringResource(R.string.ax_dynamic_bar_pause) else stringResource(R.string.ax_dynamic_bar_resume),
                    icon = if (isRecording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentColor = colors.onAccent,
                    backgroundColor = colors.accent,
                    modifier = Modifier.weight(1f),
                    onClick = { pauseResume.action.actionIntent?.sendWithBal(context) },
                )
            }
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_stop),
                icon = Icons.Filled.Stop,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (stop != null) {
                        stop.action.actionIntent?.sendWithBal(context)
                        interactor.collapseIsland()
                    } else {
                        interactor.dismissEvent(event)
                    }
                },
            )
        }
    }
}
}

@Composable
private fun KeyguardGenericPanel(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    val colors = rememberIslandColors(event)
        KeyguardPanelSurface { Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpaceSection),
            verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        ) {
            ExpandedEventContent(event, interactor, hapticsViewModelFactory)

            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                contentColor = colors.onAccent,
                backgroundColor = colors.accent,
                modifier = Modifier.fillMaxWidth(),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}

@Composable
private fun Modifier.squishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)
    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest {
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 400
                                1.066f at 120 using FastOutSlowInEasing
                                0.967f at 260
                                1f at 400
                            },
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 400
                                0.945f at 120 using FastOutSlowInEasing
                                1.033f at 260
                                1f at 400
                            },
                        )
                    }
                }
            }
    }
    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}
