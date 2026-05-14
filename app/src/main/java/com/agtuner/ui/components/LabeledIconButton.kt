package com.agtuner.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon button with a 56dp touch target and a TalkBack-friendly content description.
 *
 * The accessibility label lives on the outer IconButton's semantics, and the inner
 * Icon's `contentDescription` is forced to null so screen readers announce the button
 * exactly once — this is the easy-to-forget invariant that motivated the helper.
 */
@Composable
fun LabeledIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
    tint: Color = LocalContentColor.current,
) {
    val description = contentDescription
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(56.dp)
            .semantics { this.contentDescription = description },
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
