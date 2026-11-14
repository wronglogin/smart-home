package com.bartek.smarthome

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun SettingsScreen(vm: HomeViewModel, openEweLinkLogin: () -> Unit) {
    vm.version
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Konta i urządzenia",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(8.dp).weight(1f),
            )
            Button(onClick = { vm.saveNow() }) { Text("Zapisz") }
        }

        Section(
            "SwitchBot", vm.switchBot.isConfigured(),
            "W aplikacji SwitchBot: Profil → Preferencje → 10× dotknij „Wersja aplikacji” → Opcje dewelopera. Skopiuj Token i Secret.",
        ) {
            Field(vm, SwitchBotSource.K_TOKEN, "Token")
            Field(vm, SwitchBotSource.K_SECRET, "Secret")
        }

        Section(
            "Govee", vm.govee.isConfigured(),
            "W Govee Home: Profil → Ustawienia → Apply for API Key. Klucz przyjdzie e-mailem.",
        ) {
            Field(vm, GoveeSource.K_KEY, "Klucz API")
        }

        Section(
            "eWeLink (Sonoff)", vm.eweLink.isConfigured(),
            "Na dev.ewelink.cc utwórz aplikację i wpisz w niej poniższy Redirect URL. Potem przepisz tu APPID i APP SECRET i zaloguj się kontem eWeLink.",
        ) {
            Field(vm, EweLinkSource.K_APPID, "APPID", secret = false)
            Field(vm, EweLinkSource.K_SECRET, "APP SECRET")
            Field(vm, EweLinkSource.K_REDIRECT, "Redirect URL", secret = false, default = EweLinkSource.DEFAULT_REDIRECT)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = openEweLinkLogin, enabled = vm.eweLink.hasApp()) {
                    Text(if (vm.eweLink.isConfigured()) "Zaloguj ponownie" else "Zaloguj przez eWeLink")
                }
                if (vm.eweLink.isConfigured()) {
                    TextButton(onClick = { vm.eweLink.logout(); vm.bump() }) { Text("Wyloguj") }
                }
            }
        }

        Section(
            "Tapo", vm.tapo.isConfigured(),
            "Działa w domowym Wi-Fi. Wpisz e-mail i hasło konta TP-Link/Tapo, potem wyszukaj urządzenia albo dodaj je po adresie IP (Tapo → urządzenie → Informacje).",
        ) {
            Field(vm, TapoSource.K_USER, "E-mail konta TP-Link", secret = false)
            Field(vm, TapoSource.K_PASS, "Hasło")
            Button(onClick = { vm.scanTapo() }, enabled = !vm.scanning) {
                if (vm.scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Szukam w sieci…")
                } else {
                    Text("Szukaj urządzeń Tapo")
                }
            }
            IpEntry("Dodaj") { vm.tapo.addIp(it); vm.bump() }
            vm.tapo.ips().forEach { ip ->
                ListRow(vm.store.get("tapo_name:$ip").ifBlank { "Tapo" }, ip) { vm.tapo.removeIp(ip); vm.bump() }
            }
        }

        Section(
            "Android TV / Google TV", vm.tv.isConfigured(),
            "Telewizor musi być włączony i w tej samej sieci. Po wybraniu na ekranie TV pojawi się kod do wpisania. Żeby włączać TV z aplikacji, włącz w nim opcję typu „Sieć w trybie czuwania”.",
        ) {
            Button(onClick = { vm.discoverTv() }, enabled = !vm.tvSearching && !vm.pairingBusy) {
                if (vm.tvSearching) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Szukam telewizorów…")
                } else {
                    Text("Szukaj telewizorów")
                }
            }
            val paired = vm.tv.tvs().map { it.first }.toSet()
            vm.tvFound.filter { it.first !in paired }.forEach { (ip, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name)
                        Text(ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { vm.startPairing(ip, name) }, enabled = !vm.pairingBusy) { Text("Paruj") }
                }
            }
            IpEntry("Paruj") { vm.startPairing(it, "Telewizor") }
            if (vm.pairingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.tv.tvs().forEach { (ip, name) -> ListRow(name, ip) { vm.tv.removeTv(ip); vm.bump() } }
        }

        BackupSection(vm)

        Text(
            "Klucze i hasła są zapisane tylko na tym telefonie.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    if (vm.pairing != null) PairingDialog(vm)
}

@Composable
private fun BackupSection(vm: HomeViewModel) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSettings(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSettings(uri)
    }
    Section(
        "Kopia ustawień", false,
        "Ustawienia zapisują się od razu i zostają po aktualizacji aplikacji. Plik kopii przyda się, gdy odinstalujesz aplikację albo zmienisz telefon – zawiera klucze i hasła, więc trzymaj go u siebie.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { exportLauncher.launch("MojDom-ustawienia.json") }, modifier = Modifier.weight(1f)) {
                Text("Zapisz do pliku")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) {
                Text("Wczytaj z pliku")
            }
        }
    }
}

@Composable
private fun PairingDialog(vm: HomeViewModel) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.cancelPairing() },
        title = { Text("Parowanie z telewizorem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Wpisz 6-znakowy kod widoczny na ekranie telewizora.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    label = { Text("Kod") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.finishPairing(code) }, enabled = code.length == 6 && !vm.pairingBusy) { Text("Paruj") }
        },
        dismissButton = { TextButton(onClick = { vm.cancelPairing() }) { Text("Anuluj") } },
    )
}

@Composable
private fun Section(title: String, connected: Boolean, help: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (connected) Text("Połączono", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Field(vm: HomeViewModel, key: String, label: String, secret: Boolean = true, default: String = "") {
    var v by remember(key, vm.version) { mutableStateOf(vm.store.get(key).ifBlank { default }) }
    var show by remember { mutableStateOf(!secret) }
    OutlinedTextField(
        value = v,
        onValueChange = {
            v = it
            vm.store.set(key, it.trim())
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Pokaż")
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun IpEntry(button: String, onAdd: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it.trim() },
            label = { Text("Adres IP, np. 192.168.1.50") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        FilledTonalButton(onClick = {
            if (ip.isNotBlank()) {
                onAdd(ip)
                ip = ""
            }
        }) { Text(button) }
    }
}

@Composable
private fun ListRow(title: String, subtitle: String, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Usuń") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EweLinkLoginScreen(vm: HomeViewModel, onDone: () -> Unit) {
    val url = remember { vm.eweLink.loginUrl() }
    val redirect = remember { vm.eweLink.redirectUrl() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
            Text("Logowanie eWeLink", style = MaterialTheme.typography.titleLarge)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    var handled = false
                    fun check(u: String?): Boolean {
                        if (u == null || !u.startsWith(redirect)) return false
                        if (!handled) {
                            handled = true
                            val uri = Uri.parse(u)
                            val code = uri.getQueryParameter("code")
                            val region = uri.getQueryParameter("region") ?: "eu"
                            if (code != null) vm.eweLinkCallback(code, region) else vm.snack = "eWeLink: brak kodu logowania"
                            onDone()
                        }
                        return true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            check(request.url.toString())

                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                            if (check(u)) view.stopLoading()
                        }
                    }
                    loadUrl(url)
                }
            },
        )
    }
}
