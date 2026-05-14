package com.agtuner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.agtuner.navigation.TunerNavigation
import com.agtuner.ui.theme.TunerTheme
import com.agtuner.widget.TunerWidget
import com.agtuner.widget.WidgetLaunchMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Holds the launch mode passed in by the home-screen widget until the
    // composition consumes it (starts listening or routes through the
    // permission flow). Read by `setContent`, written by both `onCreate`
    // and `onNewIntent` so the activity reacts whether it's cold-launched
    // or re-entered (singleTop) by a widget tap.
    private var pendingLaunchMode by mutableStateOf<WidgetLaunchMode?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingLaunchMode = readLaunchMode(intent)

        setContent {
            TunerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Snapshot the state so the composition reads it; clearing happens
                    // via the onConsumed callback below.
                    val mode = pendingLaunchMode
                    TunerNavigation(
                        pendingLaunchMode = mode,
                        onPendingLaunchModeConsumed = { pendingLaunchMode = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop launches deliver the new intent here without recreating the activity.
        // Replace the activity's intent so getIntent() reflects what brought us back,
        // then update the launch-mode state to trigger the auto-start effect.
        setIntent(intent)
        pendingLaunchMode = readLaunchMode(intent)
    }

    override fun onStop() {
        super.onStop()
        // Push a widget refresh whenever the app goes to background. The widget's
        // displayed relative-time ("Last tuned 2m ago") is rendered at provideGlance
        // time and goes stale as the clock advances; refreshing on every app-leave
        // event keeps it fresh whenever the user comes back to the home screen.
        lifecycleScope.launch {
            runCatching { TunerWidget.refreshAll(applicationContext) }
        }
    }

    private fun readLaunchMode(intent: Intent?): WidgetLaunchMode? =
        WidgetLaunchMode.fromIntentExtra(intent?.getStringExtra(WidgetLaunchMode.INTENT_EXTRA_KEY))
}
