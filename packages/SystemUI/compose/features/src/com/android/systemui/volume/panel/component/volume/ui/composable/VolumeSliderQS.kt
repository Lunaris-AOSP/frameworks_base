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

package com.android.systemui.volume.panel.component.volume.ui.composable

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.PlatformSlider
import com.android.compose.PlatformSliderDefaults
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme
import com.android.systemui.res.R
import kotlin.math.floor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

private const val RINGER_NORMAL = AudioManager.RINGER_MODE_NORMAL
private const val RINGER_VIBRATE = AudioManager.RINGER_MODE_VIBRATE
private const val RINGER_SILENT = AudioManager.RINGER_MODE_SILENT

private fun ringerModeNext(current: Int, canUseSilent: Boolean): Int = when (current) {
    RINGER_NORMAL -> RINGER_VIBRATE
    RINGER_VIBRATE -> if (canUseSilent) RINGER_SILENT else RINGER_NORMAL
    else -> RINGER_NORMAL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VolumeSliderQS(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val notificationManager = remember {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    val cr = context.contentResolver
    val view = LocalView.current

    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    var currentVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var ringerMode by remember {
        mutableIntStateOf(audioManager.ringerMode)
    }
    var hapticsEnabled by remember {
        mutableStateOf(readQsVolumeHapticsEnabled(cr))
    }
    var useAxStyle by remember {
        mutableStateOf(readQsVolumeUseAxStyle(cr))
    }
    var showRingerButton by remember {
        mutableStateOf(readShowRingerButton(cr))
    }

    val canUseSilent = remember(notificationManager) {
        notificationManager.isNotificationPolicyAccessGranted
    }

    val shapeMode = rememberQsVolumeSliderShapeMode()
    val trackCornerDp: Dp = when (shapeMode) {
        1 -> 28.dp
        2 -> 18.dp
        3 -> 0.dp
        else -> QsVolumeSliderDimensions.SliderTrackRoundedCorner
    }

    val gradient = qsVolumeSliderGradient()

    val animatedValue by animateFloatAsState(
        targetValue = currentVolume.toFloat(),
        animationSpec = QsVolumeSliderSpringSpec,
        label = "QsVolumeSliderAnimatedValue",
    )
    val floatValueRange = 0f..maxVolume.toFloat()

    val hapticStepFraction = 0.1f
    var lastHapticStep by remember {
        mutableFloatStateOf(qsVolumeStepForValue(currentVolume, maxVolume, hapticStepFraction))
    }
    val performStepHaptic: (Int) -> Unit = remember(maxVolume) {
        { newValue ->
            val newStep = qsVolumeStepForValue(newValue, maxVolume, hapticStepFraction)
            if (newStep != lastHapticStep) {
                lastHapticStep = newStep
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.VOLUME_CHANGED_ACTION -> {
                        val streamType = intent.getIntExtra(
                            AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1
                        )
                        if (streamType == AudioManager.STREAM_MUSIC) {
                            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                    }
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        ringerMode = intent.getIntExtra(
                            AudioManager.EXTRA_RINGER_MODE, audioManager.ringerMode
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.VOLUME_CHANGED_ACTION)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    hapticsEnabled = readQsVolumeHapticsEnabled(cr)
                    useAxStyle = readQsVolumeUseAxStyle(cr)
                    showRingerButton = readShowRingerButton(cr)
                }
            }
        }
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_VOLUME_SLIDER_HAPTIC),
            false, observer, UserHandle.USER_ALL,
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_VOLUME_SLIDER_STYLE),
            false, observer, UserHandle.USER_ALL,
        )
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_SHOW_RINGER_BUTTON),
            false, observer, UserHandle.USER_ALL,
        )
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val contentDescription = "Volume"
    val interactionSource = remember { MutableInteractionSource() }

    val iconRes by remember(currentVolume) {
        derivedStateOf {
            if (currentVolume == 0) R.drawable.ic_volume_off
            else R.drawable.ic_volume_media
        }
    }

    val commitVolume: (Int) -> Unit = { v ->
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    var ringerToggleCount by remember { mutableIntStateOf(0) }

    val onRingerClick: () -> Unit = {
        val next = ringerModeNext(ringerMode, canUseSilent)
        try {
            audioManager.ringerModeInternal = next
        } catch (_: Exception) {
            audioManager.ringerMode = next
        }
        ringerMode = next
        ringerToggleCount++
        if (hapticsEnabled) {
            val hapticConstant = when (next) {
                RINGER_SILENT -> HapticFeedbackConstants.TOGGLE_OFF
                else -> HapticFeedbackConstants.TOGGLE_ON
            }
            view.performHapticFeedback(hapticConstant)
        }
    }

    if (useAxStyle) {
        val axIconSize = 56.dp
        val axTrackColor = gradient?.brush?.let { null } ?: CustomColorScheme.current.qsTileColor
        val sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors().copy(
            trackColor = axTrackColor ?: CustomColorScheme.current.qsTileColor,
        )

        val axActiveFraction by animateFloatAsState(
            targetValue = if (maxVolume > 0) animatedValue / maxVolume else 0f,
            animationSpec = QsVolumeSliderFractionSpringSpec,
            label = "AxQsVolumeActiveFraction",
        )

        val axIconTransition = updateTransition(
            targetState = iconRes,
            label = "AxQsVolumeIconTransition"
        )
        val axIconScale by axIconTransition.animateFloat(
            transitionSpec = { QsVolumeIconSwapScaleSpec },
            label = "AxQsVolumeIconScale",
        ) { target -> if (target == iconRes) 1f else 0.85f }

        val ringerIconRes = when (ringerMode) {
            RINGER_NORMAL -> R.drawable.ic_volume_ringer
            RINGER_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
            else -> R.drawable.ic_volume_ringer_mute
        }
        val ringerIconTransition = updateTransition(
            targetState = ringerIconRes,
            label = "AxRingerIconTransition"
        )
        val ringerIconScale by ringerIconTransition.animateFloat(
            transitionSpec = { QsVolumeIconSwapScaleSpec },
            label = "AxRingerIconScale",
        ) { target -> if (target == ringerIconRes) 1f else 0.85f }

        PlatformSlider(
            value = animatedValue,
            onValueChange = { v ->
                currentVolume = v.toInt()
                if (hapticsEnabled) performStepHaptic(currentVolume)
                commitVolume(currentVolume)
            },
            onValueChangeFinished = { commitVolume(currentVolume) },
            valueRange = floatValueRange,
            enabled = true,
            interactionSource = interactionSource,
            colors = sliderColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(axIconSize)
                .sysuiResTag("qs_volume_slider")
                .semantics(mergeDescendants = true) {
                    this.text = AnnotatedString(contentDescription)
                }
                .then(
                    if (gradient != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val activeEnd = (size.width * axActiveFraction).coerceAtLeast(0f)
                            if (activeEnd > 0f) {
                                val cr2 = CornerRadius(28.dp.toPx())
                                val clipPath = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            left = 0f,
                                            top = 0f,
                                            right = activeEnd.coerceAtMost(size.width),
                                            bottom = size.height,
                                            radiusX = cr2.x,
                                            radiusY = cr2.y,
                                        )
                                    )
                                }
                                clipPath(clipPath) {
                                    drawRect(
                                        brush = gradient.brush,
                                        topLeft = Offset.Zero,
                                        size = Size(activeEnd.coerceAtMost(size.width), size.height),
                                    )
                                }
                            }
                        }
                    } else Modifier
                ),
            icon = { _ ->
                val ringerIconRes = when (ringerMode) {
                    RINGER_NORMAL -> R.drawable.ic_volume_ringer
                    RINGER_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
                    else -> R.drawable.ic_volume_ringer_mute
                }
                val ringerIconTransition = updateTransition(
                    targetState = ringerIconRes,
                    label = "AxRingerIconTransition"
                )
                val ringerIconScale by ringerIconTransition.animateFloat(
                    transitionSpec = { QsVolumeIconSwapScaleSpec },
                    label = "AxRingerIconScale",
                ) { target -> if (target == ringerIconRes) 1f else 0.85f }

                if (showRingerButton) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .qsVolumeSquishAnimation(ringerToggleCount)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onRingerClick,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(ringerIconRes),
                            contentDescription = when (ringerMode) {
                                RINGER_NORMAL -> "Ringer normal"
                                RINGER_VIBRATE -> "Ringer vibrate"
                                else -> "Ringer silent"
                            },
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer(scaleX = ringerIconScale, scaleY = ringerIconScale),
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer(scaleX = axIconScale, scaleY = axIconScale),
                    )
                }
            },
        )
        return
    }

    val colors = qsVolumeSliderColors(gradient)
    val trackShape = RoundedCornerShape(trackCornerDp)

    val animatedFraction by animateFloatAsState(
        targetValue = if (maxVolume > 0) animatedValue / maxVolume else 0f,
        animationSpec = QsVolumeSliderFractionSpringSpec,
        label = "QsVolumeTrackFillFraction",
    )

    val musicIconPainter = painterResource(iconRes)

    var showIconActive by remember { mutableStateOf(false) }
    val iconVisibilityTransition =
        updateTransition(
            targetState = showIconActive, 
            label = "QsVolumeIconVisibilityTransition"
        )
    val iconActiveAlpha by iconVisibilityTransition.animateFloat(
        transitionSpec = { 
            if (targetState) QsVolumeIconAppearSpec else QsVolumeIconDisappearSpec 
        },
        label = "QsVolumeIconActiveAlpha",
    ) { active -> if (active) 1f else 0f }
    val iconInactiveAlpha by iconVisibilityTransition.animateFloat(
        transitionSpec = { 
            if (targetState) QsVolumeIconDisappearSpec else QsVolumeIconAppearSpec 
        },
        label = "QsVolumeIconInactiveAlpha",
    ) { active -> if (active) 0f else 1f }

    val activeIconColor  = MaterialTheme.colorScheme.onPrimary
    val inactiveIconColor = MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Slider(
            value = animatedValue,
            valueRange = floatValueRange,
            enabled = true,
            colors = colors,
            onValueChange = { v ->
                currentVolume = v.toInt()
                if (hapticsEnabled) performStepHaptic(currentVolume)
                commitVolume(currentVolume)
            },
            onValueChangeFinished = { commitVolume(currentVolume) },
            modifier = Modifier
                .weight(1f)
                .sysuiResTag("qs_volume_slider")
                .semantics(mergeDescendants = true) {
                    this.text = AnnotatedString(contentDescription)
                },
            interactionSource = interactionSource,
            thumb = {
                Box(modifier = Modifier.size(0.dp))
            },
            track = { _ ->
                Box(
                    modifier = Modifier
                        .height(QsVolumeSliderDimensions.TrackHeight)
                        .fillMaxWidth()
                        .drawWithContent {
                            val trackH = size.height
                            val trackW = size.width
                            val activeEnd = trackW * animatedFraction
                            val cornerRadius = CornerRadius(trackCornerDp.toPx())

                            drawRoundRect(
                                color = colors.inactiveTrackColor,
                                topLeft = Offset.Zero,
                                size = Size(trackW, trackH),
                                cornerRadius = cornerRadius,
                            )

                            if (activeEnd > 0f) {
                                if (gradient != null) {
                                    val outline = trackShape.createOutline(
                                        Size(activeEnd.coerceAtMost(trackW), trackH),
                                        layoutDirection,
                                        this,
                                    )
                                    val clipPath = outline.asQsVolumePath()
                                    clipPath(clipPath) {
                                        drawRect(
                                            brush = gradient.brush,
                                            topLeft = Offset.Zero,
                                            size = Size(activeEnd.coerceAtMost(trackW), trackH),
                                        )
                                    }
                                } else {
                                    drawRoundRect(
                                        color = colors.activeTrackColor,
                                        topLeft = Offset.Zero,
                                        size = Size(activeEnd, trackH),
                                        cornerRadius = cornerRadius,
                                    )
                                }
                            }

                            val iconSize = QsVolumeSliderDimensions.IconSize.toSize()
                            val iconPad  = QsVolumeSliderDimensions.IconPadding.toPx()
                            val yOffset  = trackH / 2 - iconSize.height / 2
                            val inactiveW = trackW - activeEnd

                            if (iconSize.width < inactiveW - iconPad * 2) {
                                showIconActive = false
                                with(musicIconPainter) {
                                    translate(trackW - iconPad - iconSize.width, yOffset) {
                                        draw(
                                            size = iconSize,
                                            colorFilter = ColorFilter.tint(inactiveIconColor),
                                            alpha = iconInactiveAlpha,
                                        )
                                    }
                                }
                            } else if (iconSize.width < activeEnd - iconPad * 2) {
                                showIconActive = true
                                with(musicIconPainter) {
                                    translate(activeEnd - iconPad - iconSize.width, yOffset) {
                                        draw(
                                            size = iconSize,
                                            colorFilter = ColorFilter.tint(activeIconColor),
                                            alpha = iconActiveAlpha,
                                        )
                                    }
                                }
                            }
                        }
                )
            },
        )

        if (showRingerButton) {
            Spacer(modifier = Modifier.width(10.dp))
            RingerModeButton(
                ringerMode = ringerMode,
                toggleCount = ringerToggleCount,
                hapticsEnabled = hapticsEnabled,
                shapeMode = shapeMode,
                gradient = gradient,
                buttonSize = QsVolumeSliderDimensions.TrackHeight,
                onClick = onRingerClick,
            )
        }
    }
}

