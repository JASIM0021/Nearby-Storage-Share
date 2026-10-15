package com.nearbyshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.nearbyshare.feature.HostViewModel
import com.nearbyshare.navigation.AppRoot
import com.nearbyshare.ui.theme.NearbyStorageTheme

class MainActivity : ComponentActivity() {
    private val hostViewModel: HostViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NearbyStorageTheme { AppRoot() }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app is visible, so Android allows (re)starting the foreground service if sharing was left on.
        hostViewModel.resumeIfNeeded()
    }
}
