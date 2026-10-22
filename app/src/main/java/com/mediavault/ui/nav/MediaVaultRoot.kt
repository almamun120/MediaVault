package com.mediavault.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mediavault.feature.audio.AudioScreen
import com.mediavault.feature.camera.CameraScreen
import com.mediavault.feature.media.MediaLibraryScreen
import com.mediavault.feature.settings.SettingsScreen
import com.mediavault.feature.scanner.ScannerScreen
import com.mediavault.feature.storage.StorageScreen
import com.mediavault.feature.transfers.TransfersScreen
import com.mediavault.feature.home.HomeScreen

enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Camera("camera", "Camera", Icons.Filled.PhotoCamera),
    Record("record", "Record", Icons.Filled.Mic),
    Media("media", "Media", Icons.Filled.PermMedia),
    Storage("storage", "Storage", Icons.Filled.Storage),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun MediaVaultRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    fun go(d: Dest) = nav.navigate(d.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current == d.route,
                        onClick = { go(d) },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Dest.Home.route, modifier = Modifier.padding(pad)) {
            composable(Dest.Home.route) {
                HomeScreen(
                    onOpenCamera = { go(Dest.Camera) }, onOpenAudio = { go(Dest.Record) },
                    onOpenScanner = { nav.navigate("scanner") }, onOpenTransfers = { nav.navigate("transfers") },
                )
            }
            composable("scanner") { ScannerScreen(onClose = { nav.popBackStack() }) }
            composable("transfers") { TransfersScreen() }
            composable(Dest.Camera.route) { CameraScreen() }
            composable(Dest.Record.route) { AudioScreen() }
            composable(Dest.Media.route) { MediaLibraryScreen() }
            composable(Dest.Storage.route) { StorageScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}

/** Honest placeholder: states plainly that the screen is not implemented yet and offers no fake controls. */
@Composable
private fun NotYetBuilt(title: String, phase: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("Not implemented yet — scheduled for $phase.", style = MaterialTheme.typography.bodyMedium)
    }
}
