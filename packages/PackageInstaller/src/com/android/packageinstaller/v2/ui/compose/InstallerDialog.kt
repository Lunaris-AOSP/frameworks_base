@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.packageinstaller.v2.ui.compose

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Html
import android.text.format.Formatter
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.DialogFragment
import com.android.axion.blur.AxBlurLifecycle
import com.android.axion.blur.AxBlurSurface
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallAppDetails
import kotlin.math.roundToInt

abstract class InstallerDialogFragment : DialogFragment() {
    protected var actionsEnabled by mutableStateOf(false)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), R.style.Theme_PackageInstaller_Expressive_Dialog).apply {
            setCanceledOnTouchOutside(false)
            window?.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
            )
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        return object : FrameLayout(context) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
                val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                    MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
                return event.flags and obscured == 0 && super.onFilterTouchEventForSecurity(event)
            }
        }.apply {
            filterTouchesWhenObscured = true
            addView(ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val dark = isSystemInDarkTheme()
                    MaterialExpressiveTheme(
                        colorScheme = if (dark) dynamicDarkColorScheme(context)
                            else dynamicLightColorScheme(context),
                        motionScheme = MotionScheme.expressive(),
                    ) {
                        AxBlurLifecycle(enabled = actionsEnabled) {
                            // Qualify the receiver to avoid recursively calling ComposeView.Content().
                            this@InstallerDialogFragment.Content()
                        }
                    }
                }
            })
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val metrics = resources.displayMetrics
            val width = minOf(440f * metrics.density, metrics.widthPixels - 32f * metrics.density)
            window.setLayout(width.roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setDimAmount(0.24f)
        }
    }

    override fun onResume() {
        super.onResume()
        actionsEnabled = true
    }

    override fun onPause() {
        actionsEnabled = false
        super.onPause()
    }

    @Composable
    protected abstract fun Content()
}

data class InstallerAction(val label: String, val onClick: () -> Unit)

enum class InstallerTone { Normal, Success, Error }

data class InstallerDialogState(
    val key: Any,
    val title: String,
    val subtitle: String? = null,
    val appLabel: String? = null,
    val appIcon: Drawable? = null,
    val details: InstallAppDetails? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val progress: Float? = null,
    val tone: InstallerTone = InstallerTone.Normal,
    val primary: InstallerAction? = null,
    val secondary: InstallerAction? = null,
)

