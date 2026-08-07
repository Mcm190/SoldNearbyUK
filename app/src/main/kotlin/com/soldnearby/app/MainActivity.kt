package com.soldnearby.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.soldnearby.app.data.SettingsRepository
import com.soldnearby.app.ui.MapScreen
import com.soldnearby.app.ui.SettingsScreen
import com.soldnearby.app.ui.theme.SoldNearbyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The manifest sets this activity's theme to Theme.SoldNearby.Splash so the splash
        // artwork is the very first frame drawn (as the window background, before this code
        // even runs). Swapping back here — right before the real content is set — means the
        // splash is on screen for exactly as long as cold start actually takes, not a fixed
        // timer, and disappears the moment Compose has something real to show.
        setTheme(R.style.Theme_SoldNearby)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsRepository = remember { SettingsRepository(context) }
            var settings by remember { mutableStateOf(settingsRepository.load()) }
            var showSettings by remember { mutableStateOf(false) }

            SoldNearbyTheme(darkTheme = settings.darkModeEnabled) {
                // MapScreen stays composed underneath even while Settings is open, so its
                // camera position and loaded markers survive a trip to Settings and back.
                Box(modifier = Modifier.fillMaxSize()) {
                    MapScreen(
                        settings = settings,
                        onOpenSettings = { showSettings = true }
                    )
                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onSettingsChange = {
                                settings = it
                                settingsRepository.save(it)
                            },
                            onBack = { showSettings = false }
                        )
                    }
                }
            }
        }
    }
}
