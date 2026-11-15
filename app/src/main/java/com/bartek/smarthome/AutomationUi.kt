package com.bartek.smarthome

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val DAY_NAMES = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")

fun hhmm(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

fun daysText(days: Set<Int>): String = when {
    days.size == 7 -> "Codziennie"
    days == setOf(1, 2, 3, 4, 5) -> "Pn–Pt"
    days == setOf(6, 7) -> "Weekend"
    days.isEmpty() -> "Żaden dzień"
    else -> days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
}

fun Automation.summary(): String {
    val starter = time?.let { "${hhmm(it)} · ${daysText(days)}" } ?: "Uruchamiana ręcznie"
    val n = actions.size
    val word = if (n == 1) "działanie" else if (n in 2..4) "działania" else "działań"
    return "$starter · $n $word"
}

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
            item { BatteryHint() }
            if (vm.automations.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Brak automatyzacji", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Na przykład „Dobranoc”: o 23:00 wyłącz światła i telewizor. Albo „Dzień dobry”: w dni robocze o 7:00 otwórz rolety i zapal światło w kuchni.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

/** Without this, the phone may delay automations while it sleeps. */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryHint() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true) }
    if (ignoring) return
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Palette.Primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Żeby automatyzacje działały punktualnie, wyłącz oszczędzanie baterii dla aplikacji.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    )
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                ignoring = true
            }) { Text("Wyłącz") }
        }
    }
}

