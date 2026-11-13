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

package com.android.systemui.edgeaurora

import android.animation.ValueAnimator
import android.app.Notification
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import com.android.axion.blur.AxBlurBackgroundRenderer
import com.android.axion.blur.R as AxBlurR
import com.android.internal.util.lunaris.EdgeAuroraConfig
import com.android.internal.util.lunaris.EdgeAuroraConfig.Edge
import com.android.internal.util.lunaris.EdgeAuroraConfig.Profile
import com.android.internal.util.lunaris.EdgeAuroraRenderer
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class EdgeAuroraWindow(
    private val context: Context,
    private val mainHandler: Handler,
    private val controller: EdgeAuroraController,
) {
    private val wm = context.getSystemService(WindowManager::class.java)!!
    private val ime = context.getSystemService(InputMethodManager::class.java)!!
    private val density
        get() = context.resources.displayMetrics.density

    private fun dp(value: Float) = value * density

    private fun px(value: Int) = dp(value.toFloat()).toInt()

    private var root: Surface? = null
    private var params: WindowManager.LayoutParams? = null
    private var payloads = emptyList<AuroraPayload>()
    private lateinit var config: EdgeAuroraConfig
    private var dark = false
    private var selected: String? = null
    private var reading: String? = null
    private var editing = false
    private var card: Panel? = null
    private var closingCard: Panel? = null
    private var cardStamp = ""
    private var replyField: EditText? = null
    private var scrollBody: ScrollView? = null
    private val bubbles = mutableMapOf<String, PointF>()
    private val drafts = mutableMapOf<String, String>()
    private val autoSeen = mutableMapOf<String, Long>()
    private val fold = Runnable { closePanel(true) }
    private val openInterpolator = PathInterpolator(.2f, 0f, 0f, 1f)
    private val closeInterpolator = PathInterpolator(.4f, 0f, .2f, 1f)
    val isShowing
        get() = root != null
    val isTouching
        get() = root?.touching == true

    fun show(
        items: List<AuroraPayload>,
        settings: EdgeAuroraConfig,
        screenOff: Boolean,
        autoKey: String?,
    ) {
        config = settings
        payloads = items
        bubbles.keys.retainAll(items.map { it.id }.toSet())
        val keys = items.flatMap { it.entries }.map { it.key }.toSet()
        drafts.keys.retainAll(keys)
        autoSeen.keys.retainAll(keys)
        if (selected != null && items.none { it.id == selected }) closePanel()
        if (reading != null && reading !in keys) {
            reading = null
            editing = false
            cardStamp = ""
        }
        if (root == null) {
            val view = Surface()
            val lp =
                WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT,
                    )
                    .apply {
                        title = "Edge Aurora"
                        gravity = Gravity.TOP or Gravity.LEFT
                        fitInsetsTypes = 0
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        setTrustedOverlay()
                    }
            try {
                wm.addView(view, lp)
                root = view
                params = lp
            } catch (e: RuntimeException) {
                android.util.Log.w("EdgeAurora", "Unable to attach window", e)
                return
            }
        }
        setDark(screenOff, config)
        refreshCard()
        root?.rebuildTargets()
        if (autoKey != null && !editing) {
            val entry = items.flatMap { it.entries }.find { it.key == autoKey }
            if (entry != null && autoSeen[autoKey] != entry.sbn.postTime) {
                autoSeen[autoKey] = entry.sbn.postTime
                selected = items.first { entry in it.entries }.id
                reading = null
                cardStamp = ""
                refreshCard()
                mainHandler.removeCallbacks(fold)
                if (config.peekTimeout > 0)
                    mainHandler.postDelayed(fold, config.peekTimeout * 1000L)
            }
        }
        root?.invalidate()
        controller.onWindowContentsChanged(items)
    }

    fun setDark(value: Boolean, settings: EdgeAuroraConfig) {
        dark = value
        root?.setBackgroundColor(if (dark) Color.BLACK else Color.TRANSPARENT)
        root?.clock?.visibility = if (dark && settings.clock) View.VISIBLE else View.GONE
        updateFlags()
    }

    fun closePanel(animate: Boolean = false) {
        mainHandler.removeCallbacks(fold)
        replyField?.let { ime.hideSoftInputFromWindow(it.windowToken, 0) }
        replyField = null
        scrollBody = null
        if (!animate) removeClosingCard()
        card?.let { box ->
            removeClosingCard()
            val surface = root
            val anchor = selected?.let { surface?.anchorBounds(it) }
            box.animate().cancel()
            if (animate && box.isLaidOut && anchor != null && ValueAnimator.areAnimatorsEnabled()) {
                closingCard = box
                box.closing = true
                box.pivotX = box.width / 2f
                box.pivotY = box.height / 2f
                box.animate()
                    .alpha(0f)
                    .translationX(anchor.centerX() - box.left - box.pivotX)
                    .translationY(anchor.centerY() - box.top - box.pivotY)
                    .scaleX((anchor.width() / box.width).coerceIn(.01f, 1f))
                    .scaleY((anchor.height() / box.height).coerceIn(.01f, 1f))
                    .setDuration(280)
                    .setInterpolator(closeInterpolator)
                    .setUpdateListener {
                        box.invalidate()
                        surface?.requestLayout()
                    }
                    .withEndAction {
                        mainHandler.post { if (closingCard === box) removeClosingCard() }
                    }
                    .start()
            } else surface?.removeView(box)
        }
        card = null
        selected = null
        reading = null
        editing = false
        cardStamp = ""
        updateFlags()
        root?.requestLayout()
    }

    private fun removeClosingCard() {
        val box = closingCard ?: return
        closingCard = null
        box.animate().cancel()
        root?.removeView(box)
        root?.requestLayout()
    }

    fun cancelGestures() {
        root?.cancelGestures()
        root?.rebuildTargets()
    }

    fun hide() {
        root?.cancelGestures()
        closePanel()
        root?.let {
            it.removeCallbacks(it.frame)
            runCatching { wm.removeViewImmediate(it) }
        }
        root = null
        params = null
        dark = false
        controller.onWindowContentsChanged(emptyList())
    }

    fun forgetBubbles() {
        bubbles.clear()
        drafts.clear()
        autoSeen.clear()
    }

    fun read(key: String) {
        val payload = payloads.find { p -> p.entries.any { it.key == key } } ?: return
        selected = payload.id
        reading = key
        editing = false
        cardStamp = ""
        refreshCard()
    }

    private fun updateFlags() {
        val view = root ?: return
        val lp = params ?: return
        val focus = selected != null
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (focus) 0
                else
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) or
                (if (focus && !editing) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else 0)
        if (lp.flags != flags) {
            lp.flags = flags
            runCatching { wm.updateViewLayout(view, lp) }
        }
        view.updateBackHandler(focus)
        view.requestLayout()
    }

    private fun interacted() {
        mainHandler.removeCallbacks(fold)
        controller.touched()
    }

    private fun label(entry: NotificationEntry): String =
        runCatching {
                val app = entry.sbn.getPackageContext(context)
                app.applicationInfo.loadLabel(app.packageManager).toString()
            }
            .getOrDefault(entry.sbn.packageName)

    private data class PanelColors(
        val surface: Int,
        val surfaceTint: Int,
        val surfaceHigh: Int,
        val surfaceHighTint: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val onSurfaceAccent: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
    )

    private fun panelColors(): PanelColors {
        val surface = context.getColor(android.R.color.system_surface_container_dark)
        val surfaceHigh = context.getColor(android.R.color.system_surface_container_high_dark)
        val primary = context.getColor(android.R.color.system_primary_dark)
        val surfaceTint =
            ColorUtils.setAlphaComponent(
                surface,
                Color.alpha(context.getColor(AxBlurR.color.ax_blur_surface_bright_expressive_tint)),
            )
        val surfaceHighTint =
            ColorUtils.setAlphaComponent(
                surfaceHigh,
                Color.alpha(context.getColor(AxBlurR.color.ax_blur_surface_light_expressive_tint)),
            )
        val backdrop =
            if (config.popupBlur) ColorUtils.compositeColors(surfaceTint, Color.WHITE) else surface
        return PanelColors(
            surface,
            surfaceTint,
            surfaceHigh,
            surfaceHighTint,
            panelTextColor(context.getColor(android.R.color.system_on_surface_dark), backdrop),
            panelTextColor(
                context.getColor(android.R.color.system_on_surface_variant_dark),
                backdrop,
            ),
            primary,
            panelTextColor(primary, backdrop),
            context.getColor(android.R.color.system_on_primary_dark),
            context.getColor(android.R.color.system_primary_container_dark),
            context.getColor(android.R.color.system_on_primary_container_dark),
        )
    }

    private fun panelTextColor(color: Int, backdrop: Int): Int {
        for (step in 0..20) {
            val candidate = ColorUtils.blendARGB(color, Color.WHITE, step / 20f)
            if (ColorUtils.calculateContrast(candidate, backdrop) >= 4.5) return candidate
        }
        return Color.WHITE
    }

    private fun refreshCard() {
        val surface = root ?: return
        val payload = payloads.find { it.id == selected } ?: return
        val colors = panelColors()
        val stamp =
            "${payload.id}:${reading}:${payload.profile.color}:${config.popupBlur}:${colors}:" +
                payload.entries.joinToString {
                    "${it.key}:${it.sbn.postTime}:${it.sbn.notification.hashCode()}:" +
                        "${controller.redacted(it)}:${controller.fingerprint(it.sbn.notification)}"
                }
        if (stamp == cardStamp) {
            surface.positionCard()
            return
        }
        cardStamp = stamp
        val oldField = replyField
        val keepEditing = editing && oldField?.tag == reading
        val cursorStart = oldField?.selectionStart ?: 0
        val cursorEnd = oldField?.selectionEnd ?: 0
        replyField = null
        scrollBody = null
        editing = false
        val previous = card
        val opening = previous?.payloadId != payload.id
        removeClosingCard()
        if (opening) {
            previous?.animate()?.cancel()
            previous?.let { surface.removeView(it) }
        }
        val box =
            (previous?.takeUnless { opening } ?: Panel(payload.id)).apply {
                removeAllViews()
                updateColors(colors)
                elevation = dp(12f)
                setPadding(px(12), px(8), px(12), px(8))
            }
        card = box
        val entry = payload.entries.find { it.key == reading }
        val name =
            payload.profile.name.takeIf { it.isNotBlank() }
                ?: payload.entries.firstOrNull()?.let { label(it) }
                ?: context.getString(R.string.edge_aurora_title)
        val header = row()
        payload.entries.firstOrNull()?.let {
            header.addView(icon(it), LinearLayout.LayoutParams(px(36), px(36)))
        }
        header.addView(
            text(name, 15f, colors.onSurface, true).apply {
                setPaddingRelative(px(8), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            action(R.string.edge_aurora_float, colors.onPrimaryContainer, colors.primaryContainer) {
                floatPanel(payload)
            },
            actionLayoutParams(),
        )
        header.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_expand_less_rounded)
                imageTintList = ColorStateList.valueOf(colors.primary)
                contentDescription = context.getString(R.string.edge_aurora_collapse)
                tooltipText = contentDescription
                backgroundTintList = null
                background = actionBackground(colors.surfaceHigh, colors.primary)
                setPadding(px(12), px(12), px(12), px(12))
                setOnClickListener {
                    interacted()
                    closePanel(true)
                }
            },
            LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(4) },
        )
        dragHeader(header, payload)
        box.addView(header)
        val body = column()
        if (entry != null) {
            addReader(box, body, entry, colors)
        } else {
            box.addView(
                text(
                        context.getString(R.string.edge_aurora_peek_hint),
                        12f,
                        colors.onSurfaceVariant,
                    )
                    .apply {
                        setPadding(px(4), px(2), px(4), px(4))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
            )
            payload.entries.forEach { addNotification(box, body, it, colors) }
        }
        val scroller =
            ScrollView(context).apply {
                isFillViewport = false
                addView(body)
            }
        scrollBody = scroller
        val available = max(px(120), (surface.safe.height() * .55f).toInt())
        body.measure(
            View.MeasureSpec.makeMeasureSpec(px(280), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        box.addView(
            scroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                min(available, max(px(60), body.measuredHeight)),
            ),
        )
        if (
            entry != null &&
                controller.canReply(entry) &&
                EdgeAuroraController.replyAction(entry.sbn.notification) != null
        ) {
            addReply(box, entry, colors)
        }
        val footer = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        footer.addView(
            action(R.string.edge_aurora_clear, colors.onSurfaceVariant) {
                interacted()
                (if (entry == null) payload.entries else listOf(entry)).toList().forEach {
                    controller.dismiss(it.key)
                }
            }
        )
        if (entry != null)
            footer.addView(
                action(
                    R.string.edge_aurora_open_app,
                    colors.onPrimaryContainer,
                    colors.primaryContainer,
                ) {
                    controller.open(entry.key)
                },
                actionLayoutParams(),
            )
        box.addView(footer)
        if (opening) {
            surface.addView(
                box,
                FrameLayout.LayoutParams(
                    min(px(340), max(px(160), surface.width - px(24))),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        surface.positionCard()
        if (keepEditing && replyField != null) {
            editing = true
            focusReply(cursorStart, cursorEnd)
        } else if (oldField != null) ime.hideSoftInputFromWindow(surface.windowToken, 0)
        if (opening && ValueAnimator.areAnimatorsEnabled()) {
            box.alpha = 0f
            box.doOnLayout {
                if (card !== box) return@doOnLayout
                val anchor = surface.anchorBounds(payload.id)
                box.pivotX = box.width / 2f
                box.pivotY = box.height / 2f
                if (anchor != null) {
                    box.translationX = anchor.centerX() - box.left - box.pivotX
                    box.translationY = anchor.centerY() - box.top - box.pivotY
                    box.scaleX = (anchor.width() / box.width).coerceIn(.01f, 1f)
                    box.scaleY = (anchor.height() / box.height).coerceIn(.01f, 1f)
                }
                box.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(360)
                    .setInterpolator(openInterpolator)
                    .setUpdateListener {
                        box.invalidate()
                        surface.requestLayout()
                    }
                    .start()
            }
        }
        updateFlags()
    }

    private fun addNotification(
        panel: Panel,
        parent: LinearLayout,
        entry: NotificationEntry,
        colors: PanelColors,
    ) {
        val protected = controller.redacted(entry)
        val extras = entry.sbn.notification.extras
        val item =
            column().apply {
                setPadding(px(10), px(8), px(10), px(4))
                background = ripple(panel.tonalSurface(colors, 20), colors.primary, 20)
            }
        val title = if (protected) label(entry) else notificationTitle(entry.sbn.notification)
        val summary =
            if (protected) context.getString(R.string.edge_aurora_unlock)
            else extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        item.addView(
            text(title.ifBlank { label(entry) }, 16f, colors.onSurface, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        item.addView(
            text(summary, 14f, colors.onSurfaceVariant).apply {
                setPadding(0, px(2), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        val actions = row()
        actions.addView(
            text(
                    DateUtils.getRelativeTimeSpanString(
                            entry.sbn.postTime,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        )
                        .toString(),
                    11f,
                    colors.onSurfaceVariant,
                )
                .apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            action(
                R.string.edge_aurora_expand,
                colors.onPrimaryContainer,
                colors.primaryContainer,
            ) {
                interacted()
                if (protected) controller.unlockToRead(entry.key) else read(entry.key)
            },
            actionLayoutParams(),
        )
        if (
            controller.canReply(entry) &&
                EdgeAuroraController.replyAction(entry.sbn.notification) != null
        ) {
            actions.addView(
                action(R.string.edge_aurora_reply, colors.onPrimary, colors.primary) {
                    read(entry.key)
                    focusReply()
                },
                actionLayoutParams(),
            )
        }
        item.addView(actions)
        item.setOnClickListener { controller.open(entry.key) }
        var startX = 0f
        var startY = 0f
        var swiping = false
        item.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swiping = false
                    startX = event.rawX
                    startY = event.rawY
                    interacted()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    if (abs(dx) > abs(event.rawY - startY) && abs(dx) > dp(12f)) {
                        swiping = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.translationX = dx
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (abs(view.translationX) > dp(72f)) {
                        controller.dismiss(entry.key)
                        return@setOnTouchListener true
                    }
                    view.animate().translationX(0f).setDuration(160).start()
                    if (swiping) return@setOnTouchListener true
                }
                MotionEvent.ACTION_CANCEL -> view.translationX = 0f
            }
            false
        }
        parent.addView(
            item,
            LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply { if (parent.childCount > 0) topMargin = px(6) },
        )
    }

    private fun addReader(
        panel: Panel,
        parent: LinearLayout,
        entry: NotificationEntry,
        colors: PanelColors,
    ) {
        if (controller.redacted(entry)) {
            parent.addView(
                action(
                    R.string.edge_aurora_unlock,
                    colors.onPrimaryContainer,
                    colors.primaryContainer,
                ) {
                    controller.unlockToRead(entry.key)
                }
            )
            return
        }
        val n = entry.sbn.notification
        val title = notificationTitle(n)
        val isGroupConversation = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        parent.addView(
            text(title, 17f, colors.onSurface, true).apply { setPadding(0, px(12), 0, px(8)) }
        )
        val messages =
            runCatching {
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                        n.extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
                    ) +
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                        )
                }
                .getOrDefault(emptyList())
                .takeLast(50)
        if (messages.isNotEmpty()) {
            messages.forEach { message ->
                val sender = message.senderPerson?.name?.toString().orEmpty()
                if (sender.isNotBlank() && (isGroupConversation || sender != title))
                    parent.addView(text(sender, 12f, colors.onSurfaceAccent, true))
                val content =
                    message.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.edge_aurora_attachment)
                parent.addView(
                    text(content, 16f, colors.onSurface).apply {
                        background = panel.tonalSurface(colors, 20)
                        setPadding(px(12), px(8), px(12), px(8))
                    }
                )
                parent.addView(
                    text(
                            DateUtils.formatDateTime(
                                context,
                                message.timestamp,
                                DateUtils.FORMAT_SHOW_TIME,
                            ),
                            11f,
                            colors.onSurfaceVariant,
                        )
                        .apply { setPadding(0, px(4), 0, px(10)) }
                )
            }
        } else {
            val content =
                n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: n.extras
                        .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                        ?.joinToString("\n")
                    ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            parent.addView(
                text(content, 16f, colors.onSurface).apply { setPadding(0, 0, 0, px(12)) }
            )
        }
    }

    private fun notificationTitle(notification: Notification): String {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf {
                it.isNotBlank()
            } ?: return title
        val message =
            runCatching {
                    Notification.MessagingStyle.findLatestIncomingMessage(
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                        )
                    )
                }
                .getOrNull()
        val sender =
            message
                ?.let {
                    it.senderPerson?.name?.takeIf { name -> name.isNotEmpty() }
                        ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
                }
                ?.toString()
        return if (title.isBlank() || conversationTitle == sender) conversationTitle else title
    }

    private fun addReply(parent: Panel, entry: NotificationEntry, colors: PanelColors) {
        val line = row().apply { setPadding(0, px(4), 0, 0) }
        val field =
            EditText(context).apply {
                setTextColor(colors.onSurface)
                setHintTextColor(colors.onSurfaceVariant)
                setTextSize(16f)
                hint = context.getString(R.string.edge_aurora_reply_hint)
                backgroundTintList = null
                background =
                    StateListDrawable().apply {
                        addState(
                            intArrayOf(android.R.attr.state_focused),
                            parent.tonalSurface(colors, 24, colors.primary),
                        )
                        addState(intArrayOf(), parent.tonalSurface(colors, 24))
                    }
                highlightColor = ColorUtils.setAlphaComponent(colors.primary, 0x66)
                textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(colors.primary) }
                setPadding(px(12), px(4), px(12), px(4))
                maxLines = 3
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                tag = entry.key
                setText(drafts[entry.key].orEmpty())
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            drafts[entry.key] = s.toString()
                            interacted()
                        }

                        override fun afterTextChanged(s: Editable?) {}
                    }
                )
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        interacted()
                        editing = true
                        updateFlags()
                    }
                    false
                }
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEND) {
                        send(entry.key)
                        true
                    } else false
                }
            }
        replyField = field
        line.addView(field, LinearLayout.LayoutParams(0, px(48), 1f))
        line.addView(
            action(R.string.edge_aurora_send, colors.onPrimary, colors.primary) { send(entry.key) },
            actionLayoutParams(),
        )
        parent.addView(line)
    }

    private fun focusReply(start: Int = -1, end: Int = -1) {
        interacted()
        editing = true
        updateFlags()
        replyField?.let { field ->
            field.post {
                if (field === replyField && isShowing) {
                    field.requestFocus()
                    val length = field.text.length
                    field.setSelection(
                        if (start < 0) length else start.coerceIn(0, length),
                        if (end < 0) length else end.coerceIn(0, length),
                    )
                    ime.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun send(key: String) {
        val value = replyField?.text?.toString().orEmpty().trim()
        if (value.isEmpty()) return
        if (controller.reply(key, value)) drafts.remove(key)
        else Toast.makeText(context, R.string.edge_aurora_reply_failed, Toast.LENGTH_SHORT).show()
    }

    private fun floatPanel(payload: AuroraPayload) {
        interacted()
        val surface = root ?: return
        bubbles[payload.id] =
            PointF(.8f, (card?.y ?: surface.height * .4f) / max(1, surface.height))
        closePanel(true)
        surface.rebuildTargets()
    }

    private fun dragHeader(view: View, payload: AuroraPayload) {
        var x = 0f
        var y = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX
                    y = event.rawY
                    interacted()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val inward =
                        when (payload.profile.edge) {
                            Edge.LEFT -> event.rawX - x
                            Edge.RIGHT -> x - event.rawX
                            Edge.TOP -> event.rawY - y
                            Edge.BOTTOM -> y - event.rawY
                        }
                    if (inward > dp(70f)) floatPanel(payload)
                    true
                }
                else -> true
            }
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(false)
        }

    private fun action(
        resource: Int,
        color: Int,
        fill: Int = Color.TRANSPARENT,
        onClick: () -> Unit,
    ) =
        Button(context).apply {
            setText(resource)
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color)
            minWidth = px(48)
            minimumWidth = px(48)
            minHeight = px(48)
            minimumHeight = px(48)
            stateListAnimator = null
            backgroundTintList = null
            background = actionBackground(fill, color)
            setPadding(px(12), 0, px(12), 0)
            setOnClickListener {
                interacted()
                onClick()
            }
        }

    private fun actionLayoutParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48)).apply {
            marginStart = px(4)
        }

    private fun actionBackground(fill: Int, color: Int) =
        InsetDrawable(ripple(fill, color, 20), 0, px(4), 0, px(4))

    private fun ripple(fill: Int, color: Int, radius: Int) =
        ripple(shape(fill, radius), color, radius)

    private fun ripple(fill: Drawable, color: Int, radius: Int) =
        RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x28)),
            fill,
            shape(Color.WHITE, radius),
        )

    private fun icon(entry: NotificationEntry) =
        ImageView(context).apply {
            val app = runCatching { entry.sbn.getPackageContext(context) }.getOrNull()
            setImageDrawable(
                runCatching { app?.applicationInfo?.loadIcon(app.packageManager) }.getOrNull()
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun shape(fill: Int, radius: Int, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius.toFloat())
            setStroke(max(1, px(1)), border)
        }

    private inner class Panel(val payloadId: String) : LinearLayout(context) {
        private val blur = AxBlurBackgroundRenderer(this)
        private val tonalSurfaces = mutableListOf<GradientDrawable>()
        private var drawingBlur = false
        var closing = false

        init {
            orientation = VERTICAL
            clipToOutline = true
        }

        fun updateColors(colors: PanelColors) {
            tonalSurfaces.clear()
            blur.setEnabled(config.popupBlur)
            background =
                object : GradientDrawable() {
                    init {
                        setColor(colors.surface)
                        cornerRadius = dp(28f)
                    }

                    override fun draw(canvas: Canvas) {
                        val drewBlur =
                            blur.drawBackgroundWithOverlayColor(canvas, this, colors.surfaceTint)
                        if (drawingBlur != drewBlur) {
                            drawingBlur = drewBlur
                            val fill = if (drewBlur) colors.surfaceHighTint else colors.surfaceHigh
                            tonalSurfaces.forEach { it.setColor(fill) }
                        }
                        if (!drawingBlur) {
                            super.draw(canvas)
                        }
                    }
                }
        }

        fun tonalSurface(colors: PanelColors, radius: Int, border: Int = Color.TRANSPARENT) =
            shape(if (drawingBlur) colors.surfaceHighTint else colors.surfaceHigh, radius, border)
                .also { tonalSurfaces.add(it) }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            blur.onAttachedToWindow()
        }

        override fun onDetachedFromWindow() {
            animate().cancel()
            blur.onDetachedFromWindow()
            super.onDetachedFromWindow()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            blur.onVisibilityAggregated(isVisible)
        }

        override fun verifyDrawable(who: Drawable) =
            blur.verifyDrawable(who) || super.verifyDrawable(who)

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (closing) return true
            if (event.actionMasked == MotionEvent.ACTION_DOWN) interacted()
            return super.dispatchTouchEvent(event)
        }
    }

    private inner class Surface :
        FrameLayout(context), ViewTreeObserver.OnComputeInternalInsetsListener {
        private val renderer = EdgeAuroraRenderer()
        val safe = RectF()
        private val screen = RectF()
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var bubbleHeld: String? = null
        private var bubbleTouching = false
        var touching = false
            private set
        private var bubbleZone = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var tapCandidate = false
        val clock =
            TextClock(context).apply {
                textSize = 38f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShowCurrentUserTime(true)
                format12Hour = "h:mm"
                format24Hour = "HH:mm"
                visibility = View.GONE
            }
        private val targets = mutableListOf<View>()
        private val hits = mutableMapOf<String, Rect>()
        private var imeBottom = 0
        private var held: AuroraPayload? = null
        private var startX = 0f
        private var startY = 0f
        private var moving = false
        private var moveChanged = false
        private var grabFraction = .5f
        private var dragged: Profile? = null
        private var backRegistered = false
        private val onBack = OnBackInvokedCallback { closePanel(true) }
        private val rebuild = Runnable { rebuildTargetsNow() }
        private val hold = Runnable {
            held
                ?.takeIf { tapCandidate && isAttachedToWindow && root === this }
                ?.let { heldPayload ->
                    val payload = payloads.find { it.id == heldPayload.id } ?: return@let
                    tapCandidate = false
                    if (config.openAction == EdgeAuroraConfig.OPEN_ON_HOLD) peek(payload)
                    else {
                        moving = true
                        dragged = payload.profile.copy()
                        val p = payload.profile
                        val vertical = p.edge == Edge.LEFT || p.edge == Edge.RIGHT
                        val span = if (vertical) screen.height() else screen.width()
                        val along = if (vertical) startY - screen.top else startX - screen.left
                        grabFraction = ((along / span - p.position) / p.length).coerceIn(0f, 1f)
                    }
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
        }
        val frame =
            object : Runnable {
                override fun run() {
                    if (isAttachedToWindow) {
                        if (ValueAnimator.areAnimatorsEnabled()) invalidate()
                        postDelayed(this, if (ValueAnimator.areAnimatorsEnabled()) 33 else 250)
                    }
                }
            }

        init {
            setWillNotDraw(false)
            isFocusableInTouchMode = true
            addView(
                clock,
                LayoutParams(LayoutParams.MATCH_PARENT, px(96), Gravity.TOP).apply {
                    topMargin = px(64)
                },
            )
            setOnApplyWindowInsetsListener { _, insets ->
                val stable =
                    insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                safe.set(
                    stable.left.toFloat(),
                    stable.top.toFloat(),
                    (width - stable.right).toFloat(),
                    (height - stable.bottom).toFloat(),
                )
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                rebuildTargets()
                positionCard()
                insets
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnComputeInternalInsetsListener(this)
            post(frame)
            requestApplyInsets()
        }

        override fun onDetachedFromWindow() {
            updateBackHandler(false)
            removeCallbacks(frame)
            cancelGestures()
            viewTreeObserver.removeOnComputeInternalInsetsListener(this)
            super.onDetachedFromWindow()
        }

        fun cancelGestures() {
            touching = false
            mainHandler.removeCallbacks(hold)
            mainHandler.removeCallbacks(rebuild)
            held = null
            moving = false
            moveChanged = false
            dragged = null
            tapCandidate = false
            bubbleHeld = null
            bubbleTouching = false
            bubbleZone = 0
        }

        fun updateBackHandler(enabled: Boolean) {
            if (enabled == backRegistered) return
            val dispatcher = findOnBackInvokedDispatcher() ?: return
            if (enabled)
                dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    onBack,
                )
            else dispatcher.unregisterOnBackInvokedCallback(onBack)
            backRegistered = enabled
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (oldw > 0 && oldh > 0) {
                cancelGestures()
                closePanel()
            }
            screen.set(0f, 0f, w.toFloat(), h.toFloat())
            if (safe.isEmpty) safe.set(screen)
            requestApplyInsets()
            rebuildTargets()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val time =
                if (ValueAnimator.areAnimatorsEnabled())
                    (SystemClock.uptimeMillis() % 3600000) / 1000f
                else 0f
            payloads.forEach { payload ->
                val profile = dragged?.takeIf { held?.id == payload.id } ?: payload.profile
                renderer.draw(
                    canvas,
                    profile,
                    screen,
                    density,
                    time,
                    if (payload.id in bubbles) 70 else 255,
                )
            }
            bubbleHeld?.let { id ->
                val center = clearCenter()
                targetPaint.color = if (bubbleZone == 1) 0xffb3261e.toInt() else 0xee242529.toInt()
                canvas.drawCircle(
                    center.x,
                    center.y,
                    dp(if (bubbleZone == 1) 36f else 30f),
                    targetPaint,
                )
                targetPaint.color = Color.WHITE
                targetPaint.textAlign = Paint.Align.CENTER
                targetPaint.textSize = dp(12f)
                canvas.drawText(
                    context.getString(R.string.edge_aurora_clear),
                    center.x,
                    center.y - (targetPaint.ascent() + targetPaint.descent()) / 2,
                    targetPaint,
                )
                hits[id]?.let { hit ->
                    targetPaint.style = Paint.Style.STROKE
                    targetPaint.strokeWidth = dp(if (bubbleZone == 2) 3f else 1.5f)
                    targetPaint.color = payloads.find { it.id == id }?.profile?.color ?: Color.WHITE
                    canvas.drawCircle(hit.exactCenterX(), hit.exactCenterY(), dp(24f), targetPaint)
                    targetPaint.style = Paint.Style.FILL
                }
            }
        }

        private fun clearCenter() =
            PointF(safe.centerX(), max(safe.top + dp(40f), safe.bottom - dp(64f)))

        fun anchorBounds(id: String): RectF? {
            val payload = payloads.find { it.id == id } ?: return null
            bubbles[id]?.let { position ->
                val size = dp(56f)
                val x = (position.x * width).coerceIn(safe.left, max(safe.left, safe.right - size))
                val y = (position.y * height).coerceIn(safe.top, max(safe.top, safe.bottom - size))
                return RectF(x, y, x + size, y + size)
            }
            if (screen.isEmpty) return null
            val p = payload.profile
            val vertical = p.edge == Edge.LEFT || p.edge == Edge.RIGHT
            val span = if (vertical) screen.height() else screen.width()
            val start = (if (vertical) screen.top else screen.left) + p.position * span
            val stop = start + p.length * span
            val thickness = dp(p.thickness)
            return when (p.edge) {
                Edge.LEFT -> RectF(screen.left, start, screen.left + thickness, stop)
                Edge.RIGHT -> RectF(screen.right - thickness, start, screen.right, stop)
                Edge.TOP -> RectF(start, screen.top, stop, screen.top + thickness)
                Edge.BOTTOM -> RectF(start, screen.bottom - thickness, stop, screen.bottom)
            }
        }

        fun rebuildTargets() {
            mainHandler.removeCallbacks(rebuild)
            if (isAttachedToWindow) mainHandler.post(rebuild)
        }

        private fun rebuildTargetsNow() {
            if (!isAttachedToWindow || root !== this || held != null || bubbleTouching) return
            val previous = targets.toList()
            targets.clear()
            hits.clear()
            previous.forEach { removeView(it) }
            if (width == 0 || height == 0 || safe.isEmpty) return
            payloads.forEach { payload ->
                val p = payload.profile
                val vertical = p.edge == Edge.LEFT || p.edge == Edge.RIGHT
                val span = if (vertical) screen.height() else screen.width()
                val start = (if (vertical) screen.top else screen.left) + p.position * span
                val stop = start + p.length * span
                val grip = dp(max(22f, p.thickness + 10f))
                val rect =
                    when (p.edge) {
                        Edge.LEFT -> RectF(screen.left, start, screen.left + grip, stop)
                        Edge.RIGHT -> RectF(screen.right - grip, start, screen.right, stop)
                        Edge.TOP -> RectF(start, screen.top, stop, screen.top + grip)
                        Edge.BOTTOM -> RectF(start, screen.bottom - grip, stop, screen.bottom)
                    }
                hits[payload.id] = Rect().also { rect.roundOut(it) }
                val target =
                    View(context).apply {
                        contentDescription =
                            context.getString(
                                R.string.edge_aurora_peek_app,
                                p.name.ifBlank {
                                    payload.entries.firstOrNull()?.let { label(it) }
                                        ?: context.getString(R.string.edge_aurora_title)
                                },
                            )
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        setOnClickListener {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            interacted()
                            peek(payload)
                        }
                        setOnTouchListener { view, event ->
                            val translated = MotionEvent.obtain(event)
                            translated.offsetLocation(x, y)
                            val handled = onGlowTouch(view, payload, translated)
                            translated.recycle()
                            handled
                        }
                    }
                addView(
                    target,
                    LayoutParams(max(1, rect.width().toInt()), max(1, rect.height().toInt()))
                        .apply {
                            leftMargin = rect.left.toInt()
                            topMargin = rect.top.toInt()
                        },
                )
                targets.add(target)
                bubbles[payload.id]?.let { position ->
                    val bubble =
                        payload.entries.firstOrNull()?.let { icon(it) } ?: ImageView(context)
                    bubble.contentDescription = target.contentDescription
                    bubble.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    bubble.isFocusable = true
                    bubble.setPadding(px(10), px(10), px(10), px(10))
                    bubble.background = shape(0xf5222428.toInt(), 28, p.color)
                    val bx =
                        (position.x * width).coerceIn(
                            safe.left,
                            max(safe.left, safe.right - dp(56f)),
                        )
                    val by =
                        (position.y * height).coerceIn(
                            safe.top,
                            max(safe.top, safe.bottom - dp(56f)),
                        )
                    addView(
                        bubble,
                        LayoutParams(px(56), px(56)).apply {
                            leftMargin = bx.toInt()
                            topMargin = by.toInt()
                        },
                    )
                    bubbleDrag(bubble, payload)
                    targets.add(bubble)
                }
            }
            card?.bringToFront()
            positionCard()
            requestLayout()
            invalidate()
        }

        private fun peek(payload: AuroraPayload) {
            val current = payloads.find { it.id == payload.id } ?: return
            if (current.entries.isEmpty()) return
            selected = current.id
            reading = null
            cardStamp = ""
            refreshCard()
        }

        private fun onGlowTouch(view: View, payload: AuroraPayload, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(hold)
                    interacted()
                    held = payload
                    startX = event.x
                    startY = event.y
                    moving = false
                    moveChanged = false
                    dragged = null
                    tapCandidate = true
                    mainHandler.postDelayed(hold, controller.longPressTimeout())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (!moving && max(abs(dx), abs(dy)) > touchSlop) {
                        tapCandidate = false
                        mainHandler.removeCallbacks(hold)
                    }
                    if (moving) {
                        if (!moveChanged && max(abs(dx), abs(dy)) <= touchSlop) return true
                        moveChanged = true
                        val p = dragged ?: return true
                        val distances =
                            listOf(
                                abs(event.x - screen.left),
                                abs(screen.right - event.x),
                                abs(event.y - screen.top),
                                abs(screen.bottom - event.y),
                            )
                        p.edge = Edge.values()[distances.indexOf(distances.minOrNull())]
                        val vertical = p.edge == Edge.LEFT || p.edge == Edge.RIGHT
                        p.position =
                            if (vertical)
                                (event.y - screen.top) / screen.height() - p.length * grabFraction
                            else (event.x - screen.left) / screen.width() - p.length * grabFraction
                        p.clamp()
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(hold)
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (moving && moveChanged)
                        dragged?.let { controller.move(it.id, it.edge, it.position) }
                    else if (
                        config.openAction == EdgeAuroraConfig.OPEN_ON_TAP &&
                            tapCandidate &&
                            max(abs(dx), abs(dy)) <= touchSlop
                    )
                        view.performClick()
                    held = null
                    moving = false
                    moveChanged = false
                    dragged = null
                    tapCandidate = false
                    rebuildTargets()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(hold)
                    held = null
                    moving = false
                    moveChanged = false
                    dragged = null
                    tapCandidate = false
                    rebuildTargets()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    tapCandidate = false
                    moving = false
                    moveChanged = false
                    dragged = null
                    mainHandler.removeCallbacks(hold)
                    return true
                }
            }
            return false
        }

        private fun bubbleDrag(view: View, payload: AuroraPayload) {
            var x = 0f
            var y = 0f
            var originalX = 0f
            var originalY = 0f
            var dragging = false
            view.setOnClickListener {
                interacted()
                peek(payload)
            }
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        interacted()
                        bubbleTouching = true
                        x = event.rawX
                        y = event.rawY
                        originalX = v.x
                        originalY = v.y
                        dragging = false
                        bubbleZone = 0
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - x
                        val dy = event.rawY - y
                        if (!dragging && max(abs(dx), abs(dy)) < dp(10f))
                            return@setOnTouchListener true
                        dragging = true
                        bubbleHeld = payload.id
                        closePanel()
                        val bx = originalX + dx
                        val by = originalY + dy
                        val cx = bx + v.width / 2f
                        val cy = by + v.height / 2f
                        val clear = clearCenter()
                        val hit = hits[payload.id]
                        val clearDistance = kotlin.math.hypot(cx - clear.x, cy - clear.y)
                        val dockDistance =
                            hit?.let {
                                kotlin.math.hypot(cx - it.exactCenterX(), cy - it.exactCenterY())
                            } ?: Float.MAX_VALUE
                        val zone =
                            when {
                                clearDistance < dp(72f) && clearDistance <= dockDistance -> 1
                                dockDistance < dp(56f) -> 2
                                else -> 0
                            }
                        if (zone != bubbleZone) {
                            bubbleZone = zone
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        v.x =
                            when (zone) {
                                1 -> clear.x - v.width / 2f
                                2 -> hit!!.exactCenterX() - v.width / 2f
                                else -> bx.coerceIn(safe.left, max(safe.left, safe.right - v.width))
                            }
                        v.y =
                            when (zone) {
                                1 -> clear.y - v.height / 2f
                                2 -> hit!!.exactCenterY() - v.height / 2f
                                else -> by.coerceIn(safe.top, max(safe.top, safe.bottom - v.height))
                            }
                        requestLayout()
                        invalidate()
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                        bubbleTouching = false
                        bubbleHeld = null
                        if (!cancelled && !dragging) v.performClick()
                        else if (!cancelled) {
                            when (bubbleZone) {
                                1 -> {
                                    bubbles.remove(payload.id)
                                    payloads
                                        .find { it.id == payload.id }
                                        ?.entries
                                        ?.toList()
                                        ?.forEach { controller.dismiss(it.key) }
                                }
                                2 -> bubbles.remove(payload.id)
                                else -> {
                                    val parkedX =
                                        if (v.x + v.width / 2f < safe.centerX()) safe.left
                                        else max(safe.left, safe.right - v.width)
                                    bubbles[payload.id] = PointF(parkedX / width, v.y / height)
                                }
                            }
                        }
                        dragging = false
                        bubbleZone = 0
                        rebuildTargets()
                        true
                    }
                    else -> true
                }
            }
        }

        fun positionCard() {
            val box = card ?: return
            val payload = payloads.find { it.id == selected } ?: return
            val anchor = anchorBounds(payload.id) ?: return
            val availableBottom = min(safe.bottom, height - imeBottom.toFloat())
            val availableWidth = max(px(160), safe.width().toInt() - px(24))
            val cardWidth = min(px(340), availableWidth)
            val availableHeight = max(px(120), availableBottom.toInt() - safe.top.toInt() - px(16))
            scrollBody?.let { scroll ->
                val body = scroll.getChildAt(0)
                scroll.layoutParams.height = 0
                box.measure(
                    View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val controlsHeight = box.measuredHeight
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        max(1, cardWidth - box.paddingLeft - box.paddingRight),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                scroll.layoutParams.height =
                    min(
                        body.measuredHeight,
                        min(
                            (safe.height() * .55f).toInt(),
                            max(0, availableHeight - controlsHeight),
                        ),
                    )
            }
            box.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST),
            )
            val floating = payload.id in bubbles
            val atX =
                if (floating) {
                    if (anchor.centerX() < safe.centerX()) anchor.right + dp(8f)
                    else anchor.left - cardWidth - dp(8f)
                } else {
                    when (payload.profile.edge) {
                        Edge.LEFT -> max(safe.left, anchor.right) + dp(12f)
                        Edge.RIGHT -> min(safe.right, anchor.left) - cardWidth - dp(12f)
                        else -> anchor.centerX() - cardWidth / 2f
                    }
                }
            val atY =
                if (floating) anchor.centerY() - box.measuredHeight / 2f
                else {
                    when (payload.profile.edge) {
                        Edge.TOP -> max(safe.top, anchor.bottom) + dp(8f)
                        Edge.BOTTOM ->
                            min(availableBottom, anchor.top) - box.measuredHeight - dp(8f)
                        else -> anchor.centerY() - box.measuredHeight / 2f
                    }
                }
            val left =
                atX.coerceIn(
                        safe.left + dp(4f),
                        max(safe.left + dp(4f), safe.right - cardWidth - dp(4f)),
                    )
                    .toInt()
            val top =
                atY.coerceIn(
                        safe.top + dp(8f),
                        max(safe.top + dp(8f), availableBottom - box.measuredHeight - dp(8f)),
                    )
                    .toInt()
            box.layoutParams =
                LayoutParams(cardWidth, box.measuredHeight).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    leftMargin = left
                    topMargin = top
                }
        }

        override fun onComputeInternalInsets(info: ViewTreeObserver.InternalInsetsInfo) {
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.setEmpty()
            if (dark) info.touchableRegion.set(0, 0, width, height)
            else {
                hits.values.forEach { info.touchableRegion.op(it, Region.Op.UNION) }
                targets.forEach { view ->
                    info.touchableRegion.op(
                        Rect(
                            view.x.toInt(),
                            view.y.toInt(),
                            (view.x + view.width).toInt(),
                            (view.y + view.height).toInt(),
                        ),
                        Region.Op.UNION,
                    )
                }
                (card ?: closingCard)?.let { box ->
                    val bounds = RectF(0f, 0f, box.width.toFloat(), box.height.toFloat())
                    box.matrix.mapRect(bounds)
                    bounds.offset(box.left.toFloat(), box.top.toFloat())
                    info.touchableRegion.op(Rect().also { bounds.roundOut(it) }, Region.Op.UNION)
                }
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                closePanel(true)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) touching = false
            if (event.actionMasked == MotionEvent.ACTION_UP && isAttachedToWindow) interacted()
            return handled
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (
                event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                    event.actionMasked == MotionEvent.ACTION_UP
            ) {
                interacted()
                closePanel(true)
                return true
            }
            return dark || super.onTouchEvent(event)
        }
    }
}
