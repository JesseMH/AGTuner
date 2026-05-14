package com.agtuner.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agtuner.R
import com.agtuner.tuning.StringNote
import com.agtuner.ui.theme.TunerColors
import kotlin.math.roundToInt

/**
 * Grid of string selector buttons with optional Auto mode.
 * Each button shows the string number and note, and is accessible via TalkBack.
 * Buttons wrap to new rows based on available screen width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StringSelector(
    strings: List<StringNote>,
    selectedIndex: Int,
    onStringSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    autoMode: Boolean = false,
    showAutoHighlight: Boolean = false,
    onAutoModeToggle: (() -> Unit)? = null
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Auto button (if callback provided)
        if (onAutoModeToggle != null) {
            AutoButton(
                isSelected = autoMode,
                onClick = onAutoModeToggle
            )
        }

        strings.forEachIndexed { index, stringNote ->
            StringButton(
                stringNumber = index + 1,
                note = stringNote,
                // In auto mode, no string is "selected" - only auto-highlighted when detecting
                isSelected = !autoMode && index == selectedIndex,
                // Show auto-highlight only when auto mode is on AND we have active detection
                isAutoHighlighted = showAutoHighlight && index == selectedIndex,
                onClick = {
                    // Clicking a string disables auto mode and selects that string
                    onStringSelected(index)
                }
            )
        }
    }
}

@Composable
private fun AutoButton(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val backgroundColor = when {
        isSelected && isDark -> TunerColors.DarkInTune
        isSelected -> TunerColors.LightInTune
        isDark -> TunerColors.UnselectedStringDark
        else -> TunerColors.UnselectedString
    }

    val contentColor = when {
        isSelected -> if (isDark) TunerColors.DarkBackground else TunerColors.LightBackground
        isDark -> TunerColors.DarkOnSurface
        else -> TunerColors.LightOnSurface
    }

    val description = if (isSelected) {
        stringResource(R.string.auto_mode_enabled_desc)
    } else {
        stringResource(R.string.auto_mode_disabled_desc)
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .size(72.dp)
            .semantics {
                contentDescription = description
                role = Role.Switch
                selected = isSelected
            },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        ),
        contentPadding = PaddingValues(4.dp)
    ) {
        Text(
            text = stringResource(R.string.auto_mode),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun StringButton(
    stringNumber: Int,
    note: StringNote,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAutoHighlighted: Boolean = false
) {
    val isDark = isSystemInDarkTheme()

    // When auto-highlighted (auto mode on + this is the detected string), use a different color
    val backgroundColor = when {
        isAutoHighlighted && isDark -> TunerColors.DarkInTune.copy(alpha = 0.7f)
        isAutoHighlighted -> TunerColors.LightInTune.copy(alpha = 0.7f)
        isSelected && isDark -> TunerColors.SelectedStringDark
        isSelected -> TunerColors.SelectedString
        isDark -> TunerColors.UnselectedStringDark
        else -> TunerColors.UnselectedString
    }

    val contentColor = when {
        isAutoHighlighted || isSelected -> if (isDark) TunerColors.DarkBackground else TunerColors.LightBackground
        isDark -> TunerColors.DarkOnSurface
        else -> TunerColors.LightOnSurface
    }

    // Accessibility description
    val baseDescription = stringResource(R.string.string_selector_description, stringNumber, note.spokenName, note.frequency.roundToInt())
    val autoDetectedSuffix = stringResource(R.string.string_auto_detected)
    val selectedSuffix = stringResource(R.string.string_selected)
    val description = buildString {
        append(baseDescription)
        if (isAutoHighlighted) append(", $autoDetectedSuffix")
        else if (isSelected) append(", $selectedSuffix")
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .size(72.dp) // Large touch target for accessibility
            .semantics {
                contentDescription = description
                role = Role.RadioButton
                selected = isSelected || isAutoHighlighted
            },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isSelected || isAutoHighlighted) 4.dp else 1.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "$stringNumber",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = note.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