@Composable
private fun RingerModeButton(
    ringerMode: Int,
    toggleCount: Int,
    hapticsEnabled: Boolean,
    shapeMode: Int,
    gradient: QsVolumeGradient?,
    buttonSize: Dp,
    onClick: () -> Unit,
) {
    val isActive = ringerMode != RINGER_SILENT

    val animatedCornerRadius by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isActive) {
            when (shapeMode) {
                1 -> 28.dp
                2 -> 18.dp
                3 -> 0.dp
                else -> QsVolumeSliderDimensions.SliderTrackRoundedCorner
            }
        } else {
            22.5.dp
        },
        label = "RingerCornerRadius",
    )

    val ringerShape = when (shapeMode) {
        1 -> CircleShape
        2 -> RoundedCornerShape(12.dp)
        3 -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(animatedCornerRadius)
    }

    val ringerBrush: Brush? = if (isActive) gradient?.brush else null

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isActive && ringerBrush == null -> MaterialTheme.colorScheme.primary
            isActive -> Color.Unspecified
            else -> CustomColorScheme.current.qsTileColor
        },
        label = "RingerBgColor",
    )

    val iconTint by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary
                      else MaterialTheme.colorScheme.onSurface,
        label = "RingerIconTint",
    )

    val iconRes = when (ringerMode) {
        RINGER_NORMAL -> R.drawable.ic_volume_ringer
        RINGER_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
        else -> R.drawable.ic_volume_ringer_mute
    }
    val iconTransition = updateTransition(
        targetState = iconRes,
        label = "RingerIconTransition"
    )
    val iconScale by iconTransition.animateFloat(
        transitionSpec = { QsVolumeIconSwapScaleSpec },
        label = "RingerIconScale",
    ) { target -> if (target == iconRes) 1f else 0.85f }

    Box(
        modifier = Modifier
            .size(buttonSize)
            .qsVolumeSquishAnimation(toggleCount)
            .clip(ringerShape)
            .then(
                if (ringerBrush != null) Modifier.background(ringerBrush)
                else Modifier.background(backgroundColor)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = when (ringerMode) {
                RINGER_NORMAL -> "Ringer normal"
                RINGER_VIBRATE -> "Ringer vibrate"
                else -> "Ringer silent"
            },
            tint = iconTint,
            modifier = Modifier
                .size(
                    QsVolumeSliderDimensions.IconSize.width,
                    QsVolumeSliderDimensions.IconSize.height
                )
                .graphicsLayer(scaleX = iconScale, scaleY = iconScale),
        )
    }
}

