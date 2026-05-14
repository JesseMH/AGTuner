package com.agtuner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agtuner.R
import com.agtuner.tuning.NoteFrequencies
import com.agtuner.tuning.StringNote
import com.agtuner.ui.components.LabeledIconButton
import com.agtuner.viewmodel.TunerViewModel
import kotlin.math.roundToInt

@Composable
fun StringConfigurationScreen(
    onNavigateBack: () -> Unit,
    viewModel: TunerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showNotePicker by remember { mutableStateOf<Int?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
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
                    text = stringResource(R.string.configure_strings),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics {
                        heading()
                        traversalIndex = -1f
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // String count info
            Text(
                text = stringResource(R.string.strings_configured, uiState.stringConfiguration.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // String list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(uiState.stringConfiguration) { index, stringNote ->
                    StringConfigItem(
                        stringNumber = index + 1,
                        note = stringNote,
                        canDelete = uiState.stringConfiguration.size > 1,
                        onNoteClick = { showNotePicker = index },
                        onDelete = { viewModel.removeString(index) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Add string button
            if (uiState.stringConfiguration.size < TunerViewModel.MAX_STRINGS) {
                val addStringDesc = stringResource(R.string.add_string_desc)
                Button(
                    onClick = { viewModel.addString() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .semantics {
                            contentDescription = addStringDesc
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.add_string),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reset to standard button
            val resetGuitarDesc = stringResource(R.string.reset_guitar_desc)
            TextButton(
                onClick = {
                    viewModel.updateStringConfiguration(NoteFrequencies.STANDARD_GUITAR)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = resetGuitarDesc
                    }
            ) {
                Text(
                    text = stringResource(R.string.reset_to_standard_guitar),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }

    // Note picker dialog
    showNotePicker?.let { stringIndex ->
        NotePickerDialog(
            currentNote = uiState.stringConfiguration[stringIndex],
            onNoteSelected = { note ->
                viewModel.updateStringNote(stringIndex, note)
                showNotePicker = null
            },
            onDismiss = { showNotePicker = null }
        )
    }
}

@Composable
private fun StringConfigItem(
    stringNumber: Int,
    note: StringNote,
    canDelete: Boolean,
    onNoteClick: () -> Unit,
    onDelete: () -> Unit
) {
    val stringConfigDesc = stringResource(R.string.string_config_desc, stringNumber, note.spokenName, note.frequency.roundToInt())
    val removeStringDesc = stringResource(R.string.remove_string_desc, stringNumber)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        // The clickable+merged region wraps only the note info; the delete button stays
        // a sibling so TalkBack treats it as a separate action instead of merging it
        // into the row's description.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onNoteClick, role = Role.Button)
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        bottom = 16.dp,
                        end = if (canDelete) 0.dp else 16.dp
                    )
                    // clearAndSetSemantics (not semantics(mergeDescendants = true)) — the
                    // latter concatenates child Texts into the parent description, so
                    // TalkBack would read "string 1, E2, 82 Hz" *and* the inner "1, E2,
                    // 82.41 Hz" again. Clearing descendants is the only way to suppress.
                    .clearAndSetSemantics {
                        contentDescription = stringConfigDesc
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$stringNumber",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = note.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.frequency_hz, note.frequency),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canDelete) {
                LabeledIconButton(
                    onClick = onDelete,
                    contentDescription = removeStringDesc,
                    imageVector = Icons.Default.Delete,
                    iconSize = 24.dp,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun NotePickerDialog(
    currentNote: StringNote,
    onNoteSelected: (StringNote) -> Unit,
    onDismiss: () -> Unit
) {
    val allNotes = remember { NoteFrequencies.getAllNotes() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.select_note),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.height(400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(allNotes) { note ->
                    val isSelected = note.displayName == currentNote.displayName
                    val noteFreqDesc = stringResource(R.string.note_frequency_desc, note.spokenName, note.frequency.roundToInt())

                    TextButton(
                        onClick = { onNoteSelected(note) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = noteFreqDesc
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = note.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = stringResource(R.string.frequency_hz, note.frequency),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
