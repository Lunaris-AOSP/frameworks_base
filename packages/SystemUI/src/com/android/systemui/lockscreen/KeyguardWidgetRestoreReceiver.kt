/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.lockscreen

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.lunaris.lockscreenwidgets.WidgetContract

class KeyguardWidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED ||
            intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WidgetContract.HOST_ID) return
        val old = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: return
        val new = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
        if (old.size != new.size) return
        val mapping = old.zip(new.toTypedArray()).toMap()
        val store = KeyguardWidgetStore(context)
        store.save(store.entries.map { it.copy(id = mapping[it.id] ?: it.id) })
    }
}