@Composable
private fun Modifier.qsVolumeSquishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember {
        Animatable(1f, visibilityThreshold = 0.01f)
    }
    val scaleY = remember {
        Animatable(1f, visibilityThreshold = 0.01f)
    }
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
                                1.06f at 120 using FastOutSlowInEasing
                                0.97f at 260
                                1f at 400
                            },
                        )
                    }
                    scaleY.animateTo(
                        targetValue = 1f,
                        animationSpec = keyframes {
                            durationMillis = 400
                            0.95f at 120 using FastOutSlowInEasing
                            1.03f at 260
                            1f at 400
                        },
                    )
                }
            }
    }

    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}

@Composable
fun rememberQsVolumeSliderShapeMode(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readShapeMode(): Int = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.QS_VOLUME_SLIDER_SHAPE,
            0,
            UserHandle.USER_CURRENT,
        )
    } catch (_: Throwable) { 0 }

    var shapeMode by remember {
        mutableIntStateOf(readShapeMode())
    }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute { shapeMode = readShapeMode() }
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_VOLUME_SLIDER_SHAPE),
            false, observer, UserHandle.USER_ALL,
        )
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return shapeMode
}

internal data class QsVolumeGradient(val brush: Brush)

@Composable
private fun rememberQsVolumeSliderGradientEnabled(): Boolean {
    val contentResolver = LocalContext.current.contentResolver

    fun readEnabled(): Boolean = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.QS_VOLUME_SLIDER_GRADIENT,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    } catch (_: Throwable) { false }

    var enabled by remember { mutableStateOf(readEnabled()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { enabled = readEnabled() }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_VOLUME_SLIDER_GRADIENT),
            false, observer, UserHandle.USER_ALL,
        )
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
private fun rememberQsVolumeGradientColorMode(): Int {
    val contentResolver = LocalContext.current.contentResolver

    fun readMode(): Int = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.CUSTOM_GRADIENT_COLOR_MODE,
            0,
            UserHandle.USER_CURRENT,
        )
    } catch (_: Throwable) { 0 }

    var mode by remember { mutableIntStateOf(readMode()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { mode = readMode() }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_COLOR_MODE),
            false, observer, UserHandle.USER_ALL,
        )
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }
    return mode
}

