package com.agtuner.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agtuner.R
import com.agtuner.tuning.TuningState
import com.agtuner.ui.theme.toColor

/**
 * Displays the current tuning status as text.
 * Uses live region to announce changes via TalkBack.
 */
@Composable
fun TuningStatus(
    tuningState: TuningState,
    modifier: Modifier = Modifier,
    isCalibrating: Boolean = false
) {
    val statusText = when {
        isCalibrating -> stringResource(R.string.status_calibrating)
        tuningState is TuningState.InTune -> stringResource(R.string.status_in_tune)
        tuningState is TuningState.TooLow -> stringResource(R.string.status_too_low)
        tuningState is TuningState.TooHigh -> stringResource(R.string.status_too_high)
        else -> stringResource(R.string.status_no_signal)
    }
    // Calibrating shares NoSignal's grey; otherwise the state's own color.
    val statusColor = if (isCalibrating) TuningState.NoSignal.toColor() else tuningState.toColor()

    Text(
        text = statusText,
        modifier = modifier
            .padding(vertical = 16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
            },
        style = MaterialTheme.typography.displaySmall,
        color = statusColor,
        fontWeight = FontWeight.Bold
    )
}
