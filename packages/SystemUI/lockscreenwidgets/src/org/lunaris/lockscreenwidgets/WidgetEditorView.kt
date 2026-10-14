/* SPDX-License-Identifier: Apache-2.0 */
package org.lunaris.lockscreenwidgets

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class WidgetEditorView(context: Context) : LinearLayout(context) {
    val grid = WidgetGridView(context).apply { editing = true }
    var sizingWidth = 0
    var onAdd: () -> Unit = {}
    var onRemove: (Int) -> Unit = {}
    var onConfigure: (Int) -> Unit = {}
    var onChange: (WidgetEntry) -> Unit = {}
    private var providerKeys = emptyMap<Int, String>()
    private var infos: Map<Int, AppWidgetProviderInfo> = emptyMap()
    private val controls = LinearLayout(context).apply { orientation = VERTICAL }
    private val labels = TextView(context)
    private var entries: List<WidgetEntry> = emptyList()

    init {
        orientation = VERTICAL
        addView(Button(context).apply {
            setText(R.string.app_widgets_add)
            setOnClickListener { onAdd() }
        })
        addView(labels)
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(controls)
        grid.onSelect = { updateControls() }
        grid.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateControls()
        }
    }

    fun update(items: List<WidgetEntry>, providers: Map<Int, AppWidgetProviderInfo>,
        create: (WidgetEntry) -> View) {
        val keys = providers.mapValues { it.value.viewKey() }
        if (keys != providerKeys) grid.clearWidgets()
        providerKeys = keys
        entries = items
        infos = providers
        if (items.none { it.id == grid.selectedId }) grid.selectedId = items.firstOrNull()?.id ?: -1
        grid.setWidgets(items, create)
        labels.setText(if (items.isEmpty()) R.string.app_widgets_empty else R.string.app_widgets_select)
        updateControls()
    }

    private fun updateControls() {
        controls.removeAllViews()
        val entry = entries.firstOrNull { it.id == grid.selectedId } ?: return
        choice(R.string.app_widgets_selected, entries.map {
            val name = infos[it.id]?.loadLabel(context.packageManager)
                ?: context.getString(R.string.app_widgets_unavailable)
            context.getString(R.string.app_widgets_provider_size, name, it.width, it.height)
        }, entries.indexOf(entry)) {
            grid.selectedId = entries[it].id
            updateControls()
        }
        val info = infos[entry.id]
        controls.addView(TextView(context).apply {
            text = info?.loadLabel(context.packageManager) ?: context.getString(R.string.app_widgets_unavailable)
            textSize = 18f
            setPadding(0, 12, 0, 8)
        })
        if (info != null && grid.width > 0) {
            val sizes = (WidgetSizes(context, sizingWidth.takeIf { it > 0 } ?: grid.width).allowed(info) + (entry.width to entry.height))
                .distinct().filter { (w, h) -> WidgetEntry.place(entry.id, w, h, entries) != null }
            choice(R.string.app_widgets_size, sizes.map { "${it.first} × ${it.second}" },
                sizes.indexOf(entry.width to entry.height)) { index ->
                val (w, h) = sizes[index]
                val changed = entry.copy(width = w, height = h)
                val placed = if (changed.fits(entries)) changed else WidgetEntry.place(entry.id, w, h, entries)
                placed?.let(onChange)
            }
        }
        val positions = buildList {
            for (y in 0..4 - entry.height) for (x in 0..4 - entry.width) {
                entry.copy(x = x, y = y).takeIf { it.fits(entries) }?.let(::add)
            }
        }
        choice(R.string.app_widgets_position, positions.map {
            context.getString(R.string.app_widgets_position_value, it.x + 1, it.y + 1)
        }, positions.indexOf(entry)) { onChange(positions[it]) }
        val buttons = LinearLayout(context)
        if (info?.configure != null && info.widgetFeatures and
            AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0) {
            buttons.addView(Button(context).apply {
                setText(R.string.app_widgets_configure)
                setOnClickListener { onConfigure(entry.id) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        buttons.addView(Button(context).apply {
            setText(R.string.app_widgets_remove)
            setOnClickListener { onRemove(entry.id) }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(buttons)
    }

    private fun choice(title: Int, values: List<String>, selected: Int, changed: (Int) -> Unit) {
        if (values.isEmpty()) return
        controls.addView(TextView(context).apply { setText(title) })
        controls.addView(Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != selected) changed(position)
                }
            }
        })
    }
}
