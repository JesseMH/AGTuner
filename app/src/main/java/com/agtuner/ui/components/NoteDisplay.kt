package com.agtuner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agtuner.R
import com.agtuner.tuning.NoteLabel
import com.agtuner.tuning.TuningState
import com.agtuner.ui.theme.toColor
import kotlin.math.roundToInt

/**
 * Displays the detected note and frequency. The visual label is rendered with Unicode
 * accidentals (E♭2); the spoken label drives the TalkBack content description so the
 * reader speaks "E flat 2".
 */
@Composable
fun NoteDisplay(
    detectedNote: NoteLabel,
    detectedFrequency: Float,
    targetNote: NoteLabel,
    tuningState: TuningState,
    modifier: Modifier = Modifier
) {
    val stateColor = tuningState.toColor()

    val displayNote = if (detectedNote.display.isNotEmpty()) detectedNote.display else "--"
    val displayFreq = if (detectedFrequency > 0) "${detectedFrequency.roundToInt()} Hz" else "-- Hz"

    val description = if (tuningState is TuningState.NoSignal) {
        stringResource(R.string.waiting_for_input_desc, targetNote.spoken)
    } else {
        stringResource(R.string.detected_note_desc, detectedNote.spoken, detectedFrequency.roundToInt(), targetNote.spoken)
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .clearAndSetSemantics {
                contentDescription = description
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Target note label
        Text(
            text = stringResource(R.string.tuning_to, targetNote.display),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Detected note (large)
        Text(
            text = displayNote,
            style = MaterialTheme.typography.displayLarge,
            color = stateColor,
            fontWeight = FontWeight.Bold
        )

        // Detected frequency
        Text(
            text = displayFreq,
            style = MaterialTheme.typography.displayMedium,
            color = stateColor
        )
    }
}
