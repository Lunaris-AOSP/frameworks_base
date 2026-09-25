/*
 * Copyright (C) 2026 ASCP Project
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

package com.android.systemui.qs.panels.ui.compose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.settingslib.media.MediaOutputConstants
import com.android.systemui.media.dialog.MediaOutputDialogReceiver
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme
import kotlinx.coroutines.delay

@Composable
internal fun MaterialMediaExpandedPopup(
    mediaState: SharedMediaState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val controller = mediaState.controller

    val blurEnabled = remember {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DISABLE_WINDOW_BLURS,
            if (android.os.SystemProperties.getBoolean("ro.custom.blur.enable", false)) 0 else 1
        ) != 1
    }

    var localIsPlaying by remember(controller, mediaState.isPlaying) {
        mutableStateOf(mediaState.isPlaying)
    }

    val durationMs = remember(controller, mediaState.title) {
        controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    }
    var positionMs by remember(controller) {
        mutableFloatStateOf((controller?.playbackState?.position ?: 0L).toFloat())
    }
    var isScrubbing by remember { mutableStateOf(false) }

    val popupScale = remember { Animatable(0.82f) }
    val popupAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        popupScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    LaunchedEffect(Unit) {
        popupAlpha.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 150))
    }

    LaunchedEffect(controller) {
        while (true) {
            if (!isScrubbing) {
                positionMs = (controller?.playbackState?.position ?: 0L).toFloat()
            }
            delay(500)
        }
    }

    val deviceIcon = remember {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) { emptyArray() }
        when {
            outputs.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } -> Icons.Filled.Bluetooth
            outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } -> Icons.Filled.Headset
            else -> Icons.Filled.Smartphone
        }
    }

    fun launchMediaOutputDialog() {
        val pkg = mediaState.packageName ?: return
        context.sendBroadcast(
            Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG).apply {
                putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, pkg)
                component = ComponentName("com.android.systemui", MediaOutputDialogReceiver::class.java.name)
            }
        )
    }

    fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                if (blurEnabled) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val lp = w.attributes
                    lp.blurBehindRadius = 150
                    w.attributes = lp
                } else {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (blurEnabled) 0.25f else 0.55f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .scale(popupScale.value)
                    .alpha(popupAlpha.value)
                    .clip(RoundedCornerShape(32.dp))
                    .background(CustomColorScheme.current.qsTileColor)
                    .padding(24.dp)
                    .clickable(enabled = false) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Media player",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
                            .clickable { launchMediaOutputDialog() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = deviceIcon,
                            contentDescription = "Audio Output",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (mediaState.albumArt != null) {
                    Image(
                        painter = BitmapPainter(mediaState.albumArt.asImageBitmap()),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = mediaState.title ?: "Not Playing",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!mediaState.artist.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = mediaState.artist,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(16.dp))

                val durationSafe = durationMs.coerceAtLeast(1L).toFloat()
                val progressFraction = (positionMs / durationSafe).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { isScrubbing = true },
                                onDragEnd = {
                                    controller?.transportControls?.seekTo(positionMs.toLong())
                                    isScrubbing = false
                                },
                                onDragCancel = { isScrubbing = false },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    positionMs = fraction * durationSafe
                                },
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color.White.copy(alpha = 0.25f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color.White),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatMs(positionMs.toLong()), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    Text(formatMs(durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { controller?.transportControls?.skipToPrevious() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = {
                                if (localIsPlaying) {
                                    localIsPlaying = false
                                    controller?.transportControls?.pause()
                                } else {
                                    localIsPlaying = true
                                    controller?.transportControls?.play()
                                }
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Crossfade(targetState = localIsPlaying, animationSpec = tween(200), label = "popup_play_pause") { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { controller?.transportControls?.skipToNext() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                val audioManager = remember {
                    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                }
                val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
                var volume by remember {
                    mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.VolumeDown, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))

                    val volumeFraction = (volume.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .height(20.dp)
                            .pointerInput(maxVolume) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        volume = (fraction * maxVolume).toInt()
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                    },
                                )
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(volumeFraction)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Color.White),
                        )
                    }

                    Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
