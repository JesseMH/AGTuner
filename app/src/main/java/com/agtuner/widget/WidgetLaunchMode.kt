package com.agtuner.widget

import com.agtuner.tuning.NoteFrequencies

/**
 * Identifies how the user launched the app from the home-screen widget so the
 * tuner can configure itself before starting. Each entry carries the [presetId]
 * of the tuning preset to apply on launch. Serialized in the launch intent via
 * [name]; parsed back with [fromIntentExtra].
 */
enum class WidgetLaunchMode(val presetId: String) {
    STANDARD(NoteFrequencies.PRESET_STANDARD),
    HALF_STEP_DOWN(NoteFrequencies.PRESET_HALF_STEP_DOWN),
    DROP_D(NoteFrequencies.PRESET_DROP_D),
    ;

    companion object {
        const val INTENT_EXTRA_KEY = "com.agtuner.widget.EXTRA_TUNING_MODE"

        fun fromIntentExtra(value: String?): WidgetLaunchMode? =
            value?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
