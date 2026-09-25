package bg.averagespeed.ui

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bg.averagespeed.MainActivity
import bg.averagespeed.service.TrackingHub
import bg.averagespeed.service.TrackingService

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val state by TrackingHub.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) TrackingService.start(context)
    }
    val startTracking = {
        if (hasFineLocation(context)) TrackingService.start(context)
        else permissionLauncher.launch(MainActivity.requiredPermissions)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Скорост") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Отсечки (${state.sections.size})") })
            }
            when (tab) {
                0 -> TrackingScreen(
                    state = state,
                    onStart = startTracking,
                    onStop = { TrackingService.stop(context) },
                )
                else -> SectionsScreen(state.sections)
            }
        }
    }
}

private fun hasFineLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
