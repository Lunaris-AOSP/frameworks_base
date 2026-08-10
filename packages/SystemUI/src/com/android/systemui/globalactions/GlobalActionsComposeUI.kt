/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.statusbar.BlurUtils
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.provider.Settings
import android.os.UserHandle
import android.database.ContentObserver
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

import lineageos.providers.LineageSettings;

data class TileConfig(
    val action: GlobalActionsDialogLite.Action,
    val id: String,
    val isEmergency: Boolean
)

data class RestartOption(
    val label: String,
    val reason: String?,
    val icon: ImageVector,
    val description: String
)

enum class GlobalActionsView {
    GRID,
    RESTART_CHOICE,
    RESTART_OPTIONS,
    CONFIRMATION
}

private val VividRed = Color(0xFFFA4141)
private const val BLUR_RADIUS_MULTIPLIER = 5.8f

@Composable
private fun rememberHapticClick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    }
}

@Composable
private fun rememberWindowBlursEnabled(blurUtils: BlurUtils): Boolean {
    val context = LocalContext.current
    var disabled by remember {
        mutableStateOf(
            Settings.Global.getInt(context.contentResolver,
                Settings.Global.DISABLE_WINDOW_BLURS, 0) == 1
        )
    }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.DISABLE_WINDOW_BLURS)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                disabled = Settings.Global.getInt(
                    context.contentResolver, Settings.Global.DISABLE_WINDOW_BLURS, 0
                ) == 1
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return !disabled && blurUtils.supportsBlursOnWindows()
}

fun isAdvancedRestartPossible(context: Context): Boolean {
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
    return try {
        val advRestart = LineageSettings.Secure.getInt(
            context.contentResolver,
            LineageSettings.Secure.ADVANCED_REBOOT,
            0
        ) == 1

        advRestart && keyguardManager?.isKeyguardLocked != true
    } catch (e: Exception) {
        false
    }
}

fun getActionLabel(action: GlobalActionsDialogLite.Action, context: Context): String {
    val msg = action.getMessage()
    if (msg != null) {
        val str = msg.toString()
        if (str.all { it.isDigit() }) {
            return try {
                context.getString(str.toInt())
            } catch (e: Exception) {
                cleanClassName(action.javaClass.simpleName)
            }
        }
        return str
    }

    return cleanClassName(action.javaClass.simpleName)
}

private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")

fun cleanClassName(name: String): String {
    return name
        .replace("GlobalActions", "")
        .replace("Action", "")
        .replace("DialogLite", "")
        .replace("$", "")
        .replace("6", "Device Controls")
        .replace(CAMEL_CASE_REGEX, "$1 $2")
        .trim()
}

private fun isGridAction(action: GlobalActionsDialogLite.Action): Boolean {
    val name = action.javaClass.simpleName
    return name.contains("Emergency") ||
            name.contains("Lockdown") ||
            name.contains("LockDown") ||
            (name.contains("Lock") && !name.contains("Unlock")) ||
            name.contains("ShutDown") ||
            name.contains("Power") ||
            name.contains("Restart")
}

