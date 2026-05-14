package com.agtuner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agtuner.R
import com.agtuner.tuning.TuningPreset
import com.agtuner.ui.components.LabeledIconButton
import com.agtuner.viewmodel.TunerViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: TunerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val prefs = uiState.feedbackPreferences

    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Header. isTraversalGroup + negative traversalIndex on the title makes
            // TalkBack announce the screen heading before the back button despite the
            // back button being leftmost in the layout.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { isTraversalGroup = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabeledIconButton(
                    onClick = onNavigateBack,
                    contentDescription = stringResource(R.string.go_back),
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        heading()
                        traversalIndex = -1f
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Tuning preset section (placed first so it's the easiest setting to reach)
            Text(
                text = stringResource(R.string.tuning_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            TuningPresetCard(
                presets = uiState.tuningPresets,
                selectedPresetId = uiState.selectedPresetId,
                onPresetSelected = { viewModel.selectTuningPreset(it) }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Feedback settings section
            Text(
                text = stringResource(R.string.feedback_options),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Audio feedback toggle
            SettingsToggle(
                title = stringResource(R.string.audio_feedback_title),
                description = stringResource(R.string.audio_feedback_desc),
                isChecked = prefs.audioEnabled,
                onCheckedChange = { viewModel.setAudioFeedbackEnabled(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Haptic feedback toggle
            SettingsToggle(
                title = stringResource(R.string.vibration_feedback_title),
                description = stringResource(R.string.vibration_feedback_desc),
                isChecked = prefs.hapticEnabled,
                onCheckedChange = { viewModel.setHapticFeedbackEnabled(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Voice feedback toggle
            SettingsToggle(
                title = stringResource(R.string.voice_announcements_title),
                description = stringResource(R.string.voice_announcements_desc),
                isChecked = prefs.voiceEnabled,
                onCheckedChange = { viewModel.setVoiceFeedbackEnabled(it) }
            )

            // Show voice mode toggle only when voice is enabled
            if (prefs.voiceEnabled) {
                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggle(
                    title = stringResource(R.string.voice_in_tune_only_title),
                    description = stringResource(R.string.voice_in_tune_only_desc),
                    isChecked = prefs.voiceOnInTuneOnly,
                    onCheckedChange = { viewModel.setVoiceOnInTuneOnlyEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Accessibility info
            Text(
                text = stringResource(R.string.accessibility_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            AccessibilityInfoCard()

            Spacer(modifier = Modifier.height(32.dp))

            // Debug section
            Text(
                text = stringResource(R.string.debug_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsToggle(
                title = stringResource(R.string.debug_mode_title),
                description = stringResource(R.string.debug_mode_desc),
                isChecked = uiState.debugMode,
                onCheckedChange = { viewModel.setDebugMode(it) }
            )

            // Tuning parameters - only shown when debug mode is enabled
            if (uiState.debugMode) {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.tuning_parameters),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // In-tune threshold slider
                SettingsSlider(
                    title = stringResource(R.string.in_tune_threshold_title),
                    description = stringResource(R.string.in_tune_threshold_desc),
                    value = uiState.inTuneThreshold,
                    valueRange = 3f..25f,
                    steps = 21,
                    onValueChange = { viewModel.setInTuneThreshold(it) },
                    valueLabel = stringResource(R.string.slider_value_cents, uiState.inTuneThreshold.roundToInt())
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Pitch smoothing slider
                SettingsSlider(
                    title = stringResource(R.string.pitch_smoothing_title),
                    description = stringResource(R.string.pitch_smoothing_desc),
                    value = uiState.smoothingWindow.toFloat(),
                    valueRange = 1f..30f,
                    steps = 28,
                    onValueChange = { viewModel.setSmoothingWindow(it.roundToInt()) },
                    valueLabel = stringResource(R.string.slider_value_samples, uiState.smoothingWindow)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Confidence threshold slider
                SettingsSlider(
                    title = stringResource(R.string.confidence_threshold_title),
                    description = stringResource(R.string.confidence_threshold_desc),
                    value = uiState.confidenceThreshold,
                    valueRange = 0.5f..0.99f,
                    steps = 48,
                    onValueChange = { viewModel.setConfidenceThreshold(it) },
                    valueLabel = stringResource(R.string.slider_value_percent, (uiState.confidenceThreshold * 100).roundToInt())
                )

                Spacer(modifier = Modifier.height(12.dp))

                // State stability slider
                SettingsSlider(
                    title = stringResource(R.string.state_stability_title),
                    description = stringResource(R.string.state_stability_desc),
                    value = uiState.stabilityCount.toFloat(),
                    valueRange = 1f..25f,
                    steps = 23,
                    onValueChange = { viewModel.setStabilityCount(it.roundToInt()) },
                    valueLabel = stringResource(R.string.slider_value_readings, uiState.stabilityCount)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val stateText = stringResource(if (isChecked) R.string.toggle_enabled else R.string.toggle_disabled)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title, $description, $stateText"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Inner Texts cleared — otherwise the row's merge concatenates them onto the
            // parent contentDescription and TalkBack reads title/description twice.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {}
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    valueLabel: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title, $description, current value: $valueLabel"
                }
        ) {
            // The header Row and description Text duplicate what's already in the parent
            // contentDescription, so clear them; Slider keeps its own stateDescription so
            // value adjustments still announce.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {}
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = valueLabel
                    }
            )
        }
    }
}

@Composable
private fun TuningPresetCard(
    presets: List<TuningPreset>,
    selectedPresetId: String?,
    onPresetSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            presets.forEach { preset ->
                TuningPresetRow(
                    preset = preset,
                    isSelected = preset.id == selectedPresetId,
                    onSelected = { onPresetSelected(preset.id) }
                )
            }
            // Show a non-interactive Custom row when the user's strings don't match any preset,
            // so the radio group always has a clear "selected" state.
            if (selectedPresetId == null) {
                CustomTuningRow()
            }
        }
    }
}

@Composable
private fun TuningPresetRow(
    preset: TuningPreset,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    val name = stringResource(preset.nameRes)
    val strings = stringResource(preset.stringsRes)
    val stringsSpoken = stringResource(preset.stringsSpokenRes)
    val description = stringResource(
        if (isSelected) R.string.tuning_preset_selected_desc else R.string.tuning_preset_unselected_desc,
        name,
        stringsSpoken
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onSelected,
                role = Role.RadioButton
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hide the RadioButton from accessibility — the Row already announces selection state.
        RadioButton(
            selected = isSelected,
            onClick = null,
            modifier = Modifier.clearAndSetSemantics {}
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics {}
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = strings,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CustomTuningRow() {
    val name = stringResource(R.string.tuning_preset_custom)
    val strings = stringResource(R.string.tuning_preset_custom_strings)
    val stringsSpoken = stringResource(R.string.tuning_preset_custom_strings_spoken)
    val description = stringResource(R.string.tuning_preset_selected_desc, name, stringsSpoken)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = true,
            onClick = null,
            enabled = false,
            modifier = Modifier.clearAndSetSemantics {}
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics {}
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = strings,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccessibilityInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.designed_for_accessibility),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.accessibility_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