@Composable
private fun rememberQsVolumeGradientCustomColors(): Pair<Color, Color> {
    val contentResolver = LocalContext.current.contentResolver

    fun readStart(): Int = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.CUSTOM_GRADIENT_START_COLOR,
            0,
            UserHandle.USER_CURRENT,
        )
    } catch (_: Throwable) { 0 }

    fun readEnd(): Int = try {
        Settings.System.getIntForUser(
            contentResolver,
            Settings.System.CUSTOM_GRADIENT_END_COLOR,
            0,
            UserHandle.USER_CURRENT,
        )
    } catch (_: Throwable) { 0 }

    var startInt by remember { mutableIntStateOf(readStart()) }
    var endInt by remember { mutableIntStateOf(readEnd()) }

    DisposableEffect(contentResolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                startInt = readStart()
                endInt = readEnd()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_START_COLOR),
            false, observer, UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.CUSTOM_GRADIENT_END_COLOR),
            false, observer, UserHandle.USER_ALL,
        )
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    val start = if (startInt != 0) Color(startInt) else MaterialTheme.colorScheme.primary
    val end = if (endInt != 0) Color(endInt) else MaterialTheme.colorScheme.secondary
    return start to end
}

@Composable
private fun qsVolumeSliderGradient(): QsVolumeGradient? {
    if (!rememberQsVolumeSliderGradientEnabled()) return null

    val mode = rememberQsVolumeGradientColorMode()
    val colors = if (mode == 1) {
        val (start, end) = rememberQsVolumeGradientCustomColors()
        listOf(start, end)
    } else {
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
    }

    return QsVolumeGradient(brush = Brush.horizontalGradient(colors))
}

