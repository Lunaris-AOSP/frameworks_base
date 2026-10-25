/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.ui.media

import android.graphics.Matrix
import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure
import android.graphics.RectF
import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.toPath
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.media.remedia.domain.model.MediaActionModel
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.motionart.FadingLunarisMotionArtVideo
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import java.io.File

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LunarisBoxMediaPlayer(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    hasMultipleSessions: Boolean,
    motionArt: File? = null,
    motionArtPlaying: Boolean = false,
) {
    val artwork = session?.background
    val hasArtwork = artwork != null || motionArt != null
    val foreground = if (hasArtwork) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary =
        if (hasArtwork) Color.White.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val title =
        session?.title?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.lunaris_qs_media_not_playing)
    val progress = remember(session, viewModel) { { session?.let(viewModel::progress) ?: 0f } }
    val titleStyle = MaterialTheme.typography.titleSmallEmphasized
    val labelStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        BoxMediaArtwork(artwork, session?.colorScheme?.background ?: Color.Black)
        FadingLunarisMotionArtVideo(
            file = motionArt,
            crop = true,
            isPlaying = motionArtPlaying,
            modifier =
                Modifier.fillMaxSize().drawWithCache {
                    val tint = session?.colorScheme?.background ?: Color.Black
                    onDrawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = 0.35f))
                        drawRect(tint.copy(alpha = 0.1f))
                    }
                },
        )
        val horizontalPadding = (maxWidth * 0.085f).coerceIn(10.dp, 16.dp)
        val verticalPadding = (maxHeight * 0.08f).coerceIn(8.dp, 14.dp)
        val bottomPadding = if (hasMultipleSessions) 18.dp else verticalPadding
        val metadataHeight =
            with(density) { titleStyle.lineHeight.toDp() + labelStyle.lineHeight.toDp() }
        val timeHeight = with(density) { labelStyle.lineHeight.toDp() }
        val showSubtitle =
            maxHeight -
                verticalPadding -
                bottomPadding -
                maxOf(40.dp, metadataHeight) -
                timeHeight >= 56.dp
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = verticalPadding,
                        bottom = bottomPadding,
                    ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = foreground,
                        style = titleStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    session
                        ?.subtitle
                        ?.takeIf { it.isNotBlank() && showSubtitle }
                        ?.let { subtitle ->
                            Text(
                                text = subtitle,
                                color = secondary,
                                style = labelStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                }
                BoxMediaOutput(session, viewModel, interactive, hasArtwork)
            }
            BoxMediaControls(
                session = session,
                viewModel = viewModel,
                interactive = interactive,
                foreground = foreground,
                hasArtwork = hasArtwork,
                progress = progress,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
            )
            if (session != null) {
                BoxMediaTimestamps(
                    session = session,
                    progress = progress,
                    color = if (hasArtwork) Color.White.copy(alpha = 0.85f) else secondary,
                )
            }
        }
    }
}

@Composable
private fun BoxMediaArtwork(artwork: IconModel?, tint: Color) {
    Crossfade(
        targetState = rememberAxMediaArtwork(artwork).artwork,
        animationSpec = tween(400),
        label = "LunarisBoxMediaArtwork",
        modifier = Modifier.fillMaxSize(),
    ) { currentArtwork ->
        when (currentArtwork) {
            is IconModel.Loaded -> {
                val bitmap = remember(currentArtwork) { currentArtwork.asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.65f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is IconModel.Resource ->
                Image(
                    painter = painterResource(currentArtwork.resId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.65f,
                    modifier = Modifier.fillMaxSize(),
                )
            null -> Unit
        }
        if (currentArtwork != null) {
            Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.1f)))
        }
    }
}

