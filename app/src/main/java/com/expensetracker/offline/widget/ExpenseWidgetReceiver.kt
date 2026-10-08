package com.expensetracker.offline.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class ExpenseWidgetReceiver : GlanceAppWidgetReceiver() {
    // Tells the Android OS which UI to render when it updates this widget
    override val glanceAppWidget: GlanceAppWidget = ExpenseWidget()
}