package com.agtuner.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidget host hookup. The framework discovers this via the manifest entry and
 * delegates rendering to [TunerWidget].
 */
class TunerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TunerWidget()
}
