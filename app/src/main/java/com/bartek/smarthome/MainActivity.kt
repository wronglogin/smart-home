package com.bartek.smarthome

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            SmartHomeTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == ACTION_VOICE) vm.voiceRequested = true
    }

    companion object {
        const val ACTION_VOICE = "com.bartek.smarthome.VOICE"
    }
}

@Composable
fun App(vm: HomeViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var eweLogin by remember { mutableStateOf(false) }
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(vm.voiceRequested) { if (vm.voiceRequested) tab = 0 }
    LaunchedEffect(vm.snack) {
        val s = vm.snack
        if (s != null) {
            snackHost.showSnackbar(s)
            vm.snack = null
        }
    }

    // Tabs: 0 = home, 1 = automations, 2 = settings. Leaving settings reloads devices with the new accounts.
    fun select(t: Int) {
        if (tab == 2 && t != 2) vm.onSettingsClosed()
        tab = t
    }

    BackHandler(enabled = eweLogin || tab != 0) {
        if (eweLogin) eweLogin = false else select(0)
    }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackHost) },
        bottomBar = {
            if (!eweLogin) {
                NavigationBar(containerColor = Palette.Bar) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { select(0) },
                        icon = { Icon(Icons.Filled.Home, null) },
                        label = { Text("Strona główna") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { select(1) },
                        icon = { Icon(Icons.Outlined.Schedule, null) },
                        label = { Text("Automatyzacja") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { select(2) },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text("Ustawienia") },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                eweLogin -> EweLinkLoginScreen(vm) { eweLogin = false }
                tab == 0 -> HomeScreen(vm) { select(2) }
                tab == 1 -> AutomationScreen(vm)
                else -> SettingsScreen(vm) { eweLogin = true }
            }
        }
    }
}
