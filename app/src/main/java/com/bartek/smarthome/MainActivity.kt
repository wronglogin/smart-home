package com.bartek.smarthome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartHomeTheme { App() }
        }
    }
}

@Composable
fun App(vm: HomeViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var eweLogin by remember { mutableStateOf(false) }
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(vm.snack) {
        val s = vm.snack
        if (s != null) {
            snackHost.showSnackbar(s)
            vm.snack = null
        }
    }

    fun goHome() {
        if (tab != 0) {
            tab = 0
            vm.onSettingsClosed()
        }
    }

    BackHandler(enabled = eweLogin || tab != 0) {
        if (eweLogin) eweLogin = false else goHome()
    }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackHost) },
        bottomBar = {
            if (!eweLogin) {
                NavigationBar(containerColor = Palette.Bar) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { goHome() },
                        icon = { Icon(Icons.Filled.Home, null) },
                        label = { Text("Strona główna") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
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
                tab == 0 -> HomeScreen(vm) { tab = 1 }
                else -> SettingsScreen(vm) { eweLogin = true }
            }
        }
    }
}