@Composable
private fun AutomationCard(vm: HomeViewModel, a: Automation, onEdit: () -> Unit) {
    val last = vm.lastRun(a)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onEdit),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name.ifBlank { "Bez nazwy" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(a.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (a.time != null) {
                Spacer(Modifier.width(4.dp))
                Switch(checked = a.enabled, onCheckedChange = { vm.setAutomationEnabled(a, it) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationEditor(vm: HomeViewModel, initial: Automation, onClose: () -> Unit) {
    val isNew = vm.automations.none { it.id == initial.id }
    var name by remember { mutableStateOf(initial.name) }
    var timed by remember { mutableStateOf(initial.time != null || isNew) }
    var time by remember { mutableIntStateOf(initial.time ?: (7 * 60)) }
    var days by remember { mutableStateOf(initial.days.ifEmpty { (1..7).toSet() }) }
    val actions = remember { mutableStateListOf<AutoAction>().apply { addAll(initial.actions) } }
    var pickTime by remember { mutableStateOf(false) }
    var addAction by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Zamknij") }
            Text(
                if (isNew) "Nowa automatyzacja" else "Edytuj automatyzację",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    vm.saveAutomation(
                        initial.copy(
                            name = name.trim(),
                            time = if (timed) time else null,
                            days = days,
                            actions = actions.toList(),
                            enabled = if (isNew) true else initial.enabled,
                        ),
                    )
                    onClose()
                },
                enabled = name.isNotBlank() && actions.isNotEmpty() && (!timed || days.isNotEmpty()),
            ) { Text("Zapisz") }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nazwa, np. Dobranoc") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            EditorCard("Kiedy") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = timed, onClick = { timed = true }, label = { Text("O godzinie") })
                    FilterChip(selected = !timed, onClick = { timed = false }, label = { Text("Tylko ręcznie") })
                }
                if (timed) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { pickTime = true },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null)
                            Spacer(Modifier.width(12.dp))
                            Text(hhmm(time), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                            Text("Zmień", color = Palette.Primary)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_NAMES.forEachIndexed { i, label ->
                            val day = i + 1
                            val sel = day in days
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (sel) Palette.ChipSel else Color.Transparent)
                                    .border(1.dp, if (sel) Palette.ChipSel else Color(0x55FFFFFF), CircleShape)
                                    .clickable { days = if (sel) days - day else days + day },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                    Text(daysText(days), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Każdą automatyzację możesz też uruchomić ręcznie przyciskiem ▶ albo skrótem na stronie głównej.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EditorCard("Działania") {
                if (actions.isEmpty()) {
                    Text("Dodaj co najmniej jedno działanie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                actions.forEachIndexed { i, act ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(act.device.kind, act.type != ActionType.OFF), null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vm.store.alias(act.device.key).ifBlank { act.device.name }, style = MaterialTheme.typography.bodyLarge)
                            Text(act.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (act.type == ActionType.COLOR) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF000000L or act.value.toLong())))
                        }
                        IconButton(onClick = { actions.removeAt(i) }) { Icon(Icons.Outlined.Delete, "Usuń") }
                    }
                }
                OutlinedButton(onClick = { addAction = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Dodaj działanie")
                }
            }

            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Usuń automatyzację", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (pickTime) TimeDialog(time, onPick = { time = it; pickTime = false }) { pickTime = false }
    if (addAction) ActionDialog(vm, onAdd = { actions.add(it); addAction = false }) { addAction = false }
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
private fun EditorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
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

private val ACTION_COLORS = listOf(
    0xFFFFFF, 0xFFD59A, 0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x32ADE6, 0x007AFF, 0xAF52DE, 0xFF2D92,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionDialog(vm: HomeViewModel, onAdd: (AutoAction) -> Unit, onDismiss: () -> Unit) {
    var device by remember { mutableStateOf<Device?>(null) }
    var type by remember { mutableStateOf(ActionType.ON) }
    var value by remember { mutableFloatStateOf(100f) }
    var color by remember { mutableIntStateOf(0xFFFFFF) }
    val d = device

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d == null) "Wybierz urządzenie" else vm.name(d)) },
        text = {
            if (d == null) {
                val list = vm.devices
                    .filter { ActionType.availableFor(it).isNotEmpty() }
                    .sortedWith(compareBy({ vm.room(it).lowercase() }, { vm.name(it).lowercase() }))
                if (list.isEmpty()) {
                    Text("Brak urządzeń. Odśwież stronę główną i spróbuj ponownie.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(list, key = { it.key }) { x ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        device = x
                                        type = ActionType.availableFor(x).first()
                                        value = if (x.canPosition) 100f else 100f
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(kindIcon(x.kind, x.on), null, Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(vm.name(x), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${vm.room(x)} · ${x.provider.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionType.availableFor(d).forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                        }
                    }
                    when (type) {
                        ActionType.BRIGHTNESS -> {
                            Text("Jasność: ${value.roundToInt()}%")
                            Slider(value = value.coerceIn(1f, 100f), onValueChange = { value = it }, valueRange = 1f..100f)
                        }
                        ActionType.POSITION -> {
                            Text("Otwarcie: ${value.roundToInt()}%")
                            Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f)
                        }
                        ActionType.COLOR -> FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ACTION_COLORS.forEach { c ->
                                val sel = color == c
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
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
        },
        confirmButton = {
            if (d != null) {
                TextButton(onClick = {
                    val v = when (type) {
                        ActionType.COLOR -> color
                        ActionType.BRIGHTNESS -> value.roundToInt().coerceIn(1, 100)
                        ActionType.POSITION -> value.roundToInt()
                        else -> 0
                    }
                    onAdd(AutoAction(d, type, v))
                }) { Text("Dodaj") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (d != null) device = null else onDismiss() }) {
                Text(if (d != null) "Wstecz" else "Anuluj")
            }
        },
    )
}

private val LAST_FORMAT = SimpleDateFormat("d.MM, HH:mm", Locale.forLanguageTag("pl"))

/** "Ostatnio: 15.11, 07:00 – wykonano" */
fun formatLastRun(raw: String): String? {
    val t = raw.substringBefore("|").toLongOrNull() ?: return null
    return "Ostatnio: ${LAST_FORMAT.format(Date(t))} – ${raw.substringAfter("|")}"
}