@Composable
fun InstallerDialogContent(
    state: InstallerDialogState,
    actionsEnabled: Boolean,
    extraContent: @Composable (Boolean) -> Unit = {},
) {
    var submitted by remember(state.key, actionsEnabled) { mutableStateOf(false) }
    val enabled = actionsEnabled && !submitted
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(32.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp
    // The window handles the entrance fade; keep the blur surface and text at their final scale.
    AxBlurSurface(
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .semantics { paneTitle = state.title },
        shape = shape,
        cornerRadius = 32.dp,
        surfaceColor = colors.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                AnimatedContent(
                    targetState = state.tone,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.86f)) togetherWith fadeOut() },
                    label = "installer status",
                ) { tone ->
                    val tint = when (tone) {
                        InstallerTone.Error -> colors.errorContainer
                        InstallerTone.Success -> colors.tertiaryContainer
                        InstallerTone.Normal -> colors.primaryContainer
                    }
                    val foreground = when (tone) {
                        InstallerTone.Error -> colors.onErrorContainer
                        InstallerTone.Success -> colors.onTertiaryContainer
                        InstallerTone.Normal -> colors.onPrimaryContainer
                    }
                    Box(
                        Modifier.size(56.dp).background(tint, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when (tone) {
                                InstallerTone.Success -> Icons.Rounded.Check
                                InstallerTone.Error -> Icons.Rounded.ErrorOutline
                                InstallerTone.Normal -> Icons.Rounded.Download
                            },
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onSurface,
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    state.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                if (state.appLabel != null || state.details != null) {
                    AppDetailsCard(state.appLabel, state.appIcon, state.details)
                }
                state.message?.let { InstallerMessage(it) }
                if (state.busy) {
                    val progress = state.progress
                    if (progress == null) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress.coerceIn(0f, 1f),
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "staging progress",
                        )
                        LinearWavyProgressIndicator(
                            progress = { animatedProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                extraContent(enabled)
            }
            if (state.primary != null || state.secondary != null) {
                InstallerActions(state, enabled) { action ->
                    if (actionsEnabled && !submitted) {
                        submitted = true
                        action.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetailsCard(label: String?, icon: Drawable?, details: InstallAppDetails?) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerHighest.copy(alpha = 0.75f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val iconSize = with(LocalDensity.current) { 48.dp.roundToPx() }
            val bitmap = remember(icon, iconSize) { icon?.toBitmap(iconSize, iconSize)?.asImageBitmap() }
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Icon(
                    Icons.Rounded.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = colors.primary,
                )
            }
            Text(
                label ?: details?.packageName.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        details?.let { app ->
            val unknown = stringResource(R.string.installer_detail_unavailable)
            val context = LocalContext.current
            val size = app.sizeBytes?.let { Formatter.formatFileSize(context, it) } ?: unknown
            val target = app.targetSdk?.let {
                stringResource(R.string.installer_sdk_value, it)
            } ?: unknown
            SelectionContainer {
                DetailValue(stringResource(R.string.installer_package_id), app.packageName)
            }
            BoxWithConstraints {
                when {
                    LocalDensity.current.fontScale > 1.3f -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailValue(stringResource(R.string.installer_size), size)
                            DetailValue(stringResource(R.string.installer_version), app.version ?: unknown)
                            DetailValue(stringResource(R.string.installer_target_sdk), target)
                        }
                    }
                    maxWidth < 300.dp -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DetailValue(
                                    stringResource(R.string.installer_size),
                                    size,
                                    Modifier.weight(1f),
                                )
                                DetailValue(
                                    stringResource(R.string.installer_version),
                                    app.version ?: unknown,
                                    Modifier.weight(1f),
                                )
                            }
                            DetailValue(stringResource(R.string.installer_target_sdk), target)
                        }
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailValue(
                                stringResource(R.string.installer_size), size, Modifier.weight(1f)
                            )
                            DetailValue(
                                stringResource(R.string.installer_version),
                                app.version ?: unknown,
                                Modifier.weight(1f),
                            )
                            DetailValue(
                                stringResource(R.string.installer_target_sdk),
                                target,
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun InstallerMessage(message: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(message, linkColor) {
        val spanned = Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY)
        buildAnnotatedString {
            append(spanned.toString().trimEnd())
            spanned.getSpans(0, spanned.length, URLSpan::class.java).forEach { span ->
                val start = spanned.getSpanStart(span).coerceAtMost(length)
                val end = spanned.getSpanEnd(span).coerceAtMost(length)
                if (start < end) addLink(
                    LinkAnnotation.Url(
                        span.url,
                        TextLinkStyles(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )),
                    ),
                    start, end,
                )
            }
            spanned.getSpans(0, spanned.length, StyleSpan::class.java).forEach { span ->
                if (span.style and android.graphics.Typeface.BOLD != 0) addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    spanned.getSpanStart(span).coerceAtMost(length),
                    spanned.getSpanEnd(span).coerceAtMost(length),
                )
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InstallerActions(
    state: InstallerDialogState,
    enabled: Boolean,
    onAction: (InstallerAction) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f ||
            maxOf(state.primary?.label?.length ?: 0, state.secondary?.label?.length ?: 0) > 16
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.primary?.let { action ->
                    ExpressiveInstallerButton(
                        action.label, true, enabled, Modifier.fillMaxWidth()
                    ) { onAction(action) }
                }
                state.secondary?.let { action ->
                    ExpressiveInstallerButton(
                        action.label, false, enabled, Modifier.fillMaxWidth()
                    ) { onAction(action) }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.secondary?.let { action ->
                    ExpressiveInstallerButton(
                        action.label, false, enabled, Modifier.weight(1f)
                    ) { onAction(action) }
                }
                state.primary?.let { action ->
                    ExpressiveInstallerButton(
                        action.label, true, enabled, Modifier.weight(1f)
                    ) { onAction(action) }
                }
            }
        }
    }
}

@Composable
fun ExpressiveInstallerButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "button press",
    )
    Button(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        },
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shapes = ButtonDefaults.shapes(
            shape = RoundedCornerShape(28.dp),
            pressedShape = RoundedCornerShape(16.dp),
        ),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        interactionSource = interactions,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