@Composable
private fun BoxMediaOutput(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    hasArtwork: Boolean,
) {
    val shape = RoundedCornerShape(8.dp)
    val background =
        if (hasArtwork) Color.Black.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
    val tint =
        if (hasArtwork) Color.White.copy(alpha = 0.8f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val description =
        session?.outputDevice?.name?.toString()?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(R.string.ax_dynamic_bar_media_output)
    ExpandableContainer(
        controller = rememberExpandableController(color = { Color.Transparent }, shape = shape),
        modifier = Modifier.size(width = 34.dp, height = 40.dp),
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .clip(shape)
                    .clickable(enabled = interactive && session != null, role = Role.Button) {
                        session?.let { viewModel.openOutput(it.outputDevice, expandable) }
                    }
                    .semantics { contentDescription = description },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier =
                    Modifier.clip(shape)
                        .background(background)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val iconModifier = Modifier.size(14.dp).clearAndSetSemantics {}
                if (session != null) {
                    Icon(icon = session.outputDevice.icon, tint = tint, modifier = iconModifier)
                } else {
                    MaterialIcon(
                        painter = painterResource(R.drawable.ic_phone_expressive),
                        contentDescription = null,
                        tint = tint,
                        modifier = iconModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxMediaControls(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    foreground: Color,
    hasArtwork: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val playing = session?.state == MediaSessionState.Playing
    val accent by
        animateColorAsState(
            targetValue =
                if (playing) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            animationSpec = tween(400),
            label = "LunarisBoxMediaAccent",
        )
    val onAccent by
        animateColorAsState(
            targetValue =
                if (playing) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(400),
            label = "LunarisBoxMediaOnAccent",
        )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val ringSize = minOf(74.dp, maxWidth * (74f / 170f), maxHeight)
            val sideSize = minOf(48.dp, (maxWidth - ringSize) / 2, maxHeight)
            val controlIconSize = ringSize * (28f / 74f)
            if (session?.actionButtonLayout == MediaCardActionButtonLayout.SecondaryActionsOnly) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    userScrollEnabled = interactive,
                ) {
                    items(session.additionalActions) { action ->
                        BoxMediaAction(
                            action = action,
                            viewModel = viewModel,
                            interactive = interactive,
                            tint = foreground,
                            visualSize = sideSize,
                            iconSize = minOf(30.dp, sideSize * 0.625f),
                            modifier = Modifier.size(sideSize),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoxMediaAction(
                        action = session?.leftAction,
                        viewModel = viewModel,
                        interactive = interactive,
                        tint = foreground,
                        visualSize = sideSize,
                        iconSize = controlIconSize,
                        imageVector = Icons.Filled.SkipPrevious,
                        description = stringResource(R.string.controls_media_button_prev),
                        modifier = Modifier.size(sideSize),
                    )
                    BoxMediaAction(
                        action = session?.playPauseAction,
                        viewModel = viewModel,
                        interactive = interactive,
                        tint = if (hasArtwork) Color.White else onAccent,
                        visualSize = ringSize * (52f / 74f),
                        iconSize = controlIconSize,
                        background =
                            when {
                                session == null -> Color.Transparent
                                hasArtwork -> Color.White.copy(alpha = 0.3f)
                                else -> accent
                            },
                        imageVector =
                            playPauseIcon(session).takeUnless {
                                session?.state == MediaSessionState.Buffering
                            },
                        description = stringResource(playPauseDescription(session)),
                        pressedScale = 0.86f,
                        progress = progress.takeIf { session != null },
                        ringColor =
                            if (hasArtwork) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(ringSize),
                    )
                    BoxMediaAction(
                        action = session?.rightAction,
                        viewModel = viewModel,
                        interactive = interactive,
                        tint = foreground,
                        visualSize = sideSize,
                        iconSize = controlIconSize,
                        imageVector = Icons.Filled.SkipNext,
                        description = stringResource(R.string.controls_media_button_next),
                        modifier = Modifier.size(sideSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxMediaAction(
    action: MediaActionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    tint: Color,
    visualSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    imageVector: ImageVector? = null,
    description: String? = null,
    pressedScale: Float = 0.82f,
    progress: (() -> Float)? = null,
    ringColor: Color = tint,
) {
    if (action == MediaActionModel.ReserveSpace) {
        Spacer(modifier)
        return
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val enabled = interactive && action is MediaActionModel.Action && action.onClick != null
    val scale by
        animateFloatAsState(
            targetValue = if (pressed && enabled) pressedScale else 1f,
            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
            label = "LunarisBoxMediaPress",
        )
    Box(
        modifier =
            modifier.clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                (action as? MediaActionModel.Action)?.let(viewModel::runAction)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            BoxMediaProgressRing(progress, ringColor, Modifier.fillMaxSize())
        }
        Box(
            modifier =
                Modifier.size(visualSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(background)
                    .alpha(if (enabled) 1f else 0.38f),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = imageVector,
                animationSpec = tween(180),
                label = "LunarisBoxMediaAction",
            ) { vector ->
                if (vector != null) {
                    MaterialIcon(
                        imageVector = vector,
                        contentDescription = description,
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                } else if (action is MediaActionModel.Action) {
                    Icon(icon = action.icon, tint = tint, modifier = Modifier.size(iconSize))
                }
            }
        }
    }
}

@Composable
private fun BoxMediaTimestamps(session: MediaSessionModel, progress: () -> Float, color: Color) {
    val duration = session.durationMs.coerceAtLeast(0L)
    val elapsedSeconds = (progress() * duration).toLong().coerceIn(0L, duration) / 1000L
    val durationSeconds = duration / 1000L
    val elapsed = remember(elapsedSeconds) { DateUtils.formatElapsedTime(elapsedSeconds) }
    val total = remember(durationSeconds) { DateUtils.formatElapsedTime(durationSeconds) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = elapsed,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                text = total,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxMediaProgressRing(
    progress: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val currentProgress = progress().coerceIn(0f, 1f)
    val animatedProgress =
        animateFloatAsState(
            targetValue = currentProgress,
            animationSpec = tween(300),
            label = "LunarisBoxMediaProgress",
        )
    val cookiePath = remember { MaterialShapes.Cookie9Sided.toPath() }
    val cookieBounds = remember(cookiePath) { RectF().also { cookiePath.computeBounds(it, true) } }
    Box(
        modifier
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(currentProgress, 0f..1f) }
            .drawWithCache {
                val stroke = 2.8.dp.toPx()
                val diameter = (size.minDimension - stroke).coerceAtLeast(0f)
                val scale = diameter / maxOf(cookieBounds.width(), cookieBounds.height())
                val matrix =
                    Matrix().apply {
                        postTranslate(-cookieBounds.centerX(), -cookieBounds.centerY())
                        postScale(scale, scale)
                        postTranslate(size.width / 2f, size.height / 2f)
                    }
                val path = AndroidPath(cookiePath).apply { transform(matrix) }
                val outline = path.asComposePath()
                val measure = PathMeasure(path, true)
                val segment = AndroidPath()
                val segmentPath = segment.asComposePath()
                val style = Stroke(width = stroke, cap = StrokeCap.Round)
                onDrawBehind {
                    drawPath(outline, color.copy(alpha = 0.25f), style = style)
                    segment.rewind()
                    if (
                        measure.getSegment(
                            0f,
                            measure.length * animatedProgress.value,
                            segment,
                            true,
                        )
                    ) {
                        drawPath(segmentPath, color, style = style)
                    }
                }
            }
    )
}
