package com.agtuner.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agtuner.R
import com.agtuner.tuning.NoteLabel
import com.agtuner.tuning.TuningState
import com.agtuner.tuning.toLabel
import com.agtuner.ui.components.LabeledIconButton
import com.agtuner.ui.components.NoteDisplay
import com.agtuner.ui.components.StringSelector
import com.agtuner.ui.components.TunerGauge
import com.agtuner.ui.components.TuningStatus
import com.agtuner.viewmodel.DebugStats
import com.agtuner.viewmodel.TunerViewModel
import com.agtuner.widget.WidgetLaunchMode

@Composable
fun TunerScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToStringConfig: () -> Unit,
    pendingLaunchMode: WidgetLaunchMode? = null,
    onPendingLaunchModeConsumed: () -> Unit = {},
    viewModel: TunerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ON_RESUME: re-check permission so the UI reflects changes the user made in
    //   system Settings (the "permanently denied → Settings" path).
    // ON_STOP:   the user has fully backgrounded the app (home, app switch, screen
    //   off). Stop listening so the mic isn't held in the background and the
    //   widget timestamp captures the latest in-tune from this session. On return,
    //   the user taps START (or the widget's AUTO) again to resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshPermissionState()
                Lifecycle.Event.ON_STOP -> viewModel.stopListening()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Permission launcher. On permanent denial the system suppresses the
    // dialog and returns isGranted=false immediately; in that case route
    // the user to app settings so they have a path to grant the permission.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
        if (!isGranted) {
            val activity = context as? Activity
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO
                )
            ) {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
        }
    }

    // Handle widget-driven launch. The effect re-runs whenever the launch mode is
    // (re-)set or permission flips: if granted we start listening and consume the
    // mode; if not granted we request permission and leave the mode pending so the
    // next recomposition (post-grant) completes the launch.
    LaunchedEffect(pendingLaunchMode, uiState.hasPermission) {
        val mode = pendingLaunchMode ?: return@LaunchedEffect
        if (uiState.hasPermission) {
            viewModel.startListeningFromWidget(mode)
            onPendingLaunchModeConsumed()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )

                Row {
                    LabeledIconButton(
                        onClick = onNavigateToStringConfig,
                        contentDescription = stringResource(R.string.configure_strings_desc),
                        imageVector = Icons.Default.Build,
                    )
                    LabeledIconButton(
                        onClick = onNavigateToSettings,
                        contentDescription = stringResource(R.string.open_settings_desc),
                        imageVector = Icons.Default.Settings,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Note display
            val targetNote = uiState.stringConfiguration
                .getOrNull(uiState.selectedStringIndex)
                ?.toLabel() ?: NoteLabel.Empty

            NoteDisplay(
                detectedNote = uiState.detectedNote,
                detectedFrequency = uiState.detectedFrequency,
                targetNote = targetNote,
                tuningState = uiState.tuningState
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Tuning gauge
            TunerGauge(
                cents = uiState.cents,
                tuningState = uiState.tuningState,
                inTuneThreshold = uiState.inTuneThreshold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Tuning status
            TuningStatus(
                tuningState = uiState.tuningState,
                isCalibrating = uiState.isCalibrating
            )

            // Debug overlay (only shown when debug mode is enabled)
            if (uiState.debugMode) {
                Spacer(modifier = Modifier.height(12.dp))
                DebugOverlay(stats = uiState.debugStats)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // String selector
            Text(
                text = stringResource(R.string.select_string),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // In auto mode, only show highlighted string when there's an active detection
            val hasActiveDetection = uiState.tuningState !is TuningState.NoSignal

            StringSelector(
                strings = uiState.stringConfiguration,
                selectedIndex = uiState.selectedStringIndex,
                onStringSelected = {
                    // Selecting a string manually disables auto mode
                    if (uiState.autoStringDetection) {
                        viewModel.setAutoStringDetection(false)
                    }
                    viewModel.selectString(it)
                },
                autoMode = uiState.autoStringDetection,
                // Only show auto-highlight when actually detecting something
                showAutoHighlight = uiState.autoStringDetection && hasActiveDetection,
                onAutoModeToggle = { viewModel.toggleAutoStringDetection() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Start/Stop button
            if (!uiState.hasPermission) {
                // Request permission button
                val grantMicrophoneDesc = stringResource(R.string.grant_microphone_desc)
                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .semantics {
                            contentDescription = grantMicrophoneDesc
                        },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    // Inner Text cleared so TalkBack reads only the parent's contentDescription
                    // instead of "<description>, GRANT MICROPHONE ACCESS, button, double-tap…".
                    Text(
                        text = stringResource(R.string.grant_microphone_access),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            } else {
                // Start/Stop tuning button
                val stopTuningDesc = stringResource(R.string.stop_tuning_desc)
                val startTuningDesc = stringResource(R.string.start_tuning_desc)
                Button(
                    onClick = {
                        if (uiState.isListening) {
                            viewModel.stopListening()
                        } else {
                            viewModel.startListening()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .semantics {
                            contentDescription = if (uiState.isListening) {
                                stopTuningDesc
                            } else {
                                startTuningDesc
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                ) {
                    Text(
                        text = stringResource(if (uiState.isListening) R.string.stop_tuning else R.string.start_tuning),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DebugOverlay(stats: DebugStats) {
    val yes = stringResource(R.string.debug_yes)
    val no = stringResource(R.string.debug_no)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = stringResource(R.string.debug_section),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Amplitude info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DebugItem(stringResource(R.string.debug_rms), "%.4f".format(stats.rmsAmplitude))
                DebugItem(stringResource(R.string.debug_peak), "%.4f".format(stats.peakAmplitude))
                DebugItem(stringResource(R.string.debug_decay), if (stats.isInDecay) yes else no)
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Attack detection thresholds (new row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DebugItem(stringResource(R.string.debug_noise), "%.4f".format(stats.noiseFloor))
                DebugItem(stringResource(R.string.debug_thresh), "%.4f".format(stats.minAttackThreshold))
                DebugItem(stringResource(R.string.debug_armed), if (stats.attackArmed) yes else no)
                DebugItem(stringResource(R.string.debug_hlock), if (stats.hardLockActive) yes else no)
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Sample counts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DebugItem(stringResource(R.string.debug_freq_samp), "${stats.frequencySamples}")
                DebugItem(stringResource(R.string.debug_amp_samp), "${stats.amplitudeSamples}")
                DebugItem(stringResource(R.string.debug_silent), "${stats.consecutiveSilentFrames}")
            }

            Spacer(modifier = Modifier.height(2.dp))

            // State info + audio feedback (combined into one row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DebugItem(stringResource(R.string.debug_stable), "${stats.pendingStateCount}")
                DebugItem(stringResource(R.string.debug_locked), if (stats.isLocked) yes else no)
                DebugItem(stringResource(R.string.debug_attacks), "${stats.attackCount}")
                DebugItem(stringResource(R.string.debug_audio_fb), "${stats.audioFeedbackCount}")
            }
        }
    }
}

@Composable
private fun DebugItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
