package com.bartek.smarthome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val LAST_FORMAT = SimpleDateFormat("d.MM, HH:mm", Locale.forLanguageTag("pl"))

/** "Ostatnio: 15.11, 07:00 – wykonano" */
fun formatLastRun(raw: String): String? {
    val t = raw.substringBefore("|").toLongOrNull() ?: return null
    return "Ostatnio: ${LAST_FORMAT.format(Date(t))} – ${raw.substringAfter("|")}"
}

private fun triggerIcon(t: TriggerType): ImageVector = when (t) {
    TriggerType.TIME -> Icons.Outlined.Schedule
    TriggerType.SUN -> Icons.Outlined.WbSunny
    TriggerType.DEVICE -> Icons.Outlined.Devices
    TriggerType.INTERVAL -> Icons.Outlined.Timer
}

// =====================================================================
// List
// =====================================================================

@Composable
fun AutomationScreen(vm: HomeViewModel) {
    vm.version
    var editing by remember { mutableStateOf<Automation?>(null) }
    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        AutomationEditor(vm, current) { editing = null }
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text("Automatyzacja", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(8.dp))
            }
            item { BackgroundSettings(vm) }
            if (vm.automations.isEmpty()) {
                item {
                    InfoCard {
                        Text("Brak automatyzacji", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Przykłady: „Dobranoc” – o 23:00 wyłącz wszystkie światła. „Zachód” – 15 min przed zachodem słońca zapal salon. " +
                                "„Ruch w korytarzu” – gdy czujnik wykryje ruch w nocy, włącz światło, czekaj 2 min, wyłącz.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(vm.automations, key = { it.id }) { a -> AutomationCard(vm, a) { editing = a } }
            item { Spacer(Modifier.height(80.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Automation() },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Dodaj") },
            containerColor = Palette.Primary,
            contentColor = Color(0xFF062E6F),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun AutomationCard(vm: HomeViewModel, a: Automation, onEdit: () -> Unit) {
    val last = vm.lastRun(a)
    val icon = a.triggers.firstOrNull()?.type?.let { triggerIcon(it) } ?: Icons.Filled.PlayArrow
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onEdit),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp), tint = if (a.enabled) Palette.Primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name.ifBlank { "Bez nazwy" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    a.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (last != null) {
                    Text(
                        last,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledTonalIconButton(onClick = { vm.runAutomation(a) }) { Icon(Icons.Filled.PlayArrow, "Uruchom teraz") }
            if (a.triggers.isNotEmpty()) {
                Switch(checked = a.enabled, onCheckedChange = { vm.setAutomationEnabled(a, it) })
            }
        }
    }
}

// =====================================================================
// Background settings: watching devices, location, battery, notifications
// =====================================================================

@SuppressLint("BatteryLife", "MissingPermission")
@Composable
private fun BackgroundSettings(vm: HomeViewModel) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val store = vm.store
    val pm = ctx.getSystemService(PowerManager::class.java)
    var batteryOk by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true) }
    var notifOk by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    var lat by remember { mutableStateOf(store.get(Sun.K_LAT)) }
    var lon by remember { mutableStateOf(store.get(Sun.K_LON)) }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val l = lastLocation(ctx)
            if (l != null) {
                lat = String.format(Locale.ROOT, "%.3f", l.latitude)
                lon = String.format(Locale.ROOT, "%.3f", l.longitude)
                store.set(Sun.K_LAT, lat)
                store.set(Sun.K_LON, lon)
                vm.automationSettingsChanged()
            } else {
                vm.snack = "Telefon nie ma zapisanej lokalizacji – wpisz ją ręcznie"
            }
        }
    }
    val monitoring = Monitor.enabled(store)
    val watched = Engine.watched(store).size

    InfoCard {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Działanie w tle", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildList {
                        add(if (monitoring) "obserwowanie: włączone" else "obserwowanie: wyłączone")
                        if (!batteryOk) add("oszczędzanie baterii włączone")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!batteryOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
        }
        if (!open) return@InfoCard

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Obserwuj urządzenia w tle", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Potrzebne dla wyzwalaczy „Stan urządzenia”, gdy aplikacja jest zamknięta. Pokazuje stałe powiadomienie. Obserwowane: $watched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = monitoring, onCheckedChange = {
                store.set(Monitor.K_ENABLED, if (it) "1" else "")
                vm.automationSettingsChanged()
            })
        }
        if (monitoring) {
            Text("Sprawdzaj co:", style = MaterialTheme.typography.bodyMedium)
            val cur = Monitor.interval(store)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (sec, label) ->
                    FilterChip(selected = cur == sec, onClick = {
                        store.set(Monitor.K_INTERVAL, sec.toString())
                        vm.automationSettingsChanged()
                    }, label = { Text(label) })
                }
            }
            Text(
                "SwitchBot pozwala na 10 000 zapytań dziennie – przy wielu czujnikach wybierz 1–2 min.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Text("Lokalizacja domu (wschód i zachód słońca)", style = MaterialTheme.typography.bodyLarge)
        Text(Sun.todayText(store), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it; store.set(Sun.K_LAT, it.trim()); vm.automationSettingsChanged() },
                label = { Text("Szer. (52.23)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = lon,
                onValueChange = { lon = it; store.set(Sun.K_LON, it.trim()); vm.automationSettingsChanged() },
                label = { Text("Dł. (21.01)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        OutlinedButton(onClick = { locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
            Text("Użyj lokalizacji telefonu")
        }

        if (!batteryOk || !notifOk) HorizontalDivider()
        if (!batteryOk) {
            HintRow(Icons.Outlined.Info, "Wyłącz oszczędzanie baterii, żeby automatyzacje działały punktualnie.", "Wyłącz") {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                batteryOk = true
            }
        }
        if (!notifOk) {
            HintRow(Icons.Outlined.Notifications, "Zezwól na powiadomienia (działanie „Powiadomienie”).", "Zezwól") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): android.location.Location? {
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
    return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

@Composable
private fun HintRow(icon: ImageVector, text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(button) }
    }
}

// =====================================================================
// Editor: Gdy → Tylko jeśli → Wykonaj
// =====================================================================

@Composable
private fun AutomationEditor(vm: HomeViewModel, initial: Automation, onClose: () -> Unit) {
    val isNew = vm.automations.none { it.id == initial.id }
    var name by remember { mutableStateOf(initial.name) }
    val triggers = remember { mutableStateListOf<Trigger>().apply { addAll(initial.triggers) } }
    val conditions = remember { mutableStateListOf<Condition>().apply { addAll(initial.conditions) } }
    val steps = remember { mutableStateListOf<Step>().apply { addAll(initial.steps) } }
    var editTrigger by remember { mutableStateOf<Int?>(null) }   // -1 = new
    var editCondition by remember { mutableStateOf<Int?>(null) }
    var editStep by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val autoName: (String) -> String = { id -> vm.automations.firstOrNull { it.id == id }?.name ?: "?" }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Zamknij") }
            Text(
                if (isNew) "Nowa automatyzacja" else "Edytuj",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    vm.saveAutomation(
                        initial.copy(
                            name = name.trim(), triggers = triggers.toList(), conditions = conditions.toList(),
                            steps = steps.toList(), enabled = if (isNew) true else initial.enabled,
                        ),
                    )
                    onClose()
                },
                enabled = name.isNotBlank() && steps.isNotEmpty(),
            ) { Text("Zapisz") }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Nazwa, np. Dobranoc") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            AutoSection("Gdy", "Wystarczy, że zajdzie jeden z wyzwalaczy. Bez wyzwalaczy automatyzację uruchamiasz ręcznie.") {
                triggers.forEachIndexed { i, t ->
                    ItemRow(triggerIcon(t.type), t.describe(), onClick = { editTrigger = i }, onDelete = { triggers.removeAt(i) })
                }
                AddButton("Dodaj wyzwalacz") { editTrigger = -1 }
            }

            AutoSection("Tylko jeśli", "Wszystkie warunki muszą być spełnione. Przycisk ▶ uruchamia bez sprawdzania warunków.") {
                conditions.forEachIndexed { i, c ->
                    ItemRow(Icons.Outlined.Info, c.describe(), onClick = { editCondition = i }, onDelete = { conditions.removeAt(i) })
                }
                AddButton("Dodaj warunek") { editCondition = -1 }
            }

            AutoSection("Wykonaj", "Działania wykonują się po kolei, od góry.") {
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
                        Text(
                            s.describe(autoName),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { editStep = i }.padding(vertical = 8.dp),
                        )
                        if (s.type == StepType.DEVICE && s.action == ActionType.COLOR) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFF000000L or s.value.toLong())))
                        }
                        IconButton(onClick = { if (i > 0) steps.add(i - 1, steps.removeAt(i)) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.KeyboardArrowUp, "W górę")
                        }
                        IconButton(onClick = { if (i < steps.size - 1) steps.add(i + 1, steps.removeAt(i)) }, enabled = i < steps.size - 1, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.KeyboardArrowDown, "W dół")
                        }
                        IconButton(onClick = { steps.removeAt(i) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Delete, "Usuń") }
                    }
                }
                AddButton("Dodaj działanie") { editStep = -1 }
            }

            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) { Text("Usuń automatyzację", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    editTrigger?.let { idx ->
        TriggerDialog(vm, triggers.getOrNull(idx), onSave = {
            if (idx >= 0) triggers[idx] = it else triggers.add(it)
            editTrigger = null
        }) { editTrigger = null }
    }
    editCondition?.let { idx ->
        ConditionDialog(vm, conditions.getOrNull(idx), onSave = {
            if (idx >= 0) conditions[idx] = it else conditions.add(it)
            editCondition = null
        }) { editCondition = null }
    }
    editStep?.let { idx ->
        StepDialog(vm, initial.id, steps.getOrNull(idx), onSave = {
            if (idx >= 0) steps[idx] = it else steps.add(it)
            editStep = null
        }) { editStep = null }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć „${initial.name}”?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteAutomation(initial.id); confirmDelete = false; onClose() }) {
                    Text("Usuń", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun AutoSection(title: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ItemRow(icon: ImageVector, text: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Palette.Primary)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        )
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Usuń") }
    }
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

// =====================================================================
// Shared pieces for the dialogs
// =====================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaysPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..7).forEach { day ->
            val sel = day in days
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (sel) Palette.ChipSel else Color.Transparent)
                    .border(1.dp, if (sel) Palette.ChipSel else Color(0x55FFFFFF), CircleShape)
                    .clickable { onChange(if (sel) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) { Text(dayShort(day), style = MaterialTheme.typography.labelMedium) }
        }
    }
    Text(daysText(days), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TimeButton(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { pick = true },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hhmm(minutes), style = MaterialTheme.typography.headlineMedium)
        }
    }
    if (pick) TimeDialog(minutes, onPick = { onChange(it); pick = false }) { pick = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Godzina") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/** Shows the chosen device; tapping it opens a list to pick another. */
@Composable
private fun DeviceField(vm: HomeViewModel, device: Device?, filter: (Device) -> Boolean, onPick: (Device) -> Unit) {
    var open by remember { mutableStateOf(device == null) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = true },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (device != null) {
                Icon(kindIcon(device.kind, device.on), null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.store.alias(device.key).ifBlank { device.name })
                    Text(device.provider.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Zmień", color = Palette.Primary)
            } else {
                Text("Wybierz urządzenie", color = Palette.Primary)
            }
        }
    }
    if (open) {
        val list = vm.devices.filter(filter).sortedWith(compareBy({ vm.room(it).lowercase() }, { vm.name(it).lowercase() }))
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Wybierz urządzenie") },
            text = {
                if (list.isEmpty()) {
                    Text("Brak pasujących urządzeń. Odśwież stronę główną i spróbuj ponownie.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(list, key = { it.key }) { x ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(x); open = false }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(kindIcon(x.kind, x.on), null, Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(vm.name(x), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${vm.room(x)} · ${x.provider.label} · ${statusText(x)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Zamknij") } },
        )
    }
}

