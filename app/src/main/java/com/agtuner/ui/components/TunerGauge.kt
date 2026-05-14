package com.agtuner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agtuner.R
import com.agtuner.tuning.TuningState
import com.agtuner.ui.theme.TunerColors
import com.agtuner.ui.theme.toColor
import kotlin.math.abs

private val GAUGE_HEIGHT = 80.dp

// Empty space (px) between the gauge edges and the track stroke endpoints.
private const val TRACK_END_PADDING_PX = 40f

// Inset (px) from gauge edges to the maximum extent of the needle and in-tune zone.
// Keeps the needle base (12.dp ≈ ~20px radius) from overshooting the track end.
// Coupled to TRACK_END_PADDING_PX — if you change one, sanity-check the other.
private const val NEEDLE_RANGE_INSET_PX = 60f

/**
 * Visual gauge showing how far the detected pitch is from the target.
 *
 * The needle moves left for flat notes and right for sharp notes.
 * Center position indicates in-tune. The green zone shows the acceptable range.
 */
@Composable
fun TunerGauge(
    cents: Float,
    tuningState: TuningState,
    inTuneThreshold: Float = 10f,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val inTuneColor = if (isDark) TunerColors.DarkInTune else TunerColors.LightInTune

    val needleColor = tuningState.toColor()
    val trackColor = if (isDark) TunerColors.UnselectedStringDark else TunerColors.UnselectedString
    val centerMarkColor = inTuneColor
    val inTuneZoneColor = inTuneColor.copy(alpha = 0.3f)

    // Accessibility description
    val description = when {
        tuningState is TuningState.NoSignal -> stringResource(R.string.gauge_no_signal)
        cents < -inTuneThreshold -> stringResource(R.string.gauge_flat, abs(cents).toInt())
        cents > inTuneThreshold -> stringResource(R.string.gauge_sharp, cents.toInt())
        else -> stringResource(R.string.gauge_in_tune)
    }

    // Scale the gauge's cent-axis to the threshold so the green zone stays
    // visually meaningful at tight tolerances (e.g. ±3¢ would otherwise be a sliver).
    // Floor at 25¢ so a 10¢ default still spans ±50¢ — preserving the prior look.
    val displayRange = (inTuneThreshold * 5f).coerceAtLeast(25f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(GAUGE_HEIGHT)
            .semantics {
                contentDescription = description
            }
    ) {
        val width = size.width
        val height = size.height
        val centerX = width / 2
        val centerY = height / 2

        // Draw track background
        drawLine(
            color = trackColor,
            start = Offset(TRACK_END_PADDING_PX, centerY),
            end = Offset(width - TRACK_END_PADDING_PX, centerY),
            strokeWidth = 8.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Draw in-tune zone (green area showing acceptable range)
        val trackRange = width / 2 - NEEDLE_RANGE_INSET_PX
        val zoneLeftX = centerX - (inTuneThreshold / displayRange) * trackRange
        val zoneRightX = centerX + (inTuneThreshold / displayRange) * trackRange
        val zoneHeight = 50.dp.toPx()

        // Draw the green zone rectangle
        drawRect(
            color = inTuneZoneColor,
            topLeft = Offset(zoneLeftX, centerY - zoneHeight / 2),
            size = Size(
                width = zoneRightX - zoneLeftX,
                height = zoneHeight
            )
        )

        // Draw zone boundary lines
        drawLine(
            color = centerMarkColor,
            start = Offset(zoneLeftX, centerY - zoneHeight / 2),
            end = Offset(zoneLeftX, centerY + zoneHeight / 2),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = centerMarkColor,
            start = Offset(zoneRightX, centerY - zoneHeight / 2),
            end = Offset(zoneRightX, centerY + zoneHeight / 2),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Draw center mark
        drawLine(
            color = centerMarkColor,
            start = Offset(centerX, centerY - 30.dp.toPx()),
            end = Offset(centerX, centerY + 30.dp.toPx()),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Draw side markers at half- and full-display-range, scaling with threshold.
        val markerPositions = listOf(-displayRange, -displayRange / 2f, displayRange / 2f, displayRange)
        markerPositions.forEach { centsPos ->
            val x = centerX + (centsPos / displayRange) * trackRange
            drawLine(
                color = trackColor,
                start = Offset(x, centerY - 15.dp.toPx()),
                end = Offset(x, centerY + 15.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Draw needle (only if we have a signal)
        if (tuningState !is TuningState.NoSignal) {
            val clampedCents = cents.coerceIn(-displayRange, displayRange)
            val needleX = centerX + (clampedCents / displayRange) * trackRange

            // Needle base
            drawCircle(
                color = needleColor,
                radius = 12.dp.toPx(),
                center = Offset(needleX, centerY)
            )

            // Needle pointer
            drawLine(
                color = needleColor,
                start = Offset(needleX, centerY - 35.dp.toPx()),
                end = Offset(needleX, centerY + 35.dp.toPx()),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}