class GlobalActionsComposeUI(
    context: Context,
    private val actions: List<GlobalActionsDialogLite.Action>,
    private val restartActions: List<GlobalActionsDialogLite.Action> = emptyList(),
    private val onActionClick: (GlobalActionsDialogLite.Action) -> Unit,
    private val onActionLongClick: ((GlobalActionsDialogLite.Action) -> Boolean)? = null,
    private val onUserInteraction: () -> Unit,
    private val onDismissed: () -> Unit,
    private val blurUtils: BlurUtils,
    private val sliderStyle: Boolean = false
) : Dialog(context, com.android.systemui.res.R.style.Theme_SystemUI_Dialog_GlobalActionsLite),
    LifecycleOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            WindowCompat.setDecorFitsSystemWindows(this, false)

            if (blurUtils.supportsBlursOnWindows()) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply {
                    val baseRadius = context.resources.getDimensionPixelSize(
                        com.android.systemui.res.R.dimen.max_window_blur_radius
                    )
                    blurBehindRadius = (baseRadius * BLUR_RADIUS_MULTIPLIER).toInt()
                }
                setDimAmount(0.5f)
            } else {
                setDimAmount(0.7f)
            }

            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@GlobalActionsComposeUI)
            setViewTreeSavedStateRegistryOwner(this@GlobalActionsComposeUI)
            setWindowInsetsAnimationCallback(null)
            fitsSystemWindows = false

            setContent {
                MaterialExpressiveTheme {
                    GlobalActionsScreen(
                        actions = actions,
                        restartActions = restartActions,
                        sliderStyle = sliderStyle,
                        onActionClick = onActionClick,
                        onActionLongClick = onActionLongClick,
                        blurUtils = blurUtils,
                        realDismiss = {
                            dismiss()
                        }
                    )
                }
            }
        }

        savedStateRegistryController.performRestore(null)
        setContentView(composeView)
        setOnDismissListener { onDismissed() }

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        if (lifecycleRegistry.currentState != Lifecycle.State.STARTED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
    }

    override fun onStop() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dismiss() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        super.dismiss()
    }
}

@Composable
private fun MaterialExpressiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    val colorScheme = remember(context, darkTheme) {
        if (darkTheme) {
            val accentPrimary = Color(context.getColor(android.R.color.system_accent1_200))
            val accentSecondary = Color(context.getColor(android.R.color.system_accent2_200))
            val surfaceContainer = Color(context.getColor(android.R.color.system_neutral1_900))
            val surfaceVariant = Color(context.getColor(android.R.color.system_neutral2_800))

            darkColorScheme(
                primary = accentPrimary,
                secondary = accentSecondary,
                surface = surfaceContainer,
                surfaceVariant = surfaceVariant,
                onSurface = Color.White,
                background = Color.Transparent,
                error = Color(0xFFE25C5C),
                onError = Color(0xFF410002)
            )
        } else {
            val accentPrimary = Color(context.getColor(android.R.color.system_accent1_600))
            val accentSecondary = Color(context.getColor(android.R.color.system_accent2_600))
            val surfaceContainer = Color(context.getColor(android.R.color.system_neutral1_50))
            val surfaceVariant = Color(context.getColor(android.R.color.system_neutral2_100))

            lightColorScheme(
                primary = accentPrimary,
                secondary = accentSecondary,
                surface = surfaceContainer,
                surfaceVariant = surfaceVariant,
                onSurface = Color.Black,
                background = Color.Transparent,
                onSurfaceVariant = Color.DarkGray,
                error = Color(0xFFB3261E),
                onError = Color.White
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            small = RoundedCornerShape(20.dp),
            medium = RoundedCornerShape(28.dp),
            large = RoundedCornerShape(36.dp)
        ),
        content = content
    )
}