private fun Outline.asQsVolumePath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

private fun readQsVolumeHapticsEnabled(cr: ContentResolver): Boolean = try {
    Settings.System.getIntForUser(
        cr,
        Settings.System.QS_VOLUME_SLIDER_HAPTIC,
        1,
        UserHandle.USER_CURRENT
    ) != 0
} catch (_: Throwable) { false }

private fun readQsVolumeUseAxStyle(cr: ContentResolver): Boolean = try {
    Settings.System.getIntForUser(
        cr,
        Settings.System.QS_VOLUME_SLIDER_STYLE,
        0,
        UserHandle.USER_CURRENT
    ) != 0
} catch (_: Throwable) { false }

private fun readShowRingerButton(cr: ContentResolver): Boolean = try {
    Settings.System.getIntForUser(
        cr,
        Settings.System.QS_SHOW_RINGER_BUTTON,
        1,
        UserHandle.USER_CURRENT
    ) != 0
} catch (_: Throwable) { true }

private fun qsVolumeStepForValue(value: Int, max: Int, stepFraction: Float): Float {
    if (max <= 0) return 0f
    return floor((value / max.toFloat()) / stepFraction)
}

@Composable
private fun qsVolumeSliderColors(gradient: QsVolumeGradient?): SliderColors {
    val base = SliderDefaults.colors()
    return base.copy(
        activeTrackColor = if (gradient != null) Color.Transparent
                           else base.activeTrackColor,
        inactiveTrackColor = CustomColorScheme.current.qsTileColor,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
        inactiveTickColor = MaterialTheme.colorScheme.onSurface,
    )
}

private object QsVolumeSliderDimensions {
    val SliderTrackRoundedCorner = 12.dp
    val IconSize = DpSize(28.dp, 28.dp)
    val IconPadding = 10.dp

    val TrackHeight: Dp
        @Composable @ReadOnlyComposable get() =
            dimensionResource(id = R.dimen.overlay_qs_layout_brightness_track_height)
}

private val QsVolumeSliderSpringSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val QsVolumeSliderFractionSpringSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val QsVolumeIconSwapScaleSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessHigh,
)

private val QsVolumeIconAppearSpec = tween<Float>(
    durationMillis = 100, 
    delayMillis = 33
)

private val QsVolumeIconDisappearSpec = tween<Float>(
    durationMillis = 50
)
