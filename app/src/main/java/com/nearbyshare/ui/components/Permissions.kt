package com.nearbyshare.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nearbyshare.core.common.NearbyPermissions

/** Returns a function that runs [onGranted] once the Nearby runtime permissions for this Android version are held. */
@Composable
fun rememberNearbyPermissionGate(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val granted = rememberUpdatedState(onGranted)
    val denied = rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (NearbyPermissions.missingCritical(context).isEmpty()) granted.value() else denied.value()
    }
    return {
        if (NearbyPermissions.missingCritical(context).isEmpty()) granted.value() else launcher.launch(NearbyPermissions.required())
    }
}
