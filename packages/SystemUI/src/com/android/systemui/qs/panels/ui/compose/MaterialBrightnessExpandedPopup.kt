/*
 * Copyright (C) 2026 MistOS
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

package com.android.systemui.qs.panels.ui.compose

import android.app.UiModeManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.hardware.display.ColorDisplayManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberGradientColorMode
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberGradientCustomColors
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberVolumeGradientEnabled

private val CORNER_DEFAULT = 40.dp
private val CORNER_ROUNDED = 90.dp
private val CORNER_INNER = 50.dp

@Composable
fun MaterialBrightnessExpandedPopup(
    initialBrightness: Float,
    brightnessMin: Float,
    brightnessMax: Float,
    rounded: Boolean = false,
    onDismiss: () -> Unit,
    onBrightnessChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    val cr = context.contentResolver

    val uiModeManager = remember { context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager }
    val colorDisplayManager = remember { context.getSystemService(Context.COLOR_DISPLAY_SERVICE) as ColorDisplayManager }

    var isDarkMode by remember { mutableStateOf(uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES) }
    var isNightMode by remember { mutableStateOf(colorDisplayManager.isNightDisplayActivated) }
    var isAutoBrightness by remember {
        mutableStateOf(
            Settings.System.getIntForUser(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        )
    }

    val blurEnabled = remember {
        Settings.Global.getInt(
            cr,
            Settings.Global.DISABLE_WINDOW_BLURS,
            if (android.os.SystemProperties.getBoolean("ro.custom.blur.enable", false)) 0 else 1
        ) != 1
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
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
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = rememberDrawablePainter(
                        LocalContext.current.getDrawable(
                            if (isAutoBrightness)
                                R.drawable.ic_qs_brightness_auto_on
                            else
                                R.drawable.ic_qs_brightness_auto_off
                        )
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Brightness",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(40.dp))

                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .fillMaxHeight(0.45f)
                ) {
                    MaterialLargeVerticalBrightnessSlider(
                        initialBrightness = initialBrightness,
                        brightnessMin = brightnessMin,
                        brightnessMax = brightnessMax,
                        isAutoBrightness = isAutoBrightness,
                        rounded = rounded,
                        onBrightnessChanged = onBrightnessChanged
                    )
                }

                Spacer(modifier = Modifier.height(60.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    MaterialPopupToggle(
                        iconRes = if (isAutoBrightness) R.drawable.ic_qs_brightness_auto_on
                                  else R.drawable.ic_qs_brightness_auto_off,
                        label = "Auto",
                        isActive = isAutoBrightness,
                        onClick = {
                            isAutoBrightness = !isAutoBrightness
                            Settings.System.putIntForUser(
                                cr,
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                if (isAutoBrightness) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                                UserHandle.USER_CURRENT
                            )
                        }
                    )
                    MaterialPopupToggle(
                        iconRes = if (isDarkMode) R.drawable.qs_light_dark_theme_icon_on
                                  else R.drawable.qs_light_dark_theme_icon_off,
                        label = "Dark Mode",
                        isActive = isDarkMode,
                        onClick = {
                            isDarkMode = !isDarkMode
                            uiModeManager.nightMode =
                                if (isDarkMode) UiModeManager.MODE_NIGHT_YES
                                else UiModeManager.MODE_NIGHT_NO
                        }
                    )
                    MaterialPopupToggle(
                        iconRes = if (isNightMode) R.drawable.qs_nightlight_icon_on
                                  else R.drawable.qs_nightlight_icon_off,
                        label = "Eye Shield",
                        isActive = isNightMode,
                        onClick = {
                            isNightMode = !isNightMode
                            colorDisplayManager.isNightDisplayActivated = isNightMode
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialPopupToggle(
    iconRes: Int,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val view = LocalView.current

    val bgColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary
                      else CustomColorScheme.current.qsTileColor,
        animationSpec = tween(300),
        label = "MaterialPopupToggleBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary
                      else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(300),
        label = "MaterialPopupToggleIconTint"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(bgColor)
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = rememberDrawablePainter(LocalContext.current.getDrawable(iconRes)),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

private fun brightnessToFraction(brightness: Float, min: Float, max: Float): Float {
    val gamma = BrightnessUtils.convertLinearToGammaFloat(brightness, min, max)
    return (gamma.toFloat() / BrightnessUtils.GAMMA_SPACE_MAX).coerceIn(0f, 1f)
}

private fun fractionToBrightness(fraction: Float, min: Float, max: Float): Float {
    val gamma = (fraction.coerceIn(0f, 1f) * BrightnessUtils.GAMMA_SPACE_MAX).toInt()
    return BrightnessUtils.convertGammaToLinearFloat(gamma, min, max)
}

@Composable
private fun MaterialLargeVerticalBrightnessSlider(
    initialBrightness: Float,
    brightnessMin: Float,
    brightnessMax: Float,
    isAutoBrightness: Boolean,
    rounded: Boolean = false,
    onBrightnessChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var isDragging by remember { mutableStateOf(false) }
    var currentBrightness by remember { mutableStateOf(initialBrightness) }

    val targetFraction = brightnessToFraction(currentBrightness, brightnessMin, brightnessMax)
    val animFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (isDragging)
            spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh)
        else
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label = "MaterialLargeBrightnessFraction"
    )
    val currentFraction = if (isDragging) targetFraction else animFraction

    val gradientEnabled = rememberVolumeGradientEnabled()
    val gradientColors = if (rememberGradientColorMode() == 1) {
        val g = rememberGradientCustomColors()
        listOf(g.startColor, g.endColor)
    } else {
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
        )
    }
    val fillBrush: Brush? = if (gradientEnabled && !isAutoBrightness)
        Brush.verticalGradient(colors = gradientColors.reversed())
    else null

    val fillColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label = "MaterialLargeBrightnessFill"
    )

    val trackColor = CustomColorScheme.current.qsTileColor
    val sliderShape = RoundedCornerShape(if (rounded) CORNER_ROUNDED else CORNER_DEFAULT)
    val fillShape  = RoundedCornerShape(if (rounded) CORNER_INNER else CORNER_DEFAULT)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(sliderShape)
            .background(trackColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                    fun update(y: Float) {
                        val fraction = 1f - (y / size.height).coerceIn(0f, 1f)
                        val v = fractionToBrightness(fraction, brightnessMin, brightnessMax)
                        currentBrightness = v
                        onBrightnessChanged(v)
                    }

                    update(down.position.y)

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val currentPointer = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!currentPointer.pressed) break
                            currentPointer.consume()
                            update(currentPointer.position.y)
                        }
                    } finally {
                        isDragging = false
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(currentFraction)
                .align(Alignment.BottomCenter)
                .then(
                    if (fillBrush != null)
                        Modifier.background(fillBrush, fillShape)
                    else
                        Modifier.background(fillColor, fillShape)
                )
        )
    }
}