/** Device + state + (threshold), shared by device triggers and device conditions. */
@Composable
private fun DeviceStateEditor(
    vm: HomeViewModel,
    device: Device?, state: StateKind, valueText: String,
    onDevice: (Device) -> Unit, onState: (StateKind) -> Unit, onValue: (String) -> Unit,
) {
    // Use the live device (it has current sensor readings) to know which states make sense.
    val live = device?.let { d -> vm.devices.firstOrNull { it.key == d.key } } ?: device
    DeviceField(vm, device, filter = { true }, onPick = onDevice)
    if (live != null) {
        val options = StateKind.availableFor(live)
        Choice(options, state, { it.label }) { onState(it) }
        if (state.needsValue) {
            OutlinedTextField(
                value = valueText,
                onValueChange = onValue,
                label = { Text(if (state == StateKind.TEMP_ABOVE || state == StateKind.TEMP_BELOW) "Temperatura °C" else "Wilgotność %") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}

private fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

// =====================================================================
// Trigger dialog
// =====================================================================

@Composable
private fun TriggerDialog(vm: HomeViewModel, initial: Trigger?, onSave: (Trigger) -> Unit, onDismiss: () -> Unit) {
    val i = initial ?: Trigger(TriggerType.TIME)
    var type by remember { mutableStateOf(i.type) }
    var time by remember { mutableIntStateOf(i.time) }
    var days by remember { mutableStateOf(i.days) }
    var sunrise by remember { mutableStateOf(i.sunrise) }
    var offset by remember { mutableIntStateOf(i.offset) }
    var device by remember { mutableStateOf(i.device) }
    var state by remember { mutableStateOf(i.state) }
    var valueText by remember { mutableStateOf(if (i.value != 0.0) fmtNum(i.value) else "") }
    var minutes by remember { mutableIntStateOf(i.minutes) }

    val valid = when (type) {
        TriggerType.TIME, TriggerType.SUN -> days.isNotEmpty()
        TriggerType.DEVICE -> device != null && (!state.needsValue || parseNum(valueText) != null)
        TriggerType.INTERVAL -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wyzwalacz") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(TriggerType.entries, type, { it.label }) { type = it }
                HorizontalDivider()
                when (type) {
                    TriggerType.TIME -> {
                        TimeButton("", time) { time = it }
                        DaysPicker(days) { days = it }
                    }
                    TriggerType.SUN -> {
                        Choice(listOf(true, false), sunrise, { if (it) "Wschód" else "Zachód" }) { sunrise = it }
                        Text("Przesunięcie: " + (if (offset == 0) "brak" else offsetText(offset).trim()))
                        Slider(
                            value = offset.toFloat(),
                            onValueChange = { offset = (it / 5).roundToInt() * 5 },
                            valueRange = -120f..120f,
                        )
                        DaysPicker(days) { days = it }
                        Text(Sun.todayText(vm.store), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TriggerType.DEVICE -> {
                        Text("Uruchom, gdy urządzenie zacznie spełniać stan:", style = MaterialTheme.typography.bodySmall)
                        DeviceStateEditor(vm, device, state, valueText, { device = it; state = StateKind.availableFor(it).first() }, { state = it }, { valueText = it })
                        Text(
                            "Sprawdzane, gdy aplikacja jest otwarta albo włączone jest „Obserwuj urządzenia w tle”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TriggerType.INTERVAL -> {
                        Choice(listOf(5, 10, 15, 30, 60, 120, 240, 480), minutes, { if (it < 60) "$it min" else "${it / 60} h" }) { minutes = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(Trigger(type, time, days, sunrise, offset, device, state, parseNum(valueText) ?: 0.0, minutes))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

// =====================================================================
// Condition dialog
// =====================================================================

@Composable
private fun ConditionDialog(vm: HomeViewModel, initial: Condition?, onSave: (Condition) -> Unit, onDismiss: () -> Unit) {
    val i = initial ?: Condition(ConditionType.TIME_RANGE)
    var type by remember { mutableStateOf(i.type) }
    var from by remember { mutableIntStateOf(i.from) }
    var to by remember { mutableIntStateOf(i.to) }
    var days by remember { mutableStateOf(i.days) }
    var night by remember { mutableStateOf(i.night) }
    var device by remember { mutableStateOf(i.device) }
    var state by remember { mutableStateOf(i.state) }
    var valueText by remember { mutableStateOf(if (i.value != 0.0) fmtNum(i.value) else "") }

    val valid = when (type) {
        ConditionType.DAYS -> days.isNotEmpty()
        ConditionType.DEVICE -> device != null && (!state.needsValue || parseNum(valueText) != null)
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Warunek") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(ConditionType.entries, type, { it.label }) { type = it }
                HorizontalDivider()
                when (type) {
                    ConditionType.TIME_RANGE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            TimeButton("od", from) { from = it }
                            TimeButton("do", to) { to = it }
                        }
                        Text("Może przechodzić przez północ, np. 22:00–06:00.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ConditionType.DAYS -> DaysPicker(days) { days = it }
                    ConditionType.SUN -> {
                        Choice(listOf(true, false), night, { if (it) "Noc (po zachodzie)" else "Dzień" }) { night = it }
                        Text(Sun.todayText(vm.store), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ConditionType.DEVICE ->
                        DeviceStateEditor(vm, device, state, valueText, { device = it; state = StateKind.availableFor(it).first() }, { state = it }, { valueText = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(Condition(type, from, to, days, night, device, state, parseNum(valueText) ?: 0.0))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

// =====================================================================
// Step dialog
// =====================================================================

private val STEP_COLORS = listOf(
    0xFFFFFF, 0xFFD59A, 0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x32ADE6, 0x007AFF, 0xAF52DE, 0xFF2D92,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepDialog(vm: HomeViewModel, selfId: String, initial: Step?, onSave: (Step) -> Unit, onDismiss: () -> Unit) {
    val i = initial ?: Step(StepType.DEVICE)
    var type by remember { mutableStateOf(i.type) }
    var device by remember { mutableStateOf(i.device) }
    var action by remember { mutableStateOf(i.action) }
    var value by remember { mutableFloatStateOf(if (i.value == 0) 100f else i.value.toFloat()) }
    var color by remember { mutableIntStateOf(if (i.action == ActionType.COLOR) i.value else 0xFFFFFF) }
    var on by remember { mutableStateOf(i.on) }
    val rooms = vm.rooms()
    var room by remember { mutableStateOf(i.room.ifBlank { rooms.firstOrNull() ?: DEFAULT_ROOM }) }
    var useMinutes by remember { mutableStateOf(initial == null || i.seconds % 60 == 0) }
    var amount by remember { mutableStateOf(if (initial == null) "2" else if (i.seconds % 60 == 0) "${i.seconds / 60}" else "${i.seconds}") }
    var text by remember { mutableStateOf(i.text) }
    val others = vm.automations.filter { it.id != selfId }
    var target by remember { mutableStateOf(i.target.ifBlank { others.firstOrNull()?.id ?: "" }) }
    var mode by remember { mutableStateOf(i.mode) }

    val valid = when (type) {
        StepType.DEVICE -> device != null
        StepType.DELAY -> (amount.toIntOrNull() ?: 0) > 0
        StepType.NOTIFY -> text.isNotBlank()
        StepType.AUTOMATION -> target.isNotBlank()
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Działanie") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(StepType.entries, type, { it.label }) { type = it }
                HorizontalDivider()
                when (type) {
                    StepType.DEVICE -> {
                        DeviceField(vm, device, filter = { ActionType.availableFor(it).isNotEmpty() }) {
                            device = it
                            action = ActionType.availableFor(it).first()
                        }
                        val d = device
                        if (d != null) {
                            val live = vm.devices.firstOrNull { it.key == d.key } ?: d
                            Choice(ActionType.availableFor(live), action, { it.label }) { action = it }
                            when (action) {
                                ActionType.BRIGHTNESS -> {
                                    Text("Jasność: ${value.roundToInt()}%")
                                    Slider(value = value.coerceIn(1f, 100f), onValueChange = { value = it }, valueRange = 1f..100f)
                                }
                                ActionType.POSITION -> {
                                    Text("Otwarcie: ${value.roundToInt()}%")
                                    Slider(value = value.coerceIn(0f, 100f), onValueChange = { value = it }, valueRange = 0f..100f)
                                }
                                ActionType.COLOR -> FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    STEP_COLORS.forEach { c ->
                                        val sel = color == c
                                        Box(
                                            Modifier.size(36.dp).clip(CircleShape)
                                                .background(Color(0xFF000000L or c.toLong()))
                                                .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color(0x55FFFFFF), CircleShape)
                                                .clickable { color = c },
                                        )
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    StepType.ALL_LIGHTS -> {
                        Choice(listOf(true, false), on, { if (it) "Włącz" else "Wyłącz" }) { on = it }
                        Text("Dotyczy wszystkich widocznych świateł (także przeniesionych do „Oświetlenie”).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StepType.ROOM_LIGHTS -> {
                        Choice(rooms.ifEmpty { listOf(DEFAULT_ROOM) }, room, { it }) { room = it }
                        Choice(listOf(true, false), on, { if (it) "Włącz" else "Wyłącz" }) { on = it }
                    }
                    StepType.DELAY -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("Ile") },
                                singleLine = true,
                                modifier = Modifier.width(100.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Choice(listOf(false, true), useMinutes, { if (it) "minut" else "sekund" }) { useMinutes = it }
                        }
                    }
                    StepType.NOTIFY -> OutlinedTextField(
                        value = text, onValueChange = { text = it }, label = { Text("Treść powiadomienia") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StepType.AUTOMATION -> {
                        if (others.isEmpty()) {
                            Text("Nie masz jeszcze innych automatyzacji.")
                        } else {
                            Choice(AutoMode.entries, mode, { it.label }) { mode = it }
                            Choice(others.map { it.id }, target, { id -> others.firstOrNull { it.id == id }?.name ?: "?" }) { target = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val v = when (action) {
                    ActionType.COLOR -> color
                    ActionType.BRIGHTNESS -> value.roundToInt().coerceIn(1, 100)
                    ActionType.POSITION -> value.roundToInt().coerceIn(0, 100)
                    else -> 0
                }
                val n = amount.toIntOrNull() ?: 1
                onSave(
                    Step(
                        type = type, device = device, action = action, value = v, on = on, room = room,
                        seconds = if (useMinutes) n * 60 else n, text = text.trim(), target = target, mode = mode,
                    ),
                )
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
