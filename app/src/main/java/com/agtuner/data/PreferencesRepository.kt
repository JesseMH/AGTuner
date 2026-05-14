package com.agtuner.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agtuner.feedback.FeedbackPreferences
import com.agtuner.tuning.NoteFrequencies
import com.agtuner.tuning.StringNote
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Main preferences - backed up to cloud
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tuner_preferences")

// Local-only preferences - NOT backed up (excluded via backup_rules.xml)
// Used for debug mode which should always start as false on new installs
private val Context.localDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_preferences")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PreferencesRepository"

        // Feedback preferences
        private val KEY_AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        private val KEY_HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        private val KEY_VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        private val KEY_VOICE_ON_IN_TUNE_ONLY = booleanPreferencesKey("voice_on_in_tune_only")

        // Tuning preferences
        private val KEY_IN_TUNE_THRESHOLD = floatPreferencesKey("in_tune_threshold")

        // Admin/debug tuning parameters (values are backed up, but debug_mode toggle is not)
        private val KEY_SMOOTHING_WINDOW = intPreferencesKey("smoothing_window")
        private val KEY_CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        private val KEY_STABILITY_COUNT = intPreferencesKey("stability_count")
        private val KEY_AUTO_STRING_DETECTION = booleanPreferencesKey("auto_string_detection")

        // Local-only keys (stored in localDataStore, not backed up)
        private val KEY_DEBUG_MODE = booleanPreferencesKey("debug_mode")

        // String configuration
        private val KEY_STRING_CONFIG = stringPreferencesKey("string_config")
        // Last selected string index — drives the widget's "tune last string" button label
        // and restores the user's prior selection on app launch.
        private val KEY_SELECTED_STRING_INDEX = intPreferencesKey("selected_string_index")

        // Widget state — last time we observed a stable InTune transition (epoch ms).
        // Used by the home-screen widget to show "Last tuned X ago". Null = never.
        private val KEY_LAST_IN_TUNE_AT = longPreferencesKey("last_in_tune_at_ms")
        // Preset id active when the last InTune transition was recorded. Null when the
        // user was on a custom tuning at the time (so the widget falls back to its
        // preset-less label).
        private val KEY_LAST_TUNED_PRESET_ID = stringPreferencesKey("last_tuned_preset_id")

        // Defaults
        const val DEFAULT_IN_TUNE_THRESHOLD = 10f  // ±10 cents for guitar
        const val DEFAULT_SMOOTHING_WINDOW = 20
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.90f
        const val DEFAULT_STABILITY_COUNT = 10
    }

    /**
     * Safely access DataStore with error handling for IO exceptions.
     * Returns empty preferences on error rather than crashing.
     */
    private val safeDataStore: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preferences", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    /**
     * Safely access local DataStore (not backed up) with error handling.
     */
    private val safeLocalDataStore: Flow<Preferences> = context.localDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading local preferences", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    // Feedback preferences flow
    val feedbackPreferences: Flow<FeedbackPreferences> = safeDataStore.map { prefs ->
        FeedbackPreferences(
            audioEnabled = prefs[KEY_AUDIO_ENABLED] ?: true,
            hapticEnabled = prefs[KEY_HAPTIC_ENABLED] ?: true,
            voiceEnabled = prefs[KEY_VOICE_ENABLED] ?: true,
            voiceOnInTuneOnly = prefs[KEY_VOICE_ON_IN_TUNE_ONLY] ?: false // Default: announce all states
        )
    }

    // Tuning threshold flow
    val inTuneThreshold: Flow<Float> = safeDataStore.map { prefs ->
        prefs[KEY_IN_TUNE_THRESHOLD] ?: DEFAULT_IN_TUNE_THRESHOLD
    }

    // Admin tuning parameters flows
    val smoothingWindow: Flow<Int> = safeDataStore.map { prefs ->
        prefs[KEY_SMOOTHING_WINDOW] ?: DEFAULT_SMOOTHING_WINDOW
    }

    val confidenceThreshold: Flow<Float> = safeDataStore.map { prefs ->
        prefs[KEY_CONFIDENCE_THRESHOLD] ?: DEFAULT_CONFIDENCE_THRESHOLD
    }

    val stabilityCount: Flow<Int> = safeDataStore.map { prefs ->
        prefs[KEY_STABILITY_COUNT] ?: DEFAULT_STABILITY_COUNT
    }

    // Debug mode flow (from local-only store, not backed up)
    val debugMode: Flow<Boolean> = safeLocalDataStore.map { prefs ->
        prefs[KEY_DEBUG_MODE] ?: false
    }

    // Auto string detection flow
    val autoStringDetection: Flow<Boolean> = safeDataStore.map { prefs ->
        prefs[KEY_AUTO_STRING_DETECTION] ?: false
    }

    // String configuration flow. Falls back to STANDARD_GUITAR whenever the persisted
    // value is missing, unparseable, or yields zero usable strings — so a corrupt or
    // empty saved value can never surface as a 0-string UI.
    val stringConfiguration: Flow<List<StringNote>> = safeDataStore.map { prefs ->
        val configString = prefs[KEY_STRING_CONFIG] ?: return@map NoteFrequencies.STANDARD_GUITAR
        val parsed = try {
            parseDelimitedStringConfig(configString)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse string config, using default", e)
            emptyList()
        }
        parsed.ifEmpty { NoteFrequencies.STANDARD_GUITAR }
    }

    // Last selected string index. May be out of range if the string configuration
    // shrinks — callers should coerce against the current configuration size.
    val selectedStringIndex: Flow<Int> = safeDataStore.map { prefs ->
        prefs[KEY_SELECTED_STRING_INDEX] ?: 0
    }

    // Last in-tune timestamp (epoch ms). Null when the user has never achieved InTune.
    // distinctUntilChanged because DataStore re-emits on every write (including unrelated
    // keys), and downstream collectors (e.g. the widget composable) shouldn't see
    // duplicate values.
    val lastInTuneAt: Flow<Long?> = safeDataStore.map { prefs ->
        prefs[KEY_LAST_IN_TUNE_AT]
    }.distinctUntilChanged()

    // Preset id at the time of the last InTune transition; null = custom tuning was
    // active. Mirrors [lastInTuneAt]'s distinctUntilChanged for the same reason.
    val lastTunedPresetId: Flow<String?> = safeDataStore.map { prefs ->
        prefs[KEY_LAST_TUNED_PRESET_ID]
    }.distinctUntilChanged()

    // Update methods
    suspend fun setAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUDIO_ENABLED] = enabled
        }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HAPTIC_ENABLED] = enabled
        }
    }

    suspend fun setVoiceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VOICE_ENABLED] = enabled
        }
    }

    suspend fun setVoiceOnInTuneOnly(voiceOnInTuneOnly: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VOICE_ON_IN_TUNE_ONLY] = voiceOnInTuneOnly
        }
    }

    suspend fun setInTuneThreshold(threshold: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IN_TUNE_THRESHOLD] = threshold
        }
    }

    suspend fun setSmoothingWindow(window: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SMOOTHING_WINDOW] = window.coerceIn(1, 30)
        }
    }

    suspend fun setConfidenceThreshold(threshold: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONFIDENCE_THRESHOLD] = threshold.coerceIn(0.5f, 0.99f)
        }
    }

    suspend fun setStabilityCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STABILITY_COUNT] = count.coerceIn(1, 25)
        }
    }

    suspend fun setDebugMode(enabled: Boolean) {
        context.localDataStore.edit { prefs ->
            prefs[KEY_DEBUG_MODE] = enabled
        }
    }

    suspend fun setAutoStringDetection(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_STRING_DETECTION] = enabled
        }
    }

    suspend fun setStringConfiguration(config: List<StringNote>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STRING_CONFIG] = serializeStringConfig(config)
        }
    }

    suspend fun setSelectedStringIndex(index: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_STRING_INDEX] = index.coerceAtLeast(0)
        }
    }

    suspend fun setLastInTuneAt(epochMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_IN_TUNE_AT] = epochMs
        }
    }

    suspend fun setLastTunedPresetId(presetId: String?) {
        context.dataStore.edit { prefs ->
            if (presetId == null) {
                prefs.remove(KEY_LAST_TUNED_PRESET_ID)
            } else {
                prefs[KEY_LAST_TUNED_PRESET_ID] = presetId
            }
        }
    }

    // Serialization helpers
    // Format: "name,freq,octave;name,freq,octave;..." (semicolon-delimited, not JSON)
    private fun serializeStringConfig(config: List<StringNote>): String {
        return config.joinToString(";") { "${it.name},${it.frequency},${it.octave}" }
    }

    /**
     * Parse semicolon-delimited string configuration.
     * Format: "name,freq,octave;name,freq,octave;..."
     */
    private fun parseDelimitedStringConfig(data: String): List<StringNote> {
        return data.split(";").mapNotNull { item ->
            val parts = item.split(",")
            if (parts.size == 3) {
                StringNote(
                    name = parts[0],
                    frequency = parts[1].toFloatOrNull() ?: return@mapNotNull null,
                    octave = parts[2].toIntOrNull() ?: return@mapNotNull null
                )
            } else null
        }
    }
}
