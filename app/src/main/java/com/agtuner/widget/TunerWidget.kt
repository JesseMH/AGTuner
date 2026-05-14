package com.agtuner.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.agtuner.MainActivity
import com.agtuner.R
import com.agtuner.tuning.NoteFrequencies
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Home-screen widget. 3x2 surface: three preset Buttons in a Row (Standard,
 * Half-Step, Drop D) with the "Last tuned …" timestamp below. Each button
 * launches [MainActivity] with [WidgetLaunchMode.INTENT_EXTRA_KEY] set to the
 * chosen preset; the activity dispatches to the ViewModel, which applies the
 * preset's strings, enables auto-detection, and starts listening (calibration
 * runs automatically as listening's first phase).
 *
 * Data source: the composable collects directly from the app's DataStore via a
 * [LaunchedEffect] inside the running Glance session. This sidesteps Glance's
 * own state-caching layer (which buffers the initial state snapshot at session
 * start and was failing to surface state writes for the very first render).
 * Whenever the app writes a new `lastInTuneAt` (or the preset id at the time
 * of that transition), the Flow emits, the composable's state updates, and
 * Glance re-renders the widget. The displayed timestamp is absolute (not
 * relative), so it doesn't need a periodic ticker to stay accurate.
 */
class TunerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d(TAG, "provideGlance start id=$id")
        provideContent {
            GlanceTheme {
                WidgetContent(context = context)
            }
        }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        // Live-collect lastInTuneAt + lastTunedPresetId from the app's DataStore.
        // Initial value is null (renders "Not yet tuned" momentarily); the first
        // Flow emission overwrites it. Glance's runtime observes the MutableState
        // reads here and re-renders whenever either changes.
        var lastInTune by remember { mutableStateOf<LastInTune>(LastInTune.Unknown) }
        LaunchedEffect(Unit) {
            try {
                val repo = EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .preferencesRepository()
                combine(repo.lastInTuneAt, repo.lastTunedPresetId) { ts, presetId ->
                    if (ts == null) LastInTune.Unknown else LastInTune.At(ts, presetId)
                }.collect { value ->
                    Log.d(TAG, "composable observed lastInTune=$value")
                    lastInTune = value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "DataStore collection failed", e)
            }
        }

        val timestampText = formatLastInTune(context, lastInTune)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Three preset buttons stacked vertically. defaultWeight() is a
            // ColumnScope extension on GlanceModifier and only resolves inside
            // this Column's lambda — extracting the buttons into a helper would
            // drop the scope receiver and break compilation.
            Button(
                text = context.getString(R.string.widget_preset_standard),
                onClick = actionStartActivity(launchIntent(context, WidgetLaunchMode.STANDARD)),
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            Button(
                text = context.getString(R.string.widget_preset_half_step_down),
                onClick = actionStartActivity(launchIntent(context, WidgetLaunchMode.HALF_STEP_DOWN)),
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            Button(
                text = context.getString(R.string.widget_preset_drop_d),
                onClick = actionStartActivity(launchIntent(context, WidgetLaunchMode.DROP_D)),
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            Text(
                text = timestampText,
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight(),
            )
        }
    }

    /**
     * Build a launch Intent targeting [MainActivity] with the chosen [mode] encoded
     * as an extra. CLEAR_TOP ensures we don't stack multiple tuner instances if
     * the app is already running.
     */
    private fun launchIntent(context: Context, mode: WidgetLaunchMode): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WidgetLaunchMode.INTENT_EXTRA_KEY, mode.name)
        }

    /**
     * Format the last-in-tune timestamp as an absolute date + time string,
     * inserting the preset name when known — "Last tuned Standard at 2:44 PM
     * on May 12" — and falling back to the preset-less label when the user
     * was on a custom tuning. Time honors the device's 12/24-hour preference;
     * date is locale-aware via [SimpleDateFormat] with pattern "MMMM d".
     */
    private fun formatLastInTune(context: Context, lastInTune: LastInTune): String {
        return when (lastInTune) {
            LastInTune.Unknown -> context.getString(R.string.widget_never_tuned)
            is LastInTune.At -> {
                val date = Date(lastInTune.epochMs)
                val time = DateFormat.getTimeFormat(context).format(date)
                val dateStr = SimpleDateFormat("MMMM d", Locale.getDefault()).format(date)
                val presetName = lastInTune.presetId?.let { id ->
                    NoteFrequencies.PRESETS.firstOrNull { it.id == id }?.let { preset ->
                        context.getString(preset.nameRes)
                    }
                }
                if (presetName != null) {
                    context.getString(R.string.widget_last_tuned_with_preset, presetName, time, dateStr)
                } else {
                    context.getString(R.string.widget_last_tuned, time, dateStr)
                }
            }
        }
    }

    /** Snapshot of the last InTune transition for widget display. */
    private sealed interface LastInTune {
        data object Unknown : LastInTune
        data class At(val epochMs: Long, val presetId: String?) : LastInTune
    }

    companion object {
        private const val TAG = "TunerWidget"

        /**
         * Trigger a refresh of every placed widget instance. With direct DataStore
         * collection inside the composable, the composition re-renders automatically
         * whenever the app writes a new value; this is kept for explicit refreshes
         * (e.g., from `MainActivity.onStop`) so the widget re-checks state when the
         * app goes to background, in case the in-session collector missed an
         * emission.
         */
        suspend fun refreshAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(TunerWidget::class.java)
            if (ids.isEmpty()) return
            val widget = TunerWidget()
            ids.forEach { id -> widget.update(context, id) }
        }
    }
}