@Composable
fun GlobalActionsScreen(
    actions: List<GlobalActionsDialogLite.Action>,
    restartActions: List<GlobalActionsDialogLite.Action> = emptyList(),
    sliderStyle: Boolean = false,
    onActionClick: (GlobalActionsDialogLite.Action) -> Unit,
    onActionLongClick: ((GlobalActionsDialogLite.Action) -> Boolean)?,
    blurUtils: BlurUtils,
    realDismiss: () -> Unit
) {
    val context = LocalContext.current
    val blurEnabled = rememberWindowBlursEnabled(blurUtils)
    var currentView by remember { mutableStateOf(GlobalActionsView.GRID) }
    var pendingConfirmationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmationTitle by remember { mutableStateOf("") }
    var confirmationMessage by remember { mutableStateOf("") }
    var confirmationIcon by remember { mutableStateOf(Icons.Rounded.PowerSettingsNew) }
    var confirmationColor by remember { mutableStateOf(Color.Red) }
    var confirmationReturnsToGrid by remember { mutableStateOf(false) }

    val visibleState = remember { MutableTransitionState(false) }

    LaunchedEffect(Unit) {
        visibleState.targetState = true
    }

    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) {
            realDismiss()
        }
    }

    val startExit: () -> Unit = {
        visibleState.targetState = false
    }

    val gridTiles = remember(actions) {
        actions.filter { isGridAction(it) }
            .map { TileConfig(it, it.javaClass.simpleName, it.javaClass.simpleName.contains("Emergency")) }
    }
    val pillTiles = remember(actions) {
        actions.filterNot { isGridAction(it) }
            .map { TileConfig(it, it.javaClass.simpleName, false) }
    }

    val performReboot = { reason: String? ->
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            powerManager.reboot(reason)
        } catch (e: Exception) {
        }
    }

    val errorColor = MaterialTheme.colorScheme.error

    val handleTileClick: (TileConfig) -> Unit = { tile ->
        val className = tile.action.javaClass.simpleName
        when {
            className.contains("Power") || className.contains("ShutDown") -> {
                confirmationTitle = "Power Off"
                confirmationMessage = "Slide to power off"
                confirmationIcon = Icons.Rounded.PowerSettingsNew
                confirmationColor = VividRed
                pendingConfirmationAction = { onActionClick(tile.action) }
                currentView = GlobalActionsView.CONFIRMATION
            }

            className.contains("Restart") -> {
                if (isAdvancedRestartPossible(context)) {
                    currentView = GlobalActionsView.RESTART_CHOICE
                } else {
                    confirmationTitle = "Restart Direct"
                    confirmationMessage = "Slide to restart"
                    confirmationIcon = Icons.Rounded.Refresh
                    confirmationColor = VividRed
                    confirmationReturnsToGrid = true
                    pendingConfirmationAction = { onActionClick(tile.action) }
                    currentView = GlobalActionsView.CONFIRMATION
                }
            }

            className.contains("Users") -> {
                onActionClick(tile.action)
            }

            else -> {
                onActionClick(tile.action)
                startExit()
            }
        }
    }

    val handleRestartSystemUiChosen: () -> Unit = {
        val sysUiAction = restartActions.firstOrNull {
            it is GlobalActionsDialogLite.RestartSystemUIAction
        }
        confirmationTitle = "Restart SystemUI"
        confirmationMessage = "Slide to restart"
        confirmationIcon = Icons.Rounded.Refresh
        confirmationColor = VividRed
        pendingConfirmationAction = {
            if (sysUiAction != null) onActionClick(sysUiAction)
        }
        currentView = GlobalActionsView.CONFIRMATION
    }

    val handleRestartDeviceChosen: () -> Unit = {
        val restartAction = actions.firstOrNull { it is GlobalActionsDialogLite.RestartAction }
        confirmationTitle = "Restart"
        confirmationMessage = "Slide to restart"
        confirmationIcon = Icons.Rounded.Refresh
        confirmationColor = VividRed
        pendingConfirmationAction = {
            if (restartAction != null) onActionClick(restartAction) else performReboot(null)
         }
        currentView = GlobalActionsView.CONFIRMATION
    }

    val handleRestartRecoveryChosen: () -> Unit = {
        val restartAction = restartActions.firstOrNull {
            it is GlobalActionsDialogLite.RestartRecoveryAction
        }
        confirmationTitle = "Recovery"
        confirmationMessage = "Slide to recovery"
        confirmationIcon = Icons.Rounded.MedicalServices
        confirmationColor = VividRed
        pendingConfirmationAction = {
            if (restartAction != null) onActionClick(restartAction) else performReboot("recovery")
        }
        currentView = GlobalActionsView.CONFIRMATION
    }

    val handleRestartOptionClick: (RestartOption) -> Unit = { option ->
        confirmationTitle = option.label
        confirmationMessage = option.description
        confirmationIcon = option.icon
        confirmationColor = VividRed
        pendingConfirmationAction = { performReboot(option.reason) }
        currentView = GlobalActionsView.CONFIRMATION
    }

    val handleDismiss: () -> Unit = {
        when (currentView) {
            GlobalActionsView.CONFIRMATION -> {
                currentView = when {
                    confirmationReturnsToGrid -> GlobalActionsView.GRID
                    confirmationTitle == "Power Off" -> GlobalActionsView.GRID
                    confirmationTitle == "Restart Direct" -> GlobalActionsView.GRID
                    confirmationTitle == "Restart SystemUI" -> GlobalActionsView.RESTART_CHOICE
                    confirmationTitle == "Recovery" -> GlobalActionsView.RESTART_CHOICE
                    confirmationTitle == "Restart" -> GlobalActionsView.RESTART_CHOICE
                    isAdvancedRestartPossible(context) -> GlobalActionsView.RESTART_OPTIONS
                    else -> GlobalActionsView.GRID
                 }
             }
            GlobalActionsView.RESTART_OPTIONS -> currentView = GlobalActionsView.RESTART_CHOICE
            GlobalActionsView.RESTART_CHOICE -> currentView = GlobalActionsView.GRID
            GlobalActionsView.GRID -> startExit()
        }
    }

    if (sliderStyle) {
        val restartAction = actions.firstOrNull { it is GlobalActionsDialogLite.RestartAction }
        val shutdownAction = actions.firstOrNull { it is GlobalActionsDialogLite.ShutDownAction }
        val sysuiAction = restartActions.firstOrNull {
            it is GlobalActionsDialogLite.RestartSystemUIAction
        }
        val recoveryAction = restartActions.firstOrNull {
            it is GlobalActionsDialogLite.RestartRecoveryAction
        }
        val emergencyAction = actions.firstOrNull {
            it.javaClass.simpleName.contains("Emergency")
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            startExit()
                        }
                    }
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = fadeIn(animationSpec = tween(300)) + scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(300)
                    ),
                    exit = fadeOut(animationSpec = tween(250)) + scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(250)
                    )
                ) {
                    SliderPowerMenu(
                        onRestart = { restartAction?.let { onActionClick(it) } },
                        onShutdown = { shutdownAction?.let { onActionClick(it) } },
                        onRestartSystemUi = { sysuiAction?.let { onActionClick(it) } },
                        onRestartRecovery = { recoveryAction?.let { onActionClick(it) } },
                        onEmergency = emergencyAction?.let { action ->
                            { onActionClick(action) }
                        }
                    )
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets(0, 0, 0, 0))
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures {
                        handleDismiss()
                    }
                }
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = currentView,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.9f))
                        .togetherWith(fadeOut(animationSpec = tween(200)))
                },
                label = "view_transition"
            ) { state ->
                when (state) {
                    GlobalActionsView.CONFIRMATION -> {
                        ConfirmationSliderView(
                            title = confirmationTitle,
                            message = confirmationMessage,
                            icon = confirmationIcon,
                            color = confirmationColor,
                            onConfirm = {
                                pendingConfirmationAction?.invoke()
                                startExit()
                            },
                            onCancel = { handleDismiss() },
                            blurEnabled = blurEnabled
                        )
                    }
                    GlobalActionsView.RESTART_CHOICE -> {
                        RestartChoiceMenu(
                            onRestartSystemUi = handleRestartSystemUiChosen,
                            onRestartDevice = handleRestartDeviceChosen,
                            onRestartRecovery = handleRestartRecoveryChosen,
                            onBack = { currentView = GlobalActionsView.GRID },
                            blurEnabled = blurEnabled
                        )
                    }
                    GlobalActionsView.RESTART_OPTIONS -> {
                        RestartOptionsMenu(
                            onOptionSelected = handleRestartOptionClick,
                            onBack = { currentView = GlobalActionsView.RESTART_CHOICE },
                            blurEnabled = blurEnabled
                        )
                    }
                    GlobalActionsView.GRID -> {
                        AnimatedVisibility(
                            visibleState = visibleState,
                            enter = fadeIn(animationSpec = tween(300)) + scaleIn(
                                initialScale = 0.95f,
                                animationSpec = tween(300)
                            ),
                            exit = fadeOut(animationSpec = tween(250)) + scaleOut(
                                targetScale = 0.95f,
                                animationSpec = tween(250)
                            )
                        ) {
                            PowerMenuCard(
                                gridTiles = gridTiles,
                                pillTiles = pillTiles,
                                onTileClick = handleTileClick,
                                blurEnabled = blurEnabled
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestartOptionsMenu(
    onOptionSelected: (RestartOption) -> Unit,
    onBack: () -> Unit,
    blurEnabled: Boolean = false
) {
    val backHaptic = rememberHapticClick()
    val restartOptions = listOf(
        RestartOption("System", null, Icons.Rounded.Refresh, "> > Restart system > >"),
        RestartOption("Recovery", "recovery", Icons.Rounded.MedicalServices, "> > Restart to recovery > >"),
        RestartOption("Bootloader", "bootloader", Icons.Rounded.Settings, "> > Restart to bootloader > >"),
        RestartOption("Fastboot", "fastboot", Icons.Rounded.Adb, "> > Restart to fastboot > >")
    )

    Surface(
        modifier = Modifier
            .width(340.dp)
            .padding(16.dp)
            .clip(RoundedCornerShape(32.dp))
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(32.dp),
        color = menuCardColor(blurEnabled),
        tonalElevation = if (blurEnabled) 0.dp else 6.dp,
        shadowElevation = if (blurEnabled) 0.dp else 8.dp,
        border = menuCardBorder(blurEnabled)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Restart Options",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            restartOptions.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { option ->
                        RestartOptionTile(
                            option = option,
                            onClick = { onOptionSelected(option) },
                            modifier = Modifier.weight(1f),
                            blurEnabled = blurEnabled
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = {
                    backHaptic()
                    onBack()
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = menuRowColor(blurEnabled)
                ),
                modifier = Modifier.height(44.dp)
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun RestartOptionTile(
    option: RestartOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    blurEnabled: Boolean = false
) {
    val haptic = rememberHapticClick()
    Surface(
        onClick = {
            haptic()
            onClick()
        },
        shape = RoundedCornerShape(24.dp),
        color = menuRowColor(blurEnabled),
        modifier = modifier.height(100.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PowerMenuCard(
    gridTiles: List<TileConfig>,
    pillTiles: List<TileConfig>,
    onTileClick: (TileConfig) -> Unit,
    blurEnabled: Boolean
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val cardColor = if (blurEnabled) {
        MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.92f)
    }
    val cardBorder = if (blurEnabled) {
        BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
        )
    } else {
        BorderStroke(
            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    }

    Surface(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .padding(16.dp)
            .clip(RoundedCornerShape(32.dp))
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(32.dp),
        color = cardColor,
        tonalElevation = if (blurEnabled) 0.dp else 6.dp,
        shadowElevation = if (blurEnabled) 0.dp else 10.dp,
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .then(if (isLandscape) Modifier.width(420.dp) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val columns = if (isLandscape) 4 else 2
            gridTiles.chunked(columns).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    rowTiles.forEach { tile ->
                        SquareActionTile(
                            tile = tile,
                            onClick = { onTileClick(tile) },
                            modifier = Modifier.weight(1f),
                            blurEnabled = blurEnabled
                        )
                    }
                    repeat(columns - rowTiles.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (gridTiles.isNotEmpty() && pillTiles.isNotEmpty()) {
                Divider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            pillTiles.forEachIndexed { index, tile ->
                PillActionRow(
                    tile = tile,
                    onClick = { onTileClick(tile) },
                    blurEnabled = blurEnabled
                )
                if (index != pillTiles.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SquareActionTile(
    tile: TileConfig,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    blurEnabled: Boolean = false
) {
    val context = LocalContext.current
    val label = remember(tile.id) { getActionLabel(tile.action, context) }
    val iconBitmap = remember(tile.id) {
        tile.action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
    }

    val haptic = rememberHapticClick()

    val backgroundColor = if (tile.isEmergency) {
        VividRed
    } else if (blurEnabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (tile.isEmergency) {
        VividRed
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = {
                haptic()
                onClick()
            },
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = backgroundColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (tile.isEmergency) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(52.dp)
                    ) {}
                }
                if (iconBitmap != null) {
                    Icon(
                        bitmap = iconBitmap,
                        contentDescription = label,
                        modifier = Modifier.size(30.dp),
                        tint = if (tile.isEmergency) backgroundColor else contentColor
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PillActionRow(
    tile: TileConfig,
    onClick: () -> Unit,
    blurEnabled: Boolean = false
) {
    val context = LocalContext.current
    val label = remember(tile.id) { getActionLabel(tile.action, context) }
    val iconBitmap = remember(tile.id) {
        tile.action.getIcon(context)?.toBitmap(96, 96)?.asImageBitmap()
    }
    val haptic = rememberHapticClick()

    Surface(
        onClick = {
            haptic()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = if (blurEnabled) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Icon(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun menuCardColor(blurEnabled: Boolean): Color =
    if (blurEnabled) {
        MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.92f)
    }

@Composable
private fun menuCardBorder(blurEnabled: Boolean) =
    if (blurEnabled) {
        BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.37f)
        )
    } else {
        BorderStroke(
            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    }

@Composable
private fun menuRowColor(blurEnabled: Boolean): Color =
    if (blurEnabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

@Composable
private fun RestartChoiceMenu(
    onRestartSystemUi: () -> Unit,
    onRestartDevice: () -> Unit,
    onRestartRecovery: () -> Unit,
    onBack: () -> Unit,
    blurEnabled: Boolean = false
) {
    Surface(
        modifier = Modifier
            .width(320.dp)
            .padding(16.dp)
            .clip(RoundedCornerShape(32.dp))
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(32.dp),
        color = menuCardColor(blurEnabled),
        tonalElevation = if (blurEnabled) 0.dp else 6.dp,
        shadowElevation = if (blurEnabled) 0.dp else 8.dp,
        border = menuCardBorder(blurEnabled)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Restart",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            RestartChoiceRow(
                icon = Icons.Rounded.Refresh,
                label = "Restart SystemUI",
                description = "Quick — restarts the UI only",
                onClick = onRestartSystemUi,
                blurEnabled = blurEnabled
            )

            Spacer(modifier = Modifier.height(12.dp))

            RestartChoiceRow(
                icon = Icons.Rounded.PowerSettingsNew,
                label = "Restart Device",
                description = "Full reboot",
                onClick = onRestartDevice,
                blurEnabled = blurEnabled
            )

            Spacer(modifier = Modifier.height(12.dp))

            RestartChoiceRow(
                icon = Icons.Rounded.MedicalServices,
                label = "Reboot to Recovery",
                description = "Boots straight into recovery mode",
                onClick = onRestartRecovery,
                blurEnabled = blurEnabled
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onBack,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = menuRowColor(blurEnabled)
                ),
                modifier = Modifier.height(44.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun RestartChoiceRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    blurEnabled: Boolean = false
) {
    val haptic = rememberHapticClick()
    Surface(
        onClick = {
            haptic()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(24.dp),
        color = menuRowColor(blurEnabled)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun sliderTrackColor(blurEnabled: Boolean): Color =
    if (blurEnabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    }

@Composable
private fun ConfirmationSliderView(
    title: String,
    message: String,
    icon: ImageVector,
    color: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    blurEnabled: Boolean = false
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var maxOffsetPx by remember { mutableFloatStateOf(0f) }
    val threshold = 0.75f
    var hasFiredThresholdHaptic by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thumbInset = 10.dp
    val view = LocalView.current
    val cancelHaptic = rememberHapticClick()

    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black

    Column(
        modifier = Modifier
            .width(320.dp)
            .padding(16.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(sliderTrackColor(blurEnabled))
                .border(
                    1.dp,
                    if (blurEnabled) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                    },
                    RoundedCornerShape(36.dp)
                )
                .padding(thumbInset)
                .onGloballyPositioned { coordinates ->
                    maxOffsetPx = coordinates.size.width - with(density) { 56.dp.toPx() }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = if (maxOffsetPx > 0f) (1f - (offsetX / maxOffsetPx)).coerceIn(0f, 1f) else 1f
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(color)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                if (maxOffsetPx > 0 && offsetX / maxOffsetPx > threshold) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    onConfirm()
                                } else {
                                    offsetX = 0f
                                }
                                hasFiredThresholdHaptic = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffsetPx)
                                val pastThreshold = maxOffsetPx > 0 && offsetX / maxOffsetPx > threshold
                                if (pastThreshold && !hasFiredThresholdHaptic) {
                                    hasFiredThresholdHaptic = true
                                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                                } else if (!pastThreshold && hasFiredThresholdHaptic) {
                                    hasFiredThresholdHaptic = false
                                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        TextButton(
            onClick = {
                cancelHaptic()
                onCancel()
            }
        ) {
            Text(
                text = "Cancel",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun TwoWaySlider(
    upLabel: String,
    downLabel: String,
    upColor: Color,
    icon: ImageVector,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val thumbSizeDp = 72.dp
    val trackHeightDp = 280.dp
    var offsetY by remember { mutableFloatStateOf(0f) }
    var maxOffsetPx by remember { mutableFloatStateOf(0f) }
    var hasFiredThreshold by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val threshold = 0.7f

    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = if (fired) tween(0) else spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "slider_thumb_offset"
    )

    Column(
        modifier = modifier.width(140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "Slide up: $upLabel",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        Box(
            modifier = Modifier
                .width(thumbSizeDp + 7.dp)
                .height(trackHeightDp)
                .clip(RoundedCornerShape(48.dp))
                .background(upColor.copy(alpha = 0.22f))
                .border(
                    width = 1.dp,
                    color = upColor.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(48.dp)
                )
                .onGloballyPositioned { coordinates ->
                    maxOffsetPx = coordinates.size.height / 2f -
                        with(density) { (thumbSizeDp / 2).toPx() } -
                        with(density) { 8.dp.toPx() }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val progress = if (maxOffsetPx > 0) -offsetY / maxOffsetPx else 0f
                            when {
                                progress > threshold -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    fired = true
                                    offsetY = -maxOffsetPx
                                    onUp()
                                }
                                progress < -threshold -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    fired = true
                                    offsetY = maxOffsetPx
                                    onDown()
                                }
                                else -> {
                                    fired = false
                                    offsetY = 0f
                                }
                            }
                            hasFiredThreshold = false
                        },
                        onDrag = { change, drag ->
                            if (fired) return@detectDragGestures
                            change.consume()
                            offsetY = (offsetY + drag.y).coerceIn(-maxOffsetPx, maxOffsetPx)
                            val past = maxOffsetPx > 0 &&
                                kotlin.math.abs(offsetY) / maxOffsetPx > threshold
                            if (past && !hasFiredThreshold) {
                                hasFiredThreshold = true
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                )
                            } else if (!past && hasFiredThreshold) {
                                hasFiredThreshold = false
                                view.performHapticFeedback(
                                    HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                                )
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowUp,
                contentDescription = null,
                tint = upColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(18.dp)
            )

            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                contentDescription = null,
                tint = upColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .size(18.dp)
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
                    .size(thumbSizeDp)
                    .shadow(elevation = 10.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(upColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = "Slide down: $downLabel",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun SliderPowerMenu(
    onRestart: () -> Unit,
    onShutdown: () -> Unit,
    onRestartSystemUi: () -> Unit,
    onRestartRecovery: () -> Unit,
    onEmergency: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Power Options",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TwoWaySlider(
                upLabel = "Restart",
                downLabel = "Shutdown",
                upColor = VividRed,
                icon = Icons.Rounded.PowerSettingsNew,
                onUp = onRestart,
                onDown = onShutdown
            )
            TwoWaySlider(
                upLabel = "SystemUI",
                downLabel = "Recovery",
                upColor = Color(0xFF4285F4),
                icon = Icons.Rounded.Android,
                onUp = onRestartSystemUi,
                onDown = onRestartRecovery
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (onEmergency != null) {
            TextButton(
                onClick = onEmergency,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = VividRed,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(44.dp)
            ) {
                Text("Emergency")
            }
        }
    }
}
