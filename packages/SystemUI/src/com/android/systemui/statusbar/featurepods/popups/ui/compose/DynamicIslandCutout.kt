package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.graphics.Path
import android.graphics.RectF
import android.view.DisplayCutout
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
    val cutoutBounds: Rect? = null,
    val windowWidthPx: Int = 0,
)

private data class DynamicIslandCutoutSnapshot(val bounds: Rect?, val windowWidth: Int)

private fun readDynamicIslandCutout(view: View): DynamicIslandCutoutSnapshot {
    val displayCutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val bounds = displayCutout?.dynamicIslandHoleRect()
    val width =
        view.rootView.width.takeIf { it > 0 }
            ?: view.resources.configuration.windowConfiguration.maxBounds.width()
    return DynamicIslandCutoutSnapshot(bounds, width)
}

internal fun DisplayCutout.dynamicIslandHoleRect(): Rect? {
    val top = boundingRectTop.takeUnless { it.isEmpty } ?: return null
    val bounds = RectF(top)
    cutoutPath?.let { original ->
        val clip = Path().apply { addRect(bounds, Path.Direction.CW) }
        val path = Path(original)
        if (path.op(clip, Path.Op.INTERSECT) && !path.isEmpty) {
            val preciseBounds = RectF()
            path.computeBounds(preciseBounds, true)
            if (!preciseBounds.isEmpty) bounds.set(preciseBounds)
        }
    }
    return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
}

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var snapshot by remember(view, configuration) { mutableStateOf(readDynamicIslandCutout(view)) }
    DisposableEffect(view, configuration) {
        val observer = view.rootView.viewTreeObserver
        val listener =
            ViewTreeObserver.OnGlobalLayoutListener { snapshot = readDynamicIslandCutout(view) }
        observer.addOnGlobalLayoutListener(listener)
        snapshot = readDynamicIslandCutout(view)
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener) }
    }
    return with(density) {
        DynamicIslandCutoutSpec(
            embeddedGapWidth = snapshot.bounds?.width?.toDp()?.plus(8.dp) ?: 25.dp,
            horizontalOffset =
                snapshot.bounds?.let { (it.center.x - snapshot.windowWidth / 2f).toDp() } ?: 0.dp,
            cutoutBounds = snapshot.bounds,
            windowWidthPx = snapshot.windowWidth,
        )
    }
}
