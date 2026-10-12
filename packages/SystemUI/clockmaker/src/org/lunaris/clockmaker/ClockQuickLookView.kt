/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.widget.TextView
import com.android.axion.quicklook.QuickLookAction
import com.android.axion.quicklook.QuickLookTarget
import kotlin.math.roundToInt

internal class ClockQuickLookView(context: Context) : TextView(context) {
    private var target: QuickLookTarget? = null
    private var icon: Drawable? = null
    private var iconRequest = 0
    private var onAction: ((QuickLookAction) -> Unit)? = null

    init {
        setOnClickListener {
            target?.takeUnless { it.isExpired }?.primaryAction?.let { action -> onAction?.invoke(action) }
        }
        isClickable = false
    }

    fun bind(content: ClockQuickLookContent, dozing: Boolean, onAction: ((QuickLookAction) -> Unit)?) {
        if (text.toString() != content.text) text = content.text
        this.onAction = onAction
        isClickable = onAction != null && content.target?.primaryAction != null
        isFocusable = isClickable
        compoundDrawableTintList = if (dozing) ColorStateList.valueOf(Color.WHITE) else null
        if (target !== content.target) {
            target = content.target
            iconRequest++
            icon = null
            setCompoundDrawablesRelative(null, null, null, null)
            loadIcon()
        }
    }

    private fun loadIcon() {
        val value = target ?: return
        if (!isAttachedToWindow) return
        val request = ++iconRequest
        ClockFonts.worker.execute {
            val drawable = runCatching {
                val bytes = value.iconBytes
                if (bytes != null && bytes.size <= 1024 * 1024) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 128) {
                        options.inSampleSize *= 2
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { BitmapDrawable(resources, it) }
                } else {
                    value.extras?.getParcelable(QuickLookTarget.EXTRA_SMARTSPACE_ICON, Icon::class.java)
                        ?.loadDrawable(context) ?: if (value.iconResId != 0) {
                        context.packageManager.getResourcesForApplication(ClockQuickLook.SERVICE_PACKAGE)
                            .getDrawable(value.iconResId, null)
                    } else null
                }
            }.getOrNull()
            post {
                if (request == iconRequest && isAttachedToWindow) {
                    icon = drawable?.mutate()
                    updateIconBounds()
                }
            }
        }
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        updateIconBounds()
    }

    private fun updateIconBounds() {
        val drawable = icon ?: return
        val size = (textSize * 1.1f).roundToInt().coerceAtLeast(1)
        if (drawable.bounds.width() == size && compoundDrawablesRelative[0] === drawable) return
        drawable.setBounds(0, 0, size, size)
        compoundDrawablePadding = (textSize * 0.3f).roundToInt()
        setCompoundDrawablesRelative(drawable, null, null, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (icon == null) loadIcon()
    }

    override fun onDetachedFromWindow() {
        iconRequest++
        super.onDetachedFromWindow()
    }
}
